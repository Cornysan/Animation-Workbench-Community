package com.playmation.motionlabsbackend.moderation

import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

enum class ReportCategory { COPYRIGHT, INAPPROPRIATE, BROKEN, OTHER }

enum class CaseStatus { OPEN, UPHELD, DISMISSED }

@Entity
@Table(name = "report")
class Report(
    @Id
    var id: UUID = UUID.randomUUID(),

    /**
     * Das gemeldete Paket - leer, wenn die Meldung einem KONTO gilt. Bei
     * Kommentarmeldungen bleibt es gefuellt, damit der Fall am richtigen Clip
     * haengt.
     */
    var packageId: UUID? = null,

    /**
     * Gesetzt, wenn die Meldung einem Kommentar gilt. Dann versteckt sie den
     * Kommentar statt des Pakets - `packageId` bleibt trotzdem gefuellt, damit
     * der Fall in der Fallliste am richtigen Clip haengt.
     */
    var commentId: UUID? = null,

    /**
     * Gesetzt, wenn die Meldung einem KONTO gilt - "diese Person, nicht dieser
     * Clip". Dann versteckt sie NICHTS: ein Konto zu verstecken waere die
     * Fernbedienung zum Stummschalten jedes Erstellers. Sie legt einen offenen
     * Fall an, und ein Mensch entscheidet.
     */
    var accountId: UUID? = null,

    var reporterId: UUID,
    @Enumerated(EnumType.STRING)
    var category: ReportCategory,
    var message: String,
    @Enumerated(EnumType.STRING)
    var status: CaseStatus = CaseStatus.OPEN,
    var createdAt: Instant,
    var resolvedAt: Instant? = null,
    var resolvedBy: UUID? = null,
    var resolutionNote: String? = null,
)

/**
 * Eigener Kanal für Rechteinhaber (Konzept §6): öffentlich, ohne Konto.
 * Die Erklärungen zu gutem Glauben und Richtigkeit sind Pflicht.
 */
@Entity
@Table(name = "takedown_request")
class TakedownRequest(
    @Id
    var id: UUID = UUID.randomUUID(),
    var contactName: String,
    var contactEmail: String,
    var rightsHolder: String,
    var claimedWork: String,
    /** Komma-getrennte Slugs der betroffenen Pakete. */
    var packageSlugs: String,
    var goodFaith: Boolean,
    var accurate: Boolean,
    var ipAddress: String?,
    var ipPseudonymized: Boolean = false,
    @Enumerated(EnumType.STRING)
    var status: CaseStatus = CaseStatus.OPEN,
    var createdAt: Instant,
    var resolvedAt: Instant? = null,
    var resolvedBy: UUID? = null,
    var resolutionNote: String? = null,
) {
    fun slugs(): List<String> = packageSlugs.split(',').map { it.trim() }.filter { it.isNotEmpty() }
}

/** Append-only. `actorId` null = das System selbst (Auto-Hide). */
@Entity
@Table(name = "moderation_action")
class ModerationAction(
    @Id
    var id: UUID = UUID.randomUUID(),
    var actorId: UUID?,
    var action: String,
    var targetType: String,
    var targetId: UUID,
    var reason: String?,
    var createdAt: Instant,
)

@Entity
@Table(name = "notification")
class Notification(
    @Id
    var id: UUID = UUID.randomUUID(),
    var accountId: UUID,
    var message: String,
    var createdAt: Instant,
    var readAt: Instant? = null,
)

interface ReportRepository : JpaRepository<Report, UUID> {
    fun findByStatusOrderByCreatedAtAsc(status: CaseStatus): List<Report>

    /**
     * Nur Meldungen gegen das Paket selbst. Wiederherstellen und Entfernen
     * eines Pakets duerfen Kommentarfaelle nicht mit abraeumen - die haben
     * ihren eigenen Ausgang.
     */
    fun findByPackageIdAndStatusAndCommentIdIsNull(packageId: UUID, status: CaseStatus): List<Report>
    fun existsByPackageIdAndReporterIdAndStatusAndCommentIdIsNull(packageId: UUID, reporterId: UUID, status: CaseStatus): Boolean

    fun findByCommentIdAndStatus(commentId: UUID, status: CaseStatus): List<Report>
    fun existsByCommentIdAndReporterIdAndStatus(commentId: UUID, reporterId: UUID, status: CaseStatus): Boolean

    /** Meldungen gegen ein KONTO - sie haengen an keinem Paket. */
    fun existsByAccountIdAndReporterIdAndStatus(accountId: UUID, reporterId: UUID, status: CaseStatus): Boolean
}

interface TakedownRequestRepository : JpaRepository<TakedownRequest, UUID> {
    fun findByStatusOrderByCreatedAtAsc(status: CaseStatus): List<TakedownRequest>
    fun findByIpPseudonymizedFalseAndCreatedAtBefore(before: Instant): List<TakedownRequest>
}

interface ModerationActionRepository : JpaRepository<ModerationAction, UUID> {
    fun findByTargetIdOrderByCreatedAtAsc(targetId: UUID): List<ModerationAction>
}

interface NotificationRepository : JpaRepository<Notification, UUID> {
    fun findByAccountIdOrderByCreatedAtDesc(accountId: UUID): List<Notification>
    fun countByAccountIdAndReadAtIsNull(accountId: UUID): Long
}
