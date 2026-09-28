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
 * Der Ersteller waehlt beides beim Teilen (in der Workbench) und kann es
 * spaeter aendern. Die Figur ist frei; bei den Looks sind drei in Animation
 * Workbench Lite dabei, der Rest kommt mit Pro ([Look.pro]).
 *
 * ── Wer die Grenze zieht ────────────────────────────────────────────────
 *
 * Die WORKBENCH, nicht dieses Portal. Pro wird im Asset Store gekauft, eine
 * Lizenz sieht das Portal nie - es kann Lite und Pro nicht unterscheiden und
 * versucht es auch nicht. Es sagt nur, welcher Look Pro ist; die Auswahl in
 * Unity sperrt ihn in Lite. Wer die Anfrage von Hand baut, kommt an der
 * Sperre vorbei - dieselbe weiche Tuer wie bei den anderen Pro-Grenzen, und
 * fuer eine Farbe am Mannequin kein Grund, eine Lizenzpruefung zu bauen.
 *
 * Verkauft wird hier nach wie vor nichts: bezahlt wird im Asset Store fuer
 * die Workbench, das Portal bleibt eine kostenlose Community.
 *
 * Wie ein Look aussieht, steht allein im Browser (`static/assets/figure-looks.js`);
 * die Schluessel hier und dort muessen dieselben sein.
 */
object FigureLooks {
    const val DEFAULT_FIGURE = "default"
    const val DEFAULT_LOOK = "classic"

    data class Figure(val key: String, val label: String)

    /** Die Figuren des Hauses - dieselben Schluessel wie `HOUSE_FIGURES` in stage.js. */
    val FIGURES = listOf(
        Figure(DEFAULT_FIGURE, "Male"),
        Figure("female", "Female"),
    )

    /** [pro] = nur mit Animation Workbench Pro waehlbar. Die Reihenfolge ist die der Auswahl. */
    data class Look(val key: String, val label: String, val pro: Boolean = false)

    val LOOKS = listOf(
        Look(DEFAULT_LOOK, "Classic"),
        Look("mint", "Mint"),
        Look("coral", "Coral"),
        Look("graphite", "Graphite", pro = true),
        Look("ocean", "Ocean", pro = true),
        Look("sunset", "Sunset", pro = true),
        Look("marble", "Marble", pro = true),
        Look("neon", "Neon", pro = true),
        Look("gold", "Gold", pro = true),
        Look("chrome", "Chrome", pro = true),
        Look("galaxy", "Galaxy", pro = true),
        Look("hologram", "Hologram", pro = true),
    )

    fun figure(key: String): Figure? = FIGURES.firstOrNull { it.key == key }
    fun look(key: String): Look? = LOOKS.firstOrNull { it.key == key }
}

data class FigureView(val key: String, val label: String)

/** Ein Look, wie die Auswahl ihn zeigt. */
data class LookView(
    val key: String,
    val label: String,
    /** Nur mit Pro waehlbar - gesperrt wird in der Workbench, siehe [FigureLooks]. */
    val pro: Boolean,
    /**
     * Das Bildchen fuer die Auswahl in der Workbench, relativ zum Portal und
     * mit dem Build im Pfad (`/assets/v/<commit>/looks/galaxy.png`): aendert
     * sich ein Look, holt Unity das neue Bild, ohne dass jemand einen Cache
     * leeren muss. Gerendert mit der Buehne, liegt unter `static/assets/looks/`.
     */
    val image: String = "",
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
        figures = FigureLooks.FIGURES.map { FigureView(it.key, it.label) },
        looks = FigureLooks.LOOKS.map { look ->
            LookView(look.key, look.label, look.pro, "${build.assets}/looks/${look.key}.png")
        },
    )

    /**
     * Was ein Clip nach einer Wahl traegt. `null` heisst "nicht angegeben" und
     * behaelt [current] (oder den Standard bei einem neuen Clip). Geprueft
     * wird nur, ob es Figur und Look gibt.
     */
    fun choose(figure: String?, look: String?, current: FigureChoice?): FigureChoice {
        val base = current ?: FigureChoice(FigureLooks.DEFAULT_FIGURE, FigureLooks.DEFAULT_LOOK)

        val chosenFigure = figure?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: base.figure
        if (FigureLooks.figure(chosenFigure) == null)
            throw PortalException.badRequest("invalid-figure", "'$chosenFigure' is not a figure.")

        val chosenLook = look?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: base.look
        if (FigureLooks.look(chosenLook) == null)
            throw PortalException.badRequest("invalid-look", "'$chosenLook' is not a look.")

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
