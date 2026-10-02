package com.playmation.motionlabsbackend.messages

import com.playmation.motionlabsbackend.account.AccountRepository
import com.playmation.motionlabsbackend.account.AccountService
import com.playmation.motionlabsbackend.account.AccountStatus
import com.playmation.motionlabsbackend.account.avatarPath
import com.playmation.motionlabsbackend.auth.PortalPrincipal
import com.playmation.motionlabsbackend.common.PortalException
import com.playmation.motionlabsbackend.common.RateLimiter
import com.playmation.motionlabsbackend.profile.AccountFollowRepository
import com.playmation.motionlabsbackend.system.AuditService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Eine blockierte Person, so wie die Kontoseite sie zum Aufheben zeigt. */
data class BlockedView(val handle: String?, val displayName: String, val avatarUrl: String?, val since: Instant)

/**
 * Blockieren. Kam mit den Direktnachrichten, weil man ohne es einer Person,
 * die nicht aufhoert, nichts entgegensetzen kann - ausser einer Meldung, und
 * die braucht einen Menschen, der sie liest.
 *
 * WAS ES BEWIRKT, in beide Richtungen:
 *   - keine Nachrichten (MessageService fragt [between])
 *   - kein Folgen; bestehendes Folgen faellt beim Blockieren weg
 *   - keine Benachrichtigungen von der blockierten Person (Notifier)
 *   - keine Kommentare unter den Clips dessen, der blockiert hat (CommentService)
 *
 * WAS ES NICHT BEWIRKT: oeffentliche Seiten bleiben oeffentlich. Ein Profil
 * und seine Clips sieht jeder, auch ohne Konto - eine Sperre davor waere ein
 * Zaun mit offenem Tor.
 */
@Service
class BlockService(
    private val blocks: AccountBlockRepository,
    private val follows: AccountFollowRepository,
    private val accountRepository: AccountRepository,
    private val accounts: AccountService,
    private val rateLimiter: RateLimiter,
    private val audit: AuditService,
    private val clock: Clock,
) {
    /** Hat eine der beiden Seiten die andere blockiert? */
    fun between(one: UUID, other: UUID): Boolean =
        blocks.existsByBlockerIdAndBlockedId(one, other) || blocks.existsByBlockerIdAndBlockedId(other, one)

    fun blockedByMe(me: UUID, other: UUID): Boolean = blocks.existsByBlockerIdAndBlockedId(me, other)

    /** Wen von diesen habe ich blockiert? Eine Abfrage fuer eine ganze Liste. */
    fun blockedAmong(me: UUID, candidates: Collection<UUID>): Set<UUID> =
        if (candidates.isEmpty()) emptySet() else blocks.blockedAmong(me, candidates.toSet()).toSet()

    /**
     * Wer von diesen steht mit [actor] auf Sperre - egal, wer wen blockiert
     * hat. Fuer Nachrichten an viele (Notifier.fromActorToMany).
     */
    fun blockedEitherWay(actor: UUID, candidates: Collection<UUID>): Set<UUID> {
        if (candidates.isEmpty()) return emptySet()
        val set = candidates.toSet()
        return (blocks.blockersAmong(actor, set) + blocks.blockedAmong(actor, set)).toSet()
    }

    /**
     * Blockieren oder aufheben. Antwort ist der neue Zustand - dieselbe
     * Bauart wie Folgen: ein Aufruf mit dem Wunsch, kein Umschalter.
     */
    @Transactional
    fun set(principal: PortalPrincipal, handle: String, blocked: Boolean, ip: String): Boolean {
        val target = accountRepository.findByHandle(handle.trim().lowercase())
            ?: throw PortalException.notFound("No such profile")
        return set(principal, target.id, blocked, ip)
    }

    /** Dasselbe ueber die Kennung - fuer "melden und blockieren" aus einem Gespraech. */
    @Transactional
    fun set(principal: PortalPrincipal, targetId: UUID, blocked: Boolean, ip: String): Boolean {
        val me = accounts.get(principal.accountId)
        val target = accounts.get(targetId)
        if (target.id == me.id) throw PortalException.badRequest("self-block", "You cannot block yourself.")

        rateLimiter.require("block", me.id.toString(), 60, Duration.ofHours(1))
        val already = blocks.existsByBlockerIdAndBlockedId(me.id, target.id)

        if (blocked && !already) {
            blocks.save(AccountBlock(me.id, target.id, clock.instant()))

            //  Folgen in beide Richtungen faellt weg: "blockiert, folgt mir
            //  aber und bekommt jeden neuen Clip gemeldet" waere keine Sperre.
            follows.deleteByFollowerIdAndFolloweeId(me.id, target.id)
            follows.deleteByFollowerIdAndFolloweeId(target.id, me.id)
            for (account in listOf(me, target)) {
                account.followerCount = follows.countByFolloweeId(account.id)
                accountRepository.save(account)
            }
            audit.record(me.id, "account.blocked", "account", target.id.toString(), null, ip)
        } else if (!blocked && already) {
            blocks.deleteByBlockerIdAndBlockedId(me.id, target.id)
            audit.record(me.id, "account.unblocked", "account", target.id.toString(), null, ip)
        }
        return blocked
    }

    @Transactional(readOnly = true)
    fun mine(principal: PortalPrincipal): List<BlockedView> {
        val rows = blocks.findByBlockerIdOrderByCreatedAtDesc(principal.accountId)
        val byId = accountRepository.findAllById(rows.map { it.blockedId }).associateBy { it.id }
        return rows.mapNotNull { row ->
            val account = byId[row.blockedId] ?: return@mapNotNull null
            val reachable = account.status != AccountStatus.BANNED
            BlockedView(account.handle.takeIf { reachable }, account.displayName,
                account.avatarPath().takeIf { reachable }, row.createdAt)
        }
    }

    /** Beim Schliessen eines Kontos: seine Sperren gehen, in beide Richtungen. */
    fun forget(accountId: UUID) {
        blocks.deleteAll(blocks.findByBlockerIdOrderByCreatedAtDesc(accountId))
        blocks.deleteAll(blocks.findByBlockedId(accountId))
    }
}
