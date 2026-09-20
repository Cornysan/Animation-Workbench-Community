package com.playmation.motionlabsbackend.economy

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
 * Woher eine Buchung kommt. Steht als Text in der Zeile, damit das Protokoll
 * auch dann noch lesbar ist, wenn der Code die Bezeichnungen weiterentwickelt.
 */
object CoinReason {
    /** Grundausstattung bei der ersten Anmeldung. */
    const val GRANT = "grant"

    /** Ausgabe: ein fremder Clip wurde freigeschaltet. */
    const val UNLOCK = "unlock"

    /** Einnahme: ein eigener Clip wurde von jemand anderem freigeschaltet. */
    const val EARN = "earn"

    const val QUEST = "quest"
    const val MILESTONE = "milestone"

    /** Rueckbuchung, wenn ein Paket wegen einer Rechteverletzung verschwindet. */
    const val REVERSAL = "reversal"

    /** Von Hand, fuer Stuetzfaelle. */
    const val ADMIN = "admin"
}

/**
 * Anhaengend: keine Update- und keine Loeschpfade, wie bei moderation_action
 * und audit_log. Eine Korrektur ist eine neue Zeile mit umgekehrtem
 * Vorzeichen.
 *
 * [idempotencyKey] traegt den VORGANG, nicht den Zeitpunkt - derselbe Vorgang
 * bucht deshalb nie zweimal, egal wie oft er ankommt.
 */
@Entity
@Table(name = "coin_entry")
class CoinEntry(
    @Id
    var id: UUID = UUID.randomUUID(),
    var accountId: UUID,
    var amount: Long,
    var reason: String,
    var refType: String? = null,
    var refId: UUID? = null,
    var idempotencyKey: String,
    var createdAt: Instant,
)

/**
 * Die Quittung: wer welches Paket freigeschaltet hat. Der zusammengesetzte
 * Schluessel macht doppeltes Abbuchen unmoeglich, ohne dass der Code pruefen
 * muss - dasselbe Mittel wie bei `PackageLike`.
 *
 * Sie gilt fuer immer. Abbestellen und erneut abonnieren kostet nie ein
 * zweites Mal, und bringt dem Besitzer nie ein zweites Mal etwas.
 */
@Entity
@Table(name = "package_unlock")
@IdClass(PackageUnlockId::class)
class PackageUnlock(
    @Id var packageId: UUID = UUID.randomUUID(),
    @Id var accountId: UUID = UUID.randomUUID(),
    var costPaid: Long = 0,
    var earned: Boolean = false,
    var createdAt: Instant = Instant.EPOCH,
)

data class PackageUnlockId(
    var packageId: UUID = UUID.randomUUID(),
    var accountId: UUID = UUID.randomUUID(),
) : java.io.Serializable

/**
 * Eine Aufgabe je Konto und Zeitraum. [periodKey] trennt die Laeufe:
 * `weekly:2026-W38` fuer die Wochenaufgabe, `lifetime` fuer alles ohne Frist.
 */
@Entity
@Table(name = "quest_progress")
@IdClass(QuestProgressId::class)
class QuestProgress(
    @Id var accountId: UUID = UUID.randomUUID(),
    @Id var questKey: String = "",
    @Id var periodKey: String = "",
    var progress: Int = 0,
    var completedAt: Instant? = null,
)

data class QuestProgressId(
    var accountId: UUID = UUID.randomUUID(),
    var questKey: String = "",
    var periodKey: String = "",
) : java.io.Serializable

interface CoinEntryRepository : JpaRepository<CoinEntry, UUID> {
    fun existsByIdempotencyKey(idempotencyKey: String): Boolean
    fun findTop20ByAccountIdOrderByCreatedAtDesc(accountId: UUID): List<CoinEntry>

    @Query(
        "select coalesce(sum(e.amount), 0) from CoinEntry e " +
            "where e.accountId = :accountId and e.reason = :reason and e.createdAt > :after"
    )
    fun sumSince(
        @Param("accountId") accountId: UUID,
        @Param("reason") reason: String,
        @Param("after") after: Instant,
    ): Long

    /** Fuer die Rueckbuchung: alle Einnahmen, die an einem Paket haengen. */
    fun findByRefTypeAndRefIdAndReason(refType: String, refId: UUID, reason: String): List<CoinEntry>
}

interface PackageUnlockRepository : JpaRepository<PackageUnlock, PackageUnlockId> {
    fun existsByPackageIdAndAccountId(packageId: UUID, accountId: UUID): Boolean
    fun countByPackageId(packageId: UUID): Long

    @Query("select u.packageId from PackageUnlock u where u.accountId = :accountId and u.packageId in :packageIds")
    fun unlockedAmong(
        @Param("accountId") accountId: UUID,
        @Param("packageIds") packageIds: Collection<UUID>,
    ): List<UUID>

    /**
     * Wie oft die Clips eines Kontos insgesamt freigeschaltet wurden - die
     * Zahl, an der die Meilensteine haengen. Nur Freischaltungen, die dem
     * Besitzer auch zugerechnet wurden.
     */
    @Query(
        "select count(u) from PackageUnlock u, com.playmation.motionlabsbackend.catalog.AnimationPackage p " +
            "where u.packageId = p.id and p.ownerId = :ownerId and u.earned = true"
    )
    fun countEarnedForOwner(@Param("ownerId") ownerId: UUID): Long
}

interface QuestProgressRepository : JpaRepository<QuestProgress, QuestProgressId> {
    fun findByAccountIdAndQuestKeyAndPeriodKey(accountId: UUID, questKey: String, periodKey: String): QuestProgress?
}
