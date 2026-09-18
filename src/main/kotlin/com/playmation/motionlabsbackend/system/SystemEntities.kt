package com.playmation.motionlabsbackend.system

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "system_setting")
class SystemSetting(
    @Id
    var settingKey: String,
    var settingValue: String,
    var updatedAt: Instant,
)

interface SystemSettingRepository : JpaRepository<SystemSetting, String>

/**
 * Append-only Protokoll über alles, was zählt (Konzept §4, "Beweismittel im
 * Ernstfall"). Nur `ipAddress` wird nach der Frist pseudonymisiert.
 */
@Entity
@Table(name = "audit_log")
class AuditEntry(
    @Id
    var id: UUID = UUID.randomUUID(),
    var actorId: UUID?,
    var action: String,
    var targetType: String?,
    var targetId: String?,
    var detail: String?,
    var ipAddress: String?,
    var ipPseudonymized: Boolean = false,
    var createdAt: Instant,
)

interface AuditEntryRepository : JpaRepository<AuditEntry, UUID> {
    fun findByIpPseudonymizedFalseAndCreatedAtBefore(before: Instant): List<AuditEntry>
    fun findTop200ByOrderByCreatedAtDesc(): List<AuditEntry>
}
