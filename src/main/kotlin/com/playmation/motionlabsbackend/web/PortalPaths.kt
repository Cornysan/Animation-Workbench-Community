package com.playmation.motionlabsbackend.web

import org.springframework.web.util.UriUtils

/**
 * Die Adressen der Seiten - an EINER Stelle.
 *
 * SEIT 2026-11-22 OHNE `.html`. Bis dahin hiess jede Seite wie die Datei, die
 * sie einmal war (`/collections.html`), und ein Clip stand unter
 * `/clip.html?p=<slug>`. Jetzt: `/collections`, `/clip/<slug>`, `/u/<handle>`
 * (Pablo: "muessen die Seiten eigentlich .html heissen?"). Die alten Adressen
 * bleiben fuer immer gueltig und leiten dauerhaft hierher weiter
 * ([LegacyPageAddresses]) - sie stehen in jeder ausgelieferten Workbench, in
 * Discord-Posts, Mails, alten Benachrichtigungen und im Suchindex.
 *
 * Was eine Seite AUSMACHT (welcher Clip, wessen Profil), steht im Pfad. Was
 * nur eine Ansicht derselben Seite ist (Reiter, Sortierung, Suche), bleibt
 * eine Abfrage: `/u/<handle>?tab=collections`, `/messages?c=<id>`.
 *
 * Dieselben Wege baut `app.js` (`AW.paths`) - wer hier einen aendert, aendert
 * ihn dort mit.
 */
object PortalPaths {
    const val HOME = "/"
    const val COLLECTIONS = "/collections"
    const val CHARACTERS = "/characters"
    const val SHARE = "/share"
    const val UPLOAD = "/upload"
    const val ACCOUNT = "/account"
    const val MESSAGES = "/messages"
    const val SIGN_IN = "/signin"
    const val DEV_SIGN_IN = "/dev"
    const val LINK = "/link"
    const val ADMIN = "/admin"
    const val ADMIN_STATS = "/admin/stats"
    const val LICENSES = "/licenses"
    const val RULES = "/rules"
    const val TERMS = "/terms"
    const val PRIVACY = "/privacy"
    const val IMPRESSUM = "/impressum"
    const val TAKEDOWN = "/takedown"

    /**
     * Die Seiten, die ein Konto verlangen. Ohne Anmeldung schickt Spring von
     * hier zur Anmeldeseite und merkt sich den Weg (SecurityConfig) - EINE
     * Liste fuer beides, damit Sperre und Umleitung nicht auseinanderlaufen.
     */
    val SIGNED_IN_PAGES = listOf(ACCOUNT, MESSAGES, UPLOAD)

    fun clip(slug: String) = "/clip/" + segment(slug)
    fun comments(slug: String) = clip(slug) + "#comments"
    fun pack(slug: String) = "/pack/" + segment(slug)
    fun collection(slug: String) = "/collection/" + segment(slug)
    fun profile(handle: String) = "/u/" + segment(handle)

    /**
     * Ein Wert als EIN Pfadstueck. Slugs und Handles bestehen heute nur aus
     * `[a-z0-9_-]` - die Kodierung ist fuer den Tag, an dem das nicht mehr
     * stimmt, und dafuer, dass ein `/` aus einer alten Adresse nie zu einem
     * zweiten Pfadstueck wird.
     */
    private fun segment(value: String) = UriUtils.encodePathSegment(value, Charsets.UTF_8)
}
