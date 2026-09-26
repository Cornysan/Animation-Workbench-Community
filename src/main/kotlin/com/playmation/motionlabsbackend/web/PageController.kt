package com.playmation.motionlabsbackend.web

import com.playmation.motionlabsbackend.account.AccountService
import com.playmation.motionlabsbackend.auth.SignInProviders
import com.playmation.motionlabsbackend.auth.portalPrincipal
import com.playmation.motionlabsbackend.catalog.CatalogService
import com.playmation.motionlabsbackend.catalog.PackService
import com.playmation.motionlabsbackend.collection.CollectionService
import com.playmation.motionlabsbackend.collection.CollectionVisibility
import com.playmation.motionlabsbackend.config.PortalProperties
import com.playmation.motionlabsbackend.format.AwclipSchema
import com.playmation.motionlabsbackend.profile.ProfileService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.Authentication
import org.springframework.security.web.savedrequest.HttpSessionRequestCache
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody

/**
 * Die Seiten des Portals. Sie lagen als fertige .html-Dateien unter `static/`
 * und trugen einen LEEREN Kopf und Fuss; `renderShell()` in app.js baute beides
 * bei jedem Seitenaufruf nach und liess den Kontoblock auf `/api/v1/me` und
 * `/api/v1/status` warten. Man sah der Seite beim Zusammensetzen zu, und das
 * las sich als vollstaendiges Neuladen - obwohl die Knoepfe INNERHALB einer
 * Seite laengst ohne Neuladen arbeiten.
 *
 * Der Server weiss ohnehin, wer angemeldet ist. Also liefert er den Rahmen
 * gleich mit: fuer ihn bleibt kein einziger API-Aufruf mehr uebrig.
 *
 * Die Adressen bleiben, wie sie waren - `.html` und alles. Sie stehen in
 * Takedown-Mails, in Discord-Vorschauen und in der Workbench; eine schoenere
 * Adresse ist keinen toten Link wert.
 */
@Controller
class PageController(
    /** Was jede Seite fuer ihren Rahmen braucht - siehe [ShellModel]. */
    private val shell: ShellModel,
    /** Nur zum Fuellen der Link-Vorschau - den Inhalt holt die Seite selbst. */
    private val catalog: CatalogService,
    private val profiles: ProfileService,
    private val collections: CollectionService,
    private val packs: PackService,
    private val portal: PortalProperties,
    private val accounts: AccountService,
    private val providers: SignInProviders,
) {

    @ModelAttribute("user")
    fun shellUser(authentication: Authentication?) = shell.user(authentication)

    @ModelAttribute("signInUrl")
    fun signInUrl() = shell.signInUrl

    @ModelAttribute("signInProviders")
    fun signInProviders() = shell.signInProviders

    @ModelAttribute("devLogin")
    fun devLogin() = shell.devLogin

    @ModelAttribute("buildLabel")
    fun buildLabel() = shell.buildLabel

    @ModelAttribute("communityEnabled")
    fun communityEnabled() = shell.communityEnabled()

    @ModelAttribute("charactersEnabled")
    fun charactersEnabled() = shell.charactersEnabled()

    @ModelAttribute("meta")
    fun defaultMeta() = shell.defaultMeta()

    /**
     * Die eine Adresse, unter der eine Seite gefuehrt werden soll - fuer
     * `rel=canonical` und `og:url`. Pfad plus genau der Parameter, der die
     * Seite ausmacht (`p`, `u`, `c`); Sortierung, Schlagwort und Suche sind
     * Ansichten derselben Seite. `/index.html` ist "/".
     */
    @ModelAttribute("canonicalUrl")
    fun canonicalUrl(request: HttpServletRequest): String {
        val path = request.requestURI.takeUnless { it == "/index.html" } ?: "/"
        val key = listOf("p", "u", "c").firstOrNull { !request.getParameter(it).isNullOrBlank() }
        val query = key?.let { "?" + it + "=" + java.net.URLEncoder.encode(request.getParameter(it).trim(), Charsets.UTF_8) } ?: ""
        return portal.publicBaseUrl.trimEnd('/') + path + query
    }

    /**
     * Die erste Station eines Crawlers. Sie sagt dasselbe wie das `robots`-Meta
     * jeder Seite und kommt aus derselben Einstellung - zwei Quellen fuer diese
     * Auskunft waeren zwei Gelegenheiten, dass sie auseinander laufen.
     */
    @GetMapping("/robots.txt", produces = ["text/plain"])
    @ResponseBody
    fun robots(): String =
        if (portal.searchIndexing)
            //  /api/ bleibt OFFEN: die Seiten holen ihren Inhalt von dort, und
            //  ein Crawler, der sie ausfuehrt, haelt sich beim Nachladen an
            //  diese Datei - gesperrt saehe er leere Seiten. Die Profilbilder
            //  dagegen gehoeren in keine Bildersuche.
            "User-agent: *\nDisallow: /admin.html\nDisallow: /dev.html\nDisallow: /link.html\n" +
                "Disallow: /signin.html\nDisallow: /me.html\nDisallow: /avatar/\n\n" +
                "Sitemap: " + portal.publicBaseUrl.trimEnd('/') + "/sitemap.xml\n"
        else
            "User-agent: *\nDisallow: /\n"

    //  Ein Ziel je Seite statt einer Tabelle: die Zuordnung Adresse -> Vorlage
    //  -> aktiver Navigationseintrag steht dann an einer Stelle und liest sich
    //  von oben nach unten.

    /**
     * DER KATALOG IST DIE STARTSEITE - wie bei Mixamo, wo der Reiter
     * "Animations" die erste Seite ist (Pablo, 2026-09-24). Dazwischen stand
     * eine eigene Startseite mit Kopf, Zahlen und zeitweise einer Figur; wer
     * herkam, wollte trotzdem zuerst die Clips sehen.
     */
    @GetMapping("/", "/index.html")
    fun animations(model: Model) = view(model, "browse", active = "animations")

    /**
     * Die alte Adresse des Katalogs. Sie steht in Discord-Nachrichten und als
     * `?author=`/`?tag=`-Link in Umlauf, also leitet sie weiter - samt
     * Abfrage, damit ein Filter-Link seinen Filter behaelt. Der Pfad ist fest
     * "/", aus der Abfrage wird also nie ein fremdes Ziel.
     */
    @GetMapping("/browse.html")
    fun browse(request: HttpServletRequest): String =
        "redirect:/" + (request.queryString?.let { "?$it" } ?: "")

    /**
     * Der zweite Reiter oben: die eigenen Sammlungen und die der Leute, denen
     * man folgt - ohne Anmeldung nur der Weg dorthin.
     */
    @GetMapping("/collections.html")
    fun collectionsPage(model: Model) = view(model, "collections", active = "collections")

    /**
     * Der Clip liegt unter „Animations" - man kommt aus dem Katalog hierher.
     *
     * Die Seite selbst holt ihren Inhalt weiter per JavaScript; der Server
     * liest den Clip hier nur, um die Vorschau des Links zu fuellen. Faellt das
     * aus - falscher Slug, versteckter Clip -, bleibt die Vorgabe stehen und
     * die Seite sagt es dem Besucher wie bisher selbst.
     */
    @GetMapping("/clip.html")
    fun clip(@RequestParam(name = "p", required = false) slug: String?, model: Model): String {
        val clip = slug?.let { runCatching { catalog.detail(it, null) }.getOrNull() }

        //  Das Rig gleich mit in die Seite: die Vorschau entscheidet daran, ob
        //  die Figur auftritt, und sie soll dafuer nicht erst einen zweiten
        //  Aufruf machen muessen. Ein privater Clip kommt hier ohne Anmeldung
        //  nicht durch - dann steht `humanoid`, und die Vorschau merkt selbst,
        //  dass sich das nicht umrechnen laesst.
        model.addAttribute("clipRig", clip?.rig ?: AwclipSchema.RIG_HUMANOID)

        //  Fuer die Vorab-Anfrage der Vorschau im Kopf der Seite (clip.html):
        //  nur wenn der Clip hier auch sichtbar ist, sonst holte sie eine 404.
        clip?.let { model.addAttribute("clipSlug", it.slug) }

        //  Titel und Beschreibung stehen damit schon im ausgelieferten HTML -
        //  fuer Suchmaschinen, die kein JavaScript ausfuehren. clip.js
        //  schreibt dieselben Werte danach noch einmal hinein.
        if (clip != null && clip.license == AwclipSchema.LICENSE_PUBLIC) {
            model.addAttribute("clipTitle", clip.title)
            model.addAttribute("clipDescription", clip.description)
        }

        //  Ein privater Clip bekommt keine eigene Vorschau - ohne Anmeldung
        //  kommt er seit Schema 10 gar nicht mehr bis hier (`visible`). Die
        //  Pruefung bleibt trotzdem stehen: sie ist billig, und eine Karte,
        //  die seine Bewegung in jeden Kanal traegt, hat niemand bestellt.
        if (clip != null && clip.license == AwclipSchema.LICENSE_PUBLIC) {
            val url = portal.publicBaseUrl.trimEnd('/') + "/clip.html?p=" + clip.slug
            model.addAttribute("meta", ShellModel.PageMeta(
                title = clip.title + " by " + clip.author,
                description = clip.description.takeIf { it.isNotBlank() }
                    ?: "A humanoid animation clip, free to use under CC0 - no credit needed.",
                image = if (clip.hasPreview) portal.publicBaseUrl.trimEnd('/') + "/clip-card/" + clip.slug + ".png" else null,
                url = url,
                noindex = !portal.searchIndexing,
            ))
        }

        return view(model, "clip", active = "animations")
    }

    /**
     * Ein Profil. Die Adresse traegt den HANDLE, nicht den Anzeigenamen: sie
     * soll eine Umbenennung ueberleben und auf genau ein Konto zeigen.
     *
     * Die Vorschau zeigt die Bewegung dieser Person - das Kartenbild ihres
     * neuesten Clips. Wer noch keinen geteilt hat, bekommt keines; ein
     * Buchstabe im Kreis waere als Vorschaubild schlechter als gar keines.
     */
    @GetMapping("/u.html")
    fun user(@RequestParam(name = "u", required = false) handle: String?, model: Model): String {
        val meta = handle?.let { runCatching { profiles.meta(it) }.getOrNull() }

        if (meta != null) {
            val base = portal.publicBaseUrl.trimEnd('/')
            model.addAttribute("meta", ShellModel.PageMeta(
                title = meta.displayName + " on Animation Workbench Community",
                description = meta.bio
                    ?: "Animation clips shared by ${meta.displayName} - free to use under CC0, no credit needed.",
                image = meta.cardSlug?.let { "$base/clip-card/$it.png" },
                url = "$base/u.html?u=$handle",
                noindex = !portal.searchIndexing,
            ))
        }

        return view(model, "user")
    }

    /**
     * Eine Sammlung. Dieselbe Rechnung wie beim Profil - der erste Clip darin
     * traegt das Bild. Eine nicht gelistete bekommt keines: wer "nur ueber den
     * Link" waehlt, hat keine Karte bestellt, die ihren Inhalt in jeden Kanal
     * traegt, in den der Link geraet.
     */
    @GetMapping("/collection.html")
    fun collection(@RequestParam(name = "c", required = false) slug: String?, model: Model): String {
        val detail = slug?.let { runCatching { collections.detail(it, null) }.getOrNull() }

        if (detail != null && detail.visibility == CollectionVisibility.PUBLIC.name) {
            val base = portal.publicBaseUrl.trimEnd('/')
            val cover = detail.items.firstOrNull { it.hasPreview }?.slug

            model.addAttribute("meta", ShellModel.PageMeta(
                title = detail.title + " by " + detail.owner,
                description = detail.description.takeIf { it.isNotBlank() }
                    ?: "A collection of ${detail.items.size} animation clips.",
                image = cover?.let { "$base/clip-card/$it.png" },
                url = "$base/collection.html?c=${detail.slug}",
                noindex = !portal.searchIndexing,
            ))
        }

        return view(model, "collection", active = "collections")
    }

    /**
     * Ein Pack. Das Bild ist sein Deckel, der erste Clip darin - wie bei der
     * Sammlung. Er steht unter "Animations": ein Pack ist Teil des Katalogs,
     * keine Auswahl von jemand anderem.
     */
    @GetMapping("/pack.html")
    fun pack(@RequestParam(name = "k", required = false) slug: String?, model: Model): String {
        val detail = slug?.let { runCatching { packs.detail(it, null) }.getOrNull() }

        if (detail != null) {
            val base = portal.publicBaseUrl.trimEnd('/')
            val cover = detail.items.firstOrNull { it.hasPreview }?.slug

            model.addAttribute("meta", ShellModel.PageMeta(
                title = detail.title + " by " + detail.author,
                description = detail.description.takeIf { it.isNotBlank() }
                    ?: "A pack of ${detail.clips} humanoid animation clips, free to use under CC0 - no credit needed.",
                image = cover?.let { "$base/clip-card/$it.png" },
                url = "$base/pack.html?k=${detail.slug}",
                noindex = !portal.searchIndexing,
            ))
        }

        return view(model, "pack", active = "animations")
    }

    /**
     * Das private Fach. KEIN `active`-Eintrag mehr: seit es Profile gibt,
     * steht es nicht in der Navigation - man kommt ueber das eigene Profil
     * hierher, und was nicht in der Leiste steht, kann dort auch nichts
     * hervorheben.
     */
    @GetMapping("/me.html")
    fun me(model: Model, authentication: Authentication?): String {
        //  Die Anmeldungen kommen gleich mit der Seite: die Zeichen der
        //  Anbieter stehen als Vorlage da (fragments/signin.html), und ein
        //  Skript, das sie nachbaute, braeuchte eine dritte Trusted-Types-
        //  Erlaubnis nur dafuer.
        authentication.portalPrincipal()?.let {
            model.addAttribute("signIns", providers.rows(accounts.signIns(it.accountId)))
        }
        return view(model, "me")
    }

    /**
     * Die Auswahl der Anbieter - und der Ort, an dem eine gescheiterte
     * Anmeldung in Worten steht. Der Grund kommt als Wort aus einer festen
     * Liste (`SignInFailureHandler`); was nicht darin steht, bekommt den
     * allgemeinen Satz, nie den Text aus der Adresse.
     */
    @GetMapping("/signin.html")
    fun signIn(
        @RequestParam(name = "error", required = false) error: String?,
        model: Model,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): String {
        //  Kam man von einer Seite, die ein Konto braucht, sagt die Zeile
        //  unter der Ueberschrift, wohin es danach geht. Spring hat die
        //  Adresse gemerkt (SecurityConfig) und fuehrt nach der Anmeldung
        //  dorthin zurueck.
        val saved = HttpSessionRequestCache().getRequest(request, response)?.redirectUrl
        model.addAttribute("signInContinue", when {
            saved == null -> null
            "/me.html" in saved -> "to open your account"
            else -> "to continue where you were"
        })

        model.addAttribute("signInError", error?.let {
            when (it) {
                "cancelled" -> "Sign-in was cancelled. Pick a way to sign in whenever you are ready."
                "banned" -> "This account is banned."
                "handoff" -> "That sign-in link has run out or was already used. Sign in below, or open your account from the Workbench again."
                else -> "Sign-in did not work. Try again, or pick another way."
            }
        })
        return view(model, "signin")
    }

    /**
     * Die eigenen Figuren. Der Server hat damit NICHTS zu tun: die Dateien
     * liegen im Browser (IndexedDB), und diese Seite ist nur ihr Ort. Sie
     * steht trotzdem in der Leiste, weil eine Funktion, die man nur findet,
     * wenn man schon weiss, dass es sie gibt, keine Funktion ist.
     */
    /**
     * Die eigenen Figuren. Der Server hat mit dem INHALT nichts zu tun: die
     * Dateien liegen im Browser, diese Seite ist nur ihr Ort.
     *
     * Ist der Schalter aus, bleibt die Adresse trotzdem bestehen und erklaert
     * sich. KEIN 404: die Doku der Workbench schickt Leute hierher ("Drop
     * those files on the portal's Characters page"), und wer dem Satz folgt,
     * hat eine Antwort verdient und keine Sackgasse. Die Vorlage zeigt dann
     * statt der Ablage einen kurzen Absatz - und sagt vor allem, dass die
     * abgelegten Figuren weiterhin im Browser liegen.
     */
    @GetMapping("/characters.html")
    fun characters(model: Model) = view(model, "characters", active = "characters")

    @GetMapping("/licenses.html")
    fun licenses(model: Model) = view(model, "licenses", active = "licenses")

    @GetMapping("/admin.html")
    fun admin(model: Model) = view(model, "admin", active = "admin")

    @GetMapping("/dev.html")
    fun dev(model: Model) = view(model, "dev")

    @GetMapping("/link.html")
    fun link(model: Model) = view(model, "link")

    @GetMapping("/rules.html")
    fun rules(model: Model) = view(model, "rules")

    @GetMapping("/terms.html")
    fun terms(model: Model) = view(model, "terms")

    @GetMapping("/privacy.html")
    fun privacy(model: Model) = view(model, "privacy")

    @GetMapping("/impressum.html")
    fun impressum(model: Model) = view(model, "impressum")

    @GetMapping("/takedown.html")
    fun takedown(model: Model) = view(model, "takedown")

    private fun view(model: Model, template: String, active: String? = null): String {
        model.addAttribute("active", active)
        return template
    }
}
