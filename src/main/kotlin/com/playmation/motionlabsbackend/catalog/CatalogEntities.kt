package com.playmation.motionlabsbackend.catalog

import com.playmation.motionlabsbackend.format.AwclipSchema
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

/**
 * Statusmodell nach Konzept §5, verdichtet:
 *
 *   PUBLISHED   sichtbar
 *   AUTO_HIDDEN eine Meldung oder ein Takedown ging ein - sofort unsichtbar,
 *               ohne dass ein Mensch beteiligt war. Der offene Fall IST das
 *               "UnderReview" des Konzepts.
 *   REMOVED     von der Moderation entfernt. Status, kein Löschen: Hash und
 *               Datei bleiben, damit derselbe Clip nicht wieder hochkommt.
 *   WITHDRAWN   vom Besitzer zurückgezogen.
 */
enum class PackageStatus { PUBLISHED, AUTO_HIDDEN, REMOVED, WITHDRAWN }

enum class VersionStatus { PUBLISHED, REMOVED }

@Entity
@Table(name = "animation_package")
class AnimationPackage(
    @Id
    var id: UUID = UUID.randomUUID(),

    /** Kurze öffentliche ID für Links. */
    var slug: String,

    var ownerId: UUID,
    var title: String,
    var description: String,

    /** Komma-getrennt, mit führendem und schließendem Komma - so findet LIKE '%,walk,%' ganze Tags. */
    var tags: String,

    var license: String,

    @Enumerated(EnumType.STRING)
    var status: PackageStatus = PackageStatus.PUBLISHED,

    var currentVersionId: UUID? = null,

    /**
     * Wie oft der Clip in ein Projekt übernommen wurde - NICHT, wie oft die
     * Datei abgerufen wurde. Die Workbench lädt zum Stöbern herunter; ein
     * Zähler am Dateiabruf wäre ein Maß fürs Scrollen.
     */
    var takeCount: Long = 0,

    var likeCount: Long = 0,

    /**
     * Wie viele PERSONEN diesen Clip in einer Sammlung haben - der Stern an
     * der Karte. Gezaehlt werden Besitzer, nicht Zeilen: wer denselben Clip in
     * drei eigene Sammlungen legt, bleibt eine Person.
     */
    var saveCount: Long = 0,

    /** Sichtbare Kommentare. Abgeleitet wie [likeCount], neu gezaehlt statt fortgeschrieben. */
    var commentCount: Long = 0,

    /**
     * Woher ein Starter-Clip stammt ("Quaternius - Universal Animation
     * Library"), samt Link. Nur bei Clips, die ein Admin eingespielt hat
     * ([StarterClips]) - ein Nutzer kann das Feld nicht setzen, also kann auch
     * niemand eine fremde Quelle vortaeuschen.
     */
    var sourceCredit: String? = null,
    var sourceUrl: String? = null,
    var createdAt: Instant,
    var updatedAt: Instant,
) {
    fun tagList(): List<String> = tags.split(',').filter { it.isNotEmpty() }

    companion object {
        fun joinTags(tags: List<String>) = if (tags.isEmpty()) "," else tags.joinToString(",", ",", ",")
    }
}

@Entity
@Table(name = "package_version")
class PackageVersion(
    @Id
    var id: UUID = UUID.randomUUID(),
    var packageId: UUID,
    var versionNumber: Int,
    var contentHash: String,
    var blobKey: String,
    var previewBlobKey: String? = null,
    var sizeBytes: Long,
    var frameRate: Float,
    var durationSeconds: Float,
    var curveCount: Int,
    var originClass: String,

    /**
     * Das Rig der Fassung - `humanoid` oder `generic`.
     *
     * Es stand bis zur Öffnung für generische Clips nirgends, weil es nur
     * einen Wert gab und der Viewer ihn raten konnte. Jetzt entscheidet er
     * daran, OB die Figur überhaupt auftritt: ein generischer Clip läuft auf
     * seinem eigenen Skelett, nicht auf dem Mannequin.
     */
    var rig: String = AwclipSchema.RIG_HUMANOID,

    @Enumerated(EnumType.STRING)
    var status: VersionStatus = VersionStatus.PUBLISHED,

    var createdAt: Instant,
)

/**
 * Unveränderlich: wer, wann, von wo, welcher Wortlaut in welcher Fassung,
 * welche Lizenz (Konzept §4). Entscheidend ist das Protokoll, nicht die Checkbox.
 */
@Entity
@Table(name = "upload_declaration")
class UploadDeclaration(
    @Id
    var id: UUID = UUID.randomUUID(),
    var accountId: UUID,
    var versionId: UUID,
    var declarationText: String,
    var declarationVersion: Int,
    var license: String,
    var originClass: String,
    var ipAddress: String?,
    var ipPseudonymized: Boolean = false,
    var createdAt: Instant,
)

interface AnimationPackageRepository : JpaRepository<AnimationPackage, UUID>, JpaSpecificationExecutor<AnimationPackage> {
    fun findBySlug(slug: String): AnimationPackage?
    fun findByOwnerIdOrderByCreatedAtDesc(ownerId: UUID): List<AnimationPackage>

    /** Alles, was oeffentlich im Katalog steht - fuer [CatalogOverviewService]. */
    fun findAllByStatusAndLicense(status: PackageStatus, license: String): List<AnimationPackage>

    /** Was auf einem Profil zaehlt: veroeffentlicht UND oeffentlich geteilt. */
    fun countByOwnerIdAndStatusAndLicense(ownerId: UUID, status: PackageStatus, license: String): Long

    /**
     * Herzen, Uebernahmen und Sternchen ueber alle Clips eines Kontos - die
     * Zahlen im Kopf des Profils und unter seinen Auszeichnungen. Drei
     * schlichte Abfragen statt einer mit drei Spalten: die zusammengesetzte
     * braeuchte eine Projektion, und die waere hier mehr Erklaerung als
     * Ersparnis.
     */
    @Query(
        "select coalesce(sum(p.likeCount), 0) from AnimationPackage p " +
            "where p.ownerId = :ownerId and p.status = :status and p.license = :license"
    )
    fun sumLikesForOwner(
        @Param("ownerId") ownerId: UUID,
        @Param("status") status: PackageStatus,
        @Param("license") license: String,
    ): Long

    @Query(
        "select coalesce(sum(p.takeCount), 0) from AnimationPackage p " +
            "where p.ownerId = :ownerId and p.status = :status and p.license = :license"
    )
    fun sumTakesForOwner(
        @Param("ownerId") ownerId: UUID,
        @Param("status") status: PackageStatus,
        @Param("license") license: String,
    ): Long

    /**
     * Wie oft Clips dieses Kontos in fremden Sammlungen liegen - die
     * Auszeichnung "Kept". Gezaehlt werden Personen je Clip, nicht Zeilen;
     * das steht schon in [AnimationPackage.saveCount] und gilt hier weiter.
     */
    @Query(
        "select coalesce(sum(p.saveCount), 0) from AnimationPackage p " +
            "where p.ownerId = :ownerId and p.status = :status and p.license = :license"
    )
    fun sumSavesForOwner(
        @Param("ownerId") ownerId: UUID,
        @Param("status") status: PackageStatus,
        @Param("license") license: String,
    ): Long

    /** Der neueste oeffentliche Clip - das Bild der Profil-Linkvorschau. */
    fun findFirstByOwnerIdAndStatusAndLicenseOrderByCreatedAtDesc(
        ownerId: UUID,
        status: PackageStatus,
        license: String,
    ): AnimationPackage?
}

/**
 * Ein Herz je Konto und Paket. Der zusammengesetzte Schlüssel macht das
 * doppelte Mögen unmöglich, ohne dass der Code prüfen muss.
 */
@Entity
@Table(name = "package_like")
@IdClass(PackageLikeId::class)
class PackageLike(
    @Id var packageId: UUID = UUID.randomUUID(),
    @Id var accountId: UUID = UUID.randomUUID(),
    var createdAt: Instant = Instant.EPOCH,
)

data class PackageLikeId(
    var packageId: UUID = UUID.randomUUID(),
    var accountId: UUID = UUID.randomUUID(),
) : java.io.Serializable

interface PackageLikeRepository : JpaRepository<PackageLike, PackageLikeId> {
    fun existsByPackageIdAndAccountId(packageId: UUID, accountId: UUID): Boolean

    /** Alle Herzen eines Kontos - beim Schliessen des Kontos. */
    fun findByAccountId(accountId: UUID): List<PackageLike>
    fun deleteByPackageIdAndAccountId(packageId: UUID, accountId: UUID): Long
    fun countByPackageId(packageId: UUID): Long

    /**
     * Wie viele Herzen dieses Konto FREMDEN Clips gegeben hat - die
     * Auszeichnung "Supporter".
     *
     * Das eigene Werk zaehlt nicht. Ein Herz am eigenen Clip ist kein
     * Zuspruch, und zehn davon waeren in einer Minute geklickt - dieselbe
     * Ueberlegung wie Regel 1 in `UnlockService`, nur dass sie dort schon
     * beim Schreiben greift und hier erst beim Zaehlen: das Herz am eigenen
     * Clip darf gesetzt werden, es traegt nur keine Auszeichnung.
     */
    @Query(
        "select count(l) from PackageLike l, AnimationPackage p " +
            "where l.packageId = p.id and l.accountId = :accountId and p.ownerId <> :accountId"
    )
    fun countGivenBy(@Param("accountId") accountId: UUID): Long

    @Query("select l.packageId from PackageLike l where l.accountId = :accountId and l.packageId in :packageIds")
    fun likedAmong(
        @Param("accountId") accountId: UUID,
        @Param("packageIds") packageIds: Collection<UUID>,
    ): List<UUID>
}

interface PackageVersionRepository : JpaRepository<PackageVersion, UUID> {
    fun findByContentHash(contentHash: String): List<PackageVersion>
    fun findByPackageIdOrderByVersionNumberDesc(packageId: UUID): List<PackageVersion>
    fun countByPackageId(packageId: UUID): Long

    @Query("select count(v) from PackageVersion v, AnimationPackage p where v.packageId = p.id and p.ownerId = :ownerId and v.createdAt > :after")
    fun countUploadsSince(@Param("ownerId") ownerId: UUID, @Param("after") after: Instant): Long
}

interface UploadDeclarationRepository : JpaRepository<UploadDeclaration, UUID> {
    fun findByIpPseudonymizedFalseAndCreatedAtBefore(before: Instant): List<UploadDeclaration>
}
