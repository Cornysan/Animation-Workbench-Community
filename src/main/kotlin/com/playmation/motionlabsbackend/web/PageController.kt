package com.playmation.motionlabsbackend.web

import com.playmation.motionlabsbackend.account.AccountService
import com.playmation.motionlabsbackend.auth.portalPrincipal
import com.playmation.motionlabsbackend.config.PortalProperties
import com.playmation.motionlabsbackend.moderation.NotificationRepository
import com.playmation.motionlabsbackend.system.SystemSettingsService
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute

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

    /** Der Clip liegt unter „Browse" - man kommt aus dem Katalog hierher. */
    @GetMapping("/clip.html")
    fun clip(model: Model) = view(model, "clip", active = "browse")

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
