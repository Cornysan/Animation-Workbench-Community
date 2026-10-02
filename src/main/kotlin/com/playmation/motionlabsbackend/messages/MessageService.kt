package com.playmation.motionlabsbackend.messages

import com.playmation.motionlabsbackend.account.Account
import com.playmation.motionlabsbackend.account.AccountRepository
import com.playmation.motionlabsbackend.account.AccountService
import com.playmation.motionlabsbackend.account.AccountStatus
import com.playmation.motionlabsbackend.account.avatarPath
import com.playmation.motionlabsbackend.auth.PortalPrincipal
import com.playmation.motionlabsbackend.common.PortalException
import com.playmation.motionlabsbackend.common.RateLimiter
import com.playmation.motionlabsbackend.common.withoutInvisible
import com.playmation.motionlabsbackend.config.PortalProperties
import com.playmation.motionlabsbackend.profile.AccountFollowRepository
import com.playmation.motionlabsbackend.system.AuditService
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Die andere Seite eines Gespraechs. `handle` null = kein Profil (mehr): geschlossen oder gesperrt. */
data class MessagePerson(val handle: String?, val displayName: String, val avatarUrl: String?)

data class MessageView(val id: UUID, val body: String, val createdAt: Instant, val mine: Boolean)

/**
 * Eine Zeile der Gespraechsliste.
 *
 * `state`: `accepted`, `request-in` (an mich, noch nicht angenommen) oder
 * `request-out` (von mir, wartet).
 */
data class ConversationSummary(
    val id: UUID,
    val with: MessagePerson,
    val state: String,
    val lastMessage: String,
    val lastFromMe: Boolean,
    val lastMessageAt: Instant,
    val unread: Boolean,
    val blockedByMe: Boolean,
)

/**
 * Ein Gespraech, wie die Seite es oeffnet - oder das noch nicht begonnene mit
 * einer Person (`id` null, `state` = `new`).
 *
 * `cannotSend` sagt, warum das Feld zum Schreiben gerade zu ist - als festes
 * Wort, den Satz macht die Seite: `blocked-by-you`, `unavailable` (die andere
 * Seite hat blockiert, ist gesperrt oder hat ihr Konto geschlossen - welches
 * davon, verraten wir nicht), `waiting` (Anfrage ist voll), `restricted`
 * (eingeschraenktes Konto beginnt keine neuen Gespraeche), `self`.
 */
data class ConversationDetail(
    val id: UUID?,
    val with: MessagePerson,
    val state: String,
    val cannotSend: String?,
    /** Bei `request-out`: wie viele Nachrichten die Anfrage noch tragen darf. */
    val pendingLeft: Int?,
    val blockedByMe: Boolean,
    /** Bei `new`: wird die erste Nachricht eine Anfrage? Nein, wenn die andere Seite einem folgt. */
    val requestNeeded: Boolean,
    /** Aelteste zuerst. */
    val messages: List<MessageView>,
    val hasMore: Boolean,
)

data class SentMessage(val conversationId: UUID, val message: MessageView, val state: String)

/**
 * Direktnachrichten zwischen zwei Konten.
 *
 * DIE GRUNDREGEL: Gespraeche liest NIEMAND mit - auch nicht die Moderation.
 * Es gibt keinen Admin-Weg in diesem Dienst. Was die Moderation von einem
 * Gespraech sieht, hat ihr ein Beteiligter mit einer Meldung selbst vorgelegt
 * (ModerationService.reportMessage). Wer hier einen Lesezugang fuer Admins
 * einbaut, bricht das Versprechen in der Datenschutzerklaerung.
 *
 * Darum auch: kein Nachrichtentext in Logs, Audit-Eintraegen oder Alarmen.
 *
 * ANFRAGEN. Jede erste Nachricht ist eine Anfrage, ausser die andere Seite
 * folgt einem schon - wer jemandem folgt, hat gesagt, dass er von ihm hoeren
 * will. Bis zur Annahme darf die schreibende Seite nur
 * `portal.messages.pending-limit` Nachrichten schicken; eine Antwort ist
 * zugleich die Annahme.
 */
@Service
class MessageService(
    private val conversations: ConversationRepository,
    private val messages: DirectMessageRepository,
    private val accountRepository: AccountRepository,
    private val accounts: AccountService,
    private val follows: AccountFollowRepository,
    private val blocks: BlockService,
    private val rateLimiter: RateLimiter,
    private val audit: AuditService,
    private val properties: PortalProperties,
    private val clock: Clock,
) {
    // ════════════════════════════════════════════════════════════════════
    // LESEN
    // ════════════════════════════════════════════════════════════════════

    /**
     * Alle Gespraeche, neueste zuerst. Weggelassen wird, was man nicht sehen
     * soll: ein geleertes Gespraech ohne Neues, eine abgelehnte Anfrage an
     * mich, und jede Anfrage von jemandem, den ich blockiert habe.
     */
    @Transactional(readOnly = true)
    fun list(principal: PortalPrincipal): List<ConversationSummary> {
        val me = principal.accountId
        val all = conversations.findAllOf(me)
        val people = accountRepository.findAllById(all.map { it.other(me) }.toSet()).associateBy { it.id }
        val blockedByMe = blocks.blockedAmong(me, all.map { it.other(me) })

        return all.mapNotNull { c ->
            val other = c.other(me)
            val cleared = c.clearedAt(me)
            if (cleared != null && !c.lastMessageAt.isAfter(cleared)) return@mapNotNull null

            val state = stateOf(c, me)
            if (state == "request-in" && (c.declinedAt != null || other in blockedByMe)) return@mapNotNull null

            val last = messages.findFirstByConversationIdOrderByCreatedAtDesc(c.id) ?: return@mapNotNull null
            val read = c.readAt(me)
            ConversationSummary(
                id = c.id,
                with = person(people[other]),
                state = state,
                lastMessage = preview(last.body),
                lastFromMe = last.senderId == me,
                lastMessageAt = c.lastMessageAt,
                unread = c.lastSenderId != me && (read == null || read.isBefore(c.lastMessageAt)) && other !in blockedByMe,
                blockedByMe = other in blockedByMe,
            )
        }
    }

    /** Die Zahl am Briefsymbol. Eine Zaehlabfrage - sie laeuft bei jedem Seitenaufruf. */
    fun unreadCount(accountId: UUID): Long = conversations.countUnread(accountId)

    /** Ein Gespraech oeffnen - und damit gelesen. `before` blaettert zurueck. */
    @Transactional
    fun open(principal: PortalPrincipal, id: UUID, before: Instant?): ConversationDetail {
        val me = accounts.get(principal.accountId)
        val conversation = mine(id, me.id)
        val other = accountRepository.findById(conversation.other(me.id)).orElse(null)
        return detail(me, other, conversation, before, markRead = before == null)
    }

    /** Das Gespraech mit einer Person - oder ein leeres, wenn es noch keins gibt. */
    @Transactional
    fun with(principal: PortalPrincipal, handle: String): ConversationDetail {
        val me = accounts.get(principal.accountId)
        val other = target(handle)
        val (a, b) = pair(me.id, other.id)
        val conversation = conversations.findByAccountAAndAccountB(a, b)
        return detail(me, other, conversation, before = null, markRead = true)
    }

    // ════════════════════════════════════════════════════════════════════
    // SCHREIBEN
    // ════════════════════════════════════════════════════════════════════

    /** An eine Person schreiben - in das bestehende Gespraech oder ein neues. */
    @Transactional
    fun sendTo(principal: PortalPrincipal, handle: String, body: String, ip: String): SentMessage {
        val me = accounts.requireUsable(principal.accountId)
        val other = target(handle)
        val (a, b) = pair(me.id, other.id)
        return post(me, other, conversations.findByAccountAAndAccountB(a, b), body, ip)
    }

    @Transactional
    fun send(principal: PortalPrincipal, id: UUID, body: String, ip: String): SentMessage {
        val me = accounts.requireUsable(principal.accountId)
        val conversation = mine(id, me.id)
        val other = accountRepository.findById(conversation.other(me.id))
            .orElseThrow { PortalException.notFound("Conversation not found") }
        return post(me, other, conversation, body, ip)
    }

    private fun post(me: Account, other: Account, existing: Conversation?, body: String, ip: String): SentMessage {
        if (other.id == me.id) throw PortalException.badRequest("self-message", "You cannot message yourself.")
        refuseIfUnreachable(me, other)

        val text = clean(body)
        rateLimiter.require("message", me.id.toString(), properties.messages.perHour, Duration.ofHours(1))

        val now = clock.instant()
        val conversation = existing ?: start(me, other, now)

        if (!conversation.accepted) {
            if (conversation.startedBy != me.id || follows.existsByFollowerIdAndFolloweeId(other.id, me.id)) {
                //  Die angefragte Seite antwortet - das IST die Annahme. Oder
                //  sie folgt inzwischen der anfragenden: dann gilt, was beim
                //  Beginnen gegolten haette.
                conversation.acceptedAt = now
                conversation.declinedAt = null
            } else if (existing != null &&
                messages.countByConversationIdAndSenderId(conversation.id, me.id) >= properties.messages.pendingLimit) {
                throw PortalException(HttpStatus.CONFLICT, "waiting",
                    "You can send more once ${other.displayName} accepts your request.")
            }
        }

        val saved = messages.save(DirectMessage(conversationId = conversation.id, senderId = me.id, body = text, createdAt = now))
        conversation.lastMessageAt = now
        conversation.lastSenderId = me.id
        //  Wer schreibt, hat gelesen, was davor stand.
        conversation.markRead(me.id, now)
        conversations.save(conversation)

        return SentMessage(conversation.id, MessageView(saved.id, saved.body, saved.createdAt, true), stateOf(conversation, me.id))
    }

    /**
     * Ein neues Gespraech. Das ist die Stelle fuer Massenanschreiben, also
     * die mit den engsten Grenzen: eingeschraenkte Konten fangen keine an,
     * und je Tag gibt es nur `new-conversations-per-day` davon.
     */
    private fun start(me: Account, other: Account, now: Instant): Conversation {
        if (me.status == AccountStatus.RESTRICTED)
            throw PortalException(HttpStatus.FORBIDDEN, "restricted",
                "Your account is restricted after a removed clip, so it cannot start new conversations.")
        rateLimiter.require("message-start", me.id.toString(), properties.messages.newConversationsPerDay, Duration.ofDays(1))

        val (a, b) = pair(me.id, other.id)
        val trusted = follows.existsByFollowerIdAndFolloweeId(other.id, me.id)
        return conversations.save(Conversation(
            accountA = a, accountB = b, startedBy = me.id,
            acceptedAt = if (trusted) now else null,
            createdAt = now, lastMessageAt = now, lastSenderId = me.id,
        ))
    }

    /** Eine Anfrage annehmen, ohne gleich zu antworten. */
    @Transactional
    fun accept(principal: PortalPrincipal, id: UUID): ConversationDetail {
        val me = accounts.requireUsable(principal.accountId)
        val conversation = mine(id, me.id)
        if (!conversation.accepted) {
            if (conversation.startedBy == me.id)
                throw PortalException.badRequest("own-request", "This is your own request.")
            conversation.acceptedAt = clock.instant()
            conversation.declinedAt = null
        }
        val other = accountRepository.findById(conversation.other(me.id)).orElse(null)
        return detail(me, other, conversation, before = null, markRead = true)
    }

    /**
     * Eine Anfrage ablehnen. Sie verschwindet aus meiner Liste; die andere
     * Seite erfaehrt nichts davon und kann nichts mehr nachschieben, sobald
     * ihre Anfrage voll ist. Wer es sich anders ueberlegt, schreibt ueber das
     * Profil - das nimmt sie an.
     */
    @Transactional
    fun decline(principal: PortalPrincipal, id: UUID) {
        val conversation = mine(id, principal.accountId)
        if (conversation.accepted || conversation.startedBy == principal.accountId)
            throw PortalException.badRequest("not-a-request", "Only a request to you can be declined.")
        conversation.declinedAt = clock.instant()
    }

    /**
     * Ein Gespraech fuer MICH leeren. Die andere Seite behaelt ihre Kopie;
     * schreibt sie wieder, erscheint das Gespraech mit dem Neuen.
     *
     * Haben beide geleert, braucht niemand die Zeilen mehr - dann gehen sie
     * wirklich. Das ist der Weg, auf dem Nachrichten je verschwinden.
     */
    @Transactional
    fun clear(principal: PortalPrincipal, id: UUID) {
        val conversation = mine(id, principal.accountId)
        conversation.markCleared(principal.accountId, clock.instant())
        if (fullyCleared(conversation)) remove(conversation)
    }

    /** Die andere Seite eines Gespraechs blockieren - aus dem Gespraech heraus. */
    @Transactional
    fun blockOther(principal: PortalPrincipal, id: UUID, ip: String) {
        val conversation = mine(id, principal.accountId)
        blocks.set(principal, conversation.other(principal.accountId), true, ip)
    }

    // ════════════════════════════════════════════════════════════════════
    // KONTO SCHLIESSEN UND EXPORT
    // ════════════════════════════════════════════════════════════════════

    /**
     * Beim Schliessen eines Kontos. Anfragen gehen ganz (niemand hat sie
     * angenommen); ein angenommenes Gespraech bleibt fuer die andere Seite
     * stehen, mit "Deleted user" als Gegenueber - wie eine Mail im Postfach
     * des Empfaengers. Hat die andere Seite es schon geleert, geht es ganz.
     */
    fun forget(accountId: UUID) {
        val now = clock.instant()
        for (conversation in conversations.findAllOf(accountId)) {
            conversation.markCleared(accountId, now)
            if (!conversation.accepted || fullyCleared(conversation)) remove(conversation)
            else conversations.save(conversation)
        }
    }

    data class ExportedConversation(
        val with: String?, val withName: String, val state: String, val startedAt: Instant,
        val clearedByYouAt: Instant?, val messages: List<ExportedMessage>,
    )
    data class ExportedMessage(val from: String, val text: String, val sentAt: Instant)

    /**
     * Fuer den Datenexport (Art. 15 DSGVO): jedes Gespraech mit allem, was
     * davon noch gespeichert ist - auch das, was man fuer sich geleert hat,
     * denn gespeichert ist es weiterhin (bei der anderen Seite).
     */
    @Transactional(readOnly = true)
    fun export(accountId: UUID): List<ExportedConversation> {
        val all = conversations.findAllOf(accountId)
        val people = accountRepository.findAllById(all.map { it.other(accountId) }.toSet()).associateBy { it.id }
        return all.sortedBy { it.createdAt }.map { c ->
            val other = people[c.other(accountId)]
            ExportedConversation(
                with = other?.handle, withName = other?.displayName ?: "Someone",
                state = stateOf(c, accountId), startedAt = c.createdAt, clearedByYouAt = c.clearedAt(accountId),
                messages = messages.findByConversationIdOrderByCreatedAtAsc(c.id).map {
                    ExportedMessage(if (it.senderId == accountId) "you" else "them", it.body, it.createdAt)
                },
            )
        }
    }

    // ── Hilfen ──────────────────────────────────────────────────────────

    private fun detail(me: Account, other: Account?, conversation: Conversation?, before: Instant?, markRead: Boolean): ConversationDetail {
        val otherId = other?.id ?: conversation?.other(me.id)
        val blockedByMe = otherId != null && blocks.blockedByMe(me.id, otherId)

        val page = if (conversation == null) emptyList() else {
            val after = conversation.clearedAt(me.id) ?: Instant.EPOCH
            val request = PageRequest.of(0, PAGE_SIZE + 1)
            if (before == null) messages.findByConversationIdAndCreatedAtAfterOrderByCreatedAtDesc(conversation.id, after, request)
            else messages.findByConversationIdAndCreatedAtAfterAndCreatedAtBeforeOrderByCreatedAtDesc(conversation.id, after, before, request)
        }

        if (conversation != null && markRead) conversation.markRead(me.id, clock.instant())

        val state = conversation?.let { stateOf(it, me.id) } ?: "new"
        val pendingLeft = if (state == "request-out")
            maxOf(0, properties.messages.pendingLimit - messages.countByConversationIdAndSenderId(conversation!!.id, me.id).toInt())
        else null

        return ConversationDetail(
            id = conversation?.id,
            with = person(other),
            state = state,
            cannotSend = cannotSend(me, other, conversation, state, pendingLeft, blockedByMe),
            pendingLeft = pendingLeft,
            blockedByMe = blockedByMe,
            requestNeeded = conversation == null && other != null &&
                !follows.existsByFollowerIdAndFolloweeId(other.id, me.id),
            messages = page.take(PAGE_SIZE).reversed().map { MessageView(it.id, it.body, it.createdAt, it.senderId == me.id) },
            hasMore = page.size > PAGE_SIZE,
        )
    }

    private fun cannotSend(me: Account, other: Account?, conversation: Conversation?, state: String,
                           pendingLeft: Int?, blockedByMe: Boolean): String? = when {
        other == null || other.status == AccountStatus.BANNED -> "unavailable"
        other.id == me.id -> "self"
        blockedByMe -> "blocked-by-you"
        blocks.between(me.id, other.id) -> "unavailable"
        conversation == null && me.status == AccountStatus.RESTRICTED -> "restricted"
        state == "request-out" && pendingLeft == 0 && !follows.existsByFollowerIdAndFolloweeId(other.id, me.id) -> "waiting"
        else -> null
    }

    private fun refuseIfUnreachable(me: Account, other: Account) {
        if (other.status == AccountStatus.BANNED)
            throw PortalException(HttpStatus.FORBIDDEN, "unavailable", "This person cannot receive messages.")
        if (blocks.blockedByMe(me.id, other.id))
            throw PortalException(HttpStatus.FORBIDDEN, "blocked-by-you", "Unblock ${other.displayName} to write to them.")
        if (blocks.between(me.id, other.id))
            throw PortalException(HttpStatus.FORBIDDEN, "unavailable", "You cannot send messages to this person.")
    }

    private fun stateOf(conversation: Conversation, me: UUID) = when {
        conversation.accepted -> "accepted"
        conversation.startedBy == me -> "request-out"
        else -> "request-in"
    }

    /** Ein Gespraech, an dem ICH beteiligt bin - sonst gibt es es nicht. */
    private fun mine(id: UUID, me: UUID): Conversation {
        val conversation = conversations.findById(id).orElseThrow { PortalException.notFound("Conversation not found") }
        if (!conversation.involves(me)) throw PortalException.notFound("Conversation not found")
        return conversation
    }

    /** Wen man anschreiben kann: wer ein Profil hat. Gesperrte Konten haben keins. */
    private fun target(handle: String): Account {
        val account = accountRepository.findByHandle(handle.trim().lowercase())
            ?: throw PortalException.notFound("No such profile")
        if (account.status == AccountStatus.BANNED) throw PortalException.notFound("No such profile")
        return account
    }

    private fun fullyCleared(c: Conversation): Boolean {
        val a = c.clearedA
        val b = c.clearedB
        return a != null && b != null && !c.lastMessageAt.isAfter(a) && !c.lastMessageAt.isAfter(b)
    }

    private fun remove(conversation: Conversation) {
        messages.deleteByConversationId(conversation.id)
        conversations.delete(conversation)
    }

    private fun person(account: Account?): MessagePerson {
        if (account == null) return MessagePerson(null, "Someone", null)
        val reachable = account.status != AccountStatus.BANNED
        return MessagePerson(account.handle.takeIf { reachable }, account.displayName, account.avatarPath().takeIf { reachable })
    }

    /** Dieselbe Reinigung wie bei Kommentaren: Umbrueche bleiben, alle anderen Steuerzeichen nicht. */
    private fun clean(body: String): String {
        val text = body.withoutInvisible()
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .filter { it == '\n' || !it.isISOControl() }
            .trim()

        if (text.isEmpty()) throw PortalException.badRequest("empty-message", "Write something first.")
        if (text.length > properties.messages.maxLength)
            throw PortalException.badRequest("message-too-long", "Messages are limited to ${properties.messages.maxLength} characters.")
        return text
    }

    private fun preview(body: String): String {
        val line = body.replace('\n', ' ')
        return if (line.length <= PREVIEW_LENGTH) line else line.take(PREVIEW_LENGTH - 1).trimEnd() + "…"
    }

    companion object {
        const val PAGE_SIZE = 50
        const val PREVIEW_LENGTH = 120

        /**
         * Die zwei Kennungen eines Paars in fester Reihenfolge - so steht es
         * in `account_a`/`account_b`, und nur so greift der eindeutige
         * Schluessel. Welche Ordnung, ist gleich; es muss nur IMMER dieselbe sein.
         */
        fun pair(one: UUID, other: UUID): Pair<UUID, UUID> = if (one < other) one to other else other to one
    }
}
