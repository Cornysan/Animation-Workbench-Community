package com.playmation.motionlabsbackend.auth

import com.playmation.motionlabsbackend.account.avatarPath
import com.playmation.motionlabsbackend.account.AccountService
import com.playmation.motionlabsbackend.account.SignIn
import com.playmation.motionlabsbackend.common.PortalException
import com.playmation.motionlabsbackend.common.clientIp
import com.playmation.motionlabsbackend.moderation.NotificationRepository
import com.playmation.motionlabsbackend.system.AuditService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/**
 * Die Filter sind Beans, damit sie Abhängigkeiten bekommen - Spring Boot würde
 * sie deshalb zusätzlich direkt im Servlet-Container registrieren. Dann liefen
 * sie einmal VOR der Security-Kette, markierten die Anfrage als gefiltert, und
 * in der Kette selbst passierte nichts mehr.
 */
@Configuration
class FilterRegistrationConfig {
    @Bean
    fun bearerTokenFilterRegistration(filter: BearerTokenFilter) =
        FilterRegistrationBean(filter).apply { isEnabled = false }

    @Bean
    fun killSwitchFilterRegistration(filter: KillSwitchFilter) =
        FilterRegistrationBean(filter).apply { isEnabled = false }
}

@RestController
@RequestMapping("/api/v1/auth/editor")
class EditorLinkController(private val links: EditorLinkService) {

    data class StartResponse(val userCode: String, val verificationUrl: String, val pollSecret: String, val expiresInSeconds: Long, val intervalSeconds: Int)
    data class PollRequest(val pollSecret: String)
    data class PollResponse(val status: String, val token: String? = null, val expiresAt: Instant? = null)
    data class ApproveRequest(val userCode: String)

    @PostMapping("/start")
    fun start(request: HttpServletRequest): StartResponse =
        links.start(request.clientIp()).let { StartResponse(it.userCode, it.verificationUrl, it.pollSecret, it.expiresInSeconds, it.intervalSeconds) }

    /** 202 solange unbestätigt, 200 mit Token genau einmal, 410 wenn abgelaufen. */
    @PostMapping("/poll")
    fun poll(@RequestBody body: PollRequest): ResponseEntity<PollResponse> =
        when (val result = links.poll(body.pollSecret)) {
            is EditorLinkService.PollResult.Pending -> ResponseEntity.status(HttpStatus.ACCEPTED).body(PollResponse("pending"))
            is EditorLinkService.PollResult.Granted -> ResponseEntity.ok(PollResponse("granted", result.token, result.expiresAt))
        }

    @PostMapping("/approve")
    fun approve(@RequestBody body: ApproveRequest, authentication: Authentication?, request: HttpServletRequest): Map<String, String> {
        links.approve(authentication.requirePrincipal(), body.userCode, request.clientIp())
        return mapOf("status" to "approved")
    }
}

@RestController
@RequestMapping("/api/v1/me")
class MeController(
    private val accounts: AccountService,
    private val notifications: NotificationRepository,
    private val tokens: ApiTokenService,
    private val providers: SignInProviders,
    private val audit: AuditService,
) {
    data class MeResponse(
        val id: UUID,
        val displayName: String,
        val role: String,
        val status: String,
        val unreadNotifications: Long,
        /** Das eigene Profilbild, fuer das Feld zum Kommentieren. */
        val avatarUrl: String? = null,
    )

    data class NotificationDto(val id: UUID, val message: String, val createdAt: Instant, val read: Boolean)

    @GetMapping
    fun me(authentication: Authentication?): MeResponse {
        val principal = authentication.requirePrincipal()
        val account = accounts.get(principal.accountId)
        return MeResponse(account.id, account.displayName, account.role.name, account.status.name,
            notifications.countByAccountIdAndReadAtIsNull(account.id), account.avatarPath())
    }

    @GetMapping("/notifications")
    @Transactional
    fun notifications(authentication: Authentication?): List<NotificationDto> {
        val principal = authentication.requirePrincipal()
        val list = notifications.findByAccountIdOrderByCreatedAtDesc(principal.accountId).take(100)
        val result = list.map { NotificationDto(it.id, it.message, it.createdAt, it.readAt != null) }
        val now = Instant.now()
        list.filter { it.readAt == null }.forEach { it.readAt = now }
        return result
    }

    /** Meldet alle Workbench-Anmeldungen dieses Kontos ab. */
    @PostMapping("/tokens/revoke-all")
    fun revokeAll(authentication: Authentication?): Map<String, String> {
        tokens.revokeAll(authentication.requirePrincipal().accountId)
        return mapOf("status" to "revoked")
    }

    /** Die Anmeldungen dieses Kontos - dieselben Zeilen, die die Kontoseite zeigt. */
    @GetMapping("/sign-ins")
    fun signIns(authentication: Authentication?): List<SignInRow> =
        providers.rows(accounts.signIns(authentication.requirePrincipal().accountId))

    data class ConnectResponse(val redirect: String)

    /**
     * Eine weitere Anmeldung verbinden, Schritt 1: den Vermerk in die Sitzung
     * legen und sagen, wohin der Browser jetzt muss. Schritt 2 ist die
     * Rueckkehr vom Anbieter ([PortalUserService]).
     *
     * Ein POST, der die Adresse NENNT, statt ein Formular, das weiterleitet:
     * ein Formular, das per 302 bei GitHub landet, haelt `form-action 'self'`
     * in der Content Security Policy auf - zu Recht. Die Seite geht selbst.
     */
    @PostMapping("/sign-ins/{provider}/connect")
    fun connect(@PathVariable provider: String, authentication: Authentication?, request: HttpServletRequest): ConnectResponse {
        val principal = authentication.requirePrincipal()
        accounts.requireUsable(principal.accountId)

        if (!providers.isEnabled(provider))
            throw PortalException.notFound("This sign-in is not offered here.")
        if (accounts.signIns(principal.accountId).any { it.provider == provider })
            throw PortalException.conflict("provider-connected", "This sign-in is already connected.")

        request.getSession(true).setAttribute(ConnectIntent.SESSION_KEY,
            ConnectIntent(principal.accountId, provider, Instant.now().plus(ConnectIntent.LIFETIME)))

        return ConnectResponse("/oauth2/authorization/$provider")
    }

    @DeleteMapping("/sign-ins/{provider}")
    fun disconnect(@PathVariable provider: String, authentication: Authentication?, request: HttpServletRequest): List<SignInRow> {
        val principal = authentication.requirePrincipal()
        accounts.disconnect(principal.accountId, provider)
        audit.record(principal.accountId, "account.sign-in.disconnected", "account", principal.accountId.toString(),
            provider, request.clientIp())
        return providers.rows(accounts.signIns(principal.accountId))
    }
}

/**
 * Anmeldung ohne Anbieter für lokale Entwicklung und Tests. Existiert nur mit
 * `portal.dev-login=true` - die Produktionskonfiguration setzt das nie.
 */
@RestController
@RequestMapping("/api/v1/dev")
@ConditionalOnProperty(prefix = "portal", name = ["dev-login"], havingValue = "true")
class DevLoginController(
    private val accounts: AccountService,
    private val tokens: ApiTokenService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    init {
        log.warn("DEVELOPER LOGIN IS ENABLED - anyone can sign in as anyone. Never run this in production.")
    }

    data class DevLoginRequest(val name: String)
    data class DevLoginResponse(val accountId: UUID, val token: String, val role: String)

    @PostMapping("/login")
    fun login(@RequestBody body: DevLoginRequest, request: HttpServletRequest, response: HttpServletResponse): DevLoginResponse {
        val name = body.name.trim()
        if (name.isEmpty() || name.length > 40 || !name.all { it.isLetterOrDigit() || it == '-' || it == '_' })
            throw PortalException.badRequest("invalid-name", "Use letters, digits, '-' and '_'.")

        val account = accounts.login(SignIn("dev", name, name))
        val principal = PortalPrincipal(account.id, account.role, account.displayName)

        //  Auch als Browser-Session, damit sich die Weboberfläche lokal ohne
        //  Anbieter bedienen lässt.
        val context = SecurityContextHolder.createEmptyContext().apply { authentication = PortalAuthentication(principal) }
        SecurityContextHolder.setContext(context)
        HttpSessionSecurityContextRepository().saveContext(context, request, response)

        return DevLoginResponse(account.id, tokens.issue(account.id, "dev login").token, account.role.name)
    }
}
