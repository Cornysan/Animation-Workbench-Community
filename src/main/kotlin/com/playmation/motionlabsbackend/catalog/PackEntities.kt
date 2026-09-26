package com.playmation.motionlabsbackend.catalog

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

/**
 * Ein Pack: die Clips, die ein Ersteller zusammen veroeffentlicht (V13).
 *
 * Gespeichert ist nur, was der Ersteller selbst sagt - Titel und Beschreibung.
 * Welche Clips dazugehoeren, steht am Clip ([AnimationPackage.packId]); Zahl,
 * Deckel, Schlagworte und Beliebtheit leiten sich daraus ab ([PackService]).
 *
 * Heisst `ClipPack` und liegt in `clip_pack`, weil "pack" in Kotlin wie in SQL
 * zu nah an anderen Woertern liegt, um es ohne Zusatz zu lesen.
 */
@Entity
@Table(name = "clip_pack")
class ClipPack(
    @Id
    var id: UUID = UUID.randomUUID(),

    /** Kurze oeffentliche ID fuer Links - wie beim Clip und der Sammlung. */
    var slug: String,

    var ownerId: UUID,
    var title: String,
    var description: String,

    var createdAt: Instant,
    var updatedAt: Instant,
)

interface ClipPackRepository : JpaRepository<ClipPack, UUID> {
    fun findBySlug(slug: String): ClipPack?
    fun findByOwnerIdOrderByCreatedAtDesc(ownerId: UUID): List<ClipPack>
}
