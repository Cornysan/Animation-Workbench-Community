package com.playmation.motionlabsbackend.showcase

import com.playmation.motionlabsbackend.catalog.PackageStatus
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

/**
 * Ein Clip oder Pack, der ins Discord-Schaufenster soll - erst Auftrag, nach
 * dem Posten Quittung. Siehe Schema 22 und [ShowcaseService].
 */
@Entity
@Table(name = "showcase_post")
class ShowcasePost(
    @Id
    var id: UUID = UUID.randomUUID(),

    /** Genau einer der beiden ist beim Anlegen gesetzt; beide null = Ziel weg. */
    var packageId: UUID? = null,
    var packId: UUID? = null,

    var accountId: UUID,
    var requestedAt: Instant,
    var postedAt: Instant? = null,
    var retractedAt: Instant? = null,
    var messageId: String? = null,
    var threadId: String? = null,
    var attempts: Int = 0,
    var outcome: String? = null,
)

interface ShowcasePostRepository : JpaRepository<ShowcasePost, UUID> {
    fun existsByPackageId(packageId: UUID): Boolean
    fun existsByPackId(packId: UUID): Boolean

    /** Was noch auf seinen Post wartet. */
    fun findByPostedAtIsNullAndOutcomeIsNullOrderByRequestedAtAsc(): List<ShowcasePost>

    /** Was Discord zeigt - fuer den Export eines Kontos. */
    fun findByAccountIdOrderByRequestedAtAsc(accountId: UUID): List<ShowcasePost>

    /**
     * Posts, deren Ziel niemand mehr sieht: zurueckgezogen, privat gestellt,
     * von der Moderation versteckt, oder ein Pack ohne sichtbaren Clip. EINE
     * Abfrage fuer alle Wege, auf denen ein Clip verschwindet - so muss keiner
     * dieser Wege vom Schaufenster wissen.
     */
    @Query(
        """
        select s from ShowcasePost s
        where s.postedAt is not null and s.retractedAt is null and (
            (s.packageId is null and s.packId is null)
            or (s.packageId is not null and not exists (
                select 1 from AnimationPackage p
                where p.id = s.packageId and p.status = :published and p.license = :license))
            or (s.packId is not null and not exists (
                select 1 from AnimationPackage c
                where c.packId = s.packId and c.status = :published and c.license = :license))
        )
        """
    )
    fun findGone(
        @Param("published") published: PackageStatus,
        @Param("license") license: String,
    ): List<ShowcasePost>
}
