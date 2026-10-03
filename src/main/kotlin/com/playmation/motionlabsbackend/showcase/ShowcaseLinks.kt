package com.playmation.motionlabsbackend.showcase

import com.playmation.motionlabsbackend.config.PortalProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Wo ueber einen Clip geredet wird: der Thread seines Schaufenster-Posts im
 * Discord-Forum, als Adresse zum Anklicken.
 *
 * Kommentare gibt es in der Workbench nicht mehr (seit 2026-09-24), und auf
 * dem Portal liest sie kaum jemand. Der Thread ist der Ort, an dem Leute
 * tatsaechlich antworten - also zeigen Karte, Clip-Seite und Workbench dorthin
 * ("Discuss on Discord", fuer den Besitzer "Open the Discord post").
 *
 * DIE ADRESSE braucht zwei Kennungen: den Thread (steht seit Schema 22 in
 * `showcase_post.thread_id`) und den Server (Guild). Die Guild steht in keiner
 * Antwort auf einen Post; der Webhook selbst nennt sie aber
 * ([ShowcaseChannel.guildId]). Geholt wird sie im Takt des Schaufensters
 * ([refreshGuild]), nie waehrend einer Anfrage - eine Kartenseite soll nicht
 * zehn Sekunden auf Discord warten, nur weil der Server gerade gestartet ist.
 * Bis dahin gibt es eben keine Links. `portal.showcase.discord-guild-id`
 * ueberstimmt das, falls der Webhook einmal nicht gefragt werden kann.
 *
 * Nur Threads: ein Post in einem Textkanal hat keine eigene Adresse, die man
 * ohne die Kanal-Kennung bauen koennte - und die wird nicht gespeichert.
 */
@Service
class ShowcaseLinks(
    private val posts: ShowcasePostRepository,
    private val channel: ShowcaseChannel,
    private val properties: PortalProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile private var fetchedGuild: String? = null
    @Volatile private var nextTry: Instant = Instant.EPOCH

    private val enabled get() = properties.showcase.discordWebhookUrl.isNotBlank()

    /** Thread-Adressen fuer diese Clips - EINE Abfrage fuer eine ganze Kartenseite. */
    fun forClips(ids: Collection<UUID>): Map<UUID, String> {
        val guild = guild() ?: return emptyMap()
        if (ids.isEmpty()) return emptyMap()
        return posts.liveThreadsForClips(ids.toSet())
            .mapNotNull { post -> url(guild, post.threadId)?.let { post.packageId!! to it } }
            .toMap()
    }

    /** Dasselbe fuer Packs. */
    fun forPacks(ids: Collection<UUID>): Map<UUID, String> {
        val guild = guild() ?: return emptyMap()
        if (ids.isEmpty()) return emptyMap()
        return posts.liveThreadsForPacks(ids.toSet())
            .mapNotNull { post -> url(guild, post.threadId)?.let { post.packId!! to it } }
            .toMap()
    }

    fun forClip(id: UUID): String? = forClips(listOf(id))[id]

    fun forPack(id: UUID): String? = forPacks(listOf(id))[id]

    /**
     * Die Guild beim Webhook erfragen, wenn sie noch fehlt. Aus dem Takt des
     * Schaufensters ([ShowcaseService.tick]); scheitert es, erst in zehn
     * Minuten wieder.
     */
    fun refreshGuild() {
        if (!enabled || configuredGuild() != null || fetchedGuild != null) return
        val now = clock.instant()
        if (now < nextTry) return
        nextTry = now.plus(Duration.ofMinutes(10))

        val guild = channel.guildId()
        if (guild != null && SNOWFLAKE.matches(guild)) {
            fetchedGuild = guild
        } else {
            log.warn("Showcase: the webhook did not name its Discord server - no thread links until it does")
        }
    }

    private fun guild(): String? {
        if (!enabled) return null
        return configuredGuild() ?: fetchedGuild
    }

    private fun configuredGuild(): String? =
        properties.showcase.discordGuildId.trim().takeIf { SNOWFLAKE.matches(it) }

    /**
     * Nur Ziffern gehen in die Adresse - beide Kennungen kommen von Discord,
     * aber eine Adresse, die eine Seite anklickbar macht, baut man nicht aus
     * ungeprueften Zeichen.
     */
    private fun url(guild: String, threadId: String?): String? {
        if (threadId == null || !SNOWFLAKE.matches(threadId)) return null
        return "https://discord.com/channels/$guild/$threadId"
    }

    companion object {
        private val SNOWFLAKE = Regex("^[0-9]{1,20}$")
    }
}
