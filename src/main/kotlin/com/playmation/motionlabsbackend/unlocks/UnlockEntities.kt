package com.playmation.motionlabsbackend.unlocks

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
 * Die Quittung: wer welches Paket geholt hat. Der zusammengesetzte Schluessel
 * macht eine zweite Zeile unmoeglich, ohne dass der Code pruefen muss -
 * dasselbe Mittel wie bei `PackageLike`.
 *
 * Sie gilt fuer immer. Abbestellen und erneut abonnieren zaehlt nie ein
 * zweites Mal.
 *
 * [costPaid] ist der Rest der entfernten Muenzwirtschaft und steht immer auf
 * 0. Die Spalte ist `not null` ohne Vorgabe, also muss das Feld sie weiter
 * schreiben - die Tabelle bleibt dafuer unangetastet.
 */
@Entity
@Table(name = "package_unlock")
@IdClass(PackageUnlockId::class)
class PackageUnlock(
    @Id var packageId: UUID = UUID.randomUUID(),
    @Id var accountId: UUID = UUID.randomUUID(),
    var costPaid: Long = 0,

    /**
     * true = der Clip stand im Katalog, die Zeile zaehlt fuer den Besitzer.
     * Sie traegt damit die Auszeichnung "In use", und private Clips und
     * Selbstabrufe blaehen sie nicht auf.
     */
    var earned: Boolean = false,
    var createdAt: Instant = Instant.EPOCH,
)

data class PackageUnlockId(
    var packageId: UUID = UUID.randomUUID(),
    var accountId: UUID = UUID.randomUUID(),
) : java.io.Serializable

interface PackageUnlockRepository : JpaRepository<PackageUnlock, PackageUnlockId> {
    fun existsByPackageIdAndAccountId(packageId: UUID, accountId: UUID): Boolean
    fun countByPackageId(packageId: UUID): Long

    /**
     * Wie viele Clips dieses Konto geholt hat - die Auszeichnung "Collector".
     *
     * Ohne Filter, und das ist kein Versehen: am eigenen Clip entsteht nie
     * eine Zeile (Regel 1), und ob der geholte Clip oeffentlich stand, geht
     * den an, der ihn geteilt hat - nicht den, der ihn benutzt.
     */
    fun countByAccountId(accountId: UUID): Long

    /** Was ein Konto geholt hat - fuer den Datenexport. */
    fun findByAccountIdOrderByCreatedAtAsc(accountId: UUID): List<PackageUnlock>

    @Query("select u.packageId from PackageUnlock u where u.accountId = :accountId and u.packageId in :packageIds")
    fun unlockedAmong(
        @Param("accountId") accountId: UUID,
        @Param("packageIds") packageIds: Collection<UUID>,
    ): List<UUID>

    /**
     * Wie oft die Clips eines Kontos insgesamt geholt wurden - die Zahl, an
     * der die Auszeichnungen haengen. Nur Abrufe, die dem Besitzer auch
     * zugerechnet wurden.
     */
    @Query(
        "select count(u) from PackageUnlock u, com.playmation.motionlabsbackend.catalog.AnimationPackage p " +
            "where u.packageId = p.id and p.ownerId = :ownerId and u.earned = true"
    )
    fun countEarnedForOwner(@Param("ownerId") ownerId: UUID): Long
}
