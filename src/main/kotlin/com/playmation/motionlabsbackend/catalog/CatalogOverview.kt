package com.playmation.motionlabsbackend.catalog

import com.playmation.motionlabsbackend.format.AwclipSchema
import org.springframework.transaction.annotation.Transactional
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Was der Katalog ueber sich selbst sagen kann: wie viele Clips von wie vielen
 * Leuten, und welche Schlagworte tatsaechlich vorkommen.
 *
 * WARUM DAS EINE ANTWORT IST. Beide Zahlenarten hat dieselbe Seite noetig - die
 * Startseite nennt den Umfang, der Katalog bietet die Schlagworte zum Anklicken
 * an. Zwei Endpunkte waeren zwei Rundwege fuer eine Zeile Text.
 *
 * WARUM SCHLAGWORTE UEBERHAUPT GEZAEHLT WERDEN. Ein Suchfeld beantwortet nur
 * Fragen, die jemand schon hat. Wer neu ist, hat keine - er will sehen, was es
 * gibt. Die Schlagwortleiste ist der Unterschied zwischen "such dir was" und
 * "hier sind die elf Arten von Bewegung, die hier liegen".
 *
 * DIE ZAEHLUNG LAEUFT IM SPEICHER, nicht in SQL. Die Schlagworte stehen als
 * eine Zeichenkette in einer Spalte (`,walk,sneak,`), damit die Suche ein
 * schlichtes LIKE bleibt - ein GROUP BY gibt es darauf nicht. Bei einem Katalog
 * dieser Groesse ist das gleichgueltig, und [cacheFor] haelt die Rechnung
 * ohnehin von den meisten Anfragen fern. Wenn das eines Tages nicht mehr
 * reicht, ist die Stelle hier und nur hier.
 */
@Service
class CatalogOverviewService(
    private val packages: AnimationPackageRepository,
    /** Nur fuers Rig: die Zahl soll dasselbe zaehlen, was der Katalog zeigt. */
    private val versions: PackageVersionRepository,
    private val clock: Clock,
) {

    data class TagCount(val tag: String, val count: Int)

    data class Overview(
        /** Oeffentlich gelistete Clips. */
        val clips: Int,
        /** Konten, von denen mindestens einer davon stammt. */
        val creators: Int,
        /** Wie oft ein Clip in ein Projekt uebernommen wurde. */
        val installs: Long,
        /** Die haeufigsten Schlagworte, haeufigstes zuerst. */
        val tags: List<TagCount>,
    )

    /**
     * Alle Schlagworte des Katalogs mit ihrer Zahl, und welche zusammen an
     * einem Clip stehen. Die Leiste oben braucht nur die ersten 18; das
     * Schlagwortfeld beim Teilen und Bearbeiten ([TagService]) braucht alle,
     * um vorzuschlagen, was es schon gibt, statt ein zweites `walking` neben
     * dem ersten `walk` entstehen zu lassen.
     */
    class TagStats(
        val counts: Map<String, Int>,
        /** `pairs["walk"]["loop"]` = an so vielen Clips stehen beide. */
        private val pairs: Map<String, Map<String, Int>>,
    ) {
        fun count(tag: String) = counts[tag] ?: 0
        fun related(tag: String): Map<String, Int> = pairs[tag].orEmpty()
    }

    private class Snapshot(val overview: Overview, val tags: TagStats)

    /**
     * Eine Minute. Lang genug, dass ein Besucher, der durch den Katalog
     * blaettert, die Rechnung nur einmal ausloest; kurz genug, dass ein
     * frischer Upload sich nicht wie ein Fehler anfuehlt.
     */
    private val cacheFor = Duration.ofMinutes(1)

    @Volatile private var cached: Snapshot? = null
    @Volatile private var cachedAt: Instant = Instant.EPOCH

    @Transactional(readOnly = true)
    fun overview(): Overview = snapshot().overview

    /** Dieselbe Zaehlung, dieselbe Minute - nur vollstaendig. */
    @Transactional(readOnly = true)
    fun tagStats(): TagStats = snapshot().tags

    private fun snapshot(): Snapshot {
        val now = clock.instant()
        cached?.let { if (Duration.between(cachedAt, now) < cacheFor) return it }

        val published = packages.findAllByStatusAndLicense(PackageStatus.PUBLISHED, AwclipSchema.LICENSE_PUBLIC)

        //  Dieselbe Auslassung wie im Katalog (CatalogService.cardsFor): was
        //  das Portal gerade nicht annimmt, zeigt es nicht - und darf es dann
        //  auch nicht mitzaehlen. Eine Startseite, die zwoelf Clips verspricht
        //  und elf zeigt, ist ein Fehler, den niemand meldet und jeder sieht.
        val rigOf = versions.findAllById(published.mapNotNull { it.currentVersionId })
            .associate { it.id to it.rig }
        val listed = published.filter { pkg ->
            val rig = rigOf[pkg.currentVersionId]
            rig != null && AwclipSchema.isAcceptedRig(rig)
        }

        val counts = HashMap<String, Int>()
        val pairs = HashMap<String, HashMap<String, Int>>()
        for (pkg in listed) {
            val tags = pkg.tagList()
            for (tag in tags) {
                counts[tag] = (counts[tag] ?: 0) + 1
                val with = pairs.getOrPut(tag) { HashMap() }
                for (other in tags) if (other != tag) with[other] = (with[other] ?: 0) + 1
            }
        }

        val fresh = Overview(
            clips = listed.size,
            creators = listed.map { it.ownerId }.toSet().size,
            installs = listed.sumOf { it.takeCount },
            tags = barTags(counts, listed.size),
        )

        val snapshot = Snapshot(fresh, TagStats(counts, pairs))
        cached = snapshot
        cachedAt = now
        return snapshot
    }

    /**
     * DIE LEISTE ZEIGT NUR, WAS EINGRENZT (Pablo, 2026-09-26: "gefuehlt zu
     * viele Tags"). Bei 19 Clips standen 18 Schlagworte da, und die meisten
     * waren keine Filter:
     *
     * - An FAST ALLEM ("gesture" an 18 von 19): wer darauf klickt, sieht
     *   dieselbe Wand noch einmal. Mehr als [BAR_MAX_SHARE] des Katalogs
     *   fliegt raus - waechst der Katalog, kommt das Wort von selbst zurueck.
     * - An FAST NICHTS ("head-shake" an einem): das ist ein Suchergebnis,
     *   keine Kategorie. Erst ab [BAR_MIN_CLIPS] Clips.
     *
     * Hoechstens [MAX_TAGS]. Nach Haeufigkeit, bei Gleichstand alphabetisch -
     * sonst tanzt die Leiste bei jedem Neuladen. Die Schlagworte am Clip
     * bleiben alle; die Suche und der Klick auf ein Schlagwort am Clip finden
     * sie weiter.
     */
    internal fun barTags(counts: Map<String, Int>, clips: Int): List<TagCount> =
        counts.entries
            .filter { it.value >= BAR_MIN_CLIPS && it.value <= clips * BAR_MAX_SHARE }
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(MAX_TAGS)
            .map { TagCount(it.key, it.value) }

    /** Der Cache haelt eine Minute - ein frischer Upload darf nicht so lange warten. */
    fun invalidate() {
        cachedAt = Instant.EPOCH
    }

    private companion object {
        /** Mehr Pillen als das liest niemand, und die Leiste bricht in die dritte Zeile. */
        const val MAX_TAGS = 10

        /** Ab so vielen Clips ist ein Schlagwort eine Kategorie. */
        const val BAR_MIN_CLIPS = 3

        /** Mehr als dieser Anteil des Katalogs - und es grenzt nichts mehr ein. */
        const val BAR_MAX_SHARE = 0.8
    }
}

@RestController
@RequestMapping("/api/v1")
class CatalogOverviewController(private val overview: CatalogOverviewService) {

    @GetMapping("/overview")
    fun overview() = overview.overview()
}
