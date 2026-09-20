package com.playmation.motionlabsbackend.profile

import com.playmation.motionlabsbackend.account.Account
import com.playmation.motionlabsbackend.common.PortalException
import org.slf4j.LoggerFactory
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Das Profilbild kommt ueber DIESEN Server, nicht direkt von Discord.
 *
 * Der kurze Weg waere gewesen, `cdn.discordapp.com/avatars/<id>/<hash>.png` in
 * die Seite zu schreiben - die Content Security Policy erlaubte es sogar
 * schon. Er kostet aber zweierlei:
 *
 *   Die DISCORD-KENNUNG steht dann im Quelltext jeder Profilseite. Sie ist
 *   nicht geheim, aber sie verbindet ein Portal-Konto mit einem Discord-Konto,
 *   und niemand hat darum gebeten.
 *
 *   DISCORD SIEHT JEDEN BESUCHER. Das Bild wird vom Browser geholt, also
 *   erfaehrt Discord die IP-Adresse auch derer, die dort gar kein Konto haben
 *   und diese Seite nur lesen.
 *
 * Beides faellt weg, wenn der Server das Bild einmal holt und danach selbst
 * ausliefert. Der Preis ist ein Zwischenspeicher, und der ist klein: ein
 * Avatar sind ein paar Kilobyte, und die Zahl der Konten ist ueberschaubar.
 *
 * KEIN OFFENER PROXY: geholt wird ausschliesslich die Adresse, die sich aus
 * einem Konto in DIESER Datenbank ergibt. Der Aufrufer nennt einen Handle,
 * keine URL.
 */
@Component
class AvatarCache(private val profiles: ProfileService) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Was ausgeliefert wird: die Bytes und der Typ, den Discord genannt hat. */
    data class Image(val bytes: ByteArray, val contentType: String, val fetchedAt: Long)

    private val cache = ConcurrentHashMap<String, Image>()

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(4))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    companion object {
        /** Ein Tag. Wer sein Bild wechselt, bekommt ohnehin beim Login einen neuen Hash. */
        val TTL: Duration = Duration.ofHours(24)

        /** Ein Avatar ist ein paar Kilobyte. Alles darueber ist nicht unser Bild. */
        const val MAX_BYTES = 512 * 1024

        /** So viele Bilder behalten wir; darueber faengt der Speicher von vorn an. */
        const val MAX_ENTRIES = 500
    }

    /**
     * Das Bild zu einem Handle - aus dem Zwischenspeicher oder frisch von
     * Discord. `null`, wenn dieses Konto kein Bild hat oder Discord nicht
     * antwortet; die Seite zeigt dann den Buchstabenkreis, den es ohnehin gibt.
     */
    fun imageFor(handle: String): Image? {
        val account = profiles.require(handle)
        val key = cacheKey(account) ?: return null

        val cached = cache[key]
        if (cached != null && System.currentTimeMillis() - cached.fetchedAt < TTL.toMillis())
            return cached

        val fetched = fetch(account) ?: return cached
        if (cache.size > MAX_ENTRIES) cache.clear()
        cache[key] = fetched
        return fetched
    }

    /** Der Schluessel traegt den Hash: ein neues Bild ist ein neuer Eintrag. */
    private fun cacheKey(account: Account): String? {
        val hash = account.avatar?.takeIf { it.isNotBlank() } ?: return null
        if (!account.discordId.all { it.isDigit() }) return null
        return account.discordId + "/" + hash
    }

    private fun fetch(account: Account): Image? {
        val key = cacheKey(account) ?: return null
        //  Animierte Avatare fangen bei Discord mit "a_" an; als .png holt man
        //  von ihnen das Standbild, und genau das wollen wir.
        val url = "https://cdn.discordapp.com/avatars/$key.png?size=128"

        return try {
            val request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(6))
                .header("User-Agent", "AnimationWorkbenchCommunity/1.0 (+avatar cache)")
                .GET()
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
            if (response.statusCode() != 200) return null

            val bytes = response.body()
            if (bytes.isEmpty() || bytes.size > MAX_BYTES) return null

            val type = response.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElse(MediaType.IMAGE_PNG_VALUE)
            if (!type.startsWith("image/")) return null

            Image(bytes, type, System.currentTimeMillis())
        } catch (ex: Exception) {
            //  Ein fehlendes Profilbild ist keine Stoerung des Portals - eine
            //  Zeile ins Protokoll, und der Buchstabe tut es auch.
            log.debug("Avatar for {} could not be fetched: {}", account.handle, ex.message)
            null
        }
    }
}

@RestController
class AvatarController(private val avatars: AvatarCache) {

    @GetMapping("/avatar/{handle}.png")
    fun avatar(@PathVariable handle: String): ResponseEntity<ByteArray> {
        val image = avatars.imageFor(handle) ?: throw PortalException.notFound("No avatar")

        return ResponseEntity.ok()
            //  Oeffentlich zwischenspeicherbar: das Bild ist dasselbe fuer
            //  jeden Besucher, und der Browser soll es nicht bei jedem
            //  Seitenaufruf neu holen.
            .cacheControl(CacheControl.maxAge(Duration.ofHours(12)).cachePublic())
            .contentType(MediaType.parseMediaType(image.contentType))
            .body(image.bytes)
    }
}
