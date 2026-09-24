package com.playmation.motionlabsbackend.auth

import com.playmation.motionlabsbackend.account.AccountRepository
import com.playmation.motionlabsbackend.account.AccountStatus
import com.playmation.motionlabsbackend.common.Crypto
import com.playmation.motionlabsbackend.common.PortalException
import com.playmation.motionlabsbackend.common.RateLimiter
import com.playmation.motionlabsbackend.common.clientIp
import com.playmation.motionlabsbackend.config.PortalProperties
import com.playmation.motionlabsbackend.system.AuditService
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseCookie
import org.springframework.security.authentication.RememberMeAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.authentication.RememberMeServices
import org.springframework.security.web.authentication.logout.LogoutHandler
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.security.web.savedrequest.HttpSessionRequestCache
import org.springframework.stereotype.Component
import org.springframework.stereotype.Controller
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/*
 * Der Browser bleibt angemeldet, und die Workbench nimmt ihn mit - Schema 9.
 *
 * Die Sitzung selbst bleibt, wie sie war: im Speicher, kurz, bei jedem
 * Neustart weg. Sie in die Datenbank zu legen haette sie ueberleben lassen,
 * aber mit Rolle und Namen vom Tag der Anmeldung - ein entzogenes Admin-Recht
 * hielte dann 30 Tage. So bleibt sie kurz, und das Cookie hier meldet den
 * Browser nach ihrem Ende leise wieder an, mit dem Konto, wie es JETZT ist.
 */

@Entity
@Table(name = "browser_login")
class BrowserLogin(
    @Id
    var id: UUID = UUID.randomUUID(),
    var accountId: UUID,
    var tokenHash: String,
    var createdAt: Instant,
    var lastUsedAt: Instant? = null,
    var expiresAt: Instant,
)

interface BrowserLoginRepository : JpaRepository<BrowserLogin, UUID> {
    fun findByTokenHash(tokenHash: String): BrowserLogin?
    fun deleteByAccountIdAndExpiresAtBefore(accountId: UUID, before: Instant)
}

/**
 * "Angemeldet bleiben", ohne Haekchen: wer sich im Browser anmeldet, bleibt
 * es 30 Tage ab dem letzten Besuch ([PortalProperties.Tokens.browserLoginDays]).
 *
 * Spring ruft [loginSuccess] nach jeder Anmeldung beim Anbieter, [autoLogin],
 * wenn eine Anfrage ohne Sitzung kommt, und [logout] beim Abmelden. Das
 * Ergebnis von [autoLogin] ist ein RememberMeAuthenticationToken mit dem
 * [PortalPrincipal] darin - `portalPrincipal()` liest es wie jedes andere.
 *
 * KEINE ROTATION des Werts bei jedem Einsatz, wie Springs eigene Fassung sie
 * macht: laedt eine Seite ohne Sitzung mehrere Dinge zugleich, legt jede
 * Anfrage den alten Wert vor, die zweite haelt das fuer Diebstahl, und der
 * Nutzer ist ganz abgemeldet. Der Wert ist 256 Bit Zufall, liegt nur als Hash
 * hier und reist nur ueber HTTPS in einem HttpOnly-Cookie - das ist dieselbe
 * Staerke wie das Sitzungs-Cookie selbst.
 */
@Component
class PortalRememberMeServices(
    private val logins: BrowserLoginRepository,
    private val accounts: AccountRepository,
    private val properties: PortalProperties,
    private val clock: Clock,
    @Value("\${server.servlet.session.cookie.secure:true}") private val secureCookie: Boolean,
) : RememberMeServices, LogoutHandler {

    companion object {
        const val COOKIE = "aw_signed_in"

        /** Nur der Abgleich zwischen diesem Dienst und Springs Pruefer (SecurityConfig). */
        const val KEY = "aw-browser-login"
    }

    private val lifetime get() = Duration.ofDays(properties.tokens.browserLoginDays)

    @Transactional
    override fun autoLogin(request: HttpServletRequest, response: HttpServletResponse): Authentication? {
        val raw = cookieValue(request) ?: return null
        val login = logins.findByTokenHash(Crypto.sha256Hex(raw))
        val now = clock.instant()
        val account = login?.takeIf { it.expiresAt.isAfter(now) }
            ?.let { accounts.findById(it.accountId).orElse(null) }

        if (login == null || account == null || account.status == AccountStatus.BANNED) {
            if (login != null) logins.delete(login)
            cancelCookie(response)
            return null
        }

        login.lastUsedAt = now
        login.expiresAt = now.plus(lifetime)
        writeCookie(response, raw)

        val principal = PortalPrincipal(account.id, account.role, account.displayName)
        return RememberMeAuthenticationToken(KEY, principal, principal.authorities())
    }

    @Transactional
    override fun loginSuccess(request: HttpServletRequest, response: HttpServletResponse, successfulAuthentication: Authentication) {
        val principal = successfulAuthentication.portalPrincipal() ?: return
        val now = clock.instant()

        //  Ein Browser, eine Zeile: trug er schon einen Wert (Anbieter
        //  verbinden, erneut angemeldet), geht der alte.
        cookieValue(request)?.let { old -> logins.findByTokenHash(Crypto.sha256Hex(old))?.let(logins::delete) }
        logins.deleteByAccountIdAndExpiresAtBefore(principal.accountId, now)

        val raw = Crypto.randomToken(32)
        logins.save(BrowserLogin(accountId = principal.accountId, tokenHash = Crypto.sha256Hex(raw), createdAt = now, expiresAt = now.plus(lifetime)))
        writeCookie(response, raw)
    }

    override fun loginFail(request: HttpServletRequest, response: HttpServletResponse) = cancelCookie(response)

    @Transactional
    override fun logout(request: HttpServletRequest, response: HttpServletResponse, authentication: Authentication?) {
        cookieValue(request)?.let { raw -> logins.findByTokenHash(Crypto.sha256Hex(raw))?.let(logins::delete) }
        cancelCookie(response)
    }

    private fun cookieValue(request: HttpServletRequest): String? =
        request.cookies?.firstOrNull { it.name == COOKIE }?.value?.takeIf { it.isNotBlank() && it.length <= 100 }

    private fun writeCookie(response: HttpServletResponse, value: String) = setCookie(response, value, lifetime)

    private fun cancelCookie(response: HttpServletResponse) = setCookie(response, "", Duration.ZERO)

    private fun setCookie(response: HttpServletResponse, value: String, maxAge: Duration) {
        val cookie = ResponseCookie.from(COOKIE, value)
            .path("/")
            .httpOnly(true)
            .secure(secureCookie)
            .sameSite("Lax")
            .maxAge(maxAge)
            .build()
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString())
    }
}

// ── Die Uebergabe aus der Workbench ─────────────────────────────────────────

@Entity
@Table(name = "sign_in_handoff")
class SignInHandoff(
    @Id
    var id: UUID = UUID.randomUUID(),
    var codeHash: String,
    var accountId: UUID,
    var nextPath: String,
    var createdAt: Instant,
    var expiresAt: Instant,
    var usedAt: Instant? = null,
)

interface SignInHandoffRepository : JpaRepository<SignInHandoff, UUID> {
    fun findByCodeHash(codeHash: String): SignInHandoff?
    fun deleteByExpiresAtBefore(before: Instant)

    /** Einloesen als EIN Schritt: kommen zwei Anfragen zugleich, gewinnt eine. */
    @Modifying
    @Query("update SignInHandoff h set h.usedAt = :now where h.id = :id and h.usedAt is null")
    fun markUsed(id: UUID, now: Instant): Int
}

/**
 * Die Workbench ist angemeldet, der Browser nicht - also nimmt sie ihn mit.
 *
 * 1. Unity fragt mit seinem Token nach einem Code ([create]) und bekommt eine
 *    Adresse, die eine Minute gilt.
 * 2. Es oeffnet sie im Browser; der loest den Code ein ([redeem]), ist
 *    angemeldet und landet auf der Seite, die Unity wollte.
 *
 * Das Ziel steht beim Code in der Datenbank, nicht in der Adresse: wer den
 * Link veraendert, lenkt nirgendwohin um. Und es ist immer ein Pfad dieses
 * Portals.
 */
@Service
class SignInHandoffService(
    private val handoffs: SignInHandoffRepository,
    private val accounts: AccountRepository,
    private val properties: PortalProperties,
    private val rateLimiter: RateLimiter,
    private val audit: AuditService,
    private val clock: Clock,
) {
    data class Redeemed(val principal: PortalPrincipal, val next: String)

    /** Ein Pfad auf diesem Portal, mit hoechstens einer schlichten Anfrage dahinter. */
    private val allowedNext = Regex("^/[A-Za-z0-9._~/-]*(\\?[A-Za-z0-9._~=&%-]*)?$")

    @Transactional
    fun create(principal: PortalPrincipal, next: String?, ip: String): String {
        rateLimiter.require("sign-in-handoff", principal.accountId.toString(), 30, Duration.ofHours(1))

        val target = next?.trim()?.takeIf { it.isNotEmpty() } ?: "/me.html"
        if (target.length > 200 || target.startsWith("//") || !allowedNext.matches(target))
            throw PortalException.badRequest("invalid-next", "The target has to be a page on this portal.")

        val now = clock.instant()
        handoffs.deleteByExpiresAtBefore(now.minus(Duration.ofHours(1)))

        val code = Crypto.randomToken(32)
        val handoff = handoffs.save(SignInHandoff(
            codeHash = Crypto.sha256Hex(code),
            accountId = principal.accountId,
            nextPath = target,
            createdAt = now,
            expiresAt = now.plusSeconds(properties.tokens.handoffSeconds),
        ))
        audit.record(principal.accountId, "browser.handoff.created", "sign_in_handoff", handoff.id.toString(), null, ip)

        return "${properties.publicBaseUrl.trimEnd('/')}/signin/handoff?code=$code"
    }

    /** null = unbekannt, abgelaufen, schon benutzt oder das Konto gesperrt. */
    @Transactional
    fun redeem(code: String, ip: String): Redeemed? {
        if (code.isBlank() || code.length > 100) return null
        val handoff = handoffs.findByCodeHash(Crypto.sha256Hex(code)) ?: return null
        val now = clock.instant()
        if (handoff.expiresAt.isBefore(now)) return null
        if (handoffs.markUsed(handoff.id, now) != 1) return null

        val account = accounts.findById(handoff.accountId).orElse(null) ?: return null
        if (account.status == AccountStatus.BANNED) return null

        audit.record(account.id, "browser.handoff.redeemed", "sign_in_handoff", handoff.id.toString(), null, ip)
        return Redeemed(PortalPrincipal(account.id, account.role, account.displayName), handoff.nextPath)
    }
}

@RestController
@RequestMapping("/api/v1/auth")
class SignInHandoffApiController(private val handoffs: SignInHandoffService) {
    data class HandoffRequest(val next: String? = null)
    data class HandoffResponse(val url: String)

    @PostMapping("/handoff")
    fun create(@RequestBody(required = false) body: HandoffRequest?, authentication: Authentication?, request: HttpServletRequest) =
        HandoffResponse(handoffs.create(authentication.requirePrincipal(), body?.next, request.clientIp()))
}

@Controller
class SignInHandoffPageController(
    private val handoffs: SignInHandoffService,
    private val rememberMe: PortalRememberMeServices,
) {
    private val contextRepository = HttpSessionSecurityContextRepository()

    /**
     * Den Code einloesen und angemeldet weiterleiten.
     *
     * NUR, WENN DER BROWSER DIE ADRESSE SELBST OEFFNET. Die Workbench oeffnet
     * sie ueber das System, dann sagt der Browser `Sec-Fetch-Site: none`. Kaeme
     * der Aufruf von einer fremden Seite, koennte die ihren Besucher
     * unbemerkt in IHR Konto anmelden - der Code wird dann gar nicht erst
     * angefasst. Aeltere Browser schicken den Kopf nicht; die laufen durch.
     */
    @GetMapping("/signin/handoff")
    fun redeem(@RequestParam(required = false) code: String?, request: HttpServletRequest, response: HttpServletResponse): String {
        val site = request.getHeader("Sec-Fetch-Site")
        if (code.isNullOrBlank() || site == "cross-site" || site == "same-site") return "redirect:/signin.html?error=handoff"

        val redeemed = handoffs.redeem(code, request.clientIp()) ?: return "redirect:/signin.html?error=handoff"

        //  Neue Sitzungskennung, wie nach jeder Anmeldung - eine vorher
        //  untergeschobene Kennung taugt danach nichts mehr.
        if (request.getSession(false) != null) request.changeSessionId()

        val authentication = PortalAuthentication(redeemed.principal)
        val context = SecurityContextHolder.createEmptyContext().apply { this.authentication = authentication }
        SecurityContextHolder.setContext(context)
        contextRepository.saveContext(context, request, response)
        rememberMe.loginSuccess(request, response, authentication)

        //  Hatte eine geschuetzte Seite vorher ihre Adresse gemerkt, ist das
        //  jetzt erledigt - sonst stuende die Anmeldeseite spaeter noch bei
        //  "to open your account", obwohl man laengst drin ist.
        HttpSessionRequestCache().removeRequest(request, response)

        return "redirect:" + redeemed.next
    }
}
