package com.playmation.motionlabsbackend.web

import com.playmation.motionlabsbackend.catalog.CatalogService
import com.playmation.motionlabsbackend.collection.CollectionService
import com.playmation.motionlabsbackend.collection.CollectionVisibility
import com.playmation.motionlabsbackend.config.PortalProperties
import com.playmation.motionlabsbackend.format.AwclipSchema
import com.playmation.motionlabsbackend.profile.ProfileService
import org.springframework.security.core.Authentication
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
    private val portal: PortalProperties,
) {

    @ModelAttribute("user")
    fun shellUser(authentication: Authentication?) = shell.user(authentication)

    @ModelAttribute("discordSignIn")
    fun discordSignIn() = shell.discordSignIn

    @ModelAttribute("devLogin")
    fun devLogin() = shell.devLogin

    @ModelAttribute("buildLabel")
    fun buildLabel() = shell.buildLabel

    @ModelAttribute("communityEnabled")
    fun communityEnabled() = shell.communityEnabled()

    @ModelAttribute("meta")
    fun defaultMeta() = shell.defaultMeta()

    /**
     * Die erste Station eines Crawlers. Sie sagt dasselbe wie das `robots`-Meta
     * jeder Seite und kommt aus derselben Einstellung - zwei Quellen fuer diese
     * Auskunft waeren zwei Gelegenheiten, dass sie auseinander laufen.
     */
    @GetMapping("/robots.txt", produces = ["text/plain"])
    @ResponseBody
    fun robots(): String =
        if (portal.searchIndexing)
            "User-agent: *\nDisallow: /admin.html\nDisallow: /dev.html\nDisallow: /link.html\n"
        else
            "User-agent: *\nDisallow: /\n"

    //  Ein Ziel je Seite statt einer Tabelle: die Zuordnung Adresse -> Vorlage
    //  -> aktiver Navigationseintrag steht dann an einer Stelle und liest sich
    //  von oben nach unten.

    @GetMapping("/", "/index.html")
    fun landing(model: Model) = view(model, "landing", active = "home")

    /**
     * Der Katalog lag bis zur Startseite auf „/". Er zieht um, nicht weg: „/"
     * gibt es weiter, also bricht kein Link - nur steht dort jetzt zuerst,
     * was das Portal ueberhaupt ist.
     */
    @GetMapping("/browse.html")
    fun browse(model: Model) = view(model, "browse", active = "browse")

    /**
     * Der Clip liegt unter „Browse" - man kommt aus dem Katalog hierher.
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

        //  Ein privater Clip bekommt keine eigene Vorschau. Wer „nicht gelistet
        //  und nicht auffindbar" waehlt, hat keine Karte bestellt, die seine
        //  Bewegung in jedem Kanal zeigt, in den der Link geraet.
        if (clip != null && clip.license == AwclipSchema.LICENSE_PUBLIC) {
            val url = portal.publicBaseUrl.trimEnd('/') + "/clip.html?p=" + clip.slug
            model.addAttribute("meta", ShellModel.PageMeta(
                title = clip.title + " by " + clip.author,
                description = clip.description.takeIf { it.isNotBlank() }
                    ?: "A humanoid animation clip, free to use under CC BY 4.0.",
                image = if (clip.hasPreview) portal.publicBaseUrl.trimEnd('/') + "/clip-card/" + clip.slug + ".png" else null,
                url = url,
                noindex = !portal.searchIndexing,
            ))
        }

        return view(model, "clip", active = "browse")
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
                title = meta.displayName + " on Animation Workbench Community (Beta)",
                description = meta.bio
                    ?: "Animation clips shared by ${meta.displayName} - free to use under CC BY 4.0.",
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

        return view(model, "collection", active = "browse")
    }

    @GetMapping("/me.html")
    fun me(model: Model) = view(model, "me", active = "me")

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
