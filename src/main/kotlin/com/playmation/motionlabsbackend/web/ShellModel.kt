package com.playmation.motionlabsbackend.web

import com.playmation.motionlabsbackend.account.AccountService
import com.playmation.motionlabsbackend.account.avatarPath
import com.playmation.motionlabsbackend.auth.portalPrincipal
import com.playmation.motionlabsbackend.config.PortalProperties
import com.playmation.motionlabsbackend.moderation.NotificationRepository
import com.playmation.motionlabsbackend.system.SystemSettingsService
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component

/**
 * Was JEDE Seite braucht, um ihren Rahmen zu zeichnen: wer angemeldet ist, ob
 * das Portal pausiert, welcher Stand antwortet, wohin "Sign in" fuehrt.
 *
 * Das stand als `@ModelAttribute`-Methoden im [PageController] und galt damit
 * genau fuer die Seiten, die dort landen. Die Fehlerseite landet dort NICHT -
 * sie entsteht aus einer Ausnahme, weit hinter jedem Controller - und haette
 * mit leerem Modell gerendert: ein Zugriff auf `meta.description` auf `null`,
 * also eine Ausnahme beim Behandeln einer Ausnahme.
 *
 * Deshalb steht es hier: eine Stelle, die beide fragen koennen. Ein
 * `@ControllerAdvice` waere der naheliegende Weg gewesen und der falsche - es
 * liefe auch bei jedem API-Aufruf und holte dann Konto und Benachrichtigungen
 * aus der Datenbank, um sie wegzuwerfen.
 */
@Component
class ShellModel(
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
        /**
         * Die eigene Profiladresse. Der Name im Kopf fuehrt dorthin - das ist
         * der kuerzeste Weg zu "wie sehen mich die anderen". Leer nur, solange
         * der Nachlauf einem Bestandskonto noch keinen Handle gegeben hat.
         */
        val handle: String?,
        val initial: String,
        val admin: Boolean,
        val unread: Long,
        val unreadLabel: String,
        val roleLabel: String,
        /** Das eigene Profilbild, sonst null - dann bleibt der Buchstabe. */
        val avatar: String? = null,
    )

    fun user(authentication: Authentication?): ShellUser? {
        val principal = authentication.portalPrincipal() ?: return null
        val account = runCatching { accounts.get(principal.accountId) }.getOrNull() ?: return null
        val unread = notifications.countByAccountIdAndReadAtIsNull(account.id)
        val admin = account.role.name == "ADMIN"

        return ShellUser(
            displayName = account.displayName,
            handle = account.handle,
            initial = account.displayName.firstOrNull()?.uppercase() ?: "?",
            admin = admin,
            unread = unread,
            unreadLabel = if (unread == 1L) "1 message" else "$unread messages",
            roleLabel = if (admin) "Moderator" else "Signed in",
            avatar = account.avatarPath(),
        )
    }

    val discordSignIn: Boolean get() = discordClientId.isNotBlank() && discordClientId != "unset"
    val devLogin: Boolean get() = portal.devLogin
    val buildLabel: String get() = build.label
    fun communityEnabled() = settings.communityEnabled()
    fun charactersEnabled() = settings.charactersEnabled()

    /**
     * Was eine Vorschau zeigt, wenn jemand eine Adresse dieses Portals in
     * Discord, Slack oder anderswo einwirft.
     *
     * Bis hierher zeigte sie nichts: es gab kein einziges `og:`-Feld, also
     * stand in der Nachricht die nackte Adresse. Fuer ein Portal, dessen
     * Verbreitung ueber einen Chat laeuft, ist das die teuerste aller stillen
     * Luecken - der Link kommt an und sagt nicht, wohin er fuehrt.
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
        val noindex: Boolean = true,
    )

    fun defaultMeta() = PageMeta(
        "Animation Workbench Community (Beta)",
        "Humanoid animation clips made in the Animation Workbench, shared as plain motion data - " +
            "no FBX, no rig, no model. Free to use under CC BY 4.0.",
        url = portal.publicBaseUrl,
        noindex = !portal.searchIndexing,
    )

    /**
     * Das ganze Modell auf einmal - fuer Seiten, die keinen Controller haben.
     *
     * `Any` statt `Any?`, weil Springs `ModelAndView.model` genau das ist; und
     * ein Nullwert hat hier ohnehin nichts verloren. Was fehlen darf - der
     * abgemeldete Nutzer - fehlt als Schluessel, und Thymeleaf liest einen
     * fehlenden Schluessel als `null`, was die Vorlage schon abfaengt.
     */
    fun fill(model: MutableMap<String, Any>, authentication: Authentication?) {
        user(authentication)?.let { model["user"] = it }
        model["discordSignIn"] = discordSignIn
        model["devLogin"] = devLogin
        model["buildLabel"] = buildLabel
        model["communityEnabled"] = communityEnabled()
        model["charactersEnabled"] = charactersEnabled()
        model["meta"] = defaultMeta()
    }
}
