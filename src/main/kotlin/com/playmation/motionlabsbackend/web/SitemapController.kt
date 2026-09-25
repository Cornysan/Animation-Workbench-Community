package com.playmation.motionlabsbackend.web

import com.playmation.motionlabsbackend.account.AccountRepository
import com.playmation.motionlabsbackend.account.AccountStatus
import com.playmation.motionlabsbackend.catalog.AnimationPackageRepository
import com.playmation.motionlabsbackend.catalog.PackageStatus
import com.playmation.motionlabsbackend.catalog.PackageVersionRepository
import com.playmation.motionlabsbackend.collection.ClipCollectionRepository
import com.playmation.motionlabsbackend.collection.CollectionStatus
import com.playmation.motionlabsbackend.collection.CollectionVisibility
import com.playmation.motionlabsbackend.config.PortalProperties
import com.playmation.motionlabsbackend.format.AwclipSchema
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Die Sitemap - fuer Suchmaschinen die Liste dessen, was es hier gibt.
 *
 * NOETIG, WEIL DIE SEITEN IHREN INHALT PER JAVASCRIPT HOLEN. Der Katalog auf
 * "/" liefert ein leeres Gitter aus und fuellt es aus /api/v1/packages; ein
 * Crawler, der kein JavaScript ausfuehrt, faende von dort keinen einzigen
 * Clip. Hier stehen sie alle als Adresse, und `robots.txt` nennt die Datei.
 *
 * Drin steht nur, was jeder ohne Anmeldung sehen kann: veroeffentlichte,
 * oeffentlich geteilte Clips eines Rigs, das der Katalog zeigt; oeffentliche
 * Sammlungen mit Inhalt; die Profile ihrer Besitzer. Profile ohne einen
 * einzigen oeffentlichen Clip oder eine Sammlung bleiben draussen - eine Seite
 * mit einem Namen und sonst nichts ist fuer eine Suche kein Treffer, und wer
 * nur liest und kommentiert, soll nicht ueber Google zu finden sein.
 *
 * Aus, solange `portal.search-indexing` aus ist: dann sagt `robots.txt`
 * ohnehin "Disallow: /", und eine Sitemap daneben widerspraeche ihr.
 */
@Controller
class SitemapController(
    private val packages: AnimationPackageRepository,
    private val versions: PackageVersionRepository,
    private val collections: ClipCollectionRepository,
    private val accounts: AccountRepository,
    private val portal: PortalProperties,
) {
    private val date = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC)

    @GetMapping("/sitemap.xml")
    @Transactional(readOnly = true)
    fun sitemap(): ResponseEntity<String> {
        if (!portal.searchIndexing)
            return ResponseEntity.notFound().build()

        val base = portal.publicBaseUrl.trimEnd('/')
        val xml = StringBuilder(4096)
        xml.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        xml.append("""<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">""").append('\n')

        fun url(path: String, lastModified: Instant? = null) {
            xml.append("  <url><loc>").append(escape(base + path)).append("</loc>")
            lastModified?.let { xml.append("<lastmod>").append(date.format(it)).append("</lastmod>") }
            xml.append("</url>\n")
        }

        //  Der Katalog und die Seiten im Fuss. "/collections.html" nicht mehr:
        //  seit dort nur noch die eigenen und die gefolgten Sammlungen stehen
        //  (2026-09-25), sieht ein Suchroboter nur den Weg zur Anmeldung. Die
        //  Sammlungen selbst stehen weiter unten einzeln.
        for (path in listOf("/", "/licenses.html", "/rules.html", "/terms.html",
                "/privacy.html", "/impressum.html"))
            url(path)

        val owners = LinkedHashSet<UUID>()

        //  Clips: dieselbe Auswahl wie der Katalog, samt Rig-Riegel - ein
        //  generischer Clip steht dort nicht, also auch hier nicht.
        val published = packages.findAllByStatusAndLicense(PackageStatus.PUBLISHED, AwclipSchema.LICENSE_PUBLIC)
        val rigs = versions.findAllById(published.mapNotNull { it.currentVersionId }).associate { it.id to it.rig }
        for (pkg in published.sortedByDescending { it.updatedAt }) {
            val rig = pkg.currentVersionId?.let(rigs::get) ?: continue
            if (!AwclipSchema.isAcceptedRig(rig)) continue
            url("/clip.html?p=" + pkg.slug, pkg.updatedAt)
            owners += pkg.ownerId
        }

        //  Sammlungen: oeffentlich und nicht leer.
        for (collection in collections.findByStatusAndVisibilityOrderByUpdatedAtDesc(
                CollectionStatus.PUBLISHED, CollectionVisibility.PUBLIC)) {
            if (collection.itemCount <= 0) continue
            url("/collection.html?c=" + collection.slug, collection.updatedAt)
            owners += collection.ownerId
        }

        for (account in accounts.findAllById(owners)) {
            val handle = account.handle ?: continue
            if (account.status == AccountStatus.BANNED) continue
            url("/u.html?u=" + handle)
        }

        xml.append("</urlset>\n")

        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_XML)
            .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
            .body(xml.toString())
    }

    private fun escape(value: String) = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
