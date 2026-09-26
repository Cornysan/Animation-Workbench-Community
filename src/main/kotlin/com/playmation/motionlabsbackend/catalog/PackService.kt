package com.playmation.motionlabsbackend.catalog

import com.playmation.motionlabsbackend.account.Account
import com.playmation.motionlabsbackend.account.AccountRepository
import com.playmation.motionlabsbackend.account.AccountService
import com.playmation.motionlabsbackend.account.avatarPath
import com.playmation.motionlabsbackend.auth.PortalPrincipal
import com.playmation.motionlabsbackend.common.Crypto
import com.playmation.motionlabsbackend.common.PortalException
import com.playmation.motionlabsbackend.common.RateLimiter
import com.playmation.motionlabsbackend.format.AwclipSchema
import com.playmation.motionlabsbackend.notification.NotificationKind
import com.playmation.motionlabsbackend.notification.NotificationLinks
import com.playmation.motionlabsbackend.profile.ProfileService
import com.playmation.motionlabsbackend.system.AuditService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Der Deckel einer Pack-Karte: der erste Clip darin, als Bewegung. */
data class PackCover(val slug: String, val rig: String)

/**
 * Ein Pack, wie ihn eine Karte zeigt. Alles ausser Titel und Beschreibung
 * kommt aus den Clips, die gerade zu sehen sind.
 */
data class PackSummary(
    val slug: String,
    val title: String,
    val description: String,
    val author: String,
    val authorHandle: String?,
    /** Sichtbare Clips - die Zahl auf dem Stapel. */
    val clips: Int,
    val cover: PackCover?,
    /** Die haeufigsten Schlagworte seiner Clips. Ein Pack hat keine eigenen. */
    val tags: List<String>,
    /** Alle Clips hintereinander. */
    val durationSeconds: Float,
    /** Uebernahmen und Herzen aller Clips zusammen - danach sortiert "Most used". */
    val downloads: Long,
    val likes: Long,
    /** Nur wenn alle Clips dieselbe Quelle nennen (Starter-Clips). */
    val source: ClipSource?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val isOwner: Boolean,
)

data class PackDetail(
    val slug: String,
    val title: String,
    val description: String,
    val author: String,
    val authorHandle: String?,
    val authorAvatar: String?,
    val clips: Int,
    val tags: List<String>,
    val durationSeconds: Float,
    val downloads: Long,
    val likes: Long,
    val source: ClipSource?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val isOwner: Boolean,
    /** Die Clips in der Reihenfolge des Packs. */
    val items: List<PackageSummary>,
)

/**
 * Packs (V13).
 *
 * Ein Pack ist die Einheit, in der jemand veroeffentlicht: "diese zwoelf Clips
 * sind ein Satz". Er enthaelt nur EIGENE Clips, und jeder Clip gehoert
 * hoechstens einem - das Gegenstueck zur Sammlung, die fremde Clips
 * zusammentraegt und in der ein Clip beliebig oft liegen darf.
 *
 * Hinein darf nur, was im Katalog steht (oeffentlich, veroeffentlicht, mit
 * einem Rig, das das Portal annimmt): ein Pack ist oeffentlich, also waere
 * ein privater Clip darin entweder sichtbar oder eine Luecke. Verschwindet ein
 * Clip spaeter, faellt er still heraus wie aus einer Sammlung, und die
 * Zugehoerigkeit bleibt stehen - kommt er zurueck, steht er wieder an seinem
 * Platz.
 *
 * Aufloesen loescht den Pack, nicht die Clips: sie stehen danach wieder
 * einzeln im Katalog.
 */
@Service
class PackService(
    private val packs: ClipPackRepository,
    private val packages: AnimationPackageRepository,
    private val catalog: CatalogService,
    private val accounts: AccountRepository,
    private val accountService: AccountService,
    /** Nur fuer die Nachricht an die Follower, wenn ein Pack erscheint. */
    private val profiles: ProfileService,
    private val audit: AuditService,
    private val rateLimiter: RateLimiter,
    private val clock: Clock,
) {
    companion object {
        const val MIN_TITLE = 3

        /** Ein Pack aus einem Clip ist ein Clip mit Umweg. */
        const val MIN_CLIPS = 2

        /** Mixamos groesster Pack hat 49; Luft nach oben, keine offene Tuer. */
        const val MAX_CLIPS = 100

        /** Wie viele Schlagworte eine Pack-Karte nennt. */
        const val MAX_TAGS = 6
    }

    // ════════════════════════════════════════════════════════════════════
    // LESEN
    // ════════════════════════════════════════════════════════════════════

    /**
     * Ein Pack mit seinen sichtbaren Clips. Hat er keinen einzigen, gibt es
     * ihn fuer alle ausser seinem Besitzer (und der Moderation) nicht - dasselbe
     * 404 wie fuer einen Slug, den es nie gab.
     */
    @Transactional(readOnly = true)
    fun detail(slug: String, principal: PortalPrincipal?): PackDetail {
        val pack = packs.findBySlug(slug.trim().lowercase()) ?: throw PortalException.notFound("Pack not found")
        val shown = shownClips(pack)
        if (shown.isEmpty() && !canManage(principal, pack) && principal?.isAdmin != true)
            throw PortalException.notFound("Pack not found")
        return detail(pack, shown, principal)
    }

    private fun detail(pack: ClipPack, principal: PortalPrincipal?) = detail(pack, shownClips(pack), principal)

    private fun detail(pack: ClipPack, shown: List<Pair<AnimationPackage, PackageVersion>>,
                       principal: PortalPrincipal?): PackDetail {
        val owner = accounts.findById(pack.ownerId).orElse(null)
        val summary = summarize(pack, shown, owner, principal)
        return PackDetail(
            summary.slug, summary.title, summary.description, summary.author, summary.authorHandle,
            owner?.avatarPath(), summary.clips, summary.tags, summary.durationSeconds, summary.downloads,
            summary.likes, summary.source, summary.createdAt, summary.updatedAt, summary.isOwner,
            items = catalog.cardsFor(shown.map { it.first }, principal),
        )
    }

    /**
     * Die Packs eines Kontos. Der Besitzer sieht auch die, in denen gerade kein
     * Clip zu sehen ist - sonst fände er einen Pack nicht wieder, dessen Clips
     * er alle auf privat gestellt hat.
     */
    @Transactional(readOnly = true)
    fun ofOwner(ownerId: UUID, principal: PortalPrincipal?): List<PackSummary> {
        val found = packs.findByOwnerIdOrderByCreatedAtDesc(ownerId)
        val manages = found.firstOrNull()?.let { canManage(principal, it) } ?: false
        return summaries(found, principal, keepEmpty = manages)
    }

    /**
     * Die Packs fuer die Katalogwand ([CatalogWall]) - ALLE, die passen, nicht
     * seitenweise. Packs sind wenige, und sortieren laesst sich erst, wenn ihre
     * Zahlen aus den Clips zusammengezaehlt sind.
     *
     * Die Suche fragt dieselben Dinge wie beim Clip: Titel und Beschreibung
     * des Packs, und die Schlagworte - die seiner Clips, er hat keine eigenen.
     *
     * @param ownerIds nur Packs dieser Konten; null = alle.
     */
    @Transactional(readOnly = true)
    fun matching(q: String?, tag: String?, ownerIds: List<UUID>?, principal: PortalPrincipal?): List<PackSummary> {
        val candidates = if (ownerIds == null) packs.findAll()
            else ownerIds.distinct().flatMap { packs.findByOwnerIdOrderByCreatedAtDesc(it) }
        if (candidates.isEmpty()) return emptyList()

        val term = q?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.take(80)
        //  Ein ungueltiges Schlagwort grenzt nichts ein - wie in der Suche der Clips.
        val wanted = tag?.trim()?.lowercase()?.takeIf { AwclipSchema.isTag(it) }

        val shown = shownClipsOf(candidates)
        val authors = authorsOf(candidates)

        return candidates.mapNotNull { pack ->
            val clips = shown[pack.id].orEmpty()
            if (clips.isEmpty()) return@mapNotNull null

            val tags = clips.flatMap { it.first.tagList() }.toSet()
            if (wanted != null && wanted !in tags) return@mapNotNull null
            if (term != null && !pack.title.lowercase().contains(term) &&
                !pack.description.lowercase().contains(term) && tags.none { it.contains(term) })
                return@mapNotNull null

            summarize(pack, clips, authors[pack.ownerId], principal)
        }
    }

    private fun summaries(found: List<ClipPack>, principal: PortalPrincipal?, keepEmpty: Boolean): List<PackSummary> {
        if (found.isEmpty()) return emptyList()
        val shown = shownClipsOf(found)
        val authors = authorsOf(found)
        return found.mapNotNull { pack ->
            val clips = shown[pack.id].orEmpty()
            if (clips.isEmpty() && !keepEmpty) null else summarize(pack, clips, authors[pack.ownerId], principal)
        }
    }

    private fun summarize(pack: ClipPack, clips: List<Pair<AnimationPackage, PackageVersion>>,
                          owner: Account?, principal: PortalPrincipal?): PackSummary {
        val cover = clips.firstOrNull { it.second.previewBlobKey != null }
            ?.let { (pkg, version) -> PackCover(pkg.slug, version.rig) }

        val tags = clips.flatMap { it.first.tagList() }.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(MAX_TAGS).map { it.key }

        //  Die Quelle nur, wenn sie fuer ALLE gilt. Ein Pack, dessen Clips
        //  verschiedene Herkunft nennen, bekommt keine Zeile, die einer davon
        //  widerspricht.
        val source = clips.map { it.first.sourceCredit to it.first.sourceUrl }.distinct().singleOrNull()
            ?.let { (credit, url) -> credit?.let { ClipSource(it, url) } }

        return PackSummary(
            slug = pack.slug,
            title = pack.title,
            description = pack.description,
            author = owner?.displayName ?: "unknown",
            authorHandle = owner?.handle,
            clips = clips.size,
            cover = cover,
            tags = tags,
            durationSeconds = clips.sumOf { it.second.durationSeconds.toDouble() }.toFloat(),
            downloads = clips.sumOf { it.first.takeCount },
            likes = clips.sumOf { it.first.likeCount },
            source = source,
            createdAt = pack.createdAt,
            updatedAt = pack.updatedAt,
            isOwner = canManage(principal, pack),
        )
    }

    /** Die sichtbaren Clips eines Packs, in seiner Reihenfolge. */
    private fun shownClips(pack: ClipPack) =
        catalog.shown(packages.findByPackIdOrderByPackPositionAsc(pack.id))

    /** Dasselbe fuer viele Packs - eine Abfrage fuer alle. */
    private fun shownClipsOf(found: List<ClipPack>): Map<UUID, List<Pair<AnimationPackage, PackageVersion>>> =
        catalog.shown(packages.findByPackIdIn(found.map { it.id }))
            .sortedBy { it.first.packPosition ?: Int.MAX_VALUE }
            .groupBy { it.first.packId!! }

    private fun authorsOf(found: List<ClipPack>): Map<UUID, Account> =
        accounts.findAllById(found.map { it.ownerId }.toSet()).associateBy { it.id }

    // ════════════════════════════════════════════════════════════════════
    // ANLEGEN UND AENDERN
    // ════════════════════════════════════════════════════════════════════

    data class PackInput(
        val title: String = "",
        val description: String = "",
        /** Die Clips, in dieser Reihenfolge - nur beim Anlegen. */
        val clips: List<String> = emptyList(),
        /** Ein Admin legt den Pack fuer die Starter-Clips an ([StarterClips]). */
        val starter: Boolean = false,
    )

    /**
     * Einen Pack aus eigenen Clips anlegen. Alle werden geprueft, BEVOR etwas
     * gespeichert ist - ein Pack, der am fuenften Clip scheitert, soll nicht mit
     * vier dastehen.
     */
    @Transactional
    fun create(principal: PortalPrincipal, input: PackInput, ip: String): PackDetail {
        val actor = accountService.requireUsable(principal.accountId)
        val ownerId = if (input.starter) {
            if (!principal.isAdmin) throw PortalException.forbidden("Only admins can make a starter pack.")
            StarterClips.ACCOUNT_ID
        } else actor.id
        rateLimiter.require("pack", actor.id.toString(), 20, Duration.ofHours(1))

        val title = title(input.title)
        val description = description(input.description)

        val slugs = slugs(input.clips)
        if (slugs.size < MIN_CLIPS)
            throw PortalException.badRequest("too-few-clips", "A pack needs at least $MIN_CLIPS clips.")
        if (slugs.size > MAX_CLIPS)
            throw PortalException.badRequest("too-many-clips", "A pack holds at most $MAX_CLIPS clips.")
        val clips = slugs.map { member(ownerId, null, it) }

        val now = clock.instant()
        val pack = packs.save(ClipPack(slug = newSlug(), ownerId = ownerId, title = title,
            description = description, createdAt = now, updatedAt = now))

        clips.forEachIndexed { index, pkg ->
            pkg.packId = pack.id
            pkg.packPosition = index
            packages.save(pkg)
        }

        audit.record(actor.id, "pack.created", "pack", pack.slug,
            "${clips.size} clips: " + clips.joinToString(",") { it.slug }, ip)

        //  EINE Nachricht fuer den ganzen Pack. Die Clips darin haben beim
        //  Hochladen je eine ausgeloest, es sei denn, die Workbench hat sie als
        //  Teil eines Packs geschickt (`notifyFollowers=false`).
        val owner = accounts.findById(ownerId).orElse(null) ?: actor
        profiles.notifyFollowers(owner, "shared a new pack: '$title' (${clips.size} clips).",
            NotificationKind.NEW_PACK, NotificationLinks.pack(pack.slug))

        return detail(pack, principal)
    }

    @Transactional
    fun update(principal: PortalPrincipal, slug: String, input: PackInput, ip: String): PackDetail {
        val pack = own(principal, slug)
        val title = title(input.title)
        val description = description(input.description)

        if (title != pack.title || description != pack.description) {
            pack.title = title
            pack.description = description
            pack.updatedAt = clock.instant()
            packs.save(pack)
            audit.record(principal.accountId, "pack.updated", "pack", pack.slug, null, ip)
        }
        return detail(pack, principal)
    }

    data class ClipsRequest(val clips: List<String> = emptyList())

    /** Clips hinten anhaengen - in der Reihenfolge, in der sie kommen. */
    @Transactional
    fun addClips(principal: PortalPrincipal, slug: String, clipSlugs: List<String>, ip: String): PackDetail {
        val pack = own(principal, slug)
        val current = packages.findByPackIdOrderByPackPositionAsc(pack.id)
        val adding = slugs(clipSlugs).map { member(pack.ownerId, pack.id, it) }.filter { it.packId != pack.id }

        if (current.size + adding.size > MAX_CLIPS)
            throw PortalException.conflict("pack-full", "A pack holds at most $MAX_CLIPS clips.")

        var position = (current.mapNotNull { it.packPosition }.maxOrNull() ?: -1) + 1
        for (pkg in adding) {
            pkg.packId = pack.id
            pkg.packPosition = position++
            packages.save(pkg)
        }

        if (adding.isNotEmpty()) {
            touch(pack)
            audit.record(principal.accountId, "pack.clips-added", "pack", pack.slug,
                adding.joinToString(",") { it.slug }, ip)
        }
        return detail(pack, principal)
    }

    /** Einen Clip herausnehmen. Er steht danach wieder einzeln im Katalog. */
    @Transactional
    fun removeClip(principal: PortalPrincipal, slug: String, clipSlug: String, ip: String): PackDetail {
        val pack = own(principal, slug)
        val pkg = packages.findBySlug(clipSlug.trim().lowercase())
            ?: throw PortalException.notFound("Clip not found")

        if (pkg.packId == pack.id) {
            pkg.packId = null
            pkg.packPosition = null
            packages.save(pkg)
            touch(pack)
            audit.record(principal.accountId, "pack.clip-removed", "pack", pack.slug, pkg.slug, ip)
        }
        return detail(pack, principal)
    }

    /**
     * Die Reihenfolge neu setzen. Was fehlt, bleibt hinten stehen - eine
     * unvollstaendige Angabe darf keinen Clip aus dem Pack werfen.
     */
    @Transactional
    fun reorder(principal: PortalPrincipal, slug: String, clipSlugs: List<String>): PackDetail {
        val pack = own(principal, slug)
        val current = packages.findByPackIdOrderByPackPositionAsc(pack.id)
        val wanted = slugs(clipSlugs).mapNotNull { s -> current.firstOrNull { it.slug == s } }
        val order = (wanted + current).distinct()

        order.forEachIndexed { index, pkg ->
            pkg.packPosition = index
            packages.save(pkg)
        }
        touch(pack)
        return detail(pack, principal)
    }

    /**
     * Den Pack aufloesen. Die Clips bleiben, wo sie sind, und stehen wieder
     * einzeln im Katalog - verloren geht nur die Klammer.
     *
     * Die Moderation darf das an jedem Pack: Titel und Beschreibung sind
     * Nutzertext, und ein Pack ohne sie ist nur noch eine Liste.
     */
    @Transactional
    fun delete(principal: PortalPrincipal, slug: String, ip: String) {
        val pack = packs.findBySlug(slug.trim().lowercase()) ?: throw PortalException.notFound("Pack not found")
        if (!canManage(principal, pack) && !principal.isAdmin)
            throw PortalException.forbidden("Only the owner can take a pack apart.")

        for (pkg in packages.findByPackIdOrderByPackPositionAsc(pack.id)) {
            pkg.packId = null
            pkg.packPosition = null
            packages.save(pkg)
        }
        packs.delete(pack)
        audit.record(principal.accountId, "pack.deleted", "pack", slug, pack.title, ip)
    }

    /**
     * Alle Packs eines Kontos aufloesen - beim Schliessen des Kontos. Titel und
     * Beschreibung gehoeren der Person und gehen mit ihr.
     */
    @Transactional
    fun deleteAllOf(ownerId: UUID): Int {
        val own = packs.findByOwnerIdOrderByCreatedAtDesc(ownerId)
        for (pack in own) {
            for (pkg in packages.findByPackIdOrderByPackPositionAsc(pack.id)) {
                pkg.packId = null
                pkg.packPosition = null
                packages.save(pkg)
            }
            packs.delete(pack)
        }
        return own.size
    }

    // ── Hilfen ──────────────────────────────────────────────────────────

    /**
     * Wer einen Pack bearbeiten darf: sein Besitzer - und bei den Starter-Clips
     * jeder Admin, wie bei den Clips selbst (CatalogService.canManage).
     */
    private fun canManage(principal: PortalPrincipal?, pack: ClipPack) =
        principal != null && (principal.accountId == pack.ownerId ||
            (principal.isAdmin && pack.ownerId == StarterClips.ACCOUNT_ID))

    private fun own(principal: PortalPrincipal, slug: String): ClipPack {
        val pack = packs.findBySlug(slug.trim().lowercase()) ?: throw PortalException.notFound("Pack not found")
        if (!canManage(principal, pack)) throw PortalException.forbidden("Only the owner can change this pack.")
        return pack
    }

    /**
     * Darf dieser Clip in diesen Pack? Er muss dem Besitzer des Packs gehoeren,
     * im Katalog stehen und in keinem ANDEREN Pack liegen.
     */
    private fun member(ownerId: UUID, packId: UUID?, slug: String): AnimationPackage {
        val pkg = packages.findBySlug(slug) ?: throw PortalException.notFound("Clip '$slug' not found")

        if (pkg.ownerId != ownerId)
            throw PortalException.forbidden("A pack holds only its owner's own clips.")

        if (catalog.shown(listOf(pkg)).isEmpty()) {
            if (pkg.status == PackageStatus.PUBLISHED && pkg.license != AwclipSchema.LICENSE_PUBLIC)
                throw PortalException.badRequest("private-clip",
                    "'${pkg.title}' is private. A pack is public, so only public clips go in.")
            throw PortalException.conflict("clip-unavailable", "'${pkg.title}' is not in the catalogue.")
        }

        val other = pkg.packId?.takeIf { it != packId }?.let { packs.findById(it).orElse(null) }
        if (other != null)
            throw PortalException.conflict("in-another-pack",
                "'${pkg.title}' is already in the pack '${other.title}'. A clip belongs to one pack.", other.slug)

        return pkg
    }

    private fun slugs(values: List<String>) =
        values.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()

    private fun touch(pack: ClipPack) {
        pack.updatedAt = clock.instant()
        packs.save(pack)
    }

    /** Dieselben Regeln wie der Titel eines Clips, nur mit Mindestlaenge. */
    private fun title(value: String): String {
        val trimmed = value.trim().replace(Regex("\\s+"), " ")
        if (trimmed.length < MIN_TITLE || trimmed.length > AwclipSchema.MAX_TITLE_LENGTH)
            throw PortalException.badRequest("invalid-title",
                "A pack name is $MIN_TITLE to ${AwclipSchema.MAX_TITLE_LENGTH} characters long.")
        if (trimmed.any { it.isISOControl() })
            throw PortalException.badRequest("invalid-title", "A pack name cannot contain control characters.")
        return trimmed
    }

    private fun description(value: String): String {
        val trimmed = value.replace("\r", "").trim()
        if (trimmed.length > AwclipSchema.MAX_DESCRIPTION_LENGTH)
            throw PortalException.badRequest("invalid-description",
                "A description is at most ${AwclipSchema.MAX_DESCRIPTION_LENGTH} characters long.")
        if (trimmed.any { it != '\n' && it.isISOControl() })
            throw PortalException.badRequest("invalid-description", "A description cannot contain control characters.")
        return trimmed
    }

    private fun newSlug(): String {
        repeat(10) {
            val slug = Crypto.randomCode(8, "abcdefghijkmnpqrstuvwxyz23456789")
            if (packs.findBySlug(slug) == null) return slug
        }
        error("Could not find a free slug")
    }
}
