package com.playmation.motionlabsbackend.web

import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.Locale

/**
 * Was eine Seite einer Suchmaschine sagt: ihr Titel im Suchergebnis und die
 * strukturierten Daten (JSON-LD) im Kopf.
 *
 * DER TITEL IST DIE ZEILE IM SUCHERGEBNIS. Bis 2026-09-26 stand dort "Thumbs
 * Up - Animation Workbench Community" - der Name der Sache und der Name des
 * Portals, aber keins der Woerter, nach denen jemand sucht: "free", "animation",
 * "fbx". Wer "free thumbs up animation fbx" eintippt, findet einen Titel, der
 * genau das sagt, eher als einen, der es verschweigt.
 *
 * JSON-LD sagt dasselbe noch einmal fuer Maschinen: was die Seite ist (ein Werk
 * unter CC0, von wem, mit welchen Schlagworten), wo sie im Katalog steht
 * (Brotkrumen) und wie die Seite heisst (WebSite auf der Startseite - daraus
 * nimmt Google den Namen ueber dem Treffer).
 */
object Seo {
    const val SITE = "Playmations"
    const val CC0 = "https://creativecommons.org/publicdomain/zero/1.0/"

    /** "sword-fight" -> "Sword Fight": ein Schlagwort, wie es in einem Titel steht. */
    fun tagLabel(tag: String): String =
        tag.split(' ', '-', '_').filter { it.isNotBlank() }
            .joinToString(" ") { word -> word.replaceFirstChar { it.titlecase(Locale.ROOT) } }

    fun homeTitle() = "Free Humanoid Animations – FBX, GLB & Unity | $SITE"

    fun tagTitle(tag: String) = "Free ${tagLabel(tag)} Animations – FBX, GLB & Unity | $SITE"

    /** "Idle Animation" soll nicht "Idle Animation – Free Animation" heissen. */
    fun clipTitle(title: String) =
        if (title.contains("animation", ignoreCase = true)) "$title – Free Download (FBX, GLB, Unity) | $SITE"
        else "$title – Free Animation (FBX, GLB, Unity) | $SITE"

    fun packTitle(title: String) = "$title – Free Animation Pack (FBX, GLB, Unity) | $SITE"

    /** Der Satz hinter jeder Clip-Beschreibung: was man hier bekommt, in den Worten einer Suche. */
    const val CLIP_PITCH = "Free humanoid animation - download it as FBX or GLB, " +
        "or import it into Unity with the Animation Workbench."

    // ── JSON-LD ──────────────────────────────────────────────────────────

    fun person(name: String, url: String?) = obj("@type" to "Person", "name" to name, "url" to url)

    fun breadcrumbs(vararg steps: Pair<String, String>) = obj(
        "@type" to "BreadcrumbList",
        "itemListElement" to steps.mapIndexed { index, (name, url) ->
            obj("@type" to "ListItem", "position" to index + 1, "name" to name, "item" to url)
        },
    )

    fun date(instant: Instant) = instant.toString()

    /** Ein Objekt ohne die Felder, die leer sind - `null` in JSON-LD ist kein "unbekannt", sondern Rauschen. */
    fun obj(vararg fields: Pair<String, Any?>): Map<String, Any> =
        linkedMapOf<String, Any>().apply {
            for ((key, value) in fields) {
                if (value == null) continue
                if (value is String && value.isBlank()) continue
                if (value is Collection<*> && value.isEmpty()) continue
                put(key, value)
            }
        }

    private val json = JsonMapper.builder().build()

    /**
     * Die Objekte als EIN `<script type="application/ld+json">`-Inhalt.
     *
     * Der Inhalt steht roh im HTML (`th:utext`), und darin darf nichts stehen,
     * was das Skript-Element beendet: ein Clip-Titel "</script><script>..."
     * waere sonst eine Tuer. `<`, `>` und `&` werden deshalb als \u-Folge
     * geschrieben - fuer JSON dasselbe Zeichen, fuer den HTML-Leser keins.
     */
    fun jsonLd(vararg nodes: Map<String, Any>): String =
        json.writeValueAsString(obj("@context" to "https://schema.org", "@graph" to nodes.toList()))
            .replace("<", "\\u003c")
            .replace(">", "\\u003e")
            .replace("&", "\\u0026")

    /** Wie `formatDuration` in app.js - damit Server und Skript dieselbe Zahl schreiben. */
    fun duration(seconds: Float): String =
        if (seconds < 10) String.format(Locale.ROOT, "%.2f s", seconds)
        else String.format(Locale.ROOT, "%.1f s", seconds)
}
