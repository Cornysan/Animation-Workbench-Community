package com.playmation.motionlabsbackend.looks

import com.playmation.motionlabsbackend.common.PortalException
import com.playmation.motionlabsbackend.web.BuildStamp
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Figur und Look eines Clips - wie das Mannequin aussieht, das ALLE auf Karte,
 * Clip-Seite und Link-Vorschau sehen.
 *
 * Der Ersteller waehlt beides beim Teilen (in der Workbench oder beim
 * Hochladen hier) und kann es spaeter aendern. Figur und Look sind frei, in
 * Lite wie in Pro.
 *
 * ── Jede Figur hat ihre eigenen Looks ───────────────────────────────────
 *
 * Seit 2026-09-29 hat jede Figur zehn Looks: zwei gemeinsame (Classic als
 * Standard, Graphite) und acht eigene, jeder davon mit einem Gegenstueck
 * bei der anderen ([Look.twin]): Gold und Rose Gold, Galaxy und Nebula.
 * Kommt ein Look zur falschen
 * Figur - eine aeltere Workbench, ein Figurwechsel beim Bearbeiten -, traegt
 * der Clip das Gegenstueck ([FigureLooks.lookFor]), statt dass die Anfrage
 * scheitert.
 *
 * ── Keine Grenze mehr ───────────────────────────────────────────────────
 *
 * Bis 2026-10-04 waren die meisten Looks Pro. Gesperrt hat nur die
 * Workbench - das Portal sieht keine Lizenz -, und im Web musste eine eigene
 * Sperre her, die anders lief als die in Unity. Pablo: auseinander. Seitdem
 * traegt jede Ausgabe jeden Look, und `pro` ist aus der Antwort verschwunden.
 *
 * Verkauft wird hier nach wie vor nichts: bezahlt wird im Asset Store fuer
 * die Workbench, das Portal bleibt eine kostenlose Community.
 *
 * Wie ein Look aussieht, steht allein im Browser (`static/assets/figure-looks.js`);
 * die Schluessel hier und dort muessen dieselben sein.
 */
object FigureLooks {
    const val DEFAULT_FIGURE = "default"
    const val FEMALE_FIGURE = "female"

    /** Der Standard beider Figuren. */
    const val DEFAULT_LOOK = "classic"

    /** [defaultLook] = was die Figur ohne Wahl traegt. */
    data class Figure(val key: String, val label: String, val defaultLook: String = DEFAULT_LOOK)

    /** Die Figuren des Hauses - dieselben Schluessel wie `HOUSE_FIGURES` in stage.js. */
    val FIGURES = listOf(
        Figure(DEFAULT_FIGURE, "Male"),
        Figure(FEMALE_FIGURE, "Female"),
    )

    /**
     * [figures] = wer ihn tragen kann: beide (Classic, Graphite) oder eine.
     * [twin] = das Gegenstueck bei der anderen Figur; ein gemeinsamer Look ist
     * sein eigenes.
     */
    data class Look(val key: String, val label: String, val figures: List<String>, val twin: String) {
        val shared: Boolean get() = figures.size > 1
    }

    /**
     * Was beide tragen, gleich aussehend (Pablo, 2026-09-29: die eigene
     * Fassung der Frau - Crimson, Noir - fiel wieder weg).
     */
    private val SHARED = listOf("classic" to "Classic", "graphite" to "Graphite")

    /**
     * Die Paare, links der Mann, rechts die Frau. Dieselbe Tabelle steht in
     * figure-looks.js (`PAIRS`) und in der Workbench (AWMannequinLooks.cs).
     */
    private val PAIRS = listOf(
        ("mint" to "Mint") to ("blush" to "Blush"),
        ("ocean" to "Ocean") to ("sunset" to "Sunset"),
        ("marble" to "Marble") to ("rosequartz" to "Rose Quartz"),
        ("neon" to "Neon") to ("magenta" to "Magenta"),
        ("gold" to "Gold") to ("rosegold" to "Rose Gold"),
        ("chrome" to "Chrome") to ("pearl" to "Pearl"),
        ("galaxy" to "Galaxy") to ("nebula" to "Nebula"),
        ("hologram" to "Hologram") to ("aurora" to "Aurora"),
    )

    /**
     * Erst die gemeinsamen, dann die acht des Mannes, dann die acht der Frau.
     * Je Figur gelesen ([looksOf]) steht das Gegenstueck an derselben Stelle.
     */
    val LOOKS: List<Look> =
        SHARED.map { (key, label) -> Look(key, label, listOf(DEFAULT_FIGURE, FEMALE_FIGURE), key) } +
            PAIRS.map { (male, female) -> Look(male.first, male.second, listOf(DEFAULT_FIGURE), female.first) } +
            PAIRS.map { (male, female) -> Look(female.first, female.second, listOf(FEMALE_FIGURE), male.first) }

    /**
     * Looks, die es nicht mehr gibt, und woraus sie wurden. Eine aeltere
     * Workbench kann sie noch schicken - Coral war in Lite -, und das soll
     * nicht scheitern.
     */
    private val RETIRED = mapOf("coral" to DEFAULT_LOOK, "crimson" to DEFAULT_LOOK, "noir" to "graphite")

    fun figure(key: String): Figure? = FIGURES.firstOrNull { it.key == key }
    fun look(key: String): Look? = LOOKS.firstOrNull { it.key == key }

    fun defaultLook(figure: String): String = figure(figure)?.defaultLook ?: DEFAULT_LOOK

    /** Die Looks einer Figur in der Reihenfolge der Auswahl. */
    fun looksOf(figure: String): List<Look> = LOOKS.filter { figure in it.figures }

    /**
     * Der Look, den [figure] fuer [key] traegt: der Look selbst, wenn sie ihn
     * tragen kann, sonst sein Gegenstueck; fuer einen ausgemusterten sein
     * Ersatz. null = einen solchen Look gab es nie.
     */
    fun lookFor(figure: String, key: String): String? {
        RETIRED[key]?.let { return lookFor(figure, it) }
        val look = look(key) ?: return null
        return if (figure in look.figures) look.key else look.twin
    }
}

data class FigureView(
    val key: String,
    val label: String,
    /** Was die Figur ohne Wahl traegt. */
    val defaultLook: String,
)

/** Ein Look, wie die Auswahl ihn zeigt. */
data class LookView(
    val key: String,
    val label: String,
    /**
     * Wem der Look gehoert - die Auswahl zeigt je Figur nur ihre. null = beiden
     * (Classic, Graphite); so liest es auch eine Workbench, die [figures] nicht kennt.
     */
    val figure: String?,
    /** Wer ihn tragen kann. */
    val figures: List<String>,
    /** Das Gegenstueck bei der anderen Figur - fuer den Figurwechsel in der Auswahl. */
    val twin: String,
    /**
     * Das Bildchen fuer die Auswahl in der Workbench, relativ zum Portal und
     * mit dem Build im Pfad (`/assets/v/<commit>/looks/galaxy.png`): aendert
     * sich ein Look, holt Unity das neue Bild, ohne dass jemand einen Cache
     * leeren muss. Gerendert mit der Buehne, liegt unter `static/assets/looks/`.
     * Bei einem gemeinsamen Look das Bild des Mannes.
     */
    val image: String = "",
    /**
     * Das Bildchen je Figur, die ihn tragen kann - bei einem gemeinsamen Look
     * zeigt die Auswahl so auf der Frau auch die Frau (`classic-female.png`).
     */
    val images: Map<String, String> = emptyMap(),
)

data class LooksView(
    val figures: List<FigureView>,
    val looks: List<LookView>,
    val defaultFigure: String = FigureLooks.DEFAULT_FIGURE,
    val defaultLook: String = FigureLooks.DEFAULT_LOOK,
)

/** Was an einem Clip steht: Figur und Look. */
data class FigureChoice(val figure: String, val look: String)

@Service
class LookService(private val build: BuildStamp) {

    /** Alle Figuren und Looks - fuer jeden gleich, mit oder ohne Konto. */
    fun looks(): LooksView = LooksView(
        figures = FigureLooks.FIGURES.map { FigureView(it.key, it.label, it.defaultLook) },
        looks = FigureLooks.LOOKS.map { look ->
            val images = look.figures.associateWith { imageOf(look, it) }
            LookView(
                key = look.key,
                label = look.label,
                figure = if (look.shared) null else look.figures.single(),
                figures = look.figures,
                twin = look.twin,
                image = images.getValue(look.figures.first()),
                images = images,
            )
        },
    )

    /** Ein gemeinsamer Look hat ein Bild je Figur; das der Frau heisst `<key>-female.png`. */
    private fun imageOf(look: FigureLooks.Look, figure: String): String {
        val suffix = if (look.shared && figure != FigureLooks.DEFAULT_FIGURE) "-$figure" else ""
        return "${build.assets}/looks/${look.key}$suffix.png"
    }

    /**
     * Was ein Clip nach einer Wahl traegt. `null` heisst "nicht angegeben" und
     * behaelt [current] - bei einem neuen Clip den Standard der Figur. Ein
     * Look der anderen Figur wird zu seinem Gegenstueck ([FigureLooks.lookFor]);
     * scheitern tut nur, was es nie gab.
     */
    fun choose(figure: String?, look: String?, current: FigureChoice?): FigureChoice {
        val chosenFigure = figure?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            ?: current?.figure ?: FigureLooks.DEFAULT_FIGURE
        if (FigureLooks.figure(chosenFigure) == null)
            throw PortalException.badRequest("invalid-figure", "'$chosenFigure' is not a figure.")

        val requested = look?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val chosenLook = when {
            requested != null -> FigureLooks.lookFor(chosenFigure, requested)
                ?: throw PortalException.badRequest("invalid-look", "'$requested' is not a look.")
            current != null -> FigureLooks.lookFor(chosenFigure, current.look) ?: FigureLooks.defaultLook(chosenFigure)
            else -> FigureLooks.defaultLook(chosenFigure)
        }

        return FigureChoice(chosenFigure, chosenLook)
    }
}

@RestController
@RequestMapping("/api/v1/looks")
class LookController(private val looks: LookService) {

    /** Offen lesbar: die Auswahl zeigt auch ohne Anmeldung, was es gibt. */
    @GetMapping
    fun looks() = looks.looks()
}
