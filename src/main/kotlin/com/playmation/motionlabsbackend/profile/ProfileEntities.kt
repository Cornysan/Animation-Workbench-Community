package com.playmation.motionlabsbackend.profile

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

/**
 * Wer wem folgt. Ein zusammengesetzter Schluessel aus beiden Seiten - wie
 * `PackageLike`: doppeltes Folgen ist damit unmoeglich, ohne dass der Code
 * vorher nachsieht.
 */
@Entity
@Table(name = "account_follow")
@IdClass(AccountFollowId::class)
class AccountFollow(
    @Id var followerId: UUID = UUID.randomUUID(),
    @Id var followeeId: UUID = UUID.randomUUID(),
    var createdAt: Instant = Instant.EPOCH,
)

data class AccountFollowId(
    var followerId: UUID = UUID.randomUUID(),
    var followeeId: UUID = UUID.randomUUID(),
) : java.io.Serializable

interface AccountFollowRepository : JpaRepository<AccountFollow, AccountFollowId> {
    fun existsByFollowerIdAndFolloweeId(followerId: UUID, followeeId: UUID): Boolean
    fun deleteByFollowerIdAndFolloweeId(followerId: UUID, followeeId: UUID): Long
    fun countByFolloweeId(followeeId: UUID): Long
    fun countByFollowerId(followerId: UUID): Long

    fun findByFolloweeIdOrderByCreatedAtDesc(followeeId: UUID): List<AccountFollow>
    fun findByFollowerIdOrderByCreatedAtDesc(followerId: UUID): List<AccountFollow>

    /**
     * Wer diesem Konto folgt - nur die Kennungen, fuer die
     * Benachrichtigung an alle Follower, wenn ein neuer Clip erscheint.
     */
    @Query("select f.followerId from AccountFollow f where f.followeeId = :followeeId")
    fun followerIdsOf(@Param("followeeId") followeeId: UUID): List<UUID>
}
