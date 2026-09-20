package com.playmation.motionlabsbackend.profile

import com.playmation.motionlabsbackend.account.Account
import com.playmation.motionlabsbackend.account.AccountHandles
import com.playmation.motionlabsbackend.account.AccountRepository
import com.playmation.motionlabsbackend.account.AccountService
import com.playmation.motionlabsbackend.account.AccountStatus
import com.playmation.motionlabsbackend.account.Role
import com.playmation.motionlabsbackend.auth.PortalPrincipal
import com.playmation.motionlabsbackend.catalog.AnimationPackageRepository
import com.playmation.motionlabsbackend.catalog.CommentStatus
import com.playmation.motionlabsbackend.catalog.PackageCommentRepository
import com.playmation.motionlabsbackend.catalog.PackageStatus
import com.playmation.motionlabsbackend.common.PortalException
import com.playmation.motionlabsbackend.common.RateLimiter
import com.playmation.motionlabsbackend.config.PortalProperties
import com.playmation.motionlabsbackend.economy.PackageUnlockRepository
import com.playmation.motionlabsbackend.format.AwclipSchema
import com.playmation.motionlabsbackend.moderation.Notification
import com.playmation.motionlabsbackend.moderation.NotificationRepository
import com.playmation.motionlabsbackend.system.AuditService
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Ein Profil, so wie die Seite es zeigt.
 *
 * Alles hier ist oeffentlich. Was ein Konto NICHT hergibt, steht auch nicht
 * drin: keine Mail, keine Discord-Kennung, kein Muenzstand, keine Verstoesse.
 * Die Zahl der Strikes ist eine Sache zwischen Moderation und Konto - auf
 * einem Profil waere sie ein Pranger.
 */
data class ProfileView(
    val handle: String,
    val displayName: String,
    val bio: String?,
    /** Fertige Adresse bei Discord, oder null - dann steht der Buchstabenkreis. */
    val avatarUrl: String?,
    val joinedAt: Instant,
    /** Fuers Abzeichen neben dem Namen. */
    val moderator: Boolean,
    val clips: Long,
    val collections: Long,
    val followers: Long,
    val following: Long,
    val likesReceived: Long,
    val takes: Long,
    val achievements: List<AchievementView>,
    val followedByMe: Boolean,
    val isMe: Boolean,
)

/** Die kleine Fassung - fuer Follower- und Folge-Listen. */
data class ProfileCard(
    val handle: String,
    val displayName: String,
    val avatarUrl: String?,
    val clips: Long,
    val followers: Long,
)

/**
 * Wie viele Sammlungen ein Konto veroeffentlicht hat.
 *
 * Als Schnittstelle, damit das Profil nichts ueber Sammlungen wissen muss -
 * und damit es auch dann noch steht, wenn es gar keine gibt. Umgekehrt waere
 * es eine Abhaengigkeit quer durch zwei Pakete fuer eine einzige Zahl.
 */
fun interface CollectionCounter {
    fun countFor(ownerId: UUID): Long
}

@Service
class ProfileService(
    private val accounts: AccountRepository,
    private val accountService: AccountService,
    private val follows: AccountFollowRepository,
    private val packages: AnimationPackageRepository,
    private val comments: PackageCommentRepository,
    private val unlocks: PackageUnlockRepository,
    private val achievements: AchievementService,
    private val notifications: NotificationRepository,
    private val audit: AuditService,
    private val rateLimiter: RateLimiter,
    /** Sammlungen, wenn es sie gibt - siehe [CollectionCounter]. */
    private val collections: ObjectProvider<CollectionCounter>,
    private val properties: PortalProperties,
    private val clock: Clock,
) {
    // ════════════════════════════════════════════════════════════════════
    // LESEN
    // ════════════════════════════════════════════════════════════════════

    /**
     * Das Konto hinter einem Handle.
     *
     * Ein gesperrtes Konto hat kein Profil - 404 wie ein entfernter Clip. Wer
     * gesperrt ist, soll nicht mit Seite, Zahlen und Folgen-Knopf weiterstehen,
     * als waere nichts.
     */
    fun require(handle: String): Account {
        val account = accounts.findByHandle(handle.trim().lowercase())
            ?: throw PortalException.notFound("No such profile")
        if (account.status == AccountStatus.BANNED) throw PortalException.notFound("No such profile")
        return account
    }

    @Transactional(readOnly = true)
    fun profile(handle: String, principal: PortalPrincipal?): ProfileView {
        val account = require(handle)
        val stats = stats(account)

        val isMe = principal?.accountId == account.id

        return ProfileView(
            handle = account.handle ?: handle,
            displayName = account.displayName,
            bio = account.bio?.takeIf { it.isNotBlank() },
            avatarUrl = avatarUrl(account),
            joinedAt = account.createdAt,
            moderator = account.role == Role.ADMIN,
            clips = stats.clips,
            collections = stats.collections,
            followers = stats.followers,
            following = follows.countByFollowerId(account.id),
            likesReceived = stats.likesReceived,
            takes = stats.takes,
            //  Auf einem fremden Profil stehen nur die erreichten. Eine Liste
            //  offener Auszeichnungen ist eine Aufgabenliste, und die gehoert
            //  dem, der sie erfuellen kann.
            achievements = achievements.forStats(stats).filter { isMe || it.earned },
            followedByMe = principal != null && follows.existsByFollowerIdAndFolloweeId(principal.accountId, account.id),
            isMe = isMe,
        )
    }

    /**
     * Was die Link-Vorschau eines Profils braucht - und sonst nichts.
     *
     * Der Clip darin ist der neueste oeffentliche: das Bild, das ein
     * Vorschau-Bot holt, soll Bewegung zeigen statt eines Buchstabens im
     * Kreis. Gibt es keinen, gibt es kein Bild.
     */
    data class ProfileMeta(val displayName: String, val bio: String?, val cardSlug: String?)

    @Transactional(readOnly = true)
    fun meta(handle: String): ProfileMeta {
        val account = require(handle)
        val newest = packages.findFirstByOwnerIdAndStatusAndLicenseOrderByCreatedAtDesc(
            account.id, PackageStatus.PUBLISHED, AwclipSchema.LICENSE_PUBLIC)

        return ProfileMeta(account.displayName, account.bio?.takeIf { it.isNotBlank() }, newest?.slug)
    }

    @Transactional(readOnly = true)
    fun followers(handle: String): List<ProfileCard> {
        val account = require(handle)
        return cards(follows.findByFolloweeIdOrderByCreatedAtDesc(account.id).map { it.followerId })
    }

    @Transactional(readOnly = true)
    fun following(handle: String): List<ProfileCard> {
        val account = require(handle)
        return cards(follows.findByFollowerIdOrderByCreatedAtDesc(account.id).map { it.followeeId })
    }

    // ════════════════════════════════════════════════════════════════════
    // FOLGEN
    // ════════════════════════════════════════════════════════════════════

    /**
     * Folgen oder entfolgen. Dieselbe Bauart wie das Herz an einem Clip
     * (`CatalogService.like`): ein Aufruf mit Wunschzustand, die Antwort ist
     * die neue Zahl, und die Zahl wird neu gezaehlt statt hoch- und
     * runtergerechnet.
     */
    @Transactional
    fun follow(principal: PortalPrincipal, handle: String, following: Boolean): Long {
        val me = accountService.requireUsable(principal.accountId)
        val target = require(handle)

        if (target.id == me.id)
            throw PortalException.badRequest("self-follow", "You cannot follow yourself.")

        rateLimiter.require("follow", me.id.toString(), properties.limits.followsPerHour, Duration.ofHours(1))

        val already = follows.existsByFollowerIdAndFolloweeId(me.id, target.id)

        if (following && !already) {
            follows.save(AccountFollow(me.id, target.id, clock.instant()))

            //  Nur beim ersten Mal, und nur in diese Richtung: dass jemand
            //  entfolgt, ist keine Nachricht, die irgendwem hilft.
            notifications.save(
                Notification(
                    accountId = target.id,
                    message = "${me.displayName} follows you now.",
                    createdAt = clock.instant(),
                )
            )
        } else if (!following && already) {
            follows.deleteByFollowerIdAndFolloweeId(me.id, target.id)
        } else {
            return target.followerCount
        }

        target.followerCount = follows.countByFolloweeId(target.id)
        accounts.save(target)
        return target.followerCount
    }

    /**
     * Wer diesem Konto folgt - fuer die Benachrichtigung beim Teilen eines
     * neuen Clips.
     *
     * Ab einer Grenze passiert nichts mehr: N Zeilen je Upload sind bei drei
     * Followern richtig und bei tausend ein Postfach voll Rauschen. Wer so
     * viele Follower hat, braucht einen Feed - und den bauen wir, wenn es
     * jemanden gibt, der ihn braucht.
     */
    @Transactional
    fun notifyFollowers(ownerId: UUID, message: String) {
        val followers = follows.followerIdsOf(ownerId)
        if (followers.isEmpty() || followers.size > MAX_FOLLOWER_NOTIFICATIONS) return

        val now = clock.instant()
        notifications.saveAll(followers.map { Notification(accountId = it, message = message, createdAt = now) })
    }

    // ════════════════════════════════════════════════════════════════════
    // DAS EIGENE PROFIL
    // ════════════════════════════════════════════════════════════════════

    data class ProfileEdit(val handle: String? = null, val bio: String? = null)

    /**
     * Handle und Bio aendern.
     *
     * Der Handle darf gewechselt werden, weil der erste aus einem
     * Discord-Namen abgeleitet wurde, den sich niemand ausgesucht hat. Der
     * Preis steht in der Antwort der Seite: der alte Link fuehrt danach ins
     * Leere. Eine Weiterleitung vom alten Handle waere die ehrlichere Loesung
     * und braucht eine Tabelle alter Handles - das ist es an dieser Stelle
     * nicht wert, solange kein Profil verlinkt ist, das jemand nicht selbst
     * geteilt hat.
     */
    @Transactional
    fun edit(principal: PortalPrincipal, edit: ProfileEdit, ip: String): ProfileView {
        val me = accountService.requireUsable(principal.accountId)

        edit.bio?.let { me.bio = it.trim().take(500).ifBlank { null } }

        edit.handle?.let { requested ->
            val handle = AccountHandles.validate(requested)
            if (handle != me.handle) {
                rateLimiter.require("handle", me.id.toString(), 3, Duration.ofDays(1))
                if (accounts.existsByHandle(handle))
                    throw PortalException.conflict("handle-taken", "That handle is already taken.")

                audit.record(me.id, "profile.handle-changed", "account", me.id.toString(),
                    "from=${me.handle} to=$handle", ip)
                me.handle = handle
            }
        }

        accounts.save(me)
        return profile(me.handle!!, principal)
    }

    // ── Hilfen ──────────────────────────────────────────────────────────

    /**
     * Die Zahlen hinter einem Profil und seinen Auszeichnungen.
     *
     * Gezaehlt wird nur, was auch im Katalog steht: veroeffentlicht und
     * oeffentlich lizenziert. Ein privater Clip ist geteilt, aber nicht mit der
     * Community - er soll weder das Profil fuellen noch eine Auszeichnung
     * tragen.
     */
    fun stats(account: Account): ProfileStats = ProfileStats(
        clips = packages.countByOwnerIdAndStatusAndLicense(
            account.id, PackageStatus.PUBLISHED, AwclipSchema.LICENSE_PUBLIC),
        collections = collectionCount(account.id),
        likesReceived = packages.sumLikesForOwner(
            account.id, PackageStatus.PUBLISHED, AwclipSchema.LICENSE_PUBLIC),
        takes = packages.sumTakesForOwner(
            account.id, PackageStatus.PUBLISHED, AwclipSchema.LICENSE_PUBLIC),
        unlocksEarned = unlocks.countEarnedForOwner(account.id),
        comments = comments.countByAccountIdAndStatus(account.id, CommentStatus.VISIBLE),
        followers = follows.countByFolloweeId(account.id),
        joinedAt = account.createdAt,
    )

    private fun collectionCount(ownerId: UUID): Long =
        collections.getIfAvailable()?.countFor(ownerId) ?: 0L

    private fun cards(ids: List<UUID>): List<ProfileCard> =
        accounts.findAllById(ids)
            .filter { it.status != AccountStatus.BANNED && it.handle != null }
            //  Die Reihenfolge kommt aus der Folge-Tabelle (neueste zuerst),
            //  nicht aus `findAllById` - die gibt zurueck, was die Datenbank
            //  gerade hergibt.
            .sortedBy { ids.indexOf(it.id) }
            .map { account ->
                ProfileCard(
                    handle = account.handle!!,
                    displayName = account.displayName,
                    avatarUrl = avatarUrl(account),
                    clips = packages.countByOwnerIdAndStatusAndLicense(
                        account.id, PackageStatus.PUBLISHED, AwclipSchema.LICENSE_PUBLIC),
                    followers = account.followerCount,
                )
            }

    /**
     * Das Bild liegt bei Discord; wir speichern nur seinen Hash.
     *
     * Die Adresse traegt die Discord-Kennung - die steht damit in einer
     * oeffentlichen Antwort. Das ist der Preis fuer ein Bild ohne eigene
     * Bildablage; die Alternative waere ein Umweg ueber das Portal, der das
     * Bild zwischenspeichert. Der Entwickler-Login hat keine Kennung, die
     * Discord kennt - dort bleibt es beim Buchstabenkreis.
     */
    private fun avatarUrl(account: Account): String? {
        val hash = account.avatar?.takeIf { it.isNotBlank() } ?: return null
        if (!account.discordId.all { it.isDigit() }) return null
        return "https://cdn.discordapp.com/avatars/${account.discordId}/$hash.png?size=128"
    }

    companion object {
        const val MAX_FOLLOWER_NOTIFICATIONS = 500
    }
}
