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
     * Eine Minute. Lang genug, dass ein Besucher, der durch den Katalog
     * blaettert, die Rechnung nur einmal ausloest; kurz genug, dass ein
     * frischer Upload sich nicht wie ein Fehler anfuehlt.
     */
    private val cacheFor = Duration.ofMinutes(1)

    @Volatile private var cached: Overview? = null
    @Volatile private var cachedAt: Instant = Instant.EPOCH

    @Transactional(readOnly = true)
    fun overview(): Overview {
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
        for (pkg in listed) for (tag in pkg.tagList()) counts[tag] = (counts[tag] ?: 0) + 1

        val fresh = Overview(
            clips = listed.size,
            creators = listed.map { it.ownerId }.toSet().size,
            installs = listed.sumOf { it.takeCount },
            //  Nach Haeufigkeit, bei Gleichstand alphabetisch - sonst tanzt die
            //  Leiste bei jedem Neuladen, obwohl sich nichts geaendert hat.
            tags = counts.entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .take(MAX_TAGS)
                .map { TagCount(it.key, it.value) },
        )

        cached = fresh
        cachedAt = now
        return fresh
    }

    /** Der Cache haelt eine Minute - ein frischer Upload darf nicht so lange warten. */
    fun invalidate() {
        cachedAt = Instant.EPOCH
    }

    private companion object {
        /** Mehr Pillen als das liest niemand, und die Leiste bricht in die dritte Zeile. */
        const val MAX_TAGS = 18
    }
}

@RestController
@RequestMapping("/api/v1")
class CatalogOverviewController(private val overview: CatalogOverviewService) {

    @GetMapping("/overview")
    fun overview() = overview.overview()
}
