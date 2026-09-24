package com.playmation.motionlabsbackend.catalog

import com.playmation.motionlabsbackend.account.avatarPath
import com.playmation.motionlabsbackend.account.AccountRepository
import com.playmation.motionlabsbackend.account.AccountService
import com.playmation.motionlabsbackend.account.AccountStatus
import com.playmation.motionlabsbackend.auth.PortalPrincipal
import com.playmation.motionlabsbackend.collection.CollectionItemRepository
import com.playmation.motionlabsbackend.common.Crypto
import com.playmation.motionlabsbackend.common.PortalException
import com.playmation.motionlabsbackend.common.RateLimiter
import com.playmation.motionlabsbackend.config.PortalProperties
import com.playmation.motionlabsbackend.format.AwclipHash
import com.playmation.motionlabsbackend.format.AwclipReadResult
import com.playmation.motionlabsbackend.format.AwclipReader
import com.playmation.motionlabsbackend.format.AwclipSchema
import com.playmation.motionlabsbackend.format.StrictJson
import com.playmation.motionlabsbackend.profile.ProfileService
import com.playmation.motionlabsbackend.storage.BlobStore
import com.playmation.motionlabsbackend.system.AuditService
import com.playmation.motionlabsbackend.system.SystemSettingsService
import com.playmation.motionlabsbackend.unlocks.UnlockService
import jakarta.persistence.criteria.Predicate
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

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
    /** Die Adresse des Erstellers - der Name auf der Karte verlinkt darauf. */
    val authorHandle: String?,
    val durationSeconds: Float,
    val frameRate: Float,
    /** `humanoid` oder `generic` - die Karte zeigt danach Figur oder Strichmännchen. */
    val rig: String,
    /** Übernahmen in ein Projekt - siehe [AnimationPackage.takeCount]. */
    val downloads: Long,
    val likes: Long,
    /** Der Stern: wie viele Personen den Clip in einer Sammlung haben. */
    val saves: Long,
    val comments: Long,
    val likedByMe: Boolean,
    /** Liegt er in einer MEINER Sammlungen - der Stern steht dann gefuellt. */
    val savedByMe: Boolean,
    /** Quittung vorhanden - die Workbench zeigt dann keinen Preis mehr an. */
    val unlockedByMe: Boolean,
    val hasPreview: Boolean,
    val createdAt: Instant,
    /**
     * Gehoert der Clip dem, der fragt? Die Workbench bietet daran "Edit on
     * the portal" an - ohne das Feld muesste sie am Anzeigenamen raten, und
     * der ist nicht eindeutig.
     */
    val isOwner: Boolean = false,
)

data class PackageDetail(
    val slug: String,
    val title: String,
    val description: String,
    val tags: List<String>,
    val license: String,
    val author: String,
    val authorHandle: String?,
    val version: Int,
    val durationSeconds: Float,
    val frameRate: Float,
    val curveCount: Int,
    val rig: String,
    val downloads: Long,
    val likes: Long,
    val saves: Long,
    val comments: Long,
    val likedByMe: Boolean,
    val savedByMe: Boolean,
    val unlockedByMe: Boolean,
    val hasPreview: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** Nur für Besitzer und Admins gesetzt. */
    val status: String?,
    val isOwner: Boolean,
    /** Das Profilbild des Erstellers, oder null - dann der Buchstabenkreis. */
    val authorAvatar: String? = null,
)

data class PageResult<T>(val items: List<T>, val page: Int, val size: Int, val total: Long)

data class DownloadLink(val url: String, val expiresAt: Instant, val fileName: String, val license: String)

@Service
class CatalogService(
    private val packages: AnimationPackageRepository,
    private val versions: PackageVersionRepository,
    private val declarations: UploadDeclarationRepository,
    private val likes: PackageLikeRepository,
    /**
     * Nur fuer den Stern: "liegt dieser Clip in einer meiner Sammlungen". Die
     * Ablage, nicht der Dienst - sonst zeigten Katalog und Sammlungen
     * aufeinander.
     */
    private val savedCollections: CollectionItemRepository,
    private val unlocks: UnlockService,
    /** Nur fuer die Nachricht an die Follower, wenn ein neuer Clip erscheint. */
    private val profiles: ProfileService,
    private val accountRepository: AccountRepository,
    private val accounts: AccountService,
    private val blobs: BlobStore,
    private val settings: SystemSettingsService,
    private val rateLimiter: RateLimiter,
    private val audit: AuditService,
    /** Zaehlt Clips und Schlagworte - und muss es neu tun, wenn sich der Katalog aendert. */
    private val overview: CatalogOverviewService,
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

        //  DIE TUER: humanoid ja, generisch noch nicht.
        //
        //  Sie steht HIER und nicht im Leser. Der Leser sagt, ob eine Datei
        //  heil ist - das ist eine Frage ueber die Datei und hat dieselbe
        //  Antwort wie in der Workbench, Fehlercode fuer Fehlercode. OB wir
        //  eine heile Datei haben wollen, ist eine Frage ueber das PORTAL, und
        //  die aendert sich, wenn wir es uns anders ueberlegen. Zwei Fragen,
        //  zwei Orte: was sich aendern darf, steht nicht dort, wo nichts sich
        //  aendern darf.
        //
        //  Eigener Code, nicht `invalid-awclip`: die Datei ist in Ordnung, und
        //  wer sie geschrieben hat, soll nicht nach einem Fehler darin suchen.
        if (!AwclipSchema.isAcceptedRig(doc.manifest.rig))
            throw PortalException.badRequest(
                "unsupported-rig",
                "The community takes humanoid clips only for now. This one animates its own skeleton.")

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
                rig = manifest.rig,
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

        //  Wer jemandem folgt, folgt ihm wegen genau dieses Augenblicks. Nur
        //  beim neuen Clip: eine zweite Fassung ist keine Nachricht wert, und
        //  ein privater Clip schon gar nicht.
        if (existing == null && manifest.license == AwclipSchema.LICENSE_PUBLIC) {
            profiles.notifyFollowers(
                account.id, "${account.displayName} shared a new clip: '${pkg.title}'.")
        }

        //  Ein frischer Clip darf nicht eine Minute darauf warten, dass die
        //  Startseite ihn mitzaehlt und seine Schlagworte in der Leiste stehen.
        overview.invalidate()

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
        overview.invalidate()
    }

    // ════════════════════════════════════════════════════════════════════
    // BEARBEITEN
    // ════════════════════════════════════════════════════════════════════

    /**
     * Was der Besitzer an einem Clip aendern darf: alles, was nicht Bewegung
     * ist. Die Bewegung selbst aendert sich nur mit einer neuen Fassung aus
     * der Workbench.
     *
     * [declarationText] und [declarationVersion] zaehlen nur beim Wechsel von
     * privat auf oeffentlich - siehe [edit].
     */
    data class PackageEdit(
        val title: String = "",
        val description: String = "",
        val tags: List<String> = emptyList(),
        val license: String = "",
        val declarationAccepted: Boolean = false,
        val declarationText: String? = null,
        val declarationVersion: Int? = null,
    )

    /**
     * Titel, Beschreibung, Schlagworte und Sichtbarkeit eines eigenen Clips.
     *
     * DIE DATEI ZIEHT MIT. Dieselben Angaben stehen im Manifest der
     * gespeicherten `.awclip`, und die Workbench liest sie beim Import von
     * dort - ohne Umschreiben kaeme nach einer Korrektur beim Herunterladen
     * der alte Titel an, und ein oeffentlich gestellter Clip meldete in Unity
     * "private, no rights granted". Der Inhalts-Hash haelt das aus: er deckt
     * nur die Bewegung ab (AwclipHash), und [rewriteManifest] prueft das nach.
     *
     * PRIVAT -> OEFFENTLICH IST EIN NEUES TEILEN. Wer hochlaedt, bestaetigt
     * die Erklaerung und die Lizenz, und beides wird protokolliert. Ein Clip,
     * der privat hochkam, hatte nur die Erklaerung fuer "nur ich" - also gilt
     * beim Wechsel dasselbe wie beim Hochladen: Erklaerung im Wortlaut, und
     * eine neue [UploadDeclaration] mit der neuen Lizenz. Der umgekehrte Weg
     * braucht nichts: CC0 laesst sich nicht zuruecknehmen, privat heisst nur,
     * dass niemand NEUES ihn mehr findet - dasselbe wie beim Zurueckziehen.
     *
     * Die Follower bekommen keine Nachricht, auch nicht beim Wechsel auf
     * oeffentlich: wer zweimal hin und her schaltet, schickte sonst zwei.
     */
    @Transactional
    fun edit(principal: PortalPrincipal, slug: String, input: PackageEdit, ip: String): PackageDetail {
        val account = accounts.requireUsable(principal.accountId)
        val pkg = packages.findBySlug(slug) ?: throw PortalException.notFound("Package not found")
        if (pkg.ownerId != account.id) throw PortalException.forbidden("Only the owner can edit a clip.")
        if (pkg.status != PackageStatus.PUBLISHED)
            throw PortalException.conflict("package-locked", "This clip is under review, removed or withdrawn.")
        val version = pkg.currentVersionId?.let { versions.findById(it).orElse(null) }
            ?: throw PortalException.notFound("Package not found")

        //  Dieselben Regeln wie der Leser fuer das Manifest (AwclipReader) -
        //  was hier durchgeht, muss auch als Datei wieder durchgehen.
        val title = input.title.trim()
        if (title.isEmpty() || title.length > AwclipSchema.MAX_TITLE_LENGTH || hasControl(title, allowNewline = false))
            throw PortalException.badRequest("invalid-title", "The title must be 1-80 characters on one line.")

        val description = input.description.replace("\r", "").trimEnd()
        if (description.length > AwclipSchema.MAX_DESCRIPTION_LENGTH || hasControl(description, allowNewline = true))
            throw PortalException.badRequest("invalid-description",
                "The description can be up to ${AwclipSchema.MAX_DESCRIPTION_LENGTH} characters.")

        val tags = input.tags.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
        if (tags.size > AwclipSchema.MAX_TAGS)
            throw PortalException.badRequest("invalid-tags", "Up to ${AwclipSchema.MAX_TAGS} tags.")
        tags.firstOrNull { !AwclipSchema.isTag(it) }?.let {
            throw PortalException.badRequest("invalid-tags", "'$it' is not a valid tag - use lowercase letters, digits and '-'.")
        }

        val license = input.license
        if (license != AwclipSchema.LICENSE_PUBLIC && license != AwclipSchema.LICENSE_PRIVATE)
            throw PortalException.badRequest("invalid-license", "Choose public or private.")

        val goingPublic = license == AwclipSchema.LICENSE_PUBLIC && pkg.license != AwclipSchema.LICENSE_PUBLIC
        if (goingPublic && (!input.declarationAccepted || input.declarationText != Declaration.TEXT ||
                input.declarationVersion != Declaration.VERSION))
            throw PortalException.badRequest("declaration-required", "Confirm that you made this animation to share it publicly.")

        val changes = buildList {
            if (title != pkg.title) add("title")
            if (description != pkg.description) add("description")
            if (tags != pkg.tagList()) add("tags")
            if (license != pkg.license) add("license ${pkg.license}->$license")
        }
        if (changes.isEmpty()) return detail(pkg, version, principal)

        val now = clock.instant()
        val rewritten = rewriteManifest(version, title, description, tags, license)
        version.blobKey = blobs.put(rewritten)
        version.sizeBytes = rewritten.size.toLong()
        versions.save(version)

        if (goingPublic) {
            declarations.save(
                UploadDeclaration(
                    accountId = account.id,
                    versionId = version.id,
                    declarationText = Declaration.TEXT,
                    declarationVersion = Declaration.VERSION,
                    license = license,
                    originClass = version.originClass,
                    ipAddress = ip,
                    createdAt = now,
                )
            )
        }

        pkg.title = title
        pkg.description = description
        pkg.tags = AnimationPackage.joinTags(tags)
        pkg.license = license
        pkg.updatedAt = now
        packages.save(pkg)

        audit.record(account.id, "package.edited", "package", slug, changes.joinToString(", "), ip)
        overview.invalidate()
        return detail(pkg, version, principal)
    }

    /**
     * Die gespeicherte Datei mit neuem Kopf: gleiche Bewegung, gleiche
     * Vorschau, nur das Manifest ist anders. Gegenprobe ueber den Leser und
     * den Hash, bevor irgendetwas davon gespeichert wird - aendert das
     * Umschreiben auch nur eine Zahl der Bewegung, bricht es ab, statt eine
     * Datei abzulegen, die nicht mehr zu ihrem Hash passt.
     */
    private fun rewriteManifest(
        version: PackageVersion, title: String, description: String, tags: List<String>, license: String,
    ): ByteArray {
        val text = blobs.open(version.blobKey).use { GZIPInputStream(it).readBytes().toString(Charsets.UTF_8) }
        val root = StrictJson.parse(text) as? StrictJson.Value.Obj
            ?: throw IllegalStateException("Stored file of version ${version.id} is not a JSON object")
        val manifest = root["manifest"] as? StrictJson.Value.Obj
            ?: throw IllegalStateException("Stored file of version ${version.id} has no manifest")

        val replaced = linkedMapOf<String, StrictJson.Value>(
            "title" to StrictJson.Value.Str(title),
            "description" to StrictJson.Value.Str(description),
            "tags" to StrictJson.Value.Arr(tags.map { StrictJson.Value.Str(it) }),
            "license" to StrictJson.Value.Str(license),
        )
        //  Beschreibung und Schlagworte sind im Format optional - fehlen sie
        //  in der Datei, kommen sie hinten dazu.
        val members = manifest.members.map { (key, value) -> key to (replaced[key] ?: value) } +
            replaced.filterKeys { key -> manifest.members.none { it.first == key } }.toList()
        val json = StrictJson.write(StrictJson.Value.Obj(root.members.map { (key, value) ->
            key to (if (key == "manifest") StrictJson.Value.Obj(members) else value)
        }))

        val bytes = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(json.toByteArray(Charsets.UTF_8)) }
        }.toByteArray()

        if (bytes.size > AwclipSchema.MAX_COMPRESSED_BYTES)
            throw PortalException(HttpStatus.PAYLOAD_TOO_LARGE, "too-large", "The file would be too large.")

        val doc = when (val read = AwclipReader.readFile(bytes.inputStream())) {
            is AwclipReadResult.Ok -> read.document
            is AwclipReadResult.Rejected -> throw IllegalStateException("Rewritten file rejected: ${read.error}")
        }
        check(AwclipHash.compute(doc) == version.contentHash) { "Rewriting the manifest changed the motion of ${version.id}" }
        check(doc.manifest.title == title && doc.manifest.license == license) { "Rewritten manifest does not read back" }
        return bytes
    }

    private fun hasControl(text: String, allowNewline: Boolean) =
        text.any { (it == '\n' && !allowNewline) || (it != '\n' && (it.code < 0x20 || it.code == 0x7f)) }

    // ════════════════════════════════════════════════════════════════════
    // LESEN
    // ════════════════════════════════════════════════════════════════════

    /**
     * @param author "alles von dieser Person" ueber den Anzeigenamen - der Weg
     *   aus der Zeit vor den Profilen, der bestehen bleibt, weil die Adresse
     *   `/browse.html?author=…` in Umlauf ist.
     * @param ownerId dasselbe, nur eindeutig: die Clips EINES Kontos, wie das
     *   Profil sie zeigt. Beides geht durch dieselbe Suche, damit es nur eine
     *   Stelle gibt, die entscheidet, was oeffentlich sichtbar ist.
     */
    @Transactional(readOnly = true)
    fun search(q: String?, tag: String?, sort: String?, page: Int, size: Int,
               principal: PortalPrincipal? = null, author: String? = null,
               ownerId: UUID? = null): PageResult<PackageSummary> {
        val pageSize = size.coerceIn(1, 50)
        val pageIndex = page.coerceAtLeast(0)

        //  "Alles von dieser Person". Ueber den Anzeigenamen statt ueber eine
        //  Konto-UUID - siehe [AccountRepository.findByDisplayName]. Gibt es den
        //  Namen nicht, ist das Ergebnis leer statt unbegrenzt: ein Tippfehler
        //  darf nicht stillschweigend den ganzen Katalog zurueckgeben.
        val authorIds = author?.trim()?.takeIf { it.isNotEmpty() }?.take(60)
            ?.let { name -> accountRepository.findByDisplayName(name).map { it.id }.ifEmpty { listOf(NO_ACCOUNT) } }

        val spec = Specification<AnimationPackage> { root, query, cb ->
            val predicates = mutableListOf(
                cb.equal(root.get<PackageStatus>("status"), PackageStatus.PUBLISHED),
                // Der Katalog zeigt nur, was öffentlich geteilt wurde. Private
                // Clips liegen im Portal, sind aber nur über ihren Link zu
                // erreichen - sie tauchen in keiner Liste und keiner Suche auf.
                cb.equal(root.get<String>("license"), AwclipSchema.LICENSE_PUBLIC),
            )

            //  Nur Rigs, die das Portal annimmt - und zwar HIER, in der
            //  Abfrage, nicht erst beim Bauen der Karten.
            //
            //  WARUM DAS EIN UNTERSCHIED IST: [cardsFor] wirft so eine Karte
            //  ohnehin weg, aber die Zahl daneben („3 clips") kommt aus
            //  `totalElements`, und die haette weiter mitgezaehlt. Genau das
            //  war im Entwicklungsstand zu sehen: drei versprochen, zwei
            //  gezeigt. Und auf Seite zwei kaemen dann 23 Karten statt 24.
            //
            //  `rig` haengt an der Fassung, nicht am Paket, und
            //  `currentVersionId` ist eine blanke Spalte ohne Beziehung -
            //  deshalb eine korrelierte EXISTS-Unterabfrage statt eines Joins.
            //
            //  `query` ist in der Signatur nullbar und in der Praxis nie null;
            //  faellt es doch einmal weg, bleibt [cardsFor] die Sperre und nur
            //  die Zahl daneben waere zu gross. Kein Grund, eine Auslage mit
            //  einer Ausnahme abzuwerfen.
            query?.let { q ->
                val version = q.subquery(UUID::class.java)
                val v = version.from(PackageVersion::class.java)
                version.select(v.get("id"))
                version.where(
                    cb.equal(v.get<UUID>("id"), root.get<UUID>("currentVersionId")),
                    v.get<String>("rig").`in`(AwclipSchema.ACCEPTED_RIGS),
                )
                predicates += cb.exists(version)
            }

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

            authorIds?.let { predicates += root.get<UUID>("ownerId").`in`(it) }
            ownerId?.let { predicates += cb.equal(root.get<UUID>("ownerId"), it) }

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

        return PageResult(cardsFor(result.content, principal), pageIndex, pageSize, result.totalElements)
    }

    /**
     * Karten zu einer Liste von Paketen - EINE Abfrage je Seite statt einer je
     * Karte, fuer Autoren, Fassungen, Herzen, Sterne und Quittungen.
     */
    private fun cardsFor(found: List<AnimationPackage>, principal: PortalPrincipal?): List<PackageSummary> {
        val authors = authors(found.map { it.ownerId })
        val currentVersions = versions.findAllById(found.mapNotNull { it.currentVersionId }).associateBy { it.id }

        val likedByMe = principal?.let { me -> likes.likedAmong(me.accountId, found.map { it.id }).toSet() } ?: emptySet()
        val unlockedByMe = principal?.let { me -> unlocks.unlockedAmong(me.accountId, found.map { it.id }) } ?: emptySet()
        val savedByMe = principal?.takeIf { found.isNotEmpty() }
            ?.let { me -> savedCollections.savedAmong(me.accountId, found.map { it.id }).toSet() } ?: emptySet()

        return found.mapNotNull { pkg ->
            val version = currentVersions[pkg.currentVersionId] ?: return@mapNotNull null

            //  Was das Portal nicht mehr annimmt, legt es auch nicht mehr aus.
            //  Der Upload ist zu (siehe [upload]), aber die Beta hat schon
            //  welche gesehen - und eine Karte, deren Figur nicht auftreten
            //  kann, ist eine tote Kachel. Sie faellt still heraus, wie ein
            //  zurueckgezogener Clip: sein Besitzer findet ihn weiter unter
            //  `/me`, und kommt [AwclipSchema.ACCEPTED_RIGS] zurueck, steht er
            //  wieder da.
            if (!AwclipSchema.isAcceptedRig(version.rig)) return@mapNotNull null

            val author = authors[pkg.ownerId]
            PackageSummary(pkg.slug, pkg.title, pkg.tagList(), pkg.license,
                author?.name ?: "unknown", author?.handle,
                version.durationSeconds, version.frameRate, version.rig, pkg.takeCount, pkg.likeCount,
                pkg.saveCount, pkg.commentCount, pkg.id in likedByMe, pkg.id in savedByMe,
                pkg.id in unlockedByMe, version.previewBlobKey != null, pkg.createdAt,
                isOwner = principal?.accountId == pkg.ownerId)
        }
    }

    /**
     * Karten zu bestimmten Paketen, in DIESER Reihenfolge - fuer eine
     * Sammlung, die ihre Auswahl selbst sortiert.
     *
     * Gezeigt wird nur, was auch im Katalog steht: ein zurueckgezogener oder
     * versteckter Clip faellt still heraus, statt als Luecke dazustehen. Die
     * Sammlung behaelt ihn trotzdem - kommt er zurueck, steht er wieder da.
     */
    @Transactional(readOnly = true)
    fun summaries(ids: List<UUID>, principal: PortalPrincipal?): List<PackageSummary> {
        if (ids.isEmpty()) return emptyList()

        val found = packages.findAllById(ids)
            .filter { it.status == PackageStatus.PUBLISHED && it.license == AwclipSchema.LICENSE_PUBLIC }
            .sortedBy { ids.indexOf(it.id) }

        return cardsFor(found, principal)
    }

    /**
     * Ein Paket, das im Katalog steht - und nur dann. Die Eintrittspruefung
     * fuer Sammlungen: was hier scheitert, kommt in keine.
     */
    @Transactional(readOnly = true)
    fun publicPackage(slug: String): AnimationPackage {
        val pkg = packages.findBySlug(slug.trim().lowercase())
            ?: throw PortalException.notFound("Package not found")

        if (pkg.status != PackageStatus.PUBLISHED)
            throw PortalException.notFound("Package not found")
        if (pkg.license != AwclipSchema.LICENSE_PUBLIC)
            throw PortalException.badRequest(
                "private-clip", "A private clip cannot go into a collection - only you can see it.")

        //  Die Eintrittspruefung fragt, was der Katalog zeigt - und der zeigt
        //  nur Rigs, die das Portal annimmt. Ohne diese Zeile liesse sich ein
        //  generischer Clip ueber seinen Slug doch noch in eine Sammlung
        //  legen, wo er dann als Luecke stuende.
        val rig = pkg.currentVersionId?.let { versions.findById(it).orElse(null) }?.rig
        if (rig == null || !AwclipSchema.isAcceptedRig(rig))
            throw PortalException.notFound("Package not found")

        return pkg
    }

    /** Die Fassung zu einer Kennung - fuer den Deckel einer Sammlungskarte. */
    fun versionOf(versionId: UUID): PackageVersion? = versions.findById(versionId).orElse(null)

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

    /**
     * Ein Link fuer einen Clip, den man schon hat. Die Quittung ist die
     * Eintrittskarte - ohne sie fuehrt der Weg ueber [unlock].
     *
     * Offen war dieser Aufruf einmal. Er ist es nicht mehr: ein Zaehler ohne
     * Konto ist nur eine Behauptung.
     */
    @Transactional(readOnly = true)
    fun downloadLink(slug: String, principal: PortalPrincipal, ip: String): DownloadLink {
        rateLimiter.require("download-link", ip, properties.limits.downloadLinksPerHourPerIp, Duration.ofHours(1))

        val (pkg, version) = visible(slug, principal)
        if (pkg.ownerId != principal.accountId && !unlocks.hasUnlocked(pkg.id, principal.accountId))
            throw PortalException.conflict("not-unlocked", "Unlock this clip first.")

        return link(pkg, version)
    }

    /**
     * Quittung schreiben, zaehlen, Link zurueckgeben - alles in einer
     * Transaktion.
     *
     * Er ersetzt den alten Zweischritt aus `download-link` und `taken`. Der
     * alte Zaehler war anonym und damit faelschbar; dieser haengt an einer
     * Zeile je Konto und Paket.
     */
    @Transactional
    fun unlock(slug: String, principal: PortalPrincipal, ip: String): DownloadLink {
        rateLimiter.require("download-link", ip, properties.limits.downloadLinksPerHourPerIp, Duration.ofHours(1))

        val account = accounts.requireUsable(principal.accountId)
        val (pkg, version) = visible(slug, principal)

        if (unlocks.unlock(account, pkg))
            audit.record(account.id, "package.unlocked", "package", pkg.slug, null, ip)

        return link(pkg, version)
    }

    private fun link(pkg: AnimationPackage, version: PackageVersion): DownloadLink {
        val expires = clock.instant().plusSeconds(properties.tokens.downloadLinkSeconds)
        val signature = sign(version.id, expires.epochSecond)
        return DownloadLink(
            "/api/v1/files/${version.id}?exp=${expires.epochSecond}&sig=$signature",
            expires, fileName(pkg, version), pkg.license,
        )
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

        //  Bewusst KEIN Zähler hier. Gezählt wird die Quittung aus [unlock];
        //  der Dateiabruf kann danach beliebig oft kommen, etwa wenn ein
        //  Abonnement erneut gestaged wird.
        return DownloadableFile(fileName(pkg, version), blobs.open(version.blobKey).use { it.readBytes() }, pkg.license)
    }

    // ════════════════════════════════════════════════════════════════════
    // HERZEN
    // ════════════════════════════════════════════════════════════════════

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

    /**
     * Öffentlich sichtbar, oder für Besitzer und Admins auch im Prüfzustand.
     *
     * DER RIEGEL FÜR JEDEN EINZELNEN CLIP: Seite, Vorschau, Download,
     * Freischalten, Herz und Kommentare fragen alle hier. Ein privater Clip
     * (`ARR`) ist nur für seinen Besitzer da - und für Admins, weil auch
     * Privates auf diesem Server liegt und eine Löschanfrage ihn erreichen
     * muss. Alle anderen bekommen dasselbe 404 wie für einen Slug, den es nie
     * gab: ob hinter einem Link ein privater Clip steckt, verrät die Antwort
     * nicht.
     */
    internal fun visible(slug: String, principal: PortalPrincipal?): Pair<AnimationPackage, PackageVersion> {
        val pkg = packages.findBySlug(slug) ?: throw PortalException.notFound("Package not found")
        val privileged = principal != null && (principal.isAdmin || principal.accountId == pkg.ownerId)

        if (pkg.status != PackageStatus.PUBLISHED && !privileged) throw PortalException.notFound("Package not found")
        if (pkg.license != AwclipSchema.LICENSE_PUBLIC && !privileged) throw PortalException.notFound("Package not found")

        val version = pkg.currentVersionId?.let { versions.findById(it).orElse(null) }
            ?: throw PortalException.notFound("Package not found")
        if (version.status != VersionStatus.PUBLISHED && !privileged) throw PortalException.notFound("Package not found")

        return pkg to version
    }

    private fun detail(pkg: AnimationPackage, version: PackageVersion, principal: PortalPrincipal?): PackageDetail {
        val isOwner = principal?.accountId == pkg.ownerId
        val author = authors(listOf(pkg.ownerId))[pkg.ownerId]
        return PackageDetail(
            pkg.slug, pkg.title, pkg.description, pkg.tagList(), pkg.license,
            author?.name ?: "unknown", author?.handle,
            version.versionNumber, version.durationSeconds, version.frameRate, version.curveCount, version.rig,
            pkg.takeCount, pkg.likeCount, pkg.saveCount, pkg.commentCount,
            principal != null && likes.existsByPackageIdAndAccountId(pkg.id, principal.accountId),
            principal != null && savedCollections.collectionsOfOwnerContaining(principal.accountId, pkg.id).isNotEmpty(),
            principal != null && (isOwner || unlocks.hasUnlocked(pkg.id, principal.accountId)),
            version.previewBlobKey != null, pkg.createdAt, pkg.updatedAt,
            if (isOwner || principal?.isAdmin == true) pkg.status.name else null,
            isOwner,
            authorAvatar = author?.avatar,
        )
    }

    /** Eine Kennung, die keinem Konto gehoert - siehe `authorIds` in [search]. */
    private val NO_ACCOUNT: UUID = UUID(0, 0)

    /** Name, Handle und Bild des Erstellers - der Name steht da, der Handle verlinkt. */
    private data class Author(val name: String, val handle: String?, val avatar: String? = null)

    private fun authors(ids: Collection<UUID>): Map<UUID, Author> =
        accountRepository.findAllById(ids.toSet()).associate { it.id to Author(it.displayName, it.handle, it.avatarPath()) }

    private fun authorNames(ids: Collection<UUID>): Map<UUID, String> =
        authors(ids).mapValues { it.value.name }

    private fun sign(versionId: UUID, exp: Long) = Crypto.hmacHex(properties.downloadSecret, "$versionId|$exp")

    private fun fileName(pkg: AnimationPackage, version: PackageVersion) = "${pkg.slug}-v${version.versionNumber}.awclip"

    private fun escapeLike(value: String) = value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}
