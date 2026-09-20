package com.playmation.motionlabsbackend.web

import com.playmation.motionlabsbackend.account.AccountService
import com.playmation.motionlabsbackend.auth.portalPrincipal
import com.playmation.motionlabsbackend.catalog.CatalogService
import com.playmation.motionlabsbackend.config.PortalProperties
import com.playmation.motionlabsbackend.format.AwclipSchema
import com.playmation.motionlabsbackend.moderation.NotificationRepository
import com.playmation.motionlabsbackend.system.SystemSettingsService
import org.springframework.beans.factory.annotation.Value
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
    private val accounts: AccountService,
    private val notifications: NotificationRepository,
    private val settings: SystemSettingsService,
    /** Nur zum Fuellen der Link-Vorschau - den Inhalt holt die Seite selbst. */
    private val catalog: CatalogService,
    private val portal: PortalProperties,
    private val build: BuildStamp,
    /** Ohne echte Discord-App steht hier die Vorgabe aus der application.yaml. */
    @Value("\${spring.security.oauth2.client.registration.discord.client-id:unset}")
    private val discordClientId: String,
) {

    /**
     * Was der Rahmen ueber den angemeldeten Nutzer wissen muss - fertig
     * aufbereitet, damit die Vorlage nur noch einsetzt. Anfangsbuchstabe und
     * Beschriftungen hier zu rechnen ist kuerzer als dieselbe Logik in
     * Thymeleaf-Ausdruecken, und man kann sie lesen.
     */
    data class ShellUser(
        val displayName: String,
        val initial: String,
        val admin: Boolean,
        val unread: Long,
        val unreadLabel: String,
        val roleLabel: String,
    )

    @ModelAttribute("user")
    fun shellUser(authentication: Authentication?): ShellUser? {
        val principal = authentication.portalPrincipal() ?: return null
        val account = runCatching { accounts.get(principal.accountId) }.getOrNull() ?: return null
        val unread = notifications.countByAccountIdAndReadAtIsNull(account.id)
        val admin = account.role.name == "ADMIN"

        return ShellUser(
            displayName = account.displayName,
            initial = account.displayName.firstOrNull()?.uppercase() ?: "?",
            admin = admin,
            unread = unread,
            unreadLabel = if (unread == 1L) "1 message" else "$unread messages",
            roleLabel = if (admin) "Moderator" else "Signed in",
        )
    }

    @ModelAttribute("discordSignIn")
    fun discordSignIn() = discordClientId.isNotBlank() && discordClientId != "unset"

    @ModelAttribute("devLogin")
    fun devLogin() = portal.devLogin

    @ModelAttribute("buildLabel")
    fun buildLabel() = build.label

    @ModelAttribute("communityEnabled")
    fun communityEnabled() = settings.communityEnabled()

    /**
     * Was eine Vorschau zeigt, wenn jemand eine Adresse dieses Portals in
     * Discord, Slack oder anderswo einwirft.
     *
     * Bis hierher zeigte sie nichts: es gab kein einziges `og:`-Feld, also
     * stand in der Nachricht die nackte Adresse. Fuer ein Portal, dessen
     * Verbreitung ueber einen Chat laeuft, ist das die teuerste aller stillen
     * Luecken - der Link kommt an und sagt nicht, wohin er fuehrt.
     *
     * Jede Seite bekommt hier eine Vorgabe; wer etwas Besseres weiss, setzt sie
     * darueber (siehe [clip]).
     */
    data class PageMeta(
        val title: String,
        val description: String,
        /** Absolute Adresse - eine relative liest kein Vorschau-Bot. */
        val image: String? = null,
        val url: String? = null,
        /**
         * Die Beta ist geschlossen und nirgends angekuendigt. Bis sie es nicht
         * mehr ist, hat keine Seite etwas in einem Suchindex zu suchen - eine
         * Vorschau in einem Chat ist etwas anderes als ein Treffer bei Google.
         */
        val noindex: Boolean,
    )

    @ModelAttribute("meta")
    fun defaultMeta() = PageMeta(
        "Animation Workbench Community",
        "Humanoid animation clips made in the Animation Workbench, shared as plain motion data - " +
            "no FBX, no rig, no model. Free to use under CC BY 4.0.",
        url = portal.publicBaseUrl,
        noindex = !portal.searchIndexing,
    )

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

        //  Ein privater Clip bekommt keine eigene Vorschau. Wer „nicht gelistet
        //  und nicht auffindbar" waehlt, hat keine Karte bestellt, die seine
        //  Bewegung in jedem Kanal zeigt, in den der Link geraet.
        if (clip != null && clip.license == AwclipSchema.LICENSE_PUBLIC) {
            val url = portal.publicBaseUrl.trimEnd('/') + "/clip.html?p=" + clip.slug
            model.addAttribute("meta", PageMeta(
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
