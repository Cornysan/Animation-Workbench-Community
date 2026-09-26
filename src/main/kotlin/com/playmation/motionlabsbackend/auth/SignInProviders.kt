package com.playmation.motionlabsbackend.auth

import com.playmation.motionlabsbackend.account.AccountService
import com.playmation.motionlabsbackend.account.AvatarSources
import com.playmation.motionlabsbackend.account.ConnectedSignIn
import com.playmation.motionlabsbackend.account.SignIn
import com.playmation.motionlabsbackend.common.PortalException
import com.playmation.motionlabsbackend.common.clientIp
import com.playmation.motionlabsbackend.system.AuditService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientProperties
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientPropertiesMapper
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.core.Authentication
import org.springframework.security.core.AuthenticationException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.security.web.authentication.AuthenticationFailureHandler
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler
import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.io.Serializable
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Ein Anbieter, bei dem man sich anmelden kann. `id` ist der Name der Registrierung in application.yaml. */
data class SignInProvider(val id: String, val label: String) {
    val authorizationUrl get() = "/oauth2/authorization/$id"
}

/** Ein Anbieter auf der Kontoseite. Die Kennung beim Anbieter steht absichtlich nicht darin. */
data class SignInRow(
    val provider: String,
    val label: String,
    val connected: Boolean,
    val connectedAt: Instant?,
    val lastUsedAt: Instant?,
    /** Name und Bild kommen von hier - siehe `AccountService.profileSource`. */
    val profileSource: Boolean,
    val canConnect: Boolean,
    /** Verbunden, aber nicht die Quelle: "Use for profile" nimmt Name und Bild kuenftig von hier. */
    val canUseForProfile: Boolean,
    /** Nicht die letzte: ohne Anmeldung kaeme niemand mehr hinein. */
    val canDisconnect: Boolean,
)

/**
 * Die Registrierungen aus application.yaml - mit EINER Korrektur, die sich dort
 * nicht schreiben laesst: GitHub ohne Scope.
 *
 * Spring setzt fuer GitHub `read:user` voraus ("alle Profildaten lesen" auf
 * GitHubs Zustimmungsseite). Gebraucht wird nur das oeffentliche Profil, und
 * das gibt GitHub ohne jeden Scope heraus. Leeren laesst sich die Vorgabe an
 * KEINER der naheliegenden Stellen: `scope: ""` bindet Spring Boot als "nicht
 * gesetzt", und `Builder.scope(leer)` laesst die alten Scopes stillschweigend
 * stehen. Also wird die Registrierung hier neu gebaut - alles uebernommen,
 * ausser den Scopes. Spring fuehrt "keine" danach als `null`; die Anfrage an
 * GitHub traegt dann keinen scope-Parameter (SignInFlowTest prueft genau das).
 *
 * Damit ersetzt diese Bohne die von Spring Boot - und deren Konfiguration hat
 * die Eigenschaften mitgebracht, deshalb stehen sie hier noch einmal.
 */
@Configuration
@EnableConfigurationProperties(OAuth2ClientProperties::class)
class ClientRegistrationConfig {

    @Bean
    fun clientRegistrationRepository(properties: OAuth2ClientProperties): InMemoryClientRegistrationRepository =
        InMemoryClientRegistrationRepository(
            OAuth2ClientPropertiesMapper(properties).asClientRegistrations().values.map { registration ->
                if (registration.registrationId == "github") withoutScopes(registration) else registration
            },
        )

    private fun withoutScopes(registration: ClientRegistration): ClientRegistration {
        val provider = registration.providerDetails
        return ClientRegistration.withRegistrationId(registration.registrationId)
            .clientId(registration.clientId)
            .clientSecret(registration.clientSecret)
            .clientAuthenticationMethod(registration.clientAuthenticationMethod)
            .authorizationGrantType(registration.authorizationGrantType)
            .redirectUri(registration.redirectUri)
            .clientName(registration.clientName)
            .clientSettings(registration.clientSettings)
            .authorizationUri(provider.authorizationUri)
            .tokenUri(provider.tokenUri)
            .userInfoUri(provider.userInfoEndpoint.uri)
            .userInfoAuthenticationMethod(provider.userInfoEndpoint.authenticationMethod)
            .userNameAttributeName(provider.userInfoEndpoint.userNameAttributeName)
            .jwkSetUri(provider.jwkSetUri)
            .issuerUri(provider.issuerUri)
            .providerConfigurationMetadata(provider.configurationMetadata)
            .build()
    }
}

/**
 * Welche Anmeldungen dieses Portal anbietet.
 *
 * Registriert sind in application.yaml immer alle drei; ANGEBOTEN wird nur,
 * wofuer eine Client-ID eingetragen ist. Ohne sie fuehrt der Knopf zum
 * Anbieter mit `client_id=unset`, und der antwortet mit einer Fehlerseite ohne
 * Ausweg (Befund B6 - damals nur fuer Discord, jetzt fuer jeden).
 */
@Component
class SignInProviders(private val registrations: ClientRegistrationRepository) {

    companion object {
        /** Reihenfolge = Reihenfolge der Knoepfe. Discord zuerst: dort ist die Community zuhause. */
        val KNOWN = listOf(
            SignInProvider("discord", "Discord"),
            SignInProvider("github", "GitHub"),
            SignInProvider("google", "Google"),
        )

        fun label(id: String): String = when (id) {
            "dev" -> "Developer sign-in"
            else -> KNOWN.firstOrNull { it.id == id }?.label ?: id
        }
    }

    /** Einmal gerechnet - die Registrierungen aendern sich nicht, solange der Server laeuft. */
    val enabled: List<SignInProvider> by lazy { KNOWN.filter { configured(it.id) } }

    fun isEnabled(id: String) = enabled.any { it.id == id }

    /**
     * Die Zeilen der Kontoseite: jeder angebotene Anbieter, verbunden oder
     * nicht - und dahinter, was verbunden ist, aber nicht (mehr) angeboten
     * wird. Die bleiben sichtbar, damit man sie loesen kann.
     */
    fun rows(connected: List<ConnectedSignIn>): List<SignInRow> {
        val byProvider = connected.associateBy { it.provider }
        val ids = enabled.map { it.id } + connected.map { it.provider }.filter { !isEnabled(it) }

        return ids.map { id ->
            val link = byProvider[id]
            SignInRow(
                provider = id,
                label = label(id),
                connected = link != null,
                connectedAt = link?.connectedAt,
                lastUsedAt = link?.lastUsedAt,
                profileSource = link?.profileSource == true,
                canConnect = link == null && isEnabled(id),
                canUseForProfile = link != null && !link.profileSource && isEnabled(id),
                canDisconnect = link != null && connected.size > 1,
            )
        }
    }

    private fun configured(id: String): Boolean {
        val clientId = registrations.findByRegistrationId(id)?.clientId
        return !clientId.isNullOrBlank() && clientId != "unset"
    }
}

/**
 * Was vom Anbieter uebrig bleibt: Kennung, Name, Bildadresse. Alles andere,
 * was er schickt, faellt hier weg - auch eine oeffentliche E-Mail-Adresse, die
 * GitHub ungefragt mitliefert, wenn jemand sie dort sichtbar gemacht hat.
 */
object SignInProfiles {

    fun from(provider: String, attributes: Map<String, Any?>): SignIn {
        fun text(key: String) = attributes[key]?.toString()?.trim()?.takeIf { it.isNotEmpty() }

        val signIn = when (provider) {
            //  `global_name` ist der Anzeigename, `username` der eindeutige
            //  Discord-Name. Der Handle entsteht - wie bisher - aus dem
            //  Anzeigenamen: der Discord-Name verbindet ein Portal-Konto enger
            //  mit einem Discord-Konto, als jemand darum gebeten hat.
            "discord" -> {
                val id = text("id") ?: missing(provider)
                SignIn(provider, id, text("global_name") ?: text("username") ?: "user",
                    AvatarSources.discord(id, text("avatar")))
            }

            //  `name` ist frei gewaehlt und oft leer, `login` gibt es immer. Die
            //  Kennung ist eine Zahl; der Login kann sich aendern.
            "github" -> {
                val id = text("id") ?: missing(provider)
                SignIn(provider, id, text("name") ?: text("login") ?: "user",
                    AvatarSources.github(id), handleHint = text("login"))
            }

            //  Scope `profile` ohne `openid`: dieselben Angaben, ohne dass eine
            //  zweite Anmeldeart (OIDC) durch den Server laufen muss.
            "google" -> {
                val sub = text("sub") ?: missing(provider)
                SignIn(provider, sub, text("name") ?: text("given_name") ?: "user",
                    AvatarSources.google(text("picture")))
            }

            else -> throw OAuth2AuthenticationException(OAuth2Error("unknown_provider"), "Unknown sign-in provider")
        }

        if (signIn.subject.length > 128) missing(provider)
        return signIn
    }

    private fun missing(provider: String): Nothing =
        throw OAuth2AuthenticationException(OAuth2Error("invalid_user"), "$provider did not return a usable user id")
}

/**
 * "Diese Anmeldung an MEIN Konto haengen" - gemerkt in der Sitzung, zwischen
 * dem Knopf auf der Kontoseite und der Rueckkehr vom Anbieter.
 *
 * Warum ein eigener Vermerk und nicht einfach "wer angemeldet ist und sich
 * anmeldet, verbindet": die Kopfleiste zeigt Angemeldeten keinen Anmeldeknopf,
 * aber `/oauth2/authorization/github` ist eine Adresse, die jede fremde Seite
 * verlinken kann. Verbunden wird nur, wer es auf der Kontoseite ausgeloest hat,
 * und nur fuer das Konto, das es ausgeloest hat.
 */
data class ConnectIntent(
    val accountId: UUID,
    val provider: String,
    val expiresAt: Instant,
    /** [CONNECT] haengt eine neue Anmeldung an, [PROFILE] nimmt Name und Bild von einer verbundenen. */
    val purpose: String = CONNECT,
) : Serializable {
    companion object {
        const val SESSION_KEY = "aw.connect-intent"

        const val CONNECT = "connect"
        const val PROFILE = "profile"

        /** Gesetzt, wenn die Rueckkehr die Profilquelle gewechselt hat - fuer den Handler unten. */
        const val PROFILE_SET = "aw.profile-set"

        /** Gesetzt, solange die Rueckkehr vom Anbieter gerade verbindet - fuer die beiden Handler unten. */
        const val CONNECTING = "aw.connecting"
        const val CONNECTED = "aw.connected"

        val LIFETIME: Duration = Duration.ofMinutes(10)
    }
}

/**
 * Die Rueckkehr von JEDEM Anbieter: Profil lesen, Konto finden, anlegen oder
 * verbinden. Ersetzt den DiscordUserService, der dasselbe fuer einen tat.
 */
@Component
class PortalUserService(
    private val accounts: AccountService,
    private val audit: AuditService,
) : OAuth2UserService<OAuth2UserRequest, OAuth2User> {
    private val delegate = DefaultOAuth2UserService()

    override fun loadUser(request: OAuth2UserRequest): OAuth2User =
        complete(request.clientRegistration.registrationId, delegate.loadUser(request).attributes)

    /** Alles nach der Antwort des Anbieters - getrennt, damit ein Test es ohne Anbieter aufrufen kann. */
    internal fun complete(provider: String, attributes: Map<String, Any?>): OAuth2User {
        val signIn = SignInProfiles.from(provider, attributes)

        val httpRequest = (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request
        val intent = takeIntent(httpRequest)
        val current = SecurityContextHolder.getContext().authentication.portalPrincipal()

        val account = try {
            if (intent != null && current != null && intent.accountId == current.accountId &&
                intent.provider == provider && intent.expiresAt.isAfter(Instant.now())) {
                httpRequest?.setAttribute(ConnectIntent.CONNECTING, provider)
                if (intent.purpose == ConnectIntent.PROFILE) {
                    accounts.useForProfile(current.accountId, signIn).also {
                        httpRequest?.setAttribute(ConnectIntent.PROFILE_SET, provider)
                        audit.record(it.id, "account.profile-source", "account", it.id.toString(),
                            provider, httpRequest?.clientIp())
                    }
                } else {
                    accounts.connect(current.accountId, signIn).also {
                        httpRequest?.setAttribute(ConnectIntent.CONNECTED, provider)
                        audit.record(it.id, "account.sign-in.connected", "account", it.id.toString(),
                            provider, httpRequest?.clientIp())
                    }
                }
            } else {
                accounts.login(signIn)
            }
        } catch (ex: PortalException) {
            throw OAuth2AuthenticationException(OAuth2Error(ex.code.replace('-', '_')), ex.message)
        }

        //  Die Attribute des Anbieters reisen NICHT in die Sitzung. Gebraucht
        //  wird davon nichts mehr, und was nicht gespeichert ist, kann auch
        //  nicht in einem Sitzungsspeicher liegen bleiben.
        return PortalOAuth2User(
            PortalPrincipal(account.id, account.role, account.displayName),
            mapOf("account" to account.id.toString()),
        )
    }

    /** Der Vermerk gilt fuer genau eine Rueckkehr - danach ist er weg, ob sie gelingt oder nicht. */
    private fun takeIntent(request: HttpServletRequest?): ConnectIntent? {
        val session = request?.getSession(false) ?: return null
        val intent = session.getAttribute(ConnectIntent.SESSION_KEY) as? ConnectIntent
        session.removeAttribute(ConnectIntent.SESSION_KEY)
        return intent
    }
}

/**
 * Nach einer gelungenen Rueckkehr: wer verbunden hat, landet wieder auf der
 * Kontoseite und sieht es dort; wer sich angemeldet hat, wie bisher auf "/".
 */
class SignInSuccessHandler : SavedRequestAwareAuthenticationSuccessHandler() {
    init {
        setDefaultTargetUrl("/")
    }

    override fun onAuthenticationSuccess(request: HttpServletRequest, response: HttpServletResponse, authentication: Authentication) {
        val connected = request.getAttribute(ConnectIntent.CONNECTED) as? String
        val profile = request.getAttribute(ConnectIntent.PROFILE_SET) as? String
        if (connected == null && profile == null) {
            super.onAuthenticationSuccess(request, response, authentication)
            return
        }
        clearAuthenticationAttributes(request)
        redirectStrategy.sendRedirect(request, response,
            if (profile != null) "/me.html?profile=$profile" else "/me.html?connected=$connected")
    }
}

/**
 * Nach einer gescheiterten Rueckkehr.
 *
 * Bis hierher landete jeder Fehler auf Springs eingebauter Anmeldeseite - auch
 * "dieses Konto ist gesperrt", in Springs Worten und Springs Aussehen. Jetzt
 * geht es zurueck dorthin, wo man herkam: auf die Anmeldeseite oder, beim
 * Verbinden, auf die Kontoseite. Die Adresse traegt nur einen Grund aus einer
 * festen Liste; den Satz dazu schreibt die Seite.
 */
class SignInFailureHandler : AuthenticationFailureHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun onAuthenticationFailure(request: HttpServletRequest, response: HttpServletResponse, exception: AuthenticationException) {
        //  Brach der Anbieter ab (Abbrechen auf seiner Seite), kam loadUser gar
        //  nicht erst dran - dann liegt der Vermerk noch in der Sitzung.
        val session = request.getSession(false)
        val pending = session?.getAttribute(ConnectIntent.SESSION_KEY) != null
        session?.removeAttribute(ConnectIntent.SESSION_KEY)
        val connecting = pending || request.getAttribute(ConnectIntent.CONNECTING) != null

        val code = (exception as? OAuth2AuthenticationException)?.error?.errorCode
        val reason = when (code) {
            "access_denied" -> "cancelled"
            "account_banned", "forbidden" -> "banned"
            "identity_taken" -> "taken"
            "provider_connected" -> "provider-connected"
            "other_identity" -> "other-account"
            else -> "failed"
        }

        //  Ein unbekannter Fehler ist fast immer eine Einstellung: falsches
        //  Geheimnis, falsche Rueckkehradresse beim Anbieter. Das soll im
        //  Protokoll stehen, nicht nur als "hat nicht geklappt" im Browser.
        if (reason == "failed") log.warn("Sign-in failed ({}): {}", code, exception.message)

        response.sendRedirect(if (connecting) "/me.html?connect-error=$reason" else "/signin.html?error=$reason")
    }
}
