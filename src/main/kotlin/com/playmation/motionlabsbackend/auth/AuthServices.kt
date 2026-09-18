package com.playmation.motionlabsbackend.auth

import com.playmation.motionlabsbackend.account.AccountRepository
import com.playmation.motionlabsbackend.account.AccountStatus
import com.playmation.motionlabsbackend.account.Role
import com.playmation.motionlabsbackend.common.Crypto
import com.playmation.motionlabsbackend.common.PortalException
import com.playmation.motionlabsbackend.common.RateLimiter
import com.playmation.motionlabsbackend.config.PortalProperties
import com.playmation.motionlabsbackend.system.AuditService
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Wer eine Anfrage stellt - egal ob per Discord-Session, Token oder Entwickler-Login. */
data class PortalPrincipal(val accountId: UUID, val role: Role, val displayName: String) {
    val isAdmin get() = role == Role.ADMIN

    fun authorities(): List<GrantedAuthority> =
        listOf(SimpleGrantedAuthority("ROLE_USER")) + if (isAdmin) listOf(SimpleGrantedAuthority("ROLE_ADMIN")) else emptyList()
}

class PortalAuthentication(val portalPrincipal: PortalPrincipal) :
    AbstractAuthenticationToken(portalPrincipal.authorities()) {
    init {
        isAuthenticated = true
    }

    override fun getCredentials(): Any? = null
    override fun getPrincipal(): Any = portalPrincipal
    override fun getName(): String = portalPrincipal.accountId.toString()
}

/** Discord-Nutzer nach dem Login: trägt das Konto mit, damit niemand erneut nachschlagen muss. */
class PortalOAuth2User(
    val portalPrincipal: PortalPrincipal,
    private val attributes: Map<String, Any>,
) : OAuth2User {
    override fun getAttributes() = attributes
    override fun getAuthorities() = portalPrincipal.authorities()
    override fun getName() = portalPrincipal.accountId.toString()
}

fun Authentication?.portalPrincipal(): PortalPrincipal? = when (val p = this?.principal) {
    is PortalPrincipal -> p
    is PortalOAuth2User -> p.portalPrincipal
    else -> null
}

fun Authentication?.requirePrincipal(): PortalPrincipal =
    portalPrincipal() ?: throw PortalException(HttpStatus.UNAUTHORIZED, "unauthorized", "Sign in first.")

@Service
class ApiTokenService(
    private val tokens: ApiTokenRepository,
    private val accounts: AccountRepository,
    private val properties: PortalProperties,
    private val clock: Clock,
) {
    companion object {
        const val PREFIX = "awc_"
    }

    data class IssuedToken(val token: String, val expiresAt: Instant)

    @Transactional
    fun issue(accountId: UUID, label: String): IssuedToken {
        val raw = PREFIX + Crypto.randomToken(32)
        val now = clock.instant()
        val expires = now.plus(Duration.ofDays(properties.tokens.editorTokenDays))
        tokens.save(ApiToken(accountId = accountId, tokenHash = Crypto.sha256Hex(raw), label = label.take(80), createdAt = now, expiresAt = expires))
        return IssuedToken(raw, expires)
    }

    /** null = Token ungültig, abgelaufen, widerrufen oder Konto gesperrt. */
    @Transactional
    fun authenticate(raw: String): PortalPrincipal? {
        if (!raw.startsWith(PREFIX) || raw.length > 128) return null
        val token = tokens.findByTokenHash(Crypto.sha256Hex(raw)) ?: return null
        val now = clock.instant()
        if (token.revokedAt != null || token.expiresAt.isBefore(now)) return null

        val account = accounts.findById(token.accountId).orElse(null) ?: return null
        if (account.status == AccountStatus.BANNED) return null

        //  Nur grob mitschreiben - ein Schreibzugriff pro Anfrage wäre teuer.
        if (token.lastUsedAt == null || Duration.between(token.lastUsedAt, now) > Duration.ofHours(1)) token.lastUsedAt = now

        return PortalPrincipal(account.id, account.role, account.displayName)
    }

    @Transactional
    fun revokeAll(accountId: UUID) {
        val now = clock.instant()
        tokens.findByAccountIdAndRevokedAtIsNull(accountId).forEach { it.revokedAt = now }
    }
}

/**
 * Device Flow für die Workbench (siehe [EditorLink]):
 * 1. Editor: [start] → Code + Prüf-URL + geheimes Poll-Geheimnis
 * 2. Nutzer öffnet die URL, meldet sich mit Discord an, bestätigt ([approve])
 * 3. Editor: [poll] mit dem Geheimnis → einmalig das Token
 */
@Service
class EditorLinkService(
    private val links: EditorLinkRepository,
    private val tokens: ApiTokenService,
    private val properties: PortalProperties,
    private val rateLimiter: RateLimiter,
    private val audit: AuditService,
    private val clock: Clock,
) {
    data class Started(val userCode: String, val verificationUrl: String, val pollSecret: String, val expiresInSeconds: Long, val intervalSeconds: Int)

    sealed class PollResult {
        object Pending : PollResult()
        data class Granted(val token: String, val expiresAt: Instant) : PollResult()
    }

    @Transactional
    fun start(ip: String): Started {
        rateLimiter.require("editor-link", ip, properties.limits.editorLinksPerHourPerIp, Duration.ofHours(1))

        val now = clock.instant()
        val minutes = properties.tokens.editorLinkMinutes
        val code = Crypto.randomCode(4) + "-" + Crypto.randomCode(4)
        val secret = Crypto.randomToken(32)

        links.save(EditorLink(userCode = code, pollSecretHash = Crypto.sha256Hex(secret), createdAt = now, expiresAt = now.plus(Duration.ofMinutes(minutes))))

        return Started(code, "${properties.publicBaseUrl.trimEnd('/')}/link.html?code=$code", secret, minutes * 60, 3)
    }

    @Transactional
    fun approve(principal: PortalPrincipal, userCode: String, ip: String) {
        val link = links.findByUserCode(userCode.trim().uppercase())
            ?: throw PortalException.notFound("Unknown code - check the code shown in the Workbench.")

        if (link.expiresAt.isBefore(clock.instant()) || link.consumedAt != null)
            throw PortalException(HttpStatus.GONE, "expired", "This code has expired. Start the sign-in again in the Workbench.")
        if (link.accountId != null && link.accountId != principal.accountId)
            throw PortalException.conflict("already-used", "This code was already confirmed by another account.")

        link.accountId = principal.accountId
        link.approvedAt = clock.instant()
        audit.record(principal.accountId, "editor.link.approved", "editor_link", link.id.toString(), null, ip)
    }

    @Transactional
    fun poll(pollSecret: String): PollResult {
        val link = links.findByPollSecretHash(Crypto.sha256Hex(pollSecret))
            ?: throw PortalException.notFound("Unknown sign-in request.")

        if (link.consumedAt != null || link.expiresAt.isBefore(clock.instant()))
            throw PortalException(HttpStatus.GONE, "expired", "This sign-in request has expired.")

        val accountId = link.accountId ?: return PollResult.Pending

        link.consumedAt = clock.instant()
        val issued = tokens.issue(accountId, "Animation Workbench")
        return PollResult.Granted(issued.token, issued.expiresAt)
    }
}
