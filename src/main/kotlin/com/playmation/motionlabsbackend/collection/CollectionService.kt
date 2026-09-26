package com.playmation.motionlabsbackend.collection

import com.playmation.motionlabsbackend.account.AccountRepository
import com.playmation.motionlabsbackend.account.AccountService
import com.playmation.motionlabsbackend.auth.PortalPrincipal
import com.playmation.motionlabsbackend.catalog.AnimationPackageRepository
import com.playmation.motionlabsbackend.catalog.CatalogService
import com.playmation.motionlabsbackend.catalog.PackageStatus
import com.playmation.motionlabsbackend.catalog.PackageSummary
import com.playmation.motionlabsbackend.common.Crypto
import com.playmation.motionlabsbackend.common.PortalException
import com.playmation.motionlabsbackend.common.RateLimiter
import com.playmation.motionlabsbackend.format.AwclipSchema
import com.playmation.motionlabsbackend.notification.NotificationKind
import com.playmation.motionlabsbackend.notification.NotificationLinks
import com.playmation.motionlabsbackend.notification.Notifier
import com.playmation.motionlabsbackend.profile.AccountFollowRepository
import com.playmation.motionlabsbackend.profile.CollectionCounter
import com.playmation.motionlabsbackend.system.AuditService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Der Deckel einer Sammlungskarte: der erste Clip darin, als Bewegung. */
data class CollectionCover(val slug: String, val rig: String)

data class CollectionSummary(
    val slug: String,
    val title: String,
    val description: String,
    val owner: String,
    val ownerHandle: String?,
    val visibility: String,
    val items: Long,
    val cover: CollectionCover?,
    val updatedAt: Instant,
    val isOwner: Boolean,
)

data class CollectionDetail(
    val slug: String,
    val title: String,
    val description: String,
    val owner: String,
    val ownerHandle: String?,
    val visibility: String,
    val items: List<PackageSummary>,
    val isOwner: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/**
 * Die Sammlungen der Leute, denen man folgt. [following] zaehlt die Leute,
 * nicht die Sammlungen: eine leere Liste heisst sonst zweierlei - "du folgst
 * niemandem" und "niemand, dem du folgst, hat eine" -, und die Workbench sagt
 * zu beidem etwas anderes.
 */
data class FollowedCollections(
    val following: Long,
    val collections: List<CollectionSummary>,
)

/** Fuer den Stern an einem Clip: meine Sammlungen, und ob dieser Clip drin ist. */
data class CollectionChoice(
    val slug: String,
    val title: String,
    val items: Long,
    val visibility: String,
    val contains: Boolean,
)

/**
 * Sammlungen.
 *
 * Eine Sammlung ist eine Auswahl mit Absender: "diese acht Clips gehoeren
 * zusammen, und ich sage das". Sie darf FREMDE Clips enthalten - das ist ihr
 * Sinn, sonst waere sie ein Ordner fuers eigene Werk.
 *
 * Was hineindarf, entscheidet nicht diese Klasse, sondern der Katalog: nur
 * was dort steht (veroeffentlicht, oeffentliche Lizenz). Damit gibt es
 * weiterhin genau EINE Stelle, die ueber Sichtbarkeit entscheidet, und eine
 * Sammlung kann keinen privaten Clip weiterreichen.
 *
 * Verschwindet ein Clip spaeter - zurueckgezogen, versteckt, entfernt -, faellt
 * er still aus jeder Ansicht. Die Zeile bleibt: kommt er zurueck, steht er
 * wieder an seinem Platz. Eine Sammlung, die sich beim Entfernen eines Clips
 * selbst umbaut, waere ein zweiter Verlust.
 */
@Service
class CollectionService(
    private val collections: ClipCollectionRepository,
    private val items: CollectionItemRepository,
    private val packages: AnimationPackageRepository,
    private val catalog: CatalogService,
    private val accounts: AccountRepository,
    private val accountService: AccountService,
    private val follows: AccountFollowRepository,
    private val notifier: Notifier,
    private val audit: AuditService,
    private val rateLimiter: RateLimiter,
    private val clock: Clock,
) : CollectionCounter {

    companion object {
        const val MIN_TITLE = 3
        const val MAX_TITLE = 60

        /**
         * Dieselbe Zahl, die der Zaehler im Dialog anzeigt. Die Spalte haelt
         * 1000 aus - der Unterschied ist Luft fuer spaeter, nicht eine zweite
         * Regel.
         */
        const val MAX_DESCRIPTION = 500

        /** Genug fuer jede Auswahl, die ein Mensch noch kuratiert. */
        const val MAX_ITEMS = 200
    }

    // ════════════════════════════════════════════════════════════════════
    // LESEN
    // ════════════════════════════════════════════════════════════════════

    /**
     * Sichtbar heisst: veroeffentlicht - oder dem Besitzer bzw. der Moderation
     * gehoerend. UNLISTED kommt durch, wer den Link hat; das ist der Sinn von
     * "nicht gelistet".
     */
    private fun visible(slug: String, principal: PortalPrincipal?): ClipCollection {
        val collection = collections.findBySlug(slug.trim().lowercase())
            ?: throw PortalException.notFound("Collection not found")

        val privileged = principal != null &&
            (principal.isAdmin || principal.accountId == collection.ownerId)

        if (collection.status != CollectionStatus.PUBLISHED && !privileged)
            throw PortalException.notFound("Collection not found")

        return collection
    }

    @Transactional(readOnly = true)
    fun detail(slug: String, principal: PortalPrincipal?): CollectionDetail {
        val collection = visible(slug, principal)
        val owner = accounts.findById(collection.ownerId).orElse(null)

        val ids = items.findByCollectionIdOrderByPositionAsc(collection.id).map { it.packageId }

        return CollectionDetail(
            slug = collection.slug,
            title = collection.title,
            description = collection.description,
            owner = owner?.displayName ?: "unknown",
            ownerHandle = owner?.handle,
            visibility = collection.visibility.name,
            items = catalog.summaries(ids, principal),
            isOwner = principal?.accountId == collection.ownerId,
            createdAt = collection.createdAt,
            updatedAt = collection.updatedAt,
        )
    }

    /** Die Sammlungen eines Kontos, wie sie auf dessen Profil stehen. */
    @Transactional(readOnly = true)
    fun ofOwner(ownerId: UUID, principal: PortalPrincipal?): List<CollectionSummary> {
        val isOwner = principal?.accountId == ownerId

        //  Nicht gelistete Sammlungen stehen auf dem eigenen Profil, aber auf
        //  keinem fremden - sonst waere "nicht gelistet" nur ein Wort.
        val found = if (isOwner)
            collections.findByOwnerIdAndStatusOrderByUpdatedAtDesc(ownerId, CollectionStatus.PUBLISHED)
        else
            collections.findByOwnerIdAndStatusAndVisibilityOrderByUpdatedAtDesc(
                ownerId, CollectionStatus.PUBLISHED, CollectionVisibility.PUBLIC)

        return found.map { summary(it, principal) }
    }

    /** Alles Oeffentliche - der Katalog der Sammlungen, neueste Aenderung zuerst. */
    @Transactional(readOnly = true)
    fun browse(principal: PortalPrincipal?, limit: Int = 48): List<CollectionSummary> =
        collections.findByStatusAndVisibilityOrderByUpdatedAtDesc(
            CollectionStatus.PUBLISHED, CollectionVisibility.PUBLIC)
            .filter { it.itemCount > 0 }
            .take(limit.coerceIn(1, 100))
            .map { summary(it, principal) }

    /**
     * Die oeffentlichen Sammlungen der Leute, denen [principal] folgt -
     * neueste Aenderung zuerst.
     *
     * Das ist, was die Workbench unter "Collections" neben den eigenen zeigt.
     * Den ganzen Katalog der Sammlungen zeigt sie bewusst nicht: eine Sammlung
     * teilt man mit Leuten, die man kennt, und wer fremde sehen will, schaut
     * auf dem Profil ihres Besitzers nach. Nicht gelistete bleiben draussen -
     * Folgen ist kein Link.
     */
    @Transactional(readOnly = true)
    fun ofFollowed(principal: PortalPrincipal, limit: Int = 48): FollowedCollections {
        val followees = follows.findByFollowerIdOrderByCreatedAtDesc(principal.accountId)
            .map { it.followeeId }

        if (followees.isEmpty())
            return FollowedCollections(0, emptyList())

        val found = collections.findByOwnerIdInAndStatusAndVisibilityOrderByUpdatedAtDesc(
            followees, CollectionStatus.PUBLISHED, CollectionVisibility.PUBLIC)
            .filter { it.itemCount > 0 }
            .take(limit.coerceIn(1, 100))
            .map { summary(it, principal) }

        return FollowedCollections(followees.size.toLong(), found)
    }

    /**
     * Meine Sammlungen fuer den Stern an einem Clip - mit der Angabe, ob
     * dieser Clip schon drin liegt. Eine Liste, die das nicht sagt, zwingt zum
     * Raten oder zum Nachsehen.
     */
    @Transactional(readOnly = true)
    fun choices(principal: PortalPrincipal, packageSlug: String?): List<CollectionChoice> {
        val mine = collections.findByOwnerIdAndStatusOrderByUpdatedAtDesc(
            principal.accountId, CollectionStatus.PUBLISHED)

        val containing = packageSlug
            ?.let { packages.findBySlug(it.trim().lowercase()) }
            ?.let { items.collectionsOfOwnerContaining(principal.accountId, it.id).toSet() }
            ?: emptySet()

        return mine.map {
            CollectionChoice(it.slug, it.title, it.itemCount, it.visibility.name, it.id in containing)
        }
    }

    override fun countFor(ownerId: UUID): Long = collections.countByOwnerIdAndStatusAndVisibility(
        ownerId, CollectionStatus.PUBLISHED, CollectionVisibility.PUBLIC)

    // ════════════════════════════════════════════════════════════════════
    // ANLEGEN UND AENDERN
    // ════════════════════════════════════════════════════════════════════

    data class CollectionInput(
        val title: String = "",
        val description: String = "",
        val visibility: String = CollectionVisibility.PUBLIC.name,
    )

    @Transactional
    fun create(principal: PortalPrincipal, input: CollectionInput, ip: String): CollectionSummary {
        val owner = accountService.requireUsable(principal.accountId)
        rateLimiter.require("collection", owner.id.toString(), 20, Duration.ofHours(1))

        val now = clock.instant()
        val collection = collections.save(
            ClipCollection(
                slug = newSlug(),
                ownerId = owner.id,
                title = title(input.title),
                description = description(input.description),
                visibility = visibility(input.visibility),
                createdAt = now,
                updatedAt = now,
            )
        )

        audit.record(owner.id, "collection.created", "collection", collection.slug, collection.title, ip)
        return summary(collection, principal)
    }

    @Transactional
    fun update(principal: PortalPrincipal, slug: String, input: CollectionInput, ip: String): CollectionSummary {
        val collection = own(principal, slug)

        if (input.title.isNotBlank()) collection.title = title(input.title)
        collection.description = description(input.description)
        collection.visibility = visibility(input.visibility)
        collection.updatedAt = clock.instant()
        collections.save(collection)

        audit.record(principal.accountId, "collection.updated", "collection", collection.slug, null, ip)
        return summary(collection, principal)
    }

    /**
     * Loeschen loescht wirklich. Eine Sammlung ist eine Auswahl, kein Werk:
     * niemand hat einen Clip verloren, wenn sie weg ist, und ein weiterer
     * Status ("zurueckgezogen") waere nur ein Versteck mehr, in das man sehen
     * muss. Die Clips selbst bleiben natuerlich, wo sie sind.
     */
    @Transactional
    fun delete(principal: PortalPrincipal, slug: String, ip: String) {
        val collection = own(principal, slug)
        val affected = items.findByCollectionIdOrderByPositionAsc(collection.id).map { it.packageId }

        items.deleteByCollectionId(collection.id)
        collections.delete(collection)

        affected.forEach { refreshSaveCount(it) }
        audit.record(principal.accountId, "collection.deleted", "collection", slug, null, ip)
    }

    // ════════════════════════════════════════════════════════════════════
    // INHALT
    // ════════════════════════════════════════════════════════════════════

    /**
     * Einen Clip hineinlegen.
     *
     * Er muss im Katalog stehen - `catalog.publicPackage` entscheidet das, und
     * zwar mit derselben Regel wie die Suche. Ein privater Clip laesst sich
     * damit nicht ueber eine Sammlung weiterreichen, auch nicht vom eigenen
     * Besitzer.
     */
    @Transactional
    fun addItem(principal: PortalPrincipal, slug: String, packageSlug: String, ip: String): CollectionSummary {
        val collection = own(principal, slug)
        val pkg = catalog.publicPackage(packageSlug)

        if (items.countByCollectionId(collection.id) >= MAX_ITEMS)
            throw PortalException.conflict("collection-full", "A collection holds at most $MAX_ITEMS clips.")

        if (!items.existsByCollectionIdAndPackageId(collection.id, pkg.id)) {
            val position = items.findByCollectionIdOrderByPositionAsc(collection.id)
                .lastOrNull()?.position?.plus(1) ?: 0

            items.save(CollectionItem(collection.id, pkg.id, position, clock.instant()))
            touch(collection)
            refreshSaveCount(pkg.id)
            audit.record(principal.accountId, "collection.item-added", "collection", collection.slug, pkg.slug, ip)

            //  Der Ersteller erfaehrt es, wenn die Sammlung oeffentlich ist.
            //  Eine ungelistete lebt nur hinter ihrem Link - die Nachricht
            //  wuerde genau diesen Link an jemand Fremdes geben.
            if (collection.visibility == CollectionVisibility.PUBLIC) {
                accounts.findById(principal.accountId).orElse(null)?.let { collector ->
                    notifier.fromActor(pkg.ownerId, collector,
                        "added '${pkg.title}' to the collection '${collection.title}'.",
                        NotificationKind.COLLECTED, NotificationLinks.collection(collection.slug), once = true)
                }
            }
        }

        return summary(collection, principal)
    }

    @Transactional
    fun removeItem(principal: PortalPrincipal, slug: String, packageSlug: String, ip: String): CollectionSummary {
        val collection = own(principal, slug)
        val pkg = packages.findBySlug(packageSlug.trim().lowercase())
            ?: throw PortalException.notFound("Package not found")

        if (items.deleteByCollectionIdAndPackageId(collection.id, pkg.id) > 0) {
            touch(collection)
            refreshSaveCount(pkg.id)
            audit.record(principal.accountId, "collection.item-removed", "collection", collection.slug, pkg.slug, ip)
        }

        return summary(collection, principal)
    }

    /**
     * Die Reihenfolge neu setzen - der Besitzer schiebt Karten, und was hier
     * ankommt, ist die vollstaendige Liste. Was fehlt, bleibt hinten stehen:
     * eine unvollstaendige Angabe darf keine Eintraege verlieren.
     */
    @Transactional
    fun reorder(principal: PortalPrincipal, slug: String, packageSlugs: List<String>): CollectionSummary {
        val collection = own(principal, slug)
        val current = items.findByCollectionIdOrderByPositionAsc(collection.id)

        val wanted = packageSlugs.mapNotNull { packages.findBySlug(it.trim().lowercase())?.id }
        val order = (wanted + current.map { it.packageId }).distinct()

        for (item in current) {
            item.position = order.indexOf(item.packageId)
            items.save(item)
        }

        touch(collection)
        return summary(collection, principal)
    }

    // ── Hilfen ──────────────────────────────────────────────────────────

    private fun own(principal: PortalPrincipal, slug: String): ClipCollection {
        val collection = collections.findBySlug(slug.trim().lowercase())
            ?: throw PortalException.notFound("Collection not found")

        if (collection.ownerId != principal.accountId)
            throw PortalException.forbidden("Only the owner can change this collection.")
        if (collection.status == CollectionStatus.REMOVED)
            throw PortalException.conflict("collection-locked", "This collection was removed by moderation.")

        return collection
    }

    private fun summary(collection: ClipCollection, principal: PortalPrincipal?): CollectionSummary {
        val owner = accounts.findById(collection.ownerId).orElse(null)
        val visibleItems = visibleItems(collection.id)

        //  Der Zaehler wird bei jeder Ansicht mitgezogen: er ist abgeleitet,
        //  und ein Clip kann zwischen zwei Aufrufen verschwinden, ohne dass
        //  jemand diese Sammlung angefasst hat.
        if (collection.itemCount != visibleItems.size.toLong()) {
            collection.itemCount = visibleItems.size.toLong()
            collections.save(collection)
        }

        val cover = visibleItems.firstOrNull()?.let { pkg ->
            val version = pkg.currentVersionId?.let { catalog.versionOf(it) }
            CollectionCover(pkg.slug, version?.rig ?: AwclipSchema.RIG_HUMANOID)
                .takeIf { version?.previewBlobKey != null }
        }

        return CollectionSummary(
            slug = collection.slug,
            title = collection.title,
            description = collection.description,
            owner = owner?.displayName ?: "unknown",
            ownerHandle = owner?.handle,
            visibility = collection.visibility.name,
            items = collection.itemCount,
            cover = cover,
            updatedAt = collection.updatedAt,
            isOwner = principal?.accountId == collection.ownerId,
        )
    }

    private fun visibleItems(collectionId: UUID) =
        items.findByCollectionIdOrderByPositionAsc(collectionId)
            .mapNotNull { packages.findById(it.packageId).orElse(null) }
            .filter { it.status == PackageStatus.PUBLISHED && it.license == AwclipSchema.LICENSE_PUBLIC }

    private fun touch(collection: ClipCollection) {
        collection.itemCount = visibleItems(collection.id).size.toLong()
        collection.updatedAt = clock.instant()
        collections.save(collection)
    }

    /** Der Stern an der Karte: wie viele Personen diesen Clip gesammelt haben. */
    private fun refreshSaveCount(packageId: UUID) {
        val pkg = packages.findById(packageId).orElse(null) ?: return
        pkg.saveCount = items.saverCount(packageId)
        packages.save(pkg)
    }

    private fun title(value: String): String {
        val trimmed = value.trim().replace(Regex("\\s+"), " ")
        if (trimmed.length < MIN_TITLE || trimmed.length > MAX_TITLE)
            throw PortalException.badRequest(
                "invalid-title", "A name is $MIN_TITLE to $MAX_TITLE characters long.")
        if (trimmed.any { it.isISOControl() })
            throw PortalException.badRequest("invalid-title", "A name cannot contain control characters.")
        return trimmed
    }

    private fun description(value: String): String {
        val trimmed = value.trim()
        if (trimmed.length > MAX_DESCRIPTION)
            throw PortalException.badRequest(
                "invalid-description", "A description is at most $MAX_DESCRIPTION characters long.")
        return trimmed
    }

    private fun visibility(value: String): CollectionVisibility =
        runCatching { CollectionVisibility.valueOf(value.uppercase()) }
            .getOrElse { throw PortalException.badRequest("invalid-visibility", "Unknown visibility.") }

    private fun newSlug(): String {
        repeat(10) {
            val slug = Crypto.randomCode(8, "abcdefghijkmnpqrstuvwxyz23456789")
            if (collections.findBySlug(slug) == null) return slug
        }
        error("Could not find a free slug")
    }
}
