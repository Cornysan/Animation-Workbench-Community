package com.playmation.motionlabsbackend.account

import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

enum class Role { USER, ADMIN }

/**
 * ACTIVE darf alles, RESTRICTED lädt mit engerem Limit hoch (nach einem
 * bestätigten Verstoß), BANNED kommt nicht mehr herein.
 */
enum class AccountStatus { ACTIVE, RESTRICTED, BANNED }

@Entity
@Table(name = "account")
class Account(
    @Id
    var id: UUID = UUID.randomUUID(),

    /** Discord-Nutzer-ID; beim Entwickler-Login "dev:<name>". */
    var discordId: String,

    var displayName: String,

    @Enumerated(EnumType.STRING)
    var role: Role = Role.USER,

    @Enumerated(EnumType.STRING)
    var status: AccountStatus = AccountStatus.ACTIVE,

    /** Bestätigte Verstöße (entfernte Uploads). */
    var strikes: Int = 0,

    /** Als unbegründet abgewiesene Meldungen - schützt Auto-Hide vor Griefing. */
    var falseReports: Int = 0,

    var createdAt: Instant = Instant.now(),
    var lastLoginAt: Instant? = null,
)

interface AccountRepository : JpaRepository<Account, UUID> {
    fun findByDiscordId(discordId: String): Account?
}
