package com.playmation.motionlabsbackend.collection

import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

/**
 * PUBLIC   steht auf dem Profil und ist verlinkbar.
 * UNLISTED existiert nur hinter seinem Link - dieselbe Trennung wie zwischen
 *          oeffentlichem und privatem Clip.
 */
enum class CollectionVisibility { PUBLIC, UNLISTED }

/**
 * Derselbe Reigen wie beim Paket, aus demselben Grund: Titel und Beschreibung
 * sind Nutzertext, also meldbar, also kann eine Sammlung in die Pruefung
 * geraten.
 */
enum class CollectionStatus { PUBLISHED, AUTO_HIDDEN, REMOVED }

/**
 * Eine Sammlung.
 *
 * Heisst `ClipCollection` und nicht `Collection`, weil jede Kotlin-Datei, die
 * sie benutzt, sonst zwischen dieser und `kotlin.collections.Collection`
 * waehlen muesste. Die Tabelle heisst trotzdem `collection` - dort gibt es das
 * Problem nicht.
 */
@Entity
@Table(name = "collection")
class ClipCollection(
    @Id
    var id: UUID = UUID.randomUUID(),

    /** Kurze oeffentliche ID fuer Links - wie beim Paket. */
    var slug: String,

    var ownerId: UUID,
    var title: String,
    var description: String,

    @Enumerated(EnumType.STRING)
    var visibility: CollectionVisibility = CollectionVisibility.PUBLIC,

    @Enumerated(EnumType.STRING)
    var status: CollectionStatus = CollectionStatus.PUBLISHED,

    /** Sichtbare Eintraege - neu gezaehlt, nicht hoch- und runtergerechnet. */
    var itemCount: Long = 0,

    var createdAt: Instant,
    var updatedAt: Instant,
)

/**
 * Ein Clip in einer Sammlung. Zusammengesetzter Schluessel: zweimal derselbe
 * Clip in derselben Sammlung ist unmoeglich, ohne dass der Code pruefen muss.
 */
@Entity
@Table(name = "collection_item")
@IdClass(CollectionItemId::class)
class CollectionItem(
    @Id var collectionId: UUID = UUID.randomUUID(),
    @Id var packageId: UUID = UUID.randomUUID(),
    var position: Int = 0,
    var addedAt: Instant = Instant.EPOCH,
)

data class CollectionItemId(
    var collectionId: UUID = UUID.randomUUID(),
    var packageId: UUID = UUID.randomUUID(),
) : java.io.Serializable

interface ClipCollectionRepository : JpaRepository<ClipCollection, UUID> {
    fun findBySlug(slug: String): ClipCollection?

    fun findByOwnerIdAndStatusOrderByUpdatedAtDesc(ownerId: UUID, status: CollectionStatus): List<ClipCollection>

    fun countByOwnerIdAndStatusAndVisibility(
        ownerId: UUID,
        status: CollectionStatus,
        visibility: CollectionVisibility,
    ): Long

    fun findByOwnerIdAndStatusAndVisibilityOrderByUpdatedAtDesc(
        ownerId: UUID,
        status: CollectionStatus,
        visibility: CollectionVisibility,
    ): List<ClipCollection>

    /** Alles Oeffentliche, neueste zuerst - der Katalog der Sammlungen. */
    fun findByStatusAndVisibilityOrderByUpdatedAtDesc(
        status: CollectionStatus,
        visibility: CollectionVisibility,
    ): List<ClipCollection>
}

interface CollectionItemRepository : JpaRepository<CollectionItem, CollectionItemId> {
    fun findByCollectionIdOrderByPositionAsc(collectionId: UUID): List<CollectionItem>
    fun existsByCollectionIdAndPackageId(collectionId: UUID, packageId: UUID): Boolean
    fun deleteByCollectionIdAndPackageId(collectionId: UUID, packageId: UUID): Long
    fun deleteByCollectionId(collectionId: UUID): Long
    fun countByCollectionId(collectionId: UUID): Long

    /** In welchen meiner Sammlungen liegt dieser Clip - fuer den Stern. */
    @Query(
        "select i.collectionId from CollectionItem i, ClipCollection c " +
            "where i.collectionId = c.id and c.ownerId = :ownerId and i.packageId = :packageId"
    )
    fun collectionsOfOwnerContaining(
        @Param("ownerId") ownerId: UUID,
        @Param("packageId") packageId: UUID,
    ): List<UUID>

    /**
     * Wie viele PERSONEN diesen Clip gesammelt haben - der Stern an der Karte.
     * Gezaehlt werden Besitzer, nicht Zeilen: wer denselben Clip in drei eigene
     * Sammlungen legt, bleibt eine Person.
     */
    @Query(
        "select count(distinct c.ownerId) from CollectionItem i, ClipCollection c " +
            "where i.collectionId = c.id and i.packageId = :packageId and c.status = " +
            "com.playmation.motionlabsbackend.collection.CollectionStatus.PUBLISHED"
    )
    fun saverCount(@Param("packageId") packageId: UUID): Long

    /** Welche dieser Clips liegen in einer meiner Sammlungen - eine Abfrage je Seite. */
    @Query(
        "select distinct i.packageId from CollectionItem i, ClipCollection c " +
            "where i.collectionId = c.id and c.ownerId = :ownerId and i.packageId in :packageIds"
    )
    fun savedAmong(
        @Param("ownerId") ownerId: UUID,
        @Param("packageIds") packageIds: Collection<UUID>,
    ): List<UUID>
}
