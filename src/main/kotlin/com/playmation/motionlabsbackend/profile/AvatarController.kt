package com.playmation.motionlabsbackend.profile

import com.playmation.motionlabsbackend.account.Account
import com.playmation.motionlabsbackend.account.AvatarSources
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
 * Das Profilbild kommt ueber DIESEN Server, nicht direkt vom Anbieter
 * (Discord, GitHub, Google).
 *
 * Der kurze Weg waere gewesen, `cdn.discordapp.com/avatars/<id>/<hash>.png` in
 * die Seite zu schreiben - die Content Security Policy erlaubte es sogar
 * schon. Er kostet aber zweierlei:
 *
 *   Die KENNUNG BEIM ANBIETER steht dann im Quelltext jeder Profilseite. Sie
 *   ist nicht geheim, aber sie verbindet ein Portal-Konto mit einem Konto
 *   dort, und niemand hat darum gebeten.
 *
 *   DER ANBIETER SIEHT JEDEN BESUCHER. Das Bild wird vom Browser geholt, also
 *   erfaehrt er die IP-Adresse auch derer, die dort gar kein Konto haben und
 *   diese Seite nur lesen.
 *
 * Beides faellt weg, wenn der Server das Bild einmal holt und danach selbst
 * ausliefert. Der Preis ist ein Zwischenspeicher, und der ist klein: ein
 * Avatar sind ein paar Kilobyte, und die Zahl der Konten ist ueberschaubar.
 *
 * KEIN OFFENER PROXY: geholt wird ausschliesslich die Adresse, die an einem
 * Konto in DIESER Datenbank steht, und nur bei den drei Servern aus
 * [AvatarSources.HOSTS]. Der Aufrufer nennt einen Handle, keine URL.
 */
@Component
class AvatarCache(private val profiles: ProfileService) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Was ausgeliefert wird: die Bytes und der Typ, den der Anbieter genannt hat. */
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

        /**
         * Welche Bildtypen dieser Server unter SEINER Adresse ausliefert.
         *
         * `startsWith("image/")` stand hier und war zu weit: `image/svg+xml`
         * faengt genauso an, und ein SVG ist kein Bild, sondern ein Dokument
         * mit Skripten darin. Ausgeliefert von dieser Adresse liefe es im
         * Ursprung dieser Seite - mit Zugriff auf Cookies und Session.
         *
         * Dass kein Anbieter unter seinen Bildadressen ein SVG schickt, ist
         * wahr und trotzdem kein Grund, sich darauf zu verlassen: dieser
         * Server ist die letzte Stelle, die den Typ noch pruefen KANN, und er
         * darf nicht davon ausgehen, dass die andere Seite sich benimmt.
         */
        val ALLOWED_TYPES = setOf("image/png", "image/jpeg", "image/gif", "image/webp")
    }

    /**
     * Das Bild zu einem Handle - aus dem Zwischenspeicher oder frisch vom
     * Anbieter. `null`, wenn dieses Konto kein Bild hat oder der Anbieter nicht
     * antwortet; die Seite zeigt dann den Buchstabenkreis, den es ohnehin gibt.
     */
    fun imageFor(handle: String): Image? {
        val account = profiles.require(handle)
        val url = sourceOf(account) ?: return null

        val cached = cache[url]
        if (cached != null && System.currentTimeMillis() - cached.fetchedAt < TTL.toMillis())
            return cached

        val fetched = fetch(url, account) ?: return cached
        if (cache.size > MAX_ENTRIES) cache.clear()
        cache[url] = fetched
        return fetched
    }

    /**
     * Die Adresse beim Anbieter, zugleich der Schluessel im Speicher: ein neues
     * Bild ist eine neue Adresse und damit ein neuer Eintrag.
     *
     * Sie wird HIER noch einmal geprueft, obwohl beim Speichern schon einmal:
     * aus ihr wird gleich eine Anfrage, und die Datenbank ist nicht die einzige
     * Quelle, die hineingeschrieben hat - die Migration V8 hat Adressen aus
     * alten Discord-Hashes zusammengesetzt, ohne sie anzusehen. Wer nicht
     * durchgeht, behaelt den Buchstabenkreis.
     */
    private fun sourceOf(account: Account): String? =
        account.avatarUrl?.takeIf { it.isNotBlank() && AvatarSources.isAllowed(it) }

    private fun fetch(url: String, account: Account): Image? {
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

            //  Nur der Typ, ohne charset und was sonst dranhaengt - und dann
            //  gegen die Liste. Was nicht darin steht, wird nicht ausgeliefert.
            val type = response.headers().firstValue(HttpHeaders.CONTENT_TYPE)
                .orElse(MediaType.IMAGE_PNG_VALUE)
                .substringBefore(';').trim().lowercase()
            if (type !in ALLOWED_TYPES) return null

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
            //  Der Typ steht schon fest - [AvatarCache.ALLOWED_TYPES] hat
            //  entschieden, sonst laege hier nichts. `parseMediaType` bekommt
            //  deshalb nur noch einen von vier bekannten Werten und kann nicht
            //  mehr an einem krummen Header des Anbieters scheitern.
            .contentType(MediaType.parseMediaType(image.contentType))
            .body(image.bytes)
    }
}
