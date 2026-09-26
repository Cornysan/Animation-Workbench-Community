package com.playmation.motionlabsbackend.catalog

import com.playmation.motionlabsbackend.auth.PortalPrincipal
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/** Ein Platz auf der Wand: ein Clip ODER ein Pack, `kind` sagt welches. */
data class CatalogEntry(
    val kind: String,
    val clip: PackageSummary? = null,
    val pack: PackSummary? = null,
)

/**
 * Eine Seite der Wand. [clips] und [packs] zaehlen getrennt, damit die Seite
 * "1 pack · 1 clip" sagen kann statt einer Zahl, die beides mischt.
 */
data class CatalogPage(
    val items: List<CatalogEntry>,
    val page: Int,
    val size: Int,
    val total: Long,
    val clips: Long,
    val packs: Long,
)

/**
 * Die Katalogwand: Clips und Packs auf EINER Wand (2026-09-26).
 *
 * OHNE SUCHE steht ein Pack als eine Karte da, und seine Clips stehen nicht
 * noch einmal einzeln daneben - das ist der Sinn der Packs: achtzehn Gesten
 * aus einer Quelle belegten vorher die ganze erste Seite. MIT SUCHE (Text oder
 * Schlagwort) findet sie die Clips einzeln, auch die in Packs, und die Packs,
 * die passen, dazu. Sonst waeren die Schlagworte der Clips in Packs wertlos.
 *
 * Zwei Quellen, eine Reihenfolge: die Clips kommen seitenweise aus der
 * Datenbank ([CatalogService.findClips]), die Packs ganz ([PackService.matching])
 * - es sind wenige, und ihre Zahlen entstehen erst beim Zusammenzaehlen. Fuer
 * Seite n braucht es dann die ersten (n+1)·Groesse Clips; beide Listen werden
 * wie zwei sortierte Stapel zusammengelegt. Die Reihenfolge der Clips
 * untereinander bleibt dabei die der Datenbank, auch bei Gleichstand - sonst
 * koennte "Load more" einen Clip zweimal bringen.
 *
 * `/api/v1/packages` bleibt, was es war: eine flache Liste von Clips. Die
 * Workbench bis 2.5.0 liest sie, und eine Pack-Karte darin waere fuer sie ein
 * Clip, dessen Vorschau es nicht gibt.
 */
@Service
class CatalogWall(
    private val catalog: CatalogService,
    private val packs: PackService,
) {
    companion object {
        /**
         * Wie tief die Wand blaettern laesst. Jede Seite holt ALLE Clips bis zu
         * ihrem Ende; hundert Seiten zu 24 sind mehr, als jemand scrollt, und
         * ein `?page=100000` wird keine Abfrage ueber den ganzen Katalog.
         */
        const val MAX_WINDOW = 2400
    }

    @Transactional(readOnly = true)
    fun page(q: String?, tag: String?, sort: String?, page: Int, size: Int,
             principal: PortalPrincipal? = null, author: String? = null, ownerId: UUID? = null): CatalogPage {
        val pageSize = size.coerceIn(1, 50)
        val pageIndex = page.coerceAtLeast(0)
        val window = ((pageIndex + 1) * pageSize).coerceAtMost(MAX_WINDOW)
        val searching = !q.isNullOrBlank() || !tag.isNullOrBlank()

        val authorIds = catalog.authorIds(author)
        val clips = catalog.findClips(q, tag, sort, 0, window, authorIds, ownerId, unpackedOnly = !searching)

        //  Dieselbe Eingrenzung auf Personen fuer die Packs: der Name UND das
        //  Konto, wenn beides gilt.
        val packOwners = when {
            ownerId != null -> if (authorIds == null || ownerId in authorIds) listOf(ownerId) else emptyList()
            else -> authorIds
        }
        val order = order(sort)
        val packRows = packs.matching(q, tag, packOwners, principal).sortedWith { a, b -> order.compare(key(a), key(b)) }

        //  Zwei sortierte Stapel zusammenlegen. Bei Gleichstand der Clip - er
        //  kommt aus der Datenbank, und seine Stelle soll sich nicht mit der
        //  Zahl der Packs verschieben.
        val rows = clips.content
        val merged = ArrayList<Any>(window)
        var i = 0
        var j = 0
        while (merged.size < window && (i < rows.size || j < packRows.size)) {
            val packFirst = j < packRows.size &&
                (i >= rows.size || order.compare(key(packRows[j]), key(rows[i])) < 0)
            merged += if (packFirst) packRows[j++] else rows[i++]
        }

        val slice = merged.drop(pageIndex * pageSize).take(pageSize)
        val cards = catalog.cardsFor(slice.filterIsInstance<AnimationPackage>(), principal).associateBy { it.slug }

        val items = slice.mapNotNull { row ->
            when (row) {
                is AnimationPackage -> cards[row.slug]?.let { CatalogEntry("clip", clip = it) }
                is PackSummary -> CatalogEntry("pack", pack = row)
                else -> null
            }
        }

        return CatalogPage(items, pageIndex, pageSize, clips.totalElements + packRows.size,
            clips.totalElements, packRows.size.toLong())
    }

    /** Wonach sortiert wird - fuer Clip und Pack dieselben drei Zahlen. */
    private data class Key(val takes: Long, val likes: Long, val createdAt: Instant)

    private fun key(pkg: AnimationPackage) = Key(pkg.takeCount, pkg.likeCount, pkg.createdAt)
    private fun key(pack: PackSummary) = Key(pack.downloads, pack.likes, pack.createdAt)

    /** Dieselbe Reihenfolge wie [CatalogService.findClips], absteigend. */
    private fun order(sort: String?): Comparator<Key> = when (sort) {
        "popular" -> compareByDescending<Key> { it.takes }.thenByDescending { it.likes }.thenByDescending { it.createdAt }
        "liked" -> compareByDescending<Key> { it.likes }.thenByDescending { it.createdAt }
        else -> compareByDescending { it.createdAt }
    }
}
