package com.playmation.motionlabsbackend.notification

import com.playmation.motionlabsbackend.account.Account
import com.playmation.motionlabsbackend.account.AccountRepository
import com.playmation.motionlabsbackend.account.AccountStatus
import com.playmation.motionlabsbackend.account.avatarPath
import com.playmation.motionlabsbackend.moderation.Notification
import com.playmation.motionlabsbackend.moderation.NotificationRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Was eine Nachricht ist. Die Seite waehlt daran ihr Zeichen - der Text steht in der Nachricht. */
object NotificationKind {
    const val FOLLOW = "follow"
    const val LIKE = "like"
    const val COLLECTED = "collected"
    const val COMMENT = "comment"
    /** Jemand schreibt unter einen Clip, unter dem man selbst schon geschrieben hat. */
    const val REPLY = "reply"
    const val NEW_CLIP = "new-clip"
    const val NEW_PACK = "new-pack"
    const val MILESTONE = "milestone"
}

/** Die Ziele, auf die eine Nachricht zeigen kann - an EINER Stelle, damit sie nicht auseinanderlaufen. */
object NotificationLinks {
    fun clip(slug: String) = "/clip.html?p=$slug"
    fun comments(slug: String) = "/clip.html?p=$slug#comments"
    fun collection(slug: String) = "/collection.html?c=$slug"
    fun pack(slug: String) = "/pack.html?k=$slug"
}

/** Wer eine Nachricht ausgeloest hat, so wie die Seite ihn zeigt. `handle` null = kein Profil (mehr). */
data class NotificationActor(val handle: String?, val displayName: String, val avatarUrl: String?)

data class NotificationView(
    val id: UUID,
    /** Der ganze Satz, Name eingeschlossen - fuer alles, was nur Text zeigen will. */
    val message: String,
    val createdAt: Instant,
    val read: Boolean,
    val kind: String?,
    val link: String?,
    val actor: NotificationActor?,
    /** Mit Absender: der Satz OHNE den Namen. Die Seite setzt den Namen als Link davor. */
    val text: String?,
)

/**
 * Der eine Weg, auf dem Nachrichten entstehen und gelesen werden.
 *
 * Vorher schrieb jeder Dienst seine Zeile selbst, als fertigen Satz. Das ging,
 * solange eine Nachricht nur Text war; mit Absender und Ziel waeren es sechs
 * Stellen, die dieselben drei Regeln einhalten muessen:
 *
 *  1. Nie an sich selbst. Wer den eigenen Clip mag, erfaehrt es schon.
 *  2. Nicht zweimal dasselbe. Herz an, Herz aus, Herz an ist EINE Nachricht.
 *  3. Der Name steht nicht im Text. Er kommt beim Lesen aus dem Konto - ein
 *     geschlossenes Konto heisst danach ueberall "Deleted user", und
 *     [forgetActor] raeumt seine Nachrichten bei anderen ganz ab.
 */
@Service
class Notifier(
    private val notifications: NotificationRepository,
    private val accounts: AccountRepository,
    private val clock: Clock,
) {
    /** Eine Nachricht ohne Absender - Moderation, Meilensteine. */
    fun send(to: UUID, message: String, kind: String? = null, link: String? = null) {
        notifications.save(Notification(
            accountId = to, message = message.take(1000), createdAt = clock.instant(), kind = kind, link = link))
    }

    /**
     * Eine Nachricht mit Absender. `text` ist der Satz ohne den Namen davor:
     * "liked 'Walk Cycle'."
     *
     * @param once nur, wenn es genau diese Nachricht (Absender, Art, Ziel,
     *   Text) an diese Person noch nie gab. Fuer alles, was sich an- und
     *   ausschalten laesst.
     * @return ob eine Nachricht entstanden ist.
     */
    fun fromActor(to: UUID, actor: Account, text: String, kind: String, link: String? = null, once: Boolean = false): Boolean {
        if (to == actor.id) return false

        val message = text.take(1000)
        if (once) {
            val seen = if (link == null) notifications.existsByAccountIdAndActorIdAndKind(to, actor.id, kind)
                else notifications.existsByAccountIdAndActorIdAndKindAndLinkAndMessage(to, actor.id, kind, link, message)
            if (seen) return false
        }

        notifications.save(Notification(
            accountId = to, message = message, createdAt = clock.instant(),
            kind = kind, actorId = actor.id, link = link))
        return true
    }

    /** Dieselbe Nachricht an viele - Follower, Mitschreibende. Der Absender selbst faellt heraus. */
    fun fromActorToMany(recipients: Collection<UUID>, actor: Account, text: String, kind: String, link: String?) {
        val now = clock.instant()
        val message = text.take(1000)
        notifications.saveAll(recipients.filter { it != actor.id }.distinct().map {
            Notification(accountId = it, message = message, createdAt = now, kind = kind, actorId = actor.id, link = link)
        })
    }

    /**
     * Liegt zu diesem Ziel schon eine ungelesene Nachricht dieser Art? Dann
     * sagt die schon, dass dort etwas los ist - eine zweite waere Rauschen.
     */
    fun hasUnread(to: UUID, kind: String, link: String): Boolean =
        notifications.existsByAccountIdAndKindAndLinkAndReadAtIsNull(to, kind, link)

    /**
     * Das Postfach, neueste zuerst - und damit gelesen.
     *
     * Gelesen heisst: einmal geholt. Wer die Glocke oeffnet oder die Kontoseite,
     * HAT sie gesehen; ein eigener Knopf dafuer waere eine Pflicht ohne Nutzen.
     * Die Antwort traegt noch den Zustand VOR dem Oeffnen, damit die Seite die
     * neuen hervorheben kann.
     */
    @Transactional
    fun inbox(accountId: UUID, limit: Int = 100, markRead: Boolean = true): List<NotificationView> {
        val list = notifications.findByAccountIdOrderByCreatedAtDesc(accountId).take(limit)
        val actors = accounts.findAllById(list.mapNotNull { it.actorId }.toSet()).associateBy { it.id }

        val result = list.map { n ->
            val actor = n.actorId?.let { id -> actorView(actors[id]) }
            NotificationView(
                id = n.id,
                message = if (actor != null) actor.displayName + " " + n.message else n.message,
                createdAt = n.createdAt,
                read = n.readAt != null,
                kind = n.kind,
                link = n.link,
                actor = actor,
                text = if (actor != null) n.message else null,
            )
        }

        if (markRead) {
            val now = clock.instant()
            list.filter { it.readAt == null }.forEach { it.readAt = now }
        }
        return result
    }

    /** Beim Schliessen eines Kontos: was es bei anderen ausgeloest hat, geht mit. */
    fun forgetActor(actorId: UUID) {
        notifications.deleteAll(notifications.findByActorId(actorId))
    }

    /**
     * Ein Konto, das es nicht mehr gibt oder das gesperrt ist, hat kein Profil
     * - der Name bleibt stehen, der Link nicht.
     */
    private fun actorView(account: Account?): NotificationActor {
        if (account == null) return NotificationActor(null, "Someone", null)
        val reachable = account.status != AccountStatus.BANNED
        return NotificationActor(
            handle = account.handle.takeIf { reachable },
            displayName = account.displayName,
            avatarUrl = account.avatarPath().takeIf { reachable },
        )
    }
}
