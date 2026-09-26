package com.playmation.motionlabsbackend.catalog

import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

/**
 * VISIBLE     steht in der Liste
 * AUTO_HIDDEN eine Meldung ging ein - sofort unsichtbar, ohne dass ein Mensch
 *             beteiligt war. Dieselbe Regel wie bei Paketen: im Solobetrieb
 *             ist "es ist längst unten" mehr wert als "ich war schnell".
 * REMOVED     vom Verfasser oder von der Moderation entfernt.
 *
 * Kein Zustand löscht die Zeile. Wer später fragt, was da stand, bekommt eine
 * Antwort.
 */
enum class CommentStatus { VISIBLE, AUTO_HIDDEN, REMOVED }

@Entity
@Table(name = "package_comment")
class PackageComment(
    @Id
    var id: UUID = UUID.randomUUID(),
    var packageId: UUID,
    var accountId: UUID,
    var body: String,

    @Enumerated(EnumType.STRING)
    var status: CommentStatus = CommentStatus.VISIBLE,

    var createdAt: Instant,
    var editedAt: Instant? = null,
    var removedBy: UUID? = null,
    var removedReason: String? = null,
)

interface PackageCommentRepository : JpaRepository<PackageComment, UUID> {
    fun findByPackageIdAndStatusOrderByCreatedAtAsc(
        packageId: UUID,
        status: CommentStatus,
        pageable: Pageable,
    ): Page<PackageComment>

    fun countByPackageIdAndStatus(packageId: UUID, status: CommentStatus): Long

    /** Wie oft sich jemand am Gespraech beteiligt hat - eine Zahl fuers Profil. */
    fun countByAccountIdAndStatus(accountId: UUID, status: CommentStatus): Long

    /** Wer unter diesem Clip schon geschrieben hat - fuer "also commented". */
    @Query("select distinct c.accountId from PackageComment c where c.packageId = :packageId and c.status = :status")
    fun participantsOf(packageId: UUID, status: CommentStatus): List<UUID>
}
