package com.playmation.motionlabsbackend.catalog

import com.playmation.motionlabsbackend.auth.PortalPrincipal
import com.playmation.motionlabsbackend.auth.portalPrincipal
import com.playmation.motionlabsbackend.common.RateLimiter
import com.playmation.motionlabsbackend.common.clientIp
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Aufrufe und Downloads - die beiden Zahlen unter jedem Clip (seit 2026-09-27).
 *
 * Die Uebernahme als .awclip zaehlt [com.playmation.motionlabsbackend.unlocks.UnlockService],
 * eine Quittung je Konto. Was dort nie ankam: wer die Seite ansieht, und wer
 * den Clip als FBX oder GLB mitnimmt. Die beiden Dateien entstehen ganz im
 * Browser (`clip-download.js`), ohne Konto - der Server sah sie nie. Deshalb
 * meldet die Seite beides selbst, und "Downloads" ist die Summe aus beiden
 * Wegen ([AnimationPackage.downloads]).
 *
 * AUFRUFE: JEDES OEFFNEN ZAEHLT - neu laden, zurueckblaettern, der eigene
 * Clip (Pablo, 2026-09-28: "die views koennen schon ercheatet werden", die
 * Zahl soll zeigen, dass auf der Seite etwas los ist). Bis dahin zaehlte ein
 * Besucher einmal am Tag und der Besitzer nie, und wer seinen eigenen Clip
 * zehnmal neu lud, sah dieselbe 1. Die Workbench meldet sich nicht hier: ein
 * Import zaehlt beim Freischalten ([CatalogService.unlock]).
 *
 * DOWNLOADS (FBX/GLB): EINMAL JE BESUCHER, CLIP UND TAG, nicht der eigene.
 * Alle Formate durchprobieren ist ein Download, nicht drei. Besucher heisst:
 * das Konto, sonst die IP-Adresse.
 *
 * Fuer beide: wer sich als Bot ausgibt, zaehlt nicht. Suchmaschinen fuehren
 * das Skript der Seite aus; ohne diese Regel waere jeder Crawl ein Aufruf.
 * Und kein Clip steigt um mehr als [VIEWS_PER_MINUTE] Aufrufe in der Minute -
 * sonst treibt ein Skript die Zahl in einer Nacht in die Millionen. Die
 * Grenze haengt am Clip, nicht am Besucher: dafuer muss niemand erkannt werden.
 *
 * DATENSCHUTZ: gespeichert wird nur die Zahl. Fuer einen Aufruf merkt sich
 * der Server nichts ueber den Besucher. Wer heute schon einen Download
 * gezaehlt hat, steht als 64-Bit-Pruefsumme im Speicher, gebildet mit einem
 * Zufallswert, der um Mitternacht (UTC) verworfen wird - danach laesst sich
 * aus keiner Pruefsumme mehr eine Adresse herausrechnen, auch nicht durch
 * Durchprobieren. Ein Neustart vergisst alles; schlimmstenfalls zaehlt jemand
 * an dem Tag zweimal.
 */
@Service
class ClipCounts(
    private val catalog: CatalogService,
    private val packages: AnimationPackageRepository,
    private val rateLimiter: RateLimiter,
    private val clock: Clock,
) {
    enum class Kind { VIEW, DOWNLOAD }

    private class Day(val date: LocalDate, val salt: ByteArray) {
        val seen: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    }

    @Volatile
    private var day = Day(LocalDate.now(clock), salt())

    /** true = gezaehlt. Ein Clip, den der Fragende nicht sehen darf, ist 404 wie ueberall. */
    @Transactional
    fun count(kind: Kind, slug: String, principal: PortalPrincipal?, ip: String, userAgent: String?): Boolean {
        val (pkg, _) = catalog.visible(slug, principal)
        if (isBot(userAgent)) return false

        when (kind) {
            Kind.VIEW -> {
                if (!rateLimiter.tryAcquire("view", pkg.id.toString(), VIEWS_PER_MINUTE, Duration.ofMinutes(1))) return false
                packages.addView(pkg.id)
            }
            Kind.DOWNLOAD -> {
                if (principal?.accountId == pkg.ownerId) return false
                if (!firstToday(kind, pkg.id, principal?.accountId?.toString() ?: ip)) return false
                packages.addFileDownload(pkg.id)
            }
        }
        return true
    }

    private fun firstToday(kind: Kind, packageId: UUID, visitor: String): Boolean {
        val today = LocalDate.now(clock)
        var current = day
        if (current.date != today) {
            synchronized(this) {
                if (day.date != today) day = Day(today, salt())
                current = day
            }
        }
        //  Voll heisst: jemand probiert Adressen durch. Dann zaehlt fuer den
        //  Rest des Tages niemand mehr - zu wenig zu zaehlen ist harmlos,
        //  doppelt zu zaehlen nicht.
        if (current.seen.size >= MAX_SEEN_PER_DAY) return false

        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(current.salt)
        digest.update("${kind.name}|$packageId|$visitor".toByteArray(Charsets.UTF_8))
        return current.seen.add(ByteBuffer.wrap(digest.digest()).long)
    }

    private fun salt() = ByteArray(32).also { SecureRandom().nextBytes(it) }

    companion object {
        /** Rund 60 Byte je Eintrag - 200 000 sind gut 10 MB. */
        const val MAX_SEEN_PER_DAY = 200_000

        /** Mehr als einmal je Sekunde laedt kein Mensch neu. */
        const val VIEWS_PER_MINUTE = 60

        /**
         * Crawler, Link-Vorschauen, Skripte. "bot" nur als Wortende:
         * Googlebot/, bingbot/, Discordbot/, DuckDuckBot-Https - aber nicht
         * das Handy "CUBOT_X30". Ohne User-Agent schickt nur ein Skript.
         */
        private val BOT = Regex(
            "(?i)bot\\b|crawl|spider|slurp|headless|lighthouse|inspectiontool|facebookexternalhit|embedly|" +
                "preview|^curl/|^wget/|python|okhttp|^java/|go-http-client|node-fetch|axios"
        )

        fun isBot(userAgent: String?): Boolean = userAgent.isNullOrBlank() || BOT.containsMatchIn(userAgent)
    }
}

/**
 * Die Seite meldet, die Antwort ist leer: ob gezaehlt wurde, verraet sie
 * nicht - sonst liesse sich abfragen, wer heute schon da war.
 */
@RestController
@RequestMapping("/api/v1/packages")
class ClipCountController(private val counts: ClipCounts) {

    /** Die Clip-Seite wurde geoeffnet (`clip.js`) - jedes Mal. */
    @PostMapping("/{slug}/viewed")
    fun viewed(@PathVariable slug: String, authentication: Authentication?, request: HttpServletRequest) =
        report(ClipCounts.Kind.VIEW, slug, authentication, request)

    /** FBX oder GLB im Browser geschrieben (`clip-download.js`). Die .awclip zaehlt `/unlock`. */
    @PostMapping("/{slug}/downloaded")
    fun downloaded(@PathVariable slug: String, authentication: Authentication?, request: HttpServletRequest) =
        report(ClipCounts.Kind.DOWNLOAD, slug, authentication, request)

    private fun report(
        kind: ClipCounts.Kind,
        slug: String,
        authentication: Authentication?,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        counts.count(kind, slug, authentication.portalPrincipal(), request.clientIp(), request.getHeader(HttpHeaders.USER_AGENT))
        return ResponseEntity.noContent().build()
    }
}
