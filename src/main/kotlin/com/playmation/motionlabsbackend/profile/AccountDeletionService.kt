package com.playmation.motionlabsbackend.profile

import com.playmation.motionlabsbackend.account.AccountIdentityRepository
import com.playmation.motionlabsbackend.account.AccountRepository
import com.playmation.motionlabsbackend.account.AccountService
import com.playmation.motionlabsbackend.account.AccountStatus
import com.playmation.motionlabsbackend.auth.ApiTokenService
import com.playmation.motionlabsbackend.auth.PortalPrincipal
import com.playmation.motionlabsbackend.catalog.AnimationPackageRepository
import com.playmation.motionlabsbackend.catalog.CatalogOverviewService
import com.playmation.motionlabsbackend.catalog.PackageLikeRepository
import com.playmation.motionlabsbackend.catalog.PackageStatus
import com.playmation.motionlabsbackend.collection.ClipCollectionRepository
import com.playmation.motionlabsbackend.collection.CollectionItemRepository
import com.playmation.motionlabsbackend.moderation.NotificationRepository
import com.playmation.motionlabsbackend.system.AuditService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * Das eigene Konto schliessen.
 *
 * ANONYMISIEREN STATT ZEILEN LOESCHEN. Das Konto ist der Ankerpunkt von allem,
 * was Moderation nachvollziehbar macht: Meldungen nennen ihren Melder,
 * Entscheidungen ihren Entscheider, die Muenzbuchungen sind anhaengend. Wer
 * diese Zeilen loescht, loescht auch die Begruendung fuer eine Sperre, die
 * jemand anders betrifft.
 *
 * Also wird die EINE Zeile anonym, an der alles haengt: Name, Handle, Bio und
 * Bild verschwinden, die Anmeldungen (Discord, GitHub, Google) werden geloest. Alles, was auf dieses Konto
 * zeigt, zeigt danach auf niemanden mehr - ein Kommentar steht als "Deleted
 * user" da, eine Meldung hat keinen Namen mehr.
 *
 * WAS BLEIBT UND WARUM:
 *   Geteilte Clips werden ZURUECKGEZOGEN, nicht geloescht. Sie sind unter
 *   CC BY 4.0 geteilt worden, und diese Lizenz ist fuer Kopien, die andere
 *   schon haben, nicht ruecknehmbar (Nutzungsbedingungen §3). Aus dem Katalog
 *   verschwinden sie trotzdem sofort.
 *
 *   Kommentare bleiben stehen, ohne Namen. Ein Gespraech, aus dem nachtraeglich
 *   die Haelfte verschwindet, ist fuer alle anderen unlesbar.
 *
 * WAS GEHT: Sammlungen (die Auswahl gehoert der Person), Herzen, Folgen in
 * beide Richtungen, Benachrichtigungen, alle Workbench-Anmeldungen.
 */
@Service
class AccountDeletionService(
    private val accounts: AccountRepository,
    private val identities: AccountIdentityRepository,
    private val accountService: AccountService,
    private val packages: AnimationPackageRepository,
    private val likes: PackageLikeRepository,
    private val follows: AccountFollowRepository,
    private val collections: ClipCollectionRepository,
    private val collectionItems: CollectionItemRepository,
    private val notifications: NotificationRepository,
    private val tokens: ApiTokenService,
    private val overview: CatalogOverviewService,
    private val audit: AuditService,
    private val clock: Clock,
) {
    /** Was verschwunden ist - die Seite sagt es dem Menschen, der es ausgeloest hat. */
    data class Result(
        val withdrawnClips: Int,
        val deletedCollections: Int,
        val removedLikes: Int,
        val removedFollows: Int,
    )

    @Transactional
    fun deleteOwnAccount(principal: PortalPrincipal, ip: String): Result {
        //  Auch ein gesperrtes Konto darf gehen - das Recht auf Loeschung
        //  haengt nicht am Wohlverhalten.
        val account = accountService.get(principal.accountId)

        // ── Clips: aus dem Katalog nehmen ────────────────────────────────
        var withdrawn = 0
        for (pkg in packages.findByOwnerIdOrderByCreatedAtDesc(account.id)) {
            if (pkg.status != PackageStatus.PUBLISHED) continue
            pkg.status = PackageStatus.WITHDRAWN
            pkg.updatedAt = clock.instant()
            packages.save(pkg)
            withdrawn++
        }

        // ── Sammlungen: die Auswahl gehoert der Person ───────────────────
        val ownCollections = collections.findByOwnerId(account.id)
        val touchedPackages = mutableSetOf<UUID>()

        for (collection in ownCollections) {
            for (item in collectionItems.findByCollectionIdOrderByPositionAsc(collection.id))
                touchedPackages += item.packageId

            collectionItems.deleteByCollectionId(collection.id)
            collections.delete(collection)
        }

        // ── Herzen ───────────────────────────────────────────────────────
        val ownLikes = likes.findByAccountId(account.id)
        for (like in ownLikes) {
            likes.deleteByPackageIdAndAccountId(like.packageId, account.id)
            touchedPackages += like.packageId
        }

        //  Die abgeleiteten Zahlen an den betroffenen Clips neu rechnen - sie
        //  sind die schnelle Antwort, nicht die Wahrheit.
        for (packageId in touchedPackages) {
            val pkg = packages.findById(packageId).orElse(null) ?: continue
            pkg.likeCount = likes.countByPackageId(packageId)
            pkg.saveCount = collectionItems.saverCount(packageId)
            packages.save(pkg)
        }

        // ── Folgen, in beide Richtungen ──────────────────────────────────
        val following = follows.findByFollowerIdOrderByCreatedAtDesc(account.id)
        val followers = follows.findByFolloweeIdOrderByCreatedAtDesc(account.id)

        for (row in following) follows.deleteByFollowerIdAndFolloweeId(account.id, row.followeeId)
        for (row in followers) follows.deleteByFollowerIdAndFolloweeId(row.followerId, account.id)

        for (row in following) {
            val other = accounts.findById(row.followeeId).orElse(null) ?: continue
            other.followerCount = follows.countByFolloweeId(other.id)
            accounts.save(other)
        }

        // ── Benachrichtigungen und Anmeldungen ───────────────────────────
        notifications.deleteAll(notifications.findByAccountIdOrderByCreatedAtDesc(account.id))
        tokens.revokeAll(account.id)

        // ── Und zuletzt das Konto selbst ─────────────────────────────────
        val marker = UUID.randomUUID().toString().replace("-", "").take(8)

        //  Die Anmeldungen gehen ganz: nur so kann dieselbe Person spaeter mit
        //  einem FRISCHEN Konto wiederkommen, statt in diese leere Huelle
        //  zurueckzufallen. Und die Kennungen bei den Anbietern sind genau das,
        //  was ein geschlossenes Konto mit einer Person verbindet.
        identities.deleteByAccountId(account.id)

        account.displayName = "Deleted user"
        account.handle = "deleted-$marker"
        account.bio = null
        account.avatarUrl = null
        account.followerCount = 0
        account.status = AccountStatus.BANNED
        accounts.save(account)

        //  Das Protokoll haelt den Vorgang fest, aber ohne Namen - die
        //  Konto-Kennung zeigt ab jetzt auf eine anonyme Zeile.
        audit.record(account.id, "account.deleted", "account", account.id.toString(),
            "clips=$withdrawn collections=${ownCollections.size}", ip)

        overview.invalidate()

        return Result(withdrawn, ownCollections.size, ownLikes.size, following.size + followers.size)
    }
}
