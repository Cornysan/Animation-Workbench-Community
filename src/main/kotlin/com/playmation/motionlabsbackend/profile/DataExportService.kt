package com.playmation.motionlabsbackend.profile

import com.playmation.motionlabsbackend.account.Account
import com.playmation.motionlabsbackend.account.AccountIdentityRepository
import com.playmation.motionlabsbackend.account.AccountRepository
import com.playmation.motionlabsbackend.account.AccountService
import com.playmation.motionlabsbackend.auth.ApiTokenRepository
import com.playmation.motionlabsbackend.auth.BrowserLoginRepository
import com.playmation.motionlabsbackend.auth.PortalPrincipal
import com.playmation.motionlabsbackend.catalog.AnimationPackage
import com.playmation.motionlabsbackend.catalog.AnimationPackageRepository
import com.playmation.motionlabsbackend.catalog.ClipPackRepository
import com.playmation.motionlabsbackend.catalog.PackageCommentRepository
import com.playmation.motionlabsbackend.catalog.PackageLikeRepository
import com.playmation.motionlabsbackend.catalog.PackageVersionRepository
import com.playmation.motionlabsbackend.catalog.UploadDeclarationRepository
import com.playmation.motionlabsbackend.catalog.VersionStatus
import com.playmation.motionlabsbackend.collection.ClipCollectionRepository
import com.playmation.motionlabsbackend.collection.CollectionItemRepository
import com.playmation.motionlabsbackend.common.RateLimiter
import com.playmation.motionlabsbackend.format.AwclipSchema
import com.playmation.motionlabsbackend.moderation.ReportRepository
import com.playmation.motionlabsbackend.notification.Notifier
import com.playmation.motionlabsbackend.storage.BlobStore
import com.playmation.motionlabsbackend.system.AuditService
import com.playmation.motionlabsbackend.unlocks.PackageUnlockRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Alles, was das Portal ueber ein Konto weiss, als eine Datei zum Mitnehmen
 * (Art. 15 und 20 DSGVO).
 *
 * Eine ZIP und nicht nur ein JSON: das Wichtigste, was jemand hierher gebracht
 * hat, sind die Clips selbst. Sie liegen darin als die `.awclip`-Dateien, die
 * hochgeladen wurden - jede Fassung, die noch gespeichert ist. `data.json`
 * beschreibt den Rest und zeigt auf sie.
 *
 * WAS NICHT DRIN IST, und warum:
 *   - Meldungen GEGEN dieses Konto oder seine Clips. Sie nennen den, der
 *     gemeldet hat, und dessen Name ist nicht Sache des Gemeldeten. Wer den
 *     Inhalt wissen will, fragt per Mail; dann entscheidet ein Mensch, was
 *     davon herausgegeben werden kann.
 *   - Das Audit-Protokoll. Es ist die Buchfuehrung der Moderation; dieselbe
 *     Mail-Anfrage deckt es ab.
 *   - Hashes der Anmelde-Token. Sie sind die einzige Stelle, an der ein
 *     gestohlenes Token erkannt werden kann, und fuer niemanden sonst lesbar.
 */
@Service
class DataExportService(
    private val accountService: AccountService,
    private val accounts: AccountRepository,
    private val identities: AccountIdentityRepository,
    private val packages: AnimationPackageRepository,
    private val versions: PackageVersionRepository,
    private val declarations: UploadDeclarationRepository,
    private val packs: ClipPackRepository,
    private val collections: ClipCollectionRepository,
    private val collectionItems: CollectionItemRepository,
    private val comments: PackageCommentRepository,
    private val likes: PackageLikeRepository,
    private val follows: AccountFollowRepository,
    private val unlocks: PackageUnlockRepository,
    private val reports: ReportRepository,
    private val notifier: Notifier,
    private val apiTokens: ApiTokenRepository,
    private val browserLogins: BrowserLoginRepository,
    private val blobs: BlobStore,
    private val rateLimiter: RateLimiter,
    private val audit: AuditService,
    private val json: JsonMapper,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Die fertige Datei - Name und Inhalt. */
    class ExportFile(val fileName: String, val bytes: ByteArray)

    // ── Was in data.json steht ──────────────────────────────────────────

    data class ClipRef(val slug: String, val title: String?, val url: String?)
    data class PersonRef(val handle: String?, val displayName: String, val since: Instant)

    data class AccountPart(
        val id: UUID, val handle: String?, val displayName: String, val bio: String?,
        val pictureUrlAtProvider: String?, val nameAndPictureFrom: String?,
        val role: String, val status: String, val createdAt: Instant, val lastSignInAt: Instant?,
    )
    data class SignInPart(
        val provider: String, val idAtProvider: String, val connectedAt: Instant, val lastUsedAt: Instant?,
        val nameAndPictureComeFromHere: Boolean,
    )
    data class VersionPart(
        val version: Int, val uploadedAt: Instant, val status: String, val file: String?,
        val rig: String, val frameRate: Float, val durationSeconds: Float, val contentHash: String,
    )
    data class ClipPart(
        val slug: String, val url: String, val title: String, val description: String, val tags: List<String>,
        val license: String, val visibility: String, val status: String, val pack: String?,
        val createdAt: Instant, val updatedAt: Instant,
        val likes: Long, val usedInProjects: Long, val savedToCollections: Long, val comments: Long,
        val versions: List<VersionPart>,
    )
    data class PackPart(val slug: String, val title: String, val description: String, val clips: List<String>,
                        val createdAt: Instant)
    data class CollectionPart(
        val slug: String, val title: String, val description: String, val visibility: String, val status: String,
        val createdAt: Instant, val clips: List<ClipRef>,
    )
    data class CommentPart(val clip: ClipRef, val text: String, val status: String, val createdAt: Instant,
                           val editedAt: Instant?)
    data class DatedClip(val clip: ClipRef, val at: Instant)
    data class ReportPart(val about: String, val clip: ClipRef?, val category: String, val message: String,
                          val status: String, val createdAt: Instant, val resolvedAt: Instant?)
    data class DeclarationPart(val clipVersion: String?, val declarationVersion: Int, val license: String,
                               val origin: String, val ipAddress: String?, val createdAt: Instant)
    data class NotificationPart(val message: String, val link: String?, val read: Boolean, val createdAt: Instant)
    data class SessionPart(val label: String?, val createdAt: Instant, val lastUsedAt: Instant?,
                           val expiresAt: Instant, val endedAt: Instant?)

    data class Export(
        val format: String,
        val formatVersion: Int,
        val exportedAt: Instant,
        val portal: String,
        val account: AccountPart,
        val signIns: List<SignInPart>,
        val clips: List<ClipPart>,
        val packs: List<PackPart>,
        val collections: List<CollectionPart>,
        val comments: List<CommentPart>,
        val likes: List<DatedClip>,
        val following: List<PersonRef>,
        val followers: List<PersonRef>,
        val clipsTaken: List<DatedClip>,
        val reportsFiled: List<ReportPart>,
        val uploadDeclarations: List<DeclarationPart>,
        val notifications: List<NotificationPart>,
        val workbenchSignIns: List<SessionPart>,
        val browserSignIns: List<SessionPart>,
    )

    // ── Bauen ───────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    fun export(principal: PortalPrincipal, baseUrl: String, ip: String): ExportFile {
        //  Auch ein gesperrtes Konto bekommt seine Daten - das Recht haengt
        //  nicht am Wohlverhalten, genau wie beim Schliessen.
        val account = accountService.get(principal.accountId)
        rateLimiter.require("export", account.id.toString(), 5, Duration.ofHours(1))

        val now = clock.instant()
        val ownClips = packages.findByOwnerIdOrderByCreatedAtDesc(account.id)
        val files = linkedMapOf<String, ByteArray>()

        //  Clips anderer Leute, auf die Herzen, Kommentare, Sammlungen zeigen -
        //  einmal geladen statt je Zeile.
        val referenced = mutableSetOf<UUID>()
        val commentRows = comments.findByAccountIdOrderByCreatedAtAsc(account.id).also { list -> referenced += list.map { it.packageId } }
        val likeRows = likes.findByAccountId(account.id).sortedBy { it.createdAt }.also { list -> referenced += list.map { it.packageId } }
        val takeRows = unlocks.findByAccountIdOrderByCreatedAtAsc(account.id).also { list -> referenced += list.map { it.packageId } }
        val collectionRows = collections.findByOwnerId(account.id).sortedBy { it.createdAt }
        val itemsByCollection = collectionRows.associate { it.id to collectionItems.findByCollectionIdOrderByPositionAsc(it.id) }
        referenced += itemsByCollection.values.flatten().map { it.packageId }
        val reportRows = reports.findByReporterIdOrderByCreatedAtAsc(account.id).also { list -> referenced += list.mapNotNull { it.packageId } }
        val clipsById = (packages.findAllById(referenced - ownClips.map { it.id }.toSet()) + ownClips).associateBy { it.id }

        fun ref(id: UUID?): ClipRef? {
            val pkg = id?.let { clipsById[it] } ?: return null
            //  Was nicht (mehr) im Katalog steht, bekommt keinen Link - aber
            //  den Titel, damit die Zeile noch etwas sagt. Eigene Clips sieht
            //  man selbst immer.
            val listed = pkg.ownerId == account.id ||
                (pkg.status.name == "PUBLISHED" && pkg.license == AwclipSchema.LICENSE_PUBLIC)
            return ClipRef(pkg.slug, if (listed) pkg.title else null,
                if (listed) "$baseUrl/clip.html?p=${pkg.slug}" else null)
        }

        val packSlugs = packs.findByOwnerIdOrderByCreatedAtDesc(account.id).associate { it.id to it.slug }

        val clipParts = ownClips.map { pkg ->
            val versionParts = versions.findByPackageIdOrderByVersionNumberDesc(pkg.id).map { version ->
                val name = "clips/${pkg.slug}-v${version.versionNumber}.awclip"
                val stored = version.status == VersionStatus.PUBLISHED && addFile(files, name, version.blobKey)
                VersionPart(version.versionNumber, version.createdAt, version.status.name,
                    if (stored) name else null, version.rig, version.frameRate, version.durationSeconds,
                    version.contentHash)
            }
            clipPart(pkg, baseUrl, pkg.packId?.let { packSlugs[it] }, versionParts)
        }

        val versionLabels = ownClips.flatMap { pkg ->
            versions.findByPackageIdOrderByVersionNumberDesc(pkg.id).map { it.id to "${pkg.slug} v${it.versionNumber}" }
        }.toMap()

        val export = Export(
            format = "playmations-account-export",
            formatVersion = 1,
            exportedAt = now,
            portal = baseUrl,
            account = AccountPart(
                account.id, account.handle, account.displayName, account.bio, account.avatarUrl,
                accountService.profileSource(account.id)?.provider,
                account.role.name, account.status.name, account.createdAt, account.lastLoginAt),
            signIns = run {
                val source = accountService.profileSource(account.id)?.id
                identities.findByAccountIdOrderByCreatedAtAsc(account.id).map {
                    SignInPart(it.provider, it.subject, it.createdAt, it.lastLoginAt, it.id == source)
                }
            },
            clips = clipParts,
            packs = packs.findByOwnerIdOrderByCreatedAtDesc(account.id).map { pack ->
                PackPart(pack.slug, pack.title, pack.description,
                    packages.findByPackIdOrderByPackPositionAsc(pack.id).map { it.slug }, pack.createdAt)
            },
            collections = collectionRows.map { c ->
                CollectionPart(c.slug, c.title, c.description, c.visibility.name, c.status.name, c.createdAt,
                    itemsByCollection[c.id].orEmpty().mapNotNull { ref(it.packageId) })
            },
            comments = commentRows.mapNotNull { c ->
                ref(c.packageId)?.let { CommentPart(it, c.body, c.status.name, c.createdAt, c.editedAt) }
            },
            likes = likeRows.mapNotNull { l -> ref(l.packageId)?.let { DatedClip(it, l.createdAt) } },
            following = people(follows.findByFollowerIdOrderByCreatedAtDesc(account.id).map { it.followeeId to it.createdAt }),
            followers = people(follows.findByFolloweeIdOrderByCreatedAtDesc(account.id).map { it.followerId to it.createdAt }),
            clipsTaken = takeRows.mapNotNull { t -> ref(t.packageId)?.let { DatedClip(it, t.createdAt) } },
            reportsFiled = reportRows.map { r ->
                ReportPart(
                    about = when {
                        r.commentId != null -> "comment"
                        r.accountId != null -> "account"
                        else -> "clip"
                    },
                    clip = ref(r.packageId), category = r.category.name, message = r.message,
                    status = r.status.name, createdAt = r.createdAt, resolvedAt = r.resolvedAt)
            },
            uploadDeclarations = declarations.findByAccountIdOrderByCreatedAtAsc(account.id).map { d ->
                DeclarationPart(versionLabels[d.versionId], d.declarationVersion, d.license, d.originClass,
                    //  Nach 30 Tagen steht dort ein Pseudonym, keine Adresse -
                    //  das waere nur eine Zeichenkette, die nach etwas aussieht.
                    d.ipAddress.takeUnless { d.ipPseudonymized }, d.createdAt)
            },
            notifications = notifier.inbox(account.id, limit = Int.MAX_VALUE, markRead = false).map {
                NotificationPart(it.message, it.link?.let { link -> baseUrl + link }, it.read, it.createdAt)
            },
            workbenchSignIns = apiTokens.findByAccountIdOrderByCreatedAtAsc(account.id).map {
                SessionPart(it.label, it.createdAt, it.lastUsedAt, it.expiresAt, it.revokedAt)
            },
            browserSignIns = browserLogins.findByAccountIdOrderByCreatedAtAsc(account.id).map {
                SessionPart(null, it.createdAt, it.lastUsedAt, it.expiresAt, null)
            },
        )

        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use { out ->
            fun put(name: String, bytes: ByteArray) {
                out.putNextEntry(ZipEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
            put("README.txt", readme(account, baseUrl, now).toByteArray(Charsets.UTF_8))
            put("data.json", json.writerWithDefaultPrettyPrinter().writeValueAsBytes(export))
            files.forEach { (name, bytes) -> put(name, bytes) }
        }

        audit.record(account.id, "account.exported", "account", account.id.toString(),
            "clips=${ownClips.size} files=${files.size}", ip)

        val day = now.atOffset(ZoneOffset.UTC).toLocalDate()
        return ExportFile("playmations-${account.handle ?: "account"}-$day.zip", zip.toByteArray())
    }

    private fun clipPart(pkg: AnimationPackage, baseUrl: String, pack: String?, versions: List<VersionPart>) = ClipPart(
        slug = pkg.slug,
        url = "$baseUrl/clip.html?p=${pkg.slug}",
        title = pkg.title,
        description = pkg.description,
        tags = pkg.tagList(),
        license = pkg.license,
        visibility = if (pkg.license == AwclipSchema.LICENSE_PUBLIC) "public" else "private",
        status = pkg.status.name,
        pack = pack,
        createdAt = pkg.createdAt,
        updatedAt = pkg.updatedAt,
        likes = pkg.likeCount,
        usedInProjects = pkg.takeCount,
        savedToCollections = pkg.saveCount,
        comments = pkg.commentCount,
        versions = versions,
    )

    /**
     * Eine Datei aus dem Speicher in die ZIP. Fehlt sie (eine entfernte
     * Fassung, deren Datei schon weg ist), steht die Fassung ohne Datei im
     * JSON - ein Export, der an einer fehlenden Datei scheitert, hilft
     * niemandem.
     */
    private fun addFile(files: MutableMap<String, ByteArray>, name: String, key: String): Boolean =
        runCatching { blobs.open(key).use { it.readBytes() } }
            .onFailure { log.warn("Export: blob {} for {} is missing", key, name) }
            .getOrNull()
            ?.let { files[name] = it; true } ?: false

    private fun people(rows: List<Pair<UUID, Instant>>): List<PersonRef> {
        val byId = accounts.findAllById(rows.map { it.first }).associateBy { it.id }
        return rows.mapNotNull { (id, since) ->
            val person: Account = byId[id] ?: return@mapNotNull null
            PersonRef(person.handle, person.displayName, since)
        }
    }

    private fun readme(account: Account, baseUrl: String, now: Instant) = """
        Your data from the Animation Workbench Community ($baseUrl)
        Exported $now for ${account.displayName}${account.handle?.let { " (@$it)" } ?: ""}.

        data.json   Everything the portal keeps about your account, in one JSON file:
                    your profile, the sign-ins connected to it (with your ID at Discord,
                    GitHub or Google), your clips and their versions, packs, collections,
                    comments, likes, who you follow and who follows you, the clips you
                    took, reports you filed, the upload declarations you accepted, your
                    notifications, and your Workbench and browser sign-ins.
        clips/      The .awclip files you uploaded, one per stored version. The
                    Animation Workbench opens them (Tools > Animation Workbench >
                    Community > Import .awclip File), and data.json names the file for
                    each version.

        Not included: reports other people filed about you or your clips (they name
        the person who reported), and the operator's audit log. Ask for those by
        email - the address is on the Legal notice page.

        Times are UTC, in ISO 8601.
    """.trimIndent() + "\n"
}
