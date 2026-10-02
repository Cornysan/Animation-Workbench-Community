package com.playmation.motionlabsbackend.messages

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

/**
 * Ein Gespraech zwischen genau zwei Konten. Die Regeln stehen in
 * V23__direct_messages.sql; hier nur, wie man sie liest.
 *
 * Beide Seiten tragen ihren Stand in EIGENEN Spalten (`readA`/`readB`,
 * `clearedA`/`clearedB`) statt in einer Teilnehmertabelle: es sind immer genau
 * zwei, und "meine Gespraeche" ist so eine Abfrage ohne Join. Die Helfer
 * unten uebersetzen von "ich" auf a oder b.
 *
 * Die Spaltennamen stehen ausdruecklich da: Springs Namensregel setzt einen
 * Unterstrich nur vor einen Grossbuchstaben, auf den ein kleiner folgt - aus
 * `accountA` wuerde `accounta`.
 */
@Entity
@Table(name = "conversation")
class Conversation(
    @Id
    var id: UUID = UUID.randomUUID(),
    /** Die kleinere der beiden Kennungen - siehe [MessageService.pair]. */
    @Column(name = "account_a") var accountA: UUID,
    @Column(name = "account_b") var accountB: UUID,
    var startedBy: UUID,
    /** Leer = noch eine Anfrage. */
    var acceptedAt: Instant? = null,
    /** Die angefragte Seite hat abgelehnt. Die Anfrage verschwindet bei ihr; die andere Seite erfaehrt es nicht. */
    var declinedAt: Instant? = null,
    var createdAt: Instant,
    var lastMessageAt: Instant,
    var lastSenderId: UUID,
    @Column(name = "read_a") var readA: Instant? = null,
    @Column(name = "read_b") var readB: Instant? = null,
    @Column(name = "cleared_a") var clearedA: Instant? = null,
    @Column(name = "cleared_b") var clearedB: Instant? = null,
) {
    fun involves(accountId: UUID) = accountId == accountA || accountId == accountB

    fun other(accountId: UUID): UUID = if (accountId == accountA) accountB else accountA

    val accepted: Boolean get() = acceptedAt != null

    /** Die angefragte Seite - nur sinnvoll, solange [accepted] false ist. */
    val requested: UUID get() = other(startedBy)

    fun readAt(accountId: UUID) = if (accountId == accountA) readA else readB

    fun clearedAt(accountId: UUID) = if (accountId == accountA) clearedA else clearedB

    fun markRead(accountId: UUID, at: Instant) {
        if (accountId == accountA) readA = at else readB = at
    }

    fun markCleared(accountId: UUID, at: Instant) {
        if (accountId == accountA) clearedA = at else clearedB = at
    }
}

@Entity
@Table(name = "direct_message")
class DirectMessage(
    @Id
    var id: UUID = UUID.randomUUID(),
    var conversationId: UUID,
    var senderId: UUID,
    var body: String,
    var createdAt: Instant,
)

/**
 * Wer wen blockiert hat. Wirkt in BEIDE Richtungen (siehe V23), gespeichert
 * wird trotzdem nur die eine - aufheben darf nur, wer blockiert hat.
 */
@Entity
@Table(name = "account_block")
@IdClass(AccountBlockId::class)
class AccountBlock(
    @Id var blockerId: UUID = UUID.randomUUID(),
    @Id var blockedId: UUID = UUID.randomUUID(),
    var createdAt: Instant = Instant.EPOCH,
)

data class AccountBlockId(
    var blockerId: UUID = UUID.randomUUID(),
    var blockedId: UUID = UUID.randomUUID(),
) : java.io.Serializable

interface ConversationRepository : JpaRepository<Conversation, UUID> {
    fun findByAccountAAndAccountB(accountA: UUID, accountB: UUID): Conversation?

    @Query("select c from Conversation c where c.accountA = :id or c.accountB = :id order by c.lastMessageAt desc")
    fun findAllOf(@Param("id") accountId: UUID): List<Conversation>

    /**
     * Gespraeche mit Neuem fuer dieses Konto - die Zahl am Briefsymbol im Kopf
     * JEDER Seite, deshalb eine Zaehlabfrage und keine geladene Liste.
     *
     * Neu heisst: die letzte Nachricht kam von der anderen Seite, nach dem
     * eigenen Lesen und nach dem eigenen Leeren. Nicht mit: eine abgelehnte
     * Anfrage (ohne Annahme kann nur die anfragende Seite geschrieben haben,
     * also ist "letzte von der anderen" hier immer die Anfrage an mich) und
     * alles von jemandem, den man blockiert hat.
     */
    @Query("""
        select count(c) from Conversation c
        where c.lastSenderId <> :id
          and ((c.accountA = :id and (c.readA is null or c.readA < c.lastMessageAt)
                                 and (c.clearedA is null or c.clearedA < c.lastMessageAt))
            or (c.accountB = :id and (c.readB is null or c.readB < c.lastMessageAt)
                                 and (c.clearedB is null or c.clearedB < c.lastMessageAt)))
          and not (c.acceptedAt is null and c.declinedAt is not null)
          and not exists (select 1 from AccountBlock b where b.blockerId = :id and b.blockedId = c.lastSenderId)
    """)
    fun countUnread(@Param("id") accountId: UUID): Long
}

interface DirectMessageRepository : JpaRepository<DirectMessage, UUID> {
    /** Neueste zuerst - die Seite dreht die Reihenfolge selbst um. */
    fun findByConversationIdAndCreatedAtAfterOrderByCreatedAtDesc(
        conversationId: UUID, after: Instant, pageable: Pageable): List<DirectMessage>

    fun findByConversationIdAndCreatedAtAfterAndCreatedAtBeforeOrderByCreatedAtDesc(
        conversationId: UUID, after: Instant, before: Instant, pageable: Pageable): List<DirectMessage>

    fun findFirstByConversationIdOrderByCreatedAtDesc(conversationId: UUID): DirectMessage?

    fun countByConversationIdAndSenderId(conversationId: UUID, senderId: UUID): Long

    /** Fuer den Datenexport: alles, was zu diesem Gespraech noch gespeichert ist. */
    fun findByConversationIdOrderByCreatedAtAsc(conversationId: UUID): List<DirectMessage>

    fun deleteByConversationId(conversationId: UUID): Long
}

interface AccountBlockRepository : JpaRepository<AccountBlock, AccountBlockId> {
    fun existsByBlockerIdAndBlockedId(blockerId: UUID, blockedId: UUID): Boolean
    fun deleteByBlockerIdAndBlockedId(blockerId: UUID, blockedId: UUID): Long
    fun findByBlockerIdOrderByCreatedAtDesc(blockerId: UUID): List<AccountBlock>
    fun findByBlockedId(blockedId: UUID): List<AccountBlock>

    /** Wer von diesen hat [blockedId] blockiert? Fuer Nachrichten an viele (Notifier). */
    @Query("select b.blockerId from AccountBlock b where b.blockedId = :blockedId and b.blockerId in :candidates")
    fun blockersAmong(@Param("blockedId") blockedId: UUID, @Param("candidates") candidates: Collection<UUID>): List<UUID>

    /** Und umgekehrt: wen von diesen hat [blockerId] blockiert? */
    @Query("select b.blockedId from AccountBlock b where b.blockerId = :blockerId and b.blockedId in :candidates")
    fun blockedAmong(@Param("blockerId") blockerId: UUID, @Param("candidates") candidates: Collection<UUID>): List<UUID>
}
