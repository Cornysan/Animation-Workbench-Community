package com.playmation.motionlabsbackend.requests

import com.playmation.motionlabsbackend.account.Account
import com.playmation.motionlabsbackend.account.AccountRepository
import com.playmation.motionlabsbackend.account.AccountStatus
import com.playmation.motionlabsbackend.account.avatarPath
import com.playmation.motionlabsbackend.auth.PortalPrincipal
import com.playmation.motionlabsbackend.common.PortalException
import com.playmation.motionlabsbackend.config.PortalProperties
import com.playmation.motionlabsbackend.showcase.DiscordWebhook
import com.playmation.motionlabsbackend.showcase.ShowcasePosted
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import tools.jackson.databind.json.JsonMapper
import java.net.URLEncoder
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.UUID

/**
 * "Ask for it on Discord": eine Suche in der Workbench fand nichts, und wer
 * gesucht hat, stellt den Suchtext ins Discord-Forum fuer Clip-Wuensche.
 *
 * WARUM ES DAS GIBT: Bei einem kleinen Katalog ist "nichts gefunden" der
 * haeufigste Zustand der Wand. Ein Wunsch macht daraus etwas: Ersteller sehen,
 * was gefragt ist, und das Forum bekommt Inhalt.
 *
 * SOFORT, NICHT IM TAKT: anders als im Schaufenster wartet hier nichts auf
 * ein Bild. Wer klickt, bekommt den Thread gleich zurueck; die Anfrage dauert
 * dafuer so lange wie Discord, hoechstens zehn Sekunden. Ohne Transaktion
 * darum, aus demselben Grund wie im Schaufenster.
 *
 * EIN THREAD JE WUNSCH: wer nach etwas fragt, das in den letzten
 * [PortalProperties.Requests.openDays] Tagen schon jemand gewuenscht hat,
 * bekommt dessen Thread statt eines zweiten - und das zaehlt nicht aufs
 * Tageslimit.
 *
 * WAS IM POST STEHT: der Suchtext und sonst nichts Fremdes - und darin nur
 * Buchstaben, Ziffern und wenige Zeichen ([ALLOWED]). Damit gibt es weder
 * Markdown noch Links noch Erwaehnungen; der Webhook postet mit dem Namen des
 * Portals. Mit Haekchen dazu Name und Bild des Kontos, wie im Schaufenster.
 *
 * WAS VERSCHWINDET: was zurueckgenommen wird ([takeBack]), sofort, und alles
 * von gesperrten oder geschlossenen Konten im Takt. Der Thread bleibt, ein
 * Webhook darf keine Threads loeschen.
 */
@Service
class ClipRequestService(
    private val requests: ClipRequestRepository,
    private val accounts: AccountRepository,
    private val channel: ClipRequestChannel,
    private val properties: PortalProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /** Danach gilt ein Loeschen als gescheitert und wird nicht mehr versucht. */
        const val MAX_RETRACT_ATTEMPTS = 10

        const val OUTCOME_RETRACT_FAILED = "retract-failed"

        /**
         * Buchstaben und Ziffern jeder Schrift, Leerzeichen und `' & + , . / -`.
         * Kein Doppelpunkt (keine Adresse), keine Klammern, Sterne, Striche
         * oder Rauten (kein Markdown), kein `@` und kein `<` (keine Erwaehnung).
         */
        private val ALLOWED = Regex("^[\\p{L}\\p{N} '&+,./-]+$")
        private val LETTER = Regex("\\p{L}")
        private val SPACES = Regex("[\\s_]+")
        private val SNOWFLAKE = Regex("^[0-9]{1,20}$")
    }

    val enabled get() = properties.requests.discordWebhookUrl.isNotBlank()

    private val base get() = properties.publicBaseUrl.trimEnd('/')

    /** Ein Wunsch, wie Workbench und Seite ihn sehen. */
    data class RequestView(
        val id: UUID,
        val phrase: String,
        val askedAt: Instant,
        /** Selbst gewuenscht - dann bietet die Workbench "Take it back" an. */
        val mine: Boolean,
        /** Der Thread im Forum, oder null (Textkanal, oder der Server ist noch unbekannt). */
        val discordUrl: String?,
    )

    /** [existing] = schon gewuenscht, es wurde nichts gepostet. */
    data class AskResult(val request: RequestView, val existing: Boolean)

    // ════════════════════════════════════════════════════════════════════
    // FRAGEN, NACHSEHEN, ZURUECKNEHMEN
    // ════════════════════════════════════════════════════════════════════

    /** Der offene Wunsch zu diesem Suchtext, oder null - fuer die leere Wand. */
    fun find(principal: PortalPrincipal, phrase: String): RequestView? {
        if (!enabled) return null
        val clean = normalize(phrase) ?: return null
        return open(key(clean))?.let { view(it, principal) }
    }

    fun ask(principal: PortalPrincipal, phrase: String, named: Boolean): AskResult {
        if (!enabled) throw PortalException.unavailable("Asking on Discord is not available right now.")

        val clean = normalize(phrase)
            ?: throw PortalException.badRequest("bad-phrase",
                "A request is ${MIN_LENGTH} to ${properties.requests.maxLength} characters long.")
        if (!ALLOWED.matches(clean) || !LETTER.containsMatchIn(clean))
            throw PortalException.badRequest("bad-phrase", "Only letters, numbers and spaces can go into a request.")

        val key = key(clean)
        open(key)?.let { return AskResult(view(it, principal), existing = true) }

        val me = accounts.findById(principal.accountId).orElseThrow { PortalException.notFound("Account not found") }
        if (me.status == AccountStatus.RESTRICTED)
            throw PortalException(HttpStatus.FORBIDDEN, "restricted",
                "Your account is restricted after a removed clip, so it cannot ask on Discord.")
        if (me.status == AccountStatus.BANNED)
            throw PortalException.forbidden("This account cannot ask on Discord.")

        val now = clock.instant()
        val perDay = properties.requests.perDay
        if (requests.countByAccountIdAndAskedAtAfter(me.id, now.minus(Duration.ofDays(1))) >= perDay)
            throw PortalException(HttpStatus.TOO_MANY_REQUESTS, "rate-limited",
                "You can ask for $perDay clips a day. Try again tomorrow.")

        val posted = channel.post(message(clean, if (named) me else null))
            ?: throw PortalException.unavailable("Discord did not take the post. Try again in a minute.")

        val row = requests.save(ClipRequest(
            accountId = me.id,
            phrase = clean,
            phraseKey = key,
            named = named,
            askedAt = now,
            messageId = posted.messageId,
            threadId = posted.threadId,
        ))

        //  Beim allerersten Wunsch kennt der Dienst den Server noch nicht.
        //  Discord hat eben geantwortet, also darf auch diese Frage warten -
        //  sonst kaeme der erste Wunsch ohne Link zurueck.
        if (guild() == null) refreshGuild(force = true)

        return AskResult(view(row, principal), existing = false)
    }

    /**
     * "Take it back" - der Post geht SOFORT, nicht erst im Takt. Wer
     * zuruecknimmt, schaut gleich auf Discord nach, und bis 2026-10-03 stand
     * er dort dann noch bis zu einer Minute lang. Ohne Transaktion um den
     * Aufruf, wie beim Fragen.
     *
     * Erst markieren, dann loeschen: lehnt Discord ab oder antwortet nicht,
     * ist der Wunsch trotzdem schon zurueckgenommen, und der Takt versucht es
     * weiter ([runOnce]).
     */
    fun takeBack(principal: PortalPrincipal, id: UUID) {
        val row = requests.findById(id).orElse(null)
        if (row == null || (row.accountId != principal.accountId && !principal.isAdmin))
            throw PortalException.notFound("No such request")
        if (row.retractWanted || row.retractedAt != null) return
        row.retractWanted = true
        requests.save(row)

        if (enabled && channel.delete(row.messageId, row.threadId)) {
            row.retractedAt = clock.instant()
            requests.save(row)
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // DER TAKT
    // ════════════════════════════════════════════════════════════════════

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    fun tick() {
        if (!enabled) return
        try {
            runOnce(clock.instant())
        } catch (ex: Exception) {
            log.warn("Clip request tick failed: {}", ex.message)
        }
    }

    fun runOnce(now: Instant) {
        for (row in requests.findToRetract(AccountStatus.BANNED)) {
            if (channel.delete(row.messageId, row.threadId)) {
                row.retractedAt = now
            } else {
                row.attempts++
                if (row.attempts >= MAX_RETRACT_ATTEMPTS) {
                    row.retractedAt = now
                    row.outcome = OUTCOME_RETRACT_FAILED
                }
            }
            requests.save(row)
        }
        refreshGuild(force = false)
    }

    // ════════════════════════════════════════════════════════════════════
    // DER SERVER FUER DIE THREAD-ADRESSE
    // ════════════════════════════════════════════════════════════════════

    @Volatile private var fetchedGuild: String? = null
    @Volatile private var nextTry: Instant = Instant.EPOCH

    /** Wie [com.playmation.motionlabsbackend.showcase.ShowcaseLinks.refreshGuild], fuer dieses Forum. */
    private fun refreshGuild(force: Boolean) {
        if (!enabled || guild() != null) return
        val now = clock.instant()
        if (!force && now < nextTry) return
        nextTry = now.plus(Duration.ofMinutes(10))

        val guild = channel.guildId()
        if (guild != null && SNOWFLAKE.matches(guild)) fetchedGuild = guild
        else log.warn("Clip requests: the webhook did not name its Discord server - no thread links until it does")
    }

    private fun guild(): String? {
        val configured = properties.requests.discordGuildId.ifBlank { properties.showcase.discordGuildId }.trim()
        return configured.takeIf { SNOWFLAKE.matches(it) } ?: fetchedGuild
    }

    // ════════════════════════════════════════════════════════════════════
    // KLEINKRAM
    // ════════════════════════════════════════════════════════════════════

    private fun open(key: String): ClipRequest? =
        requests.findFirstByPhraseKeyAndAskedAtAfterAndRetractWantedFalseAndRetractedAtIsNullOrderByAskedAtDesc(
            key, clock.instant().minus(Duration.ofDays(properties.requests.openDays)))

    private fun view(row: ClipRequest, principal: PortalPrincipal) = RequestView(
        id = row.id,
        phrase = row.phrase,
        askedAt = row.askedAt,
        mine = row.accountId == principal.accountId,
        discordUrl = threadUrl(row.threadId),
    )

    /** Nur Ziffern gehen in die Adresse - wie in ShowcaseLinks. */
    private fun threadUrl(threadId: String?): String? {
        val guild = guild() ?: return null
        if (threadId == null || !SNOWFLAKE.matches(threadId)) return null
        return "https://discord.com/channels/$guild/$threadId"
    }

    /**
     * Leerraum und Unterstriche werden ein Leerzeichen - aus `walk_cycle`
     * wird `walk cycle`, wie die Suche es ohnehin liest. Null = zu kurz oder
     * zu lang.
     */
    internal fun normalize(phrase: String): String? {
        val clean = phrase.replace(SPACES, " ").trim()
        return clean.takeIf { it.length in MIN_LENGTH..properties.requests.maxLength }
    }

    private fun key(clean: String) = clean.lowercase(Locale.ROOT)

    private fun message(phrase: String, asker: Account?) = ClipRequestMessage(
        threadName = "Looking for: $phrase".take(100),
        title = "Looking for: $phrase",
        url = "$base/?q=" + URLEncoder.encode(phrase, Charsets.UTF_8),
        description = "Nothing in the Community matches this yet. If you make one, share it from the " +
            "Animation Workbench, and everyone who searches for it finds it there.",
        authorName = asker?.displayName,
        authorUrl = asker?.handle?.let { "$base/u.html?u=" + URLEncoder.encode(it, Charsets.UTF_8) },
        authorIcon = asker?.avatarPath()?.let { base + it },
    )
}

private const val MIN_LENGTH = 2

/** Ein Wunsch, wie er an Discord geht. Ohne [authorName] steht er ohne Namen da. */
data class ClipRequestMessage(
    val threadName: String,
    val title: String,
    val url: String,
    val description: String,
    val authorName: String?,
    val authorUrl: String?,
    val authorIcon: String?,
)

/** Das Forum, in das gepostet wird - eine Naht, damit die Tests ohne Discord laufen. */
interface ClipRequestChannel {
    /** Null = nicht gepostet; der Wunsch scheitert, und niemand hat ihn. */
    fun post(message: ClipRequestMessage): ShowcasePosted?

    /** true = weg (auch: war schon weg). */
    fun delete(messageId: String, threadId: String?): Boolean

    /** Der Discord-Server des Forums - fuer die Thread-Adresse. Null = unbekannt. */
    fun guildId(): String? = null
}

@Component
class DiscordClipRequestChannel(private val properties: PortalProperties) : ClipRequestChannel {
    private val hook = DiscordWebhook("Clip request") { properties.requests.discordWebhookUrl }
    private val json = JsonMapper.builder().build()

    override fun post(message: ClipRequestMessage): ShowcasePosted? = hook.post { asThread -> payload(message, asThread) }

    override fun delete(messageId: String, threadId: String?): Boolean = hook.delete(messageId, threadId)

    override fun guildId(): String? = hook.guildId()

    internal fun payload(message: ClipRequestMessage, asThread: Boolean): String {
        val embed = linkedMapOf<String, Any>(
            "title" to message.title.take(256),
            "url" to message.url,
            "description" to message.description,
            "footer" to mapOf("text" to "Asked in the Animation Workbench"),
        )
        message.authorName?.let { name ->
            embed["author"] = linkedMapOf<String, Any>("name" to name.take(256)).apply {
                message.authorUrl?.let { put("url", it) }
                message.authorIcon?.let { put("icon_url", it) }
            }
        }

        val body = linkedMapOf<String, Any>(
            "embeds" to listOf(embed),
            //  Niemand wird angepingt - der Suchtext kann ohnehin kein `@`
            //  tragen, aber ein Name kann es.
            "allowed_mentions" to mapOf("parse" to emptyList<String>()),
        )
        if (asThread) {
            body["thread_name"] = message.threadName
            val tags = properties.requests.forumTags.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (tags.isNotEmpty()) body["applied_tags"] = tags
        }
        return json.writeValueAsString(body)
    }
}
