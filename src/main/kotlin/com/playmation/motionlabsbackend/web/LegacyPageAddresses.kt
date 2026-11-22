package com.playmation.motionlabsbackend.web

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam

/**
 * Die alten Adressen mit `.html` - sie leiten DAUERHAFT (301) auf die neuen
 * weiter ([PortalPaths]), und zwar fuer immer.
 *
 * Sie stehen in jeder ausgelieferten Workbench (`/clip.html?p=…`,
 * `/u.html?u=…`, `/collection.html?c=…`, `/pack.html?k=…`, `/me.html`), in
 * Discord-Posts, Takedown-Mails, alten Benachrichtigungen und im Suchindex.
 * Ein toter Link ist teurer als diese Datei. 301 statt 302, damit
 * Suchmaschinen die neue Adresse uebernehmen und Browser sie sich merken.
 *
 * Der Rest der Abfrage zieht mit (`/u.html?u=x&tab=collections` wird
 * `/u/x?tab=collections`), der Anker `#comments` ohnehin - den behaelt der
 * Browser selbst. Das Ziel ist immer ein fester Pfad dieses Portals; aus der
 * Abfrage wird nie ein fremdes.
 */
@Controller
class LegacyPageAddresses {

    /** Seiten ohne eigenen Schluessel: neuer Pfad, dieselbe Abfrage. */
    private val renamed = mapOf(
        "/index.html" to PortalPaths.HOME,
        "/browse.html" to PortalPaths.HOME,
        "/collections.html" to PortalPaths.COLLECTIONS,
        "/characters.html" to PortalPaths.CHARACTERS,
        "/share.html" to PortalPaths.SHARE,
        "/upload.html" to PortalPaths.UPLOAD,
        "/me.html" to PortalPaths.ACCOUNT,
        "/messages.html" to PortalPaths.MESSAGES,
        "/signin.html" to PortalPaths.SIGN_IN,
        "/dev.html" to PortalPaths.DEV_SIGN_IN,
        "/link.html" to PortalPaths.LINK,
        "/admin.html" to PortalPaths.ADMIN,
        "/admin-stats.html" to PortalPaths.ADMIN_STATS,
        "/licenses.html" to PortalPaths.LICENSES,
        "/rules.html" to PortalPaths.RULES,
        "/terms.html" to PortalPaths.TERMS,
        "/privacy.html" to PortalPaths.PRIVACY,
        "/impressum.html" to PortalPaths.IMPRESSUM,
        "/takedown.html" to PortalPaths.TAKEDOWN,
    )

    //  Dieselbe Liste wie oben - eine Annotation will Konstanten, keine Map.
    //  LegacyPageAddressesTest prueft, dass beide uebereinstimmen.
    @GetMapping(
        "/index.html", "/browse.html", "/collections.html", "/characters.html", "/share.html",
        "/upload.html", "/me.html", "/messages.html", "/signin.html", "/dev.html", "/link.html",
        "/admin.html", "/admin-stats.html", "/licenses.html", "/rules.html", "/terms.html",
        "/privacy.html", "/impressum.html", "/takedown.html",
    )
    fun renamed(request: HttpServletRequest): ResponseEntity<Void> =
        moved(renamed.getValue(request.requestURI), request, null)

    /** Ohne Slug gab es nie einen Clip zu sehen - dann eben den Katalog. */
    @GetMapping("/clip.html")
    fun clip(@RequestParam(required = false) p: String?, request: HttpServletRequest) =
        moved(key(p)?.let(PortalPaths::clip) ?: PortalPaths.HOME, request, "p")

    @GetMapping("/pack.html")
    fun pack(@RequestParam(required = false) k: String?, request: HttpServletRequest) =
        moved(key(k)?.let(PortalPaths::pack) ?: PortalPaths.HOME, request, "k")

    @GetMapping("/collection.html")
    fun collection(@RequestParam(required = false) c: String?, request: HttpServletRequest) =
        moved(key(c)?.let(PortalPaths::collection) ?: PortalPaths.COLLECTIONS, request, "c")

    @GetMapping("/u.html")
    fun profile(@RequestParam(required = false) u: String?, request: HttpServletRequest) =
        moved(key(u)?.let(PortalPaths::profile) ?: PortalPaths.HOME, request, "u")

    private fun key(value: String?) = value?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * 301 auf [path], mit der uebrigen Abfrage - ohne [consumed], der jetzt im
     * Pfad steht. Die Abfrage wandert roh weiter, so wie sie kam: sie neu zu
     * kodieren hiesse, sie zu veraendern.
     */
    private fun moved(path: String, request: HttpServletRequest, consumed: String?): ResponseEntity<Void> {
        val rest = request.queryString.orEmpty().split('&')
            .filter { it.isNotEmpty() && (consumed == null || it.substringBefore('=') != consumed) }
            .joinToString("&")
        val target = if (rest.isEmpty()) path else "$path?$rest"
        return ResponseEntity.status(HttpStatus.MOVED_PERMANENTLY).header(HttpHeaders.LOCATION, target).build()
    }
}
