package com.playmation.motionlabsbackend.web

import com.playmation.motionlabsbackend.auth.PortalPrincipal
import com.playmation.motionlabsbackend.auth.requirePrincipal
import com.playmation.motionlabsbackend.catalog.AnimationPackageRepository
import com.playmation.motionlabsbackend.catalog.CatalogService
import com.playmation.motionlabsbackend.catalog.PackageStatus
import com.playmation.motionlabsbackend.catalog.PackageVersionRepository
import com.playmation.motionlabsbackend.common.PortalException
import com.playmation.motionlabsbackend.common.RateLimiter
import com.playmation.motionlabsbackend.config.PortalProperties
import com.playmation.motionlabsbackend.format.AwclipSchema
import com.playmation.motionlabsbackend.storage.BlobStore
import com.playmation.motionlabsbackend.system.SystemSettingsService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RestController
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * Die Bilder, die erscheinen, wenn jemand einen Link auf das Portal teilt.
 *
 * BIS 2026-09-27 ZEICHNETE SIE DER SERVER SELBST (`ClipCard.kt`): eine flache
 * Strichfigur mit Trapez als Rumpf, weil es hier weder WebGL noch eine Schrift
 * gibt. In Discord sah das neben dem Portal-Namen aus wie ein Platzhalter
 * (Pablo: "das Strichmaennchen sieht schlimm aus").
 *
 * JETZT RENDERT SIE DER BROWSER, mit genau der Buehne, die auch die Clip-Seite
 * zeigt (`og-card.js`): Mannequin, Licht, Boden, bei Clips mit Weg zwei
 * Zwischenposen. Hochladen duerfen das nur der Besitzer des Clips und Admins -
 * ein Bild, das in jedem Chat erscheint, in den der Link geraet, laedt nicht
 * irgendwer hoch. Die Seite des Besitzers rendert es selbst, wenn es fehlt
 * (`clip.js`); fuer alle anderen gibt es einen Knopf auf der Admin-Seite.
 *
 * Bis ein Clip sein Bild hat, steht dort das Bild der Seite ([siteCardKey]),
 * mit kurzer Haltbarkeit - sonst hielte ein Zwischenspeicher eine Woche lang
 * das Ersatzbild fest. Die Adresse traegt ausserdem `?v=`, damit ein neues
 * Bild auch eine neue Adresse ist.
 */
@Service
class PreviewCards(
    private val packages: AnimationPackageRepository,
    private val versions: PackageVersionRepository,
    private val catalog: CatalogService,
    private val blobs: BlobStore,
    private val settings: SystemSettingsService,
    private val rateLimiter: RateLimiter,
    private val portal: PortalProperties,
) {
    data class Card(val bytes: ByteArray, val own: Boolean)

    private val base get() = portal.publicBaseUrl.trimEnd('/')

    /** Das Bild eines oeffentlichen Clips, oder das der Seite. Null = gar keins. */
    @Transactional(readOnly = true)
    fun forClip(slug: String): Card? {
        val key = ownKey(slug)
        if (key != null) return Card(blobs.open(key).use { it.readBytes() }, own = true)
        return site()?.let { Card(it, own = false) }
    }

    fun site(): ByteArray? = settings.siteCardKey()?.let { key -> blobs.open(key).use { it.readBytes() } }

    /**
     * Fuer `og:image`: das eigene Bild des Clips, sonst das der Seite. Null nur,
     * wenn es noch gar keins gibt (frische Installation) - dann lieber kein Bild
     * als ein kaputtes.
     */
    @Transactional(readOnly = true)
    fun imageFor(slug: String?): String? {
        val own = slug?.let { ownKey(it) }
        if (own != null) return "$base/clip-card/$slug.png?v=${own.take(8)}"
        return siteImage()
    }

    /** Nur das EIGENE Bild - fuer das JSON-LD, wo ein fremdes Bild falsch waere. */
    @Transactional(readOnly = true)
    fun ownImage(slug: String): String? = ownKey(slug)?.let { "$base/clip-card/$slug.png?v=${it.take(8)}" }

    fun siteImage(): String? = settings.siteCardKey()?.let { "$base/site-card.png?v=${it.take(8)}" }

    private fun ownKey(slug: String): String? {
        val pkg = packages.findBySlug(slug) ?: return null
        if (pkg.status != PackageStatus.PUBLISHED || pkg.license != AwclipSchema.LICENSE_PUBLIC) return null
        val version = pkg.currentVersionId?.let { versions.findById(it).orElse(null) } ?: return null
        return version.cardBlobKey
    }

    /** Hochladen fuer einen Clip: Besitzer oder Admin, nur oeffentlich, nur die aktuelle Version. */
    @Transactional
    fun storeForClip(slug: String, principal: PortalPrincipal, bytes: ByteArray) {
        rateLimiter.require("preview-card", principal.accountId.toString(), 600, Duration.ofHours(1))
        val (pkg, version) = catalog.visible(slug, principal)
        if (pkg.ownerId != principal.accountId && !principal.isAdmin) throw PortalException.forbidden("Only the owner can set the preview image of this clip.")
        if (pkg.license != AwclipSchema.LICENSE_PUBLIC || pkg.status != PackageStatus.PUBLISHED)
            throw PortalException.badRequest("not-public", "Only public clips get a preview image.")

        version.cardBlobKey = blobs.put(normalize(bytes))
        versions.save(version)
    }

    @Transactional
    fun storeSite(bytes: ByteArray) {
        settings.setSiteCardKey(blobs.put(normalize(bytes)))
    }

    /**
     * Admin: die Slugs, die ein Bild brauchen - alle, oder nur die ohne. Nur
     * humanoide: ein generischer Clip laeuft auf seinem eigenen Skelett, das
     * Mannequin kann ihn nicht spielen (er behaelt das Bild der Seite).
     */
    @Transactional(readOnly = true)
    fun todo(all: Boolean): List<String> =
        versions.publicCards(PackageStatus.PUBLISHED, AwclipSchema.LICENSE_PUBLIC, AwclipSchema.RIG_HUMANOID)
            .filter { all || it[1] == null }
            .map { it[0] as String }

    companion object {
        const val WIDTH = 1200
        const val HEIGHT = 630

        /** Ein gerendertes Bild in 1200 x 630 liegt bei 300-700 KB. */
        const val MAX_BYTES = 3 * 1024 * 1024

        private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

        /**
         * Was gespeichert wird, ist nie die hochgeladene Datei, sondern ein neu
         * geschriebenes PNG: nur Bildpunkte, keine Textbloecke, keine Beigaben.
         *
         * DIE GROESSE WIRD VOR DEM DEKODIEREN GEPRUEFT. Ein PNG von 3 MB kann
         * 60 000 x 60 000 Bildpunkte behaupten; `ImageIO.read` wuerde dafuer
         * erst Speicher anlegen und dann scheitern - der Leser nennt die Masse
         * vorher.
         */
        fun normalize(bytes: ByteArray): ByteArray {
            fun bad(): Nothing = throw PortalException.badRequest("bad-image", "Expected a $WIDTH x $HEIGHT PNG.")
            if (bytes.size > MAX_BYTES || bytes.size < PNG.size || !bytes.copyOfRange(0, PNG.size).contentEquals(PNG)) bad()

            val image = ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input ->
                val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: bad()
                try {
                    reader.input = input
                    if (reader.getWidth(0) != WIDTH || reader.getHeight(0) != HEIGHT) bad()
                    reader.read(0)
                } catch (e: PortalException) {
                    throw e
                } catch (e: Exception) {
                    bad()
                } finally {
                    reader.dispose()
                }
            }
            return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        }

        /** Den Koerper lesen, aber nicht mehr als [MAX_BYTES] - ein Byte mehr heisst: zu gross. */
        fun readBody(request: HttpServletRequest): ByteArray {
            if (request.contentLengthLong > MAX_BYTES) throw PortalException.badRequest("bad-image", "The image is too large.")
            val bytes = request.inputStream.readNBytes(MAX_BYTES + 1)
            if (bytes.size > MAX_BYTES) throw PortalException.badRequest("bad-image", "The image is too large.")
            return bytes
        }
    }
}

@RestController
class PreviewCardController(private val cards: PreviewCards) {

    /**
     * Das Bild eines Clips. Der Name traegt `.png`, weil manche Dienste an der
     * Endung entscheiden, ob sie ein Bild ueberhaupt holen.
     */
    @GetMapping("/clip-card/{slug}.png", produces = [MediaType.IMAGE_PNG_VALUE])
    fun clip(@PathVariable slug: String): ResponseEntity<ByteArray> {
        val card = cards.forClip(slug) ?: throw PortalException.notFound("No preview image for this clip.")
        //  Das eigene Bild aendert sich nur mit einer neuen Adresse (`?v=`).
        //  Das Ersatzbild dagegen soll verschwinden, sobald das eigene da ist.
        val cache = if (card.own) CacheControl.maxAge(30, TimeUnit.DAYS) else CacheControl.maxAge(10, TimeUnit.MINUTES)
        return ResponseEntity.ok().cacheControl(cache.cachePublic()).body(card.bytes)
    }

    @GetMapping("/site-card.png", produces = [MediaType.IMAGE_PNG_VALUE])
    fun site(): ResponseEntity<ByteArray> {
        val bytes = cards.site() ?: throw PortalException.notFound("No preview image yet.")
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(1, TimeUnit.DAYS).cachePublic()).body(bytes)
    }

    @PutMapping("/api/v1/packages/{slug}/card", consumes = [MediaType.IMAGE_PNG_VALUE])
    fun upload(@PathVariable slug: String, authentication: Authentication?, request: HttpServletRequest): ResponseEntity<Void> {
        cards.storeForClip(slug, authentication.requirePrincipal(), PreviewCards.readBody(request))
        return ResponseEntity.noContent().build()
    }

    @PutMapping("/api/v1/admin/site-card", consumes = [MediaType.IMAGE_PNG_VALUE])
    fun uploadSite(request: HttpServletRequest): ResponseEntity<Void> {
        cards.storeSite(PreviewCards.readBody(request))
        return ResponseEntity.noContent().build()
    }

    /** `?all=true`: auch die, die schon eins haben - nach einer neuen Gestaltung. */
    @GetMapping("/api/v1/admin/cards")
    fun todo(@org.springframework.web.bind.annotation.RequestParam(defaultValue = "false") all: Boolean) =
        mapOf("slugs" to cards.todo(all))
}
