package com.playmation.motionlabsbackend.catalog

import com.playmation.motionlabsbackend.account.AccountRepository
import com.playmation.motionlabsbackend.account.AccountService
import com.playmation.motionlabsbackend.account.AccountStatus
import com.playmation.motionlabsbackend.auth.PortalPrincipal
import com.playmation.motionlabsbackend.common.Crypto
import com.playmation.motionlabsbackend.common.PortalException
import com.playmation.motionlabsbackend.common.RateLimiter
import com.playmation.motionlabsbackend.config.PortalProperties
import com.playmation.motionlabsbackend.format.AwclipHash
import com.playmation.motionlabsbackend.format.AwclipReadResult
import com.playmation.motionlabsbackend.format.AwclipReader
import com.playmation.motionlabsbackend.format.AwclipSchema
import com.playmation.motionlabsbackend.format.StrictJson
import com.playmation.motionlabsbackend.storage.BlobStore
import com.playmation.motionlabsbackend.system.AuditService
import com.playmation.motionlabsbackend.system.SystemSettingsService
import jakarta.persistence.criteria.Predicate
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Die Upload-Erklärung (Konzept §11): eine Zeile, die tatsächlich gelesen
 * wird. Wortgleich mit `AWClipShareWindow.Declaration` in der Workbench - der
 * Server nimmt nur genau diesen Text in genau dieser Fassung an und
 * protokolliert beides.
 */
object Declaration {
    const val TEXT = "I created this animation myself and have the right to share it here."

    /**
     * Zählt mit, wenn sich [TEXT] ändert: das Protokoll hält Wortlaut UND
     * Version fest, und trüge zweimal dieselbe Version einen anderen Text,
     * wäre nicht mehr zu sagen, wem jemand zugestimmt hat. 2 seit dem
     * Wechsel auf zwei Teilen-Wege.
     */
    const val VERSION = 2
}

data class PackageSummary(
    val slug: String,
    val title: String,
    val tags: List<String>,
    val license: String,
    val author: String,
    val durationSeconds: Float,
    val frameRate: Float,
    /** Übernahmen in ein Projekt - siehe [AnimationPackage.takeCount]. */
    val downloads: Long,
    val likes: Long,
    val likedByMe: Boolean,
    val hasPreview: Boolean,
    val createdAt: Instant,
)

data class PackageDetail(
    val slug: String,
    val title: String,
    val description: String,
    val tags: List<String>,
    val license: String,
    val author: String,
    val version: Int,
    val durationSeconds: Float,
    val frameRate: Float,
    val curveCount: Int,
    val downloads: Long,
    val likes: Long,
    val likedByMe: Boolean,
    val hasPreview: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** Nur für Besitzer und Admins gesetzt. */
    val status: String?,
    val isOwner: Boolean,
)

data class PageResult<T>(val items: List<T>, val page: Int, val size: Int, val total: Long)

data class DownloadLink(val url: String, val expiresAt: Instant, val fileName: String, val license: String)

@Service
class CatalogService(
    private val packages: AnimationPackageRepository,
    private val versions: PackageVersionRepository,
    private val declarations: UploadDeclarationRepository,
    private val likes: PackageLikeRepository,
    private val accountRepository: AccountRepository,
    private val accounts: AccountService,
    private val blobs: BlobStore,
    private val settings: SystemSettingsService,
    private val rateLimiter: RateLimiter,
    private val audit: AuditService,
    private val properties: PortalProperties,
    private val clock: Clock,
) {
    // ════════════════════════════════════════════════════════════════════
    // UPLOAD
    // ════════════════════════════════════════════════════════════════════

    /**
     * Konzept §6, Upload: Format prüfen → Erklärung protokollieren → Hash →
     * Duplikat- und Wiederupload-Sperre → veröffentlichen.
     *
     * @param targetSlug null = neues Paket, sonst neue Version eines eigenen.
     */
    @Transactional
    fun upload(
        principal: PortalPrincipal,
        bytes: ByteArray,
        declarationText: String?,
        declarationVersion: Int?,
        declarationAccepted: Boolean,
        ip: String,
        targetSlug: String?,
    ): PackageDetail {
        if (!settings.uploadsEnabled()) throw PortalException.unavailable("Uploads are paused right now.")

        val account = accounts.requireUsable(principal.accountId)
        val now = clock.instant()

        val dailyLimit = if (account.status == AccountStatus.RESTRICTED) properties.limits.uploadsPerDayRestricted else properties.limits.uploadsPerDay
        if (versions.countUploadsSince(account.id, now.minus(Duration.ofDays(1))) >= dailyLimit)
            throw PortalException(HttpStatus.TOO_MANY_REQUESTS, "upload-limit", "You reached today's upload limit.")
        rateLimiter.require("upload-ip", ip, properties.limits.uploadsPerDayPerIp, Duration.ofDays(1))

        if (!declarationAccepted || declarationText != Declaration.TEXT || declarationVersion != Declaration.VERSION)
            throw PortalException.badRequest("declaration-required", "Confirm the upload declaration.")

        if (bytes.size > AwclipSchema.MAX_COMPRESSED_BYTES)
            throw PortalException(HttpStatus.PAYLOAD_TOO_LARGE, "too-large", "The file is too large.")

        val doc = when (val read = AwclipReader.readFile(bytes.inputStream())) {
            is AwclipReadResult.Ok -> read.document
            is AwclipReadResult.Rejected -> throw PortalException.badRequest("invalid-awclip", read.error.toString())
        }

        val hash = AwclipHash.compute(doc)
        checkNotAlreadyThere(hash, account.id)

        val existing = targetSlug?.let { slug ->
            val pkg = packages.findBySlug(slug) ?: throw PortalException.notFound("Package not found")
            if (pkg.ownerId != account.id) throw PortalException.forbidden("Only the owner can add versions.")
            if (pkg.status == PackageStatus.REMOVED || pkg.status == PackageStatus.AUTO_HIDDEN)
                throw PortalException.conflict("package-locked", "This package is under review or removed.")
            pkg
        }

        val blobKey = blobs.put(bytes)
        val previewKey = doc.preview?.let { blobs.put(StrictJson.write(it).toByteArray(Charsets.UTF_8)) }
        val manifest = doc.manifest

        val pkg = existing ?: AnimationPackage(
            slug = newSlug(),
            ownerId = account.id,
            title = manifest.title,
            description = manifest.description,
            tags = AnimationPackage.joinTags(manifest.tags),
            license = manifest.license,
            createdAt = now,
            updatedAt = now,
        )

        pkg.title = manifest.title
        pkg.description = manifest.description
        pkg.tags = AnimationPackage.joinTags(manifest.tags)
        pkg.license = manifest.license
        pkg.status = PackageStatus.PUBLISHED
        pkg.updatedAt = now
        packages.save(pkg)

        val version = versions.save(
            PackageVersion(
                packageId = pkg.id,
                versionNumber = (versions.findByPackageIdOrderByVersionNumberDesc(pkg.id).firstOrNull()?.versionNumber ?: 0) + 1,
                contentHash = hash,
                blobKey = blobKey,
                previewBlobKey = previewKey,
                sizeBytes = bytes.size.toLong(),
                frameRate = manifest.frameRate,
                durationSeconds = manifest.duration,
                curveCount = doc.curves.size,
                originClass = doc.origin,
                createdAt = now,
            )
        )

        pkg.currentVersionId = version.id
        packages.save(pkg)

        declarations.save(
            UploadDeclaration(
                accountId = account.id,
                versionId = version.id,
                declarationText = Declaration.TEXT,
                declarationVersion = Declaration.VERSION,
                license = manifest.license,
                originClass = doc.origin,
                ipAddress = ip,
                createdAt = now,
            )
        )

        audit.record(account.id, if (existing == null) "package.created" else "package.version-added", "package", pkg.slug,
            "v${version.versionNumber} hash=$hash origin=${doc.origin} license=${manifest.license}", ip)

        return detail(pkg, version, principal)
    }

    private fun checkNotAlreadyThere(hash: String, accountId: UUID) {
        for (version in versions.findByContentHash(hash)) {
            val pkg = packages.findById(version.packageId).orElse(null) ?: continue

            if (version.status == VersionStatus.REMOVED || pkg.status == PackageStatus.REMOVED)
                throw PortalException.conflict("removed-content", "This animation was removed from the community and cannot be uploaded again.")
            if (pkg.status == PackageStatus.AUTO_HIDDEN)
                throw PortalException.conflict("under-review", "This animation is currently under review.")
            if (pkg.status == PackageStatus.WITHDRAWN && pkg.ownerId == accountId)
                continue

            throw PortalException.conflict("duplicate",
                if (pkg.status == PackageStatus.PUBLISHED) "This exact animation is already in the community as '${pkg.title}'."
                else "This exact animation was already uploaded.")
        }
    }

    private fun newSlug(): String {
        repeat(10) {
            val slug = Crypto.randomCode(8, "abcdefghijkmnpqrstuvwxyz23456789")
            if (packages.findBySlug(slug) == null) return slug
        }
        error("Could not find a free slug")
    }

    @Transactional
    fun withdraw(principal: PortalPrincipal, slug: String, ip: String) {
        val pkg = packages.findBySlug(slug) ?: throw PortalException.notFound("Package not found")
        if (pkg.ownerId != principal.accountId) throw PortalException.forbidden("Only the owner can withdraw a package.")
        if (pkg.status == PackageStatus.REMOVED) throw PortalException.conflict("package-locked", "This package was removed by moderation.")

        //  Zurückziehen während einer Prüfung beendet die Prüfung nicht - der
        //  Fall bleibt offen, nur die Sichtbarkeit ist ohnehin schon weg.
        if (pkg.status == PackageStatus.PUBLISHED) pkg.status = PackageStatus.WITHDRAWN
        pkg.updatedAt = clock.instant()
        audit.record(principal.accountId, "package.withdrawn", "package", slug, null, ip)
    }

    // ════════════════════════════════════════════════════════════════════
    // LESEN
    // ════════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    fun search(q: String?, tag: String?, sort: String?, page: Int, size: Int,
               principal: PortalPrincipal? = null): PageResult<PackageSummary> {
        val pageSize = size.coerceIn(1, 50)
        val pageIndex = page.coerceAtLeast(0)

        val spec = Specification<AnimationPackage> { root, _, cb ->
            val predicates = mutableListOf(
                cb.equal(root.get<PackageStatus>("status"), PackageStatus.PUBLISHED),
                // Der Katalog zeigt nur, was öffentlich geteilt wurde. Private
                // Clips liegen im Portal, sind aber nur über ihren Link zu
                // erreichen - sie tauchen in keiner Liste und keiner Suche auf.
                cb.equal(root.get<String>("license"), AwclipSchema.LICENSE_PUBLIC),
            )

            q?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.take(80)?.let { term ->
                val pattern = "%" + escapeLike(term) + "%"
                predicates += cb.or(
                    cb.like(cb.lower(root.get("title")), pattern, '\\'),
                    cb.like(cb.lower(root.get("description")), pattern, '\\'),
                    cb.like(root.get("tags"), pattern, '\\'),
                )
            }

            tag?.trim()?.lowercase()?.takeIf { AwclipSchema.isTag(it) }?.let {
                predicates += cb.like(root.get("tags"), "%," + escapeLike(it) + ",%", '\\')
            }

            cb.and(*predicates.toTypedArray())
        }

        //  "Popular" zählt Übernahmen UND Herzen: die eine Zahl sagt, wer den
        //  Clip benutzt, die andere, wer ihn gut findet. Herzen wiegen weniger,
        //  weil sie billiger zu vergeben sind.
        val order = when (sort) {
            "popular" -> Sort.by(Sort.Order.desc("takeCount"), Sort.Order.desc("likeCount"), Sort.Order.desc("createdAt"))
            "liked" -> Sort.by(Sort.Order.desc("likeCount"), Sort.Order.desc("createdAt"))
            else -> Sort.by(Sort.Order.desc("createdAt"))
        }

        val result = packages.findAll(spec, PageRequest.of(pageIndex, pageSize, order))
        val authors = authorNames(result.content.map { it.ownerId })
        val currentVersions = versions.findAllById(result.content.mapNotNull { it.currentVersionId }).associateBy { it.id }

        //  EINE Abfrage für die ganze Seite statt einer je Karte.
        val likedByMe = principal?.let { me ->
            likes.likedAmong(me.accountId, result.content.map { it.id }).toSet()
        } ?: emptySet()

        return PageResult(
            result.content.mapNotNull { pkg ->
                val version = currentVersions[pkg.currentVersionId] ?: return@mapNotNull null
                PackageSummary(pkg.slug, pkg.title, pkg.tagList(), pkg.license, authors[pkg.ownerId] ?: "unknown",
                    version.durationSeconds, version.frameRate, pkg.takeCount, pkg.likeCount,
                    pkg.id in likedByMe, version.previewBlobKey != null, pkg.createdAt)
            },
            pageIndex, pageSize, result.totalElements,
        )
    }

    @Transactional(readOnly = true)
    fun detail(slug: String, principal: PortalPrincipal?): PackageDetail {
        val (pkg, version) = visible(slug, principal)
        return detail(pkg, version, principal)
    }

    @Transactional(readOnly = true)
    fun ownPackages(principal: PortalPrincipal): List<PackageDetail> =
        packages.findByOwnerIdOrderByCreatedAtDesc(principal.accountId).mapNotNull { pkg ->
            val version = pkg.currentVersionId?.let { versions.findById(it).orElse(null) } ?: return@mapNotNull null
            detail(pkg, version, principal)
        }

    @Transactional(readOnly = true)
    fun previewJson(slug: String, principal: PortalPrincipal?): ByteArray {
        val (_, version) = visible(slug, principal)
        val key = version.previewBlobKey ?: throw PortalException.notFound("This clip has no preview.")
        return blobs.open(key).use { it.readBytes() }
    }

    @Transactional(readOnly = true)
    fun downloadLink(slug: String, ip: String): DownloadLink {
        rateLimiter.require("download-link", ip, properties.limits.downloadLinksPerHourPerIp, Duration.ofHours(1))

        val (pkg, version) = visible(slug, null)
        val expires = clock.instant().plusSeconds(properties.tokens.downloadLinkSeconds)
        val signature = sign(version.id, expires.epochSecond)
        val url = "/api/v1/files/${version.id}?exp=${expires.epochSecond}&sig=$signature"

        return DownloadLink(url, expires, fileName(pkg, version), pkg.license)
    }

    data class DownloadableFile(val fileName: String, val bytes: ByteArray, val license: String)

    /**
     * Prüft Signatur, Ablauf UND den aktuellen Status - ein Link, der vor
     * einer Meldung ausgegeben wurde, funktioniert danach sofort nicht mehr.
     */
    @Transactional
    fun download(versionId: UUID, exp: Long, sig: String): DownloadableFile {
        if (exp < clock.instant().epochSecond || !Crypto.constantTimeEquals(sign(versionId, exp), sig))
            throw PortalException(HttpStatus.FORBIDDEN, "link-expired", "This download link has expired - request a new one.")

        val version = versions.findById(versionId).orElseThrow { PortalException.notFound() }
        val pkg = packages.findById(version.packageId).orElseThrow { PortalException.notFound() }

        if (pkg.status != PackageStatus.PUBLISHED || version.status != VersionStatus.PUBLISHED || pkg.currentVersionId != version.id)
            throw PortalException.notFound("This clip is not available.")

        //  Bewusst KEIN Zähler hier: die Workbench lädt Clips schon zum
        //  Stöbern. Gezählt wird die Übernahme in ein Projekt - siehe [taken].
        return DownloadableFile(fileName(pkg, version), blobs.open(version.blobKey).use { it.readBytes() }, pkg.license)
    }

    // ════════════════════════════════════════════════════════════════════
    // ÜBERNAHME UND HERZEN
    // ════════════════════════════════════════════════════════════════════

    /**
     * Der Clip ist in einem Projekt gelandet. Braucht keine Anmeldung, weil
     * Herunterladen auch keine braucht - dafür ein Limit je IP.
     *
     * Ehrlich dazu: ein solcher Ruf ist fälschbar. Das gilt für jeden
     * Download-Zähler im Netz; die Zahl ist ein weiches Maß und kein Beleg.
     * Sie am Dateiabruf festzumachen wäre nicht fälschungssicherer gewesen,
     * nur zusätzlich falsch.
     */
    @Transactional
    fun taken(slug: String, ip: String) {
        rateLimiter.require("taken", ip, properties.limits.downloadLinksPerHourPerIp, Duration.ofHours(1))

        val (pkg, _) = visible(slug, null)
        pkg.takeCount++
        packages.save(pkg)
    }

    /**
     * Ein Herz setzen oder zurücknehmen. Anmeldepflichtig - sonst wäre die Zahl
     * eine Einladung an jeden Skriptschreiber.
     */
    @Transactional
    fun like(slug: String, principal: PortalPrincipal, liked: Boolean): Long {
        val (pkg, _) = visible(slug, principal)

        val already = likes.existsByPackageIdAndAccountId(pkg.id, principal.accountId)

        if (liked && !already)
            likes.save(PackageLike(pkg.id, principal.accountId, clock.instant()))
        else if (!liked && already)
            likes.deleteByPackageIdAndAccountId(pkg.id, principal.accountId)
        else
            return pkg.likeCount

        //  Neu zählen statt hoch- und runterzuzählen: der abgeleitete Wert darf
        //  nicht auseinanderlaufen, und die Abfrage ist ein Index-Zugriff.
        pkg.likeCount = likes.countByPackageId(pkg.id)
        packages.save(pkg)
        return pkg.likeCount
    }

    // ── Hilfen ──────────────────────────────────────────────────────────

    /** Öffentlich sichtbar, oder für Besitzer und Admins auch im Prüfzustand. */
    private fun visible(slug: String, principal: PortalPrincipal?): Pair<AnimationPackage, PackageVersion> {
        val pkg = packages.findBySlug(slug) ?: throw PortalException.notFound("Package not found")
        val privileged = principal != null && (principal.isAdmin || principal.accountId == pkg.ownerId)

        if (pkg.status != PackageStatus.PUBLISHED && !privileged) throw PortalException.notFound("Package not found")

        val version = pkg.currentVersionId?.let { versions.findById(it).orElse(null) }
            ?: throw PortalException.notFound("Package not found")
        if (version.status != VersionStatus.PUBLISHED && !privileged) throw PortalException.notFound("Package not found")

        return pkg to version
    }

    private fun detail(pkg: AnimationPackage, version: PackageVersion, principal: PortalPrincipal?): PackageDetail {
        val isOwner = principal?.accountId == pkg.ownerId
        return PackageDetail(
            pkg.slug, pkg.title, pkg.description, pkg.tagList(), pkg.license,
            authorNames(listOf(pkg.ownerId))[pkg.ownerId] ?: "unknown",
            version.versionNumber, version.durationSeconds, version.frameRate, version.curveCount,
            pkg.takeCount, pkg.likeCount,
            principal != null && likes.existsByPackageIdAndAccountId(pkg.id, principal.accountId),
            version.previewBlobKey != null, pkg.createdAt, pkg.updatedAt,
            if (isOwner || principal?.isAdmin == true) pkg.status.name else null,
            isOwner,
        )
    }

    private fun authorNames(ids: Collection<UUID>): Map<UUID, String> =
        accountRepository.findAllById(ids.toSet()).associate { it.id to it.displayName }

    private fun sign(versionId: UUID, exp: Long) = Crypto.hmacHex(properties.downloadSecret, "$versionId|$exp")

    private fun fileName(pkg: AnimationPackage, version: PackageVersion) = "${pkg.slug}-v${version.versionNumber}.awclip"

    private fun escapeLike(value: String) = value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}
