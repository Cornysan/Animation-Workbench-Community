package com.playmation.motionlabsbackend.showcase

import org.slf4j.LoggerFactory
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Ein Discord-Webhook: posten, loeschen, den Server erfragen. Geteilt vom
 * Schaufenster ([DiscordShowcaseChannel]) und von den Clip-Wuenschen
 * ([com.playmation.motionlabsbackend.requests.DiscordClipRequestChannel]) -
 * was sie posten, bauen sie selbst.
 *
 * Zeigt er auf einen Forum-Kanal, wird jeder Post ein eigener Thread
 * (`thread_name`); zeigt er auf einen Textkanal, antwortet Discord darauf mit
 * Code 220003, und der Post geht ohne Thread-Felder noch einmal hinaus.
 * Derselbe Weg wie beim Feedback-Dienst.
 *
 * @param label steht vor jeder Warnung im Log ("Showcase post failed").
 * @param address die Webhook-Adresse - als Funktion, damit ein Test die
 *   Eigenschaften nach dem Start noch setzen kann.
 */
class DiscordWebhook(private val label: String, private val address: () -> String) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val json = JsonMapper.builder().build()

    private val webhook get() = address().trim().substringBefore('?')

    /**
     * [payload] baut den Rumpf - zuerst als Thread, und nur wenn Discord das
     * ablehnt, ohne. Null = nicht gepostet.
     */
    fun post(payload: (asThread: Boolean) -> String): ShowcasePosted? = try {
        val forum = send(payload(true))
        when {
            forum.statusCode() in 200..299 -> posted(forum.body(), asThread = true)
            forum.statusCode() == 400 && discordCode(forum.body()) == 220003 -> {
                val plain = send(payload(false))
                if (plain.statusCode() in 200..299) posted(plain.body(), asThread = false)
                else null.also { log.warn("{} post failed: HTTP {} {}", label, plain.statusCode(), plain.body().take(300)) }
            }
            else -> null.also { log.warn("{} post failed: HTTP {} {}", label, forum.statusCode(), forum.body().take(300)) }
        }
    } catch (ex: Exception) {
        log.warn("{} post failed: {}", label, ex.message)
        null
    }

    /** true = weg (auch: war schon weg). */
    fun delete(messageId: String, threadId: String?): Boolean = try {
        val url = "$webhook/messages/$messageId" + (threadId?.let { "?thread_id=$it" } ?: "")
        val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).DELETE().build()
        val status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
        //  404: schon von Hand geloescht - auch gut.
        (status in 200..299 || status == 404).also { if (!it) log.warn("{} delete failed: HTTP {}", label, status) }
    } catch (ex: Exception) {
        log.warn("{} delete failed: {}", label, ex.message)
        false
    }

    /**
     * Ein GET auf den Webhook selbst liefert ihn als Objekt, samt `guild_id`.
     * Kostet nichts, darf aber dauern - deshalb nur aus einem Takt oder direkt
     * nach einem Post, auf den ohnehin gewartet wurde.
     */
    fun guildId(): String? = try {
        val request = HttpRequest.newBuilder(URI.create(webhook)).timeout(Duration.ofSeconds(10)).GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() in 200..299) json.readTree(response.body()).path("guild_id").asString("").ifEmpty { null }
        else null.also { log.warn("{}: reading the webhook failed: HTTP {}", label, response.statusCode()) }
    } catch (ex: Exception) {
        log.warn("{}: reading the webhook failed: {}", label, ex.message)
        null
    }

    private fun send(body: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("$webhook?wait=true"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun posted(body: String, asThread: Boolean): ShowcasePosted? {
        val node = json.readTree(body)
        val id = node.path("id").asString("").ifEmpty { return null }
        return ShowcasePosted(id, if (asThread) node.path("channel_id").asString("").ifEmpty { null } else null)
    }

    private fun discordCode(body: String): Int? = try {
        json.readTree(body).path("code").takeIf { it.isNumber }?.asInt()
    } catch (ex: Exception) {
        null
    }
}
