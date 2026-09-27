package com.playmation.motionlabsbackend.web

import com.playmation.motionlabsbackend.account.AccountService
import com.playmation.motionlabsbackend.auth.SignInProviders
import com.playmation.motionlabsbackend.auth.portalPrincipal
import com.playmation.motionlabsbackend.catalog.CatalogEntry
import com.playmation.motionlabsbackend.catalog.CatalogOverviewService
import com.playmation.motionlabsbackend.catalog.CatalogService
import com.playmation.motionlabsbackend.catalog.CatalogWall
import com.playmation.motionlabsbackend.catalog.PackService
import com.playmation.motionlabsbackend.catalog.PackageDetail
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
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

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
    /** Die Karten der Startseite stehen im HTML - siehe [animations]. */
    private val wall: CatalogWall,
    private val overview: CatalogOverviewService,
    /** Die Bilder fuer Link-Vorschauen - gerendert im Browser, siehe PreviewCards. */
    private val cards: PreviewCards,
) {
    private val base get() = portal.publicBaseUrl.trimEnd('/')

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

    @ModelAttribute("store")
    fun store() = shell.store

    @ModelAttribute("meta")
    fun defaultMeta() = shell.defaultMeta()

    /**
     * Die eine Adresse, unter der eine Seite gefuehrt werden soll - fuer
     * `rel=canonical` und `og:url`. Pfad plus genau der Parameter, der die
     * Seite ausmacht (`p`, `u`, `c`, `k`); Sortierung und Suche sind
     * Ansichten derselben Seite. `/index.html` ist "/". Ein Schlagwort ist
     * seit 2026-09-26 eine eigene Seite - das setzt [animations] selbst.
     *
     * `k` fehlte bis 2026-09-26: jede Pack-Seite nannte sich "/pack.html",
     * und fuer eine Suchmaschine waren damit alle Packs dieselbe Seite.
     */
    @ModelAttribute("canonicalUrl")
    fun canonicalUrl(request: HttpServletRequest): String {
        val path = request.requestURI.takeUnless { it == "/index.html" } ?: "/"
        val key = listOf("p", "u", "c", "k").firstOrNull { !request.getParameter(it).isNullOrBlank() }
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
            "User-agent: *\nDisallow: /admin.html\nDisallow: /admin-stats.html\nDisallow: /dev.html\nDisallow: /link.html\n" +
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
    fun animations(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) tag: String?,
        @RequestParam(required = false) author: String?,
        @RequestParam(required = false) sort: String?,
        @RequestParam(required = false) page: Int?,
        model: Model,
    ): String {
        //  DIE KARTEN STEHEN IM HTML. Bis hierher kam "/" mit einem leeren
        //  Gitter an, und browse.js fuellte es; im ausgelieferten Dokument
        //  stand kein einziger Link auf einen Clip. Jetzt liefert der Server
        //  dieselbe Seite der Wand als schlichte Karten (Titel, Person, Dauer),
        //  und browse.js ersetzt sie durch die lebenden. Fuer den Besucher ist
        //  das ein Ladezustand mit Inhalt statt grauer Balken.
        val searching = !q.isNullOrBlank() || !author.isNullOrBlank()
        val tagName = tag?.trim()?.takeIf { it.isNotEmpty() }
        val found = runCatching { wall.page(q, tagName, sort, page ?: 0, 24, null, author) }.getOrNull()
        model.addAttribute("wallItems", found?.items.orEmpty().map { card(it) })

        val indexing = portal.searchIndexing
        when {
            //  Eine Suche ist keine Seite fuer den Index - ihre Clips schon.
            searching -> model.addAttribute("meta", shell.defaultMeta().copy(noindex = true, follow = indexing))

            //  EIN SCHLAGWORT IST EINE SEITE ("Free walk animations"). Genau
            //  danach wird gesucht, und bis hierher zeigte ihre kanonische
            //  Adresse auf "/" - fuer Google war jede Schlagwortseite die
            //  Startseite noch einmal. Ohne Treffer bleibt sie draussen.
            tagName != null -> {
                val clips = found?.clips ?: 0
                model.addAttribute("catalogHeading", Seo.tagLabel(tagName) + " animations")
                if (clips > 0) {
                    val url = "$base/?tag=" + java.net.URLEncoder.encode(tagName, Charsets.UTF_8)
                    model.addAttribute("pageTitle", Seo.tagTitle(tagName))
                    model.addAttribute("canonicalUrl", url)
                    model.addAttribute("meta", ShellModel.PageMeta(
                        title = "Free " + Seo.tagLabel(tagName).lowercase() + " animations",
                        description = (if (clips == 1L) "One free " else "$clips free ") +
                            Seo.tagLabel(tagName).lowercase() + " animation" + (if (clips == 1L) "" else "s") +
                            " for Unity and any humanoid rig. Preview in the browser, download FBX or GLB.",
                        url = url,
                        noindex = !indexing,
                    ))
                    model.addAttribute("jsonLd", Seo.jsonLd(
                        Seo.obj("@type" to "CollectionPage", "name" to Seo.tagLabel(tagName) + " animations",
                            "url" to url, "license" to Seo.CC0),
                        Seo.breadcrumbs("Animations" to "$base/", Seo.tagLabel(tagName) to url),
                    ))
                } else {
                    model.addAttribute("meta", shell.defaultMeta().copy(noindex = true, follow = indexing))
                }
            }

            else -> {
                model.addAttribute("pageTitle", Seo.homeTitle())
                //  Aus WebSite nimmt Google den Namen ueber dem Treffer -
                //  sonst raet es aus der Domain, und die heisst anders.
                model.addAttribute("jsonLd", Seo.jsonLd(Seo.obj(
                    "@type" to "WebSite",
                    "name" to Seo.SITE,
                    "alternateName" to listOf("Playmations", "AW Community"),
                    "url" to "$base/",
                )))
            }
        }

        return view(model, "browse", active = "animations")
    }

    /** Eine Karte, wie sie ohne JavaScript im Gitter steht. */
    data class WallCard(val href: String, val title: String, val author: String, val authorHref: String,
                        val duration: String, val clips: Int?)

    private fun card(entry: CatalogEntry): WallCard? {
        entry.clip?.let { clip ->
            return WallCard("/clip.html?p=" + clip.slug, clip.title, clip.author,
                profileHref(clip.authorHandle, clip.author), Seo.duration(clip.durationSeconds), null)
        }
        entry.pack?.let { pack ->
            return WallCard("/pack.html?k=" + pack.slug, pack.title, pack.author,
                profileHref(pack.authorHandle, pack.author), Seo.duration(pack.durationSeconds), pack.clips)
        }
        return null
    }

    /** Wie `profileHref` in app.js: das Profil, sonst der alte Filter nach Namen. */
    private fun profileHref(handle: String?, name: String) =
        if (handle != null) "/u.html?u=" + java.net.URLEncoder.encode(handle, Charsets.UTF_8)
        else "/?author=" + java.net.URLEncoder.encode(name, Charsets.UTF_8)

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

        //  DIE SEITE STEHT IM HTML, nicht nur Titel und Beschreibung: Person,
        //  Schlagworte, Zahlen, Nachbarschaft. Vorher stand das alles erst nach
        //  clip.js da, und der ganze Block trug `hidden` - eine Suchmaschine
        //  sah eine Ueberschrift in einem unsichtbaren Kasten. clip.js
        //  schreibt dieselben Stellen danach noch einmal (replaceChildren),
        //  mit dem Konto im Blick (Herz, Stern, Bearbeiten).
        if (clip != null && clip.license == AwclipSchema.LICENSE_PUBLIC) {
            model.addAttribute("clipTitle", clip.title)
            model.addAttribute("clipDescription", clip.description)
            model.addAttribute("clipPage", clipPage(clip))
            model.addAttribute("pageTitle", Seo.clipTitle(clip.title))
            model.addAttribute("jsonLd", clipJsonLd(clip))
        }

        //  Ein privater Clip bekommt keine eigene Vorschau - ohne Anmeldung
        //  kommt er seit Schema 10 gar nicht mehr bis hier (`visible`). Die
        //  Pruefung bleibt trotzdem stehen: sie ist billig, und eine Karte,
        //  die seine Bewegung in jeden Kanal traegt, hat niemand bestellt.
        if (clip != null && clip.license == AwclipSchema.LICENSE_PUBLIC) {
            val url = portal.publicBaseUrl.trimEnd('/') + "/clip.html?p=" + clip.slug
            model.addAttribute("meta", ShellModel.PageMeta(
                title = clip.title + " by " + clip.author,
                //  Der eigene Satz des Clips zuerst, dann was man hier bekommt -
                //  die Beschreibung allein ist oft ein halber Satz ueber eine Hand.
                description = listOfNotNull(clip.description.takeIf { it.isNotBlank() }, Seo.CLIP_PITCH)
                    .joinToString(" "),
                image = cards.imageFor(clip.slug),
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
                title = meta.displayName + " on Playmations",
                description = meta.bio
                    ?: "Animation clips shared by ${meta.displayName} - preview them in the browser, " +
                        "download them as FBX or GLB, or take them into Unity.",
                image = cards.imageFor(meta.cardSlug),
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
                image = cards.imageFor(cover),
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

            val url = "$base/pack.html?k=${detail.slug}"
            model.addAttribute("meta", ShellModel.PageMeta(
                title = detail.title + " by " + detail.author,
                description = detail.description.takeIf { it.isNotBlank() }
                    ?: "A pack of ${detail.clips} humanoid animation clips - preview them in the browser, " +
                        "download them as FBX or GLB, or take them into Unity.",
                image = cards.imageFor(cover),
                url = url,
                noindex = !portal.searchIndexing,
            ))
            model.addAttribute("pageTitle", Seo.packTitle(detail.title))
            model.addAttribute("jsonLd", Seo.jsonLd(
                Seo.obj(
                    "@type" to "CreativeWork",
                    "name" to detail.title,
                    "description" to detail.description,
                    "url" to url,
                    "license" to Seo.CC0,
                    "isAccessibleForFree" to true,
                    "author" to Seo.person(detail.author, detail.authorHandle?.let { "$base/u.html?u=$it" }),
                    "image" to cover?.let { cards.ownImage(it) },
                    "hasPart" to detail.items.map { item ->
                        Seo.obj("@type" to "CreativeWork", "name" to item.title, "url" to "$base/clip.html?p=${item.slug}")
                    },
                ),
                Seo.breadcrumbs("Animations" to "$base/", detail.title to url),
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

    /**
     * Wie ein Clip hierher kommt: aus Unity, mit der Animation Workbench. Die
     * Seite hinter dem Reiter "Share" - und das Ziel jeder leeren Stelle, an
     * der sonst "noch nichts geteilt" ohne Weg weiter stuende.
     */
    @GetMapping("/share.html")
    fun share(model: Model): String {
        model.addAttribute("pageTitle", "Share Your Animations - Animation Workbench for Unity | ${Seo.SITE}")
        model.addAttribute("meta", shell.defaultMeta().copy(
            title = "Share your animations",
            description = "Clips come to Playmations from Unity. The free Animation " +
                "Workbench shares any humanoid clip in two clicks and imports clips from here the same way.",
        ))
        return view(model, "share", active = "share")
    }

    @GetMapping("/licenses.html")
    fun licenses(model: Model) = view(model, "licenses", active = "licenses")

    @GetMapping("/admin.html")
    fun admin(model: Model) = view(model, "admin", active = "admin")

    /**
     * Kennzahlen fuer Admins (system/AdminStats.kt). Die Seite selbst ist ein
     * leerer Rahmen - ihr Inhalt kommt aus `/api/v1/admin/stats`, und das
     * verlangt die Rolle. Im Index hat sie trotzdem nichts zu suchen.
     */
    @GetMapping("/admin-stats.html")
    fun adminStats(model: Model): String {
        model.addAttribute("meta", ShellModel.PageMeta("Stats", "Portal statistics for administrators.", noindex = true))
        return view(model, "admin-stats", active = "admin")
    }

    /**
     * Der Entwickler-Login - nur, wo es ihn gibt. Ohne `portal.dev-login`
     * antwortet die Schnittstelle dahinter ohnehin mit 404; die Seite soll
     * dann nicht trotzdem ein Formular zeigen, das nichts tut.
     */
    @GetMapping("/dev.html")
    fun dev(model: Model, response: HttpServletResponse): String {
        if (!portal.devLogin) {
            response.status = HttpServletResponse.SC_NOT_FOUND
            return view(model, "notfound")
        }
        return view(model, "dev")
    }

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

    // ── Clip-Seite im HTML ───────────────────────────────────────────────

    data class Fact(val label: String, val value: String)
    data class Related(val href: String, val title: String, val meta: String)
    data class ClipPage(
        val author: String,
        val authorHref: String,
        val authorAvatar: String?,
        val authorInitial: String,
        val sub: String,
        val tags: List<String>,
        val packSlug: String?,
        val packTitle: String?,
        val sourceCredit: String?,
        val sourceUrl: String?,
        val facts: List<Fact>,
        val relatedTitle: String?,
        val related: List<Related>,
    )

    private val shared = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH).withZone(ZoneOffset.UTC)

    /**
     * "2 weeks ago" - dieselbe Rechnung wie `formatRelative` in app.js, damit
     * die Zeile nicht umspringt, wenn das Skript sie neu schreibt. Ab einem
     * Monat das Datum: dann ist "vor 43 Tagen" eine Rechenaufgabe.
     */
    private fun ago(then: java.time.Instant): String {
        val seconds = java.time.Duration.between(then, java.time.Instant.now()).seconds
        if (seconds < 45) return "just now"
        var label: Pair<String, Long>? = null
        for ((unit, size) in listOf("minute" to 60L, "hour" to 3600L, "day" to 86400L, "week" to 604800L)) {
            val value = seconds / size
            if (value < 1) break
            if (unit == "week" && value > 4) break
            label = unit to value
        }
        val (unit, value) = label ?: return shared.format(then)
        return if (value == 1L) "1 $unit ago" else "$value ${unit}s ago"
    }


    private fun clipPage(clip: PackageDetail): ClipPage {
        //  Dieselbe Zeile wie clip.js (`whenLine`): Aufrufe und wann, wie
        //  bei YouTube. Die Schlagworte haengt die Vorlage an.
        val sub = listOfNotNull(
            clip.views.takeIf { it > 0 }?.let { if (it == 1L) "1 view" else String.format(Locale.ENGLISH, "%,d views", it) },
            ago(clip.createdAt),
        ).joinToString("  ")

        //  Die Nachbarschaft wie clip.js: erst das erste Schlagwort, sonst das
        //  Beliebte. Fuer einen Crawler sind das die Wege zum naechsten Clip.
        val firstTag = clip.tags.firstOrNull()
        fun some(tag: String?) = runCatching {
            catalog.search(null, tag, "popular", 0, 7).items.filter { it.slug != clip.slug }.take(6)
        }.getOrDefault(emptyList())

        var others = firstTag?.let { some(it) }.orEmpty()
        val byTag = others.isNotEmpty()
        if (!byTag) others = some(null)

        return ClipPage(
            author = clip.author,
            authorHref = profileHref(clip.authorHandle, clip.author),
            authorAvatar = clip.authorAvatar,
            authorInitial = (clip.author.firstOrNull() ?: '?').uppercase(),
            sub = sub,
            tags = clip.tags,
            packSlug = clip.pack?.slug,
            packTitle = clip.pack?.title,
            sourceCredit = clip.source?.credit,
            sourceUrl = clip.source?.url,
            facts = listOf(
                Fact("Duration", Seo.duration(clip.durationSeconds)),
                Fact("Frame rate", Math.round(clip.frameRate).toString() + " fps"),
                Fact("Curves", clip.curveCount.toString()),
                Fact("Rig", if (clip.rig == AwclipSchema.RIG_HUMANOID) "Humanoid" else "Generic"),
                Fact("Version", clip.version.toString()),
            ),
            relatedTitle = if (others.isEmpty()) null else if (byTag) "More “$firstTag”" else "Popular right now",
            related = others.map {
                Related("/clip.html?p=" + it.slug, it.title, it.author + " · " + Seo.duration(it.durationSeconds))
            },
        )
    }

    private fun clipJsonLd(clip: PackageDetail): String {
        val url = "$base/clip.html?p=${clip.slug}"
        //  Nur das EIGENE Bild des Clips - das Ersatzbild der Seite zeigt eine
        //  andere Bewegung und haette in der Bildersuche nichts verloren.
        val image = cards.ownImage(clip.slug)
        //  Ein Starter-Clip ist nicht von der Person, deren Konto ihn traegt,
        //  sondern aus einer Sammlung mit Namen - das sagt die Seite auch.
        val creator = clip.source?.let { Seo.obj("@type" to "Organization", "name" to it.credit, "url" to it.url) }
            ?: Seo.person(clip.author, clip.authorHandle?.let { "$base/u.html?u=$it" })
        val firstTag = clip.tags.firstOrNull()

        val work = Seo.obj(
            "@type" to "CreativeWork",
            "name" to clip.title,
            "description" to clip.description,
            "url" to url,
            "keywords" to clip.tags.joinToString(", "),
            "license" to Seo.CC0,
            "isAccessibleForFree" to true,
            "datePublished" to Seo.date(clip.createdAt),
            "dateModified" to Seo.date(clip.updatedAt),
            "creator" to creator,
            "isPartOf" to clip.pack?.let { Seo.obj("@type" to "CreativeWork", "name" to it.title, "url" to "$base/pack.html?k=${it.slug}") },
            //  Das Bild traegt dieselbe Lizenz - Google zeigt dann in der
            //  Bildersuche, dass und wie man es verwenden darf.
            "image" to image?.let {
                Seo.obj("@type" to "ImageObject", "contentUrl" to it, "url" to it, "width" to 1200, "height" to 630,
                    "license" to Seo.CC0, "acquireLicensePage" to url, "creditText" to (clip.source?.credit ?: clip.author),
                    "creator" to creator)
            },
            "interactionStatistic" to listOfNotNull(
                clip.likes.takeIf { it > 0 }?.let {
                    Seo.obj("@type" to "InteractionCounter", "interactionType" to "https://schema.org/LikeAction",
                        "userInteractionCount" to it)
                },
                clip.comments.takeIf { it > 0 }?.let {
                    Seo.obj("@type" to "InteractionCounter", "interactionType" to "https://schema.org/CommentAction",
                        "userInteractionCount" to it)
                },
                clip.downloads.takeIf { it > 0 }?.let {
                    Seo.obj("@type" to "InteractionCounter", "interactionType" to "https://schema.org/DownloadAction",
                        "userInteractionCount" to it)
                },
                clip.views.takeIf { it > 0 }?.let {
                    Seo.obj("@type" to "InteractionCounter", "interactionType" to "https://schema.org/ViewAction",
                        "userInteractionCount" to it)
                },
            ),
        )

        val crumbs = listOfNotNull(
            "Animations" to "$base/",
            firstTag?.let { Seo.tagLabel(it) to "$base/?tag=" + java.net.URLEncoder.encode(it, Charsets.UTF_8) },
            clip.title to url,
        )
        return Seo.jsonLd(work, Seo.breadcrumbs(*crumbs.toTypedArray()))
    }

    private fun view(model: Model, template: String, active: String? = null): String {
        model.addAttribute("active", active)
        return template
    }
}
