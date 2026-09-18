package com.playmation.motionlabsbackend.auth

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

/**
 * Zugangstoken für die Animation Workbench. Gespeichert wird nur der
 * SHA-256 des Tokens - ein Datenbank-Leck liefert keine benutzbaren Tokens.
 */
@Entity
@Table(name = "api_token")
class ApiToken(
    @Id
    var id: UUID = UUID.randomUUID(),
    var accountId: UUID,
    var tokenHash: String,
    var label: String,
    var createdAt: Instant,
    var expiresAt: Instant,
    var lastUsedAt: Instant? = null,
    var revokedAt: Instant? = null,
)

interface ApiTokenRepository : JpaRepository<ApiToken, UUID> {
    fun findByTokenHash(tokenHash: String): ApiToken?
    fun findByAccountIdAndRevokedAtIsNull(accountId: UUID): List<ApiToken>
}

/**
 * Verknüpfung Editor ↔ Konto nach dem Muster des OAuth Device Flow: der
 * Editor zeigt einen kurzen Code, der Nutzer bestätigt ihn im Browser, der
 * Editor holt sich danach mit seinem Geheimnis das Token ab. So braucht Unity
 * weder einen lokalen Webserver noch das Discord-Passwort.
 */
@Entity
@Table(name = "editor_link")
class EditorLink(
    @Id
    var id: UUID = UUID.randomUUID(),
    var userCode: String,
    var pollSecretHash: String,
    var accountId: UUID? = null,
    var createdAt: Instant,
    var expiresAt: Instant,
    var approvedAt: Instant? = null,
    var consumedAt: Instant? = null,
)

interface EditorLinkRepository : JpaRepository<EditorLink, UUID> {
    fun findByUserCode(userCode: String): EditorLink?
    fun findByPollSecretHash(pollSecretHash: String): EditorLink?
}
