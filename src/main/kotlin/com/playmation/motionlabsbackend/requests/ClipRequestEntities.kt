package com.playmation.motionlabsbackend.requests

import com.playmation.motionlabsbackend.account.AccountStatus
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

/**
 * Ein Clip-Wunsch im Discord-Forum - die Quittung des Posts, angelegt erst,
 * wenn Discord ihn angenommen hat. Siehe Schema 24 und [ClipRequestService].
 */
@Entity
@Table(name = "clip_request")
class ClipRequest(
    @Id
    var id: UUID = UUID.randomUUID(),

    var accountId: UUID,

    /** Der Suchtext, wie er im Post steht ([ClipRequestService.normalize]). */
    var phrase: String,

    /** Derselbe in Kleinbuchstaben - fuer "schon gewuenscht". */
    var phraseKey: String,

    /** Name und Bild des Kontos im Post - das Haekchen beim Fragen. */
    var named: Boolean,

    var askedAt: Instant,
    var messageId: String,
    var threadId: String? = null,

    /** "Take it back" - geloescht wird sofort, und scheitert das, im Takt. */
    var retractWanted: Boolean = false,
    var retractedAt: Instant? = null,
    var attempts: Int = 0,
    var outcome: String? = null,
)

interface ClipRequestRepository : JpaRepository<ClipRequest, UUID> {

    /** Der juengste offene Wunsch zu diesem Suchtext. */
    fun findFirstByPhraseKeyAndAskedAtAfterAndRetractWantedFalseAndRetractedAtIsNullOrderByAskedAtDesc(
        phraseKey: String, since: Instant,
    ): ClipRequest?

    /** Fuer das Tageslimit - gezaehlt in der Datenbank, nicht im Speicher. */
    fun countByAccountIdAndAskedAtAfter(accountId: UUID, since: Instant): Long

    /** Was ein Konto gewuenscht hat - fuer den Export. */
    fun findByAccountIdOrderByAskedAtAsc(accountId: UUID): List<ClipRequest>

    /**
     * Posts, die weg sollen: zurueckgenommen, oder ihr Konto ist gesperrt
     * oder geschlossen (beides steht als [AccountStatus.BANNED]). EINE
     * Abfrage, damit weder Moderation noch Kontoloeschung von den Wuenschen
     * wissen muessen - wie [com.playmation.motionlabsbackend.showcase.ShowcasePostRepository.findGone].
     */
    @Query(
        """
        select r from ClipRequest r
        where r.retractedAt is null and (
            r.retractWanted = true
            or exists (select 1 from Account a where a.id = r.accountId and a.status = :banned)
        )
        """
    )
    fun findToRetract(@Param("banned") banned: AccountStatus): List<ClipRequest>
}
