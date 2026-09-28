package com.playmation.motionlabsbackend.looks

import com.playmation.motionlabsbackend.account.AccountRepository
import com.playmation.motionlabsbackend.auth.PortalPrincipal
import com.playmation.motionlabsbackend.auth.portalPrincipal
import com.playmation.motionlabsbackend.common.PortalException
import com.playmation.motionlabsbackend.profile.AchievementService
import com.playmation.motionlabsbackend.profile.ProfileService
import com.playmation.motionlabsbackend.profile.TierCheck
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Figur und Look eines Clips - wie das Mannequin aussieht, das ALLE auf Karte,
 * Clip-Seite und Link-Vorschau sehen.
 *
 * Der Ersteller waehlt beides beim Teilen (in der Workbench) und kann es
 * spaeter aendern. Die Figur ist frei; die meisten Looks haengen an einer
 * Auszeichnung ([AchievementService]) und werden mit ihr freigeschaltet.
 *
 * ── Nichts wird verliehen, nichts wird gekauft ───────────────────────────
 *
 * Ein Look ist frei, sobald die Auszeichnung dahinter die Stufe erreicht -
 * gerechnet, nicht gespeichert, genau wie die Auszeichnung selbst. Es gibt
 * keine Waehrung und nichts zu kaufen (siehe die Unity-Linie in der Doku zum
 * Portal: es darf nie etwas kaeuflich werden).
 *
 * Faellt ein Konto spaeter unter eine Stufe zurueck - ein Clip zurueckgezogen,
 * ein Herz weg -, behalten seine Clips ihren Look. Geprueft wird nur beim
 * WAEHLEN; was einmal gewaehlt ist, steht am Clip.
 *
 * Admins duerfen jeden Look waehlen: fuer Starter-Clips und zum Ansehen.
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

    /**
     * [achievement] und [tier] nennen die Stufe einer Auszeichnung, die den
     * Look freischaltet; ohne [achievement] ist er frei. Die Reihenfolge ist
     * die der Auswahl.
     */
    data class Look(val key: String, val label: String, val achievement: String? = null, val tier: Int = 1)

    val LOOKS = listOf(
        Look(DEFAULT_LOOK, "Classic"),
        Look("graphite", "Graphite"),
        Look("mint", "Mint"),
        Look("coral", "Coral"),
        Look("ocean", "Ocean", "clips", 1),
        Look("sunset", "Sunset", "clips", 1),
        Look("marble", "Marble", "collections", 1),
        Look("neon", "Neon", "supporter", 1),
        Look("gold", "Gold", "likes", 1),
        Look("chrome", "Chrome", "unlocks", 1),
        Look("galaxy", "Galaxy", "clips", 2),
        //  Nur wer in der geschlossenen Beta dabei war - danach nie wieder.
        Look("hologram", "Hologram", "beta", 1),
    )

    fun figure(key: String): Figure? = FIGURES.firstOrNull { it.key == key }
    fun look(key: String): Look? = LOOKS.firstOrNull { it.key == key }
}

data class FigureView(val key: String, val label: String)

/**
 * Ein Look, wie die Auswahl ihn zeigt. Gesperrt steht dabei, was zu tun ist
 * ([task], etwa "Share 5 clips") und wie weit es ist.
 */
data class LookView(
    val key: String,
    val label: String,
    val unlocked: Boolean,
    /** Der Name der Auszeichnung dahinter ("Contributor"), oder null bei einem freien Look. */
    val achievement: String? = null,
    val task: String? = null,
    val progress: Long? = null,
    val goal: Long? = null,
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
class LookService(
    private val accounts: AccountRepository,
    private val profiles: ProfileService,
    private val achievements: AchievementService,
) {
    /** Alle Figuren und Looks, und welche davon [principal] tragen darf. */
    @Transactional(readOnly = true)
    fun looks(principal: PortalPrincipal?): LooksView {
        val checks = checks(principal)
        return LooksView(
            figures = FigureLooks.FIGURES.map { FigureView(it.key, it.label) },
            looks = FigureLooks.LOOKS.map { look ->
                val check = checks(look)
                LookView(
                    key = look.key,
                    label = look.label,
                    unlocked = check == null || check.met || principal?.isAdmin == true,
                    achievement = check?.achievement,
                    task = check?.task,
                    progress = check?.progress,
                    goal = check?.goal,
                )
            },
        )
    }

    /**
     * Was ein Clip nach einer Wahl traegt. `null` heisst "nicht angegeben" und
     * behaelt [current] (oder den Standard bei einem neuen Clip).
     *
     * Geprueft wird nur, was sich AENDERT: ein Look, den der Clip schon
     * traegt, bleibt erlaubt, auch wenn die Stufe dahinter inzwischen
     * verloren ist.
     */
    @Transactional(readOnly = true)
    fun choose(principal: PortalPrincipal, figure: String?, look: String?, current: FigureChoice?): FigureChoice {
        val base = current ?: FigureChoice(FigureLooks.DEFAULT_FIGURE, FigureLooks.DEFAULT_LOOK)

        val chosenFigure = figure?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: base.figure
        if (FigureLooks.figure(chosenFigure) == null)
            throw PortalException.badRequest("invalid-figure", "'$chosenFigure' is not a figure.")

        val chosenLook = look?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: base.look
        val entry = FigureLooks.look(chosenLook)
            ?: throw PortalException.badRequest("invalid-look", "'$chosenLook' is not a look.")

        if (chosenLook != base.look && !principal.isAdmin) {
            val check = checks(principal)(entry)
            if (check != null && !check.met) {
                throw PortalException(HttpStatus.BAD_REQUEST, "look-locked",
                    "${entry.label} unlocks with ${check.achievement}: ${check.task} (${check.progress}/${check.goal}).")
            }
        }

        return FigureChoice(chosenFigure, chosenLook)
    }

    /**
     * Je Look die Stufe, an der er haengt - mit den Zahlen dieses Kontos.
     * Die Zahlen werden einmal gezaehlt, nicht je Look.
     */
    private fun checks(principal: PortalPrincipal?): (FigureLooks.Look) -> TierCheck? {
        val account = principal?.let { accounts.findById(it.accountId).orElse(null) }
        val stats = account?.let(profiles::stats)
        return { look ->
            val key = look.achievement
            when {
                key == null -> null
                //  Ohne Konto: dieselbe Stufe, bei null. Die Auswahl zeigt
                //  dann, was es braucht, statt nichts.
                stats == null -> achievements.check(profiles.emptyStats(), key, look.tier)
                else -> achievements.check(stats, key, look.tier)
            }
        }
    }
}

@RestController
@RequestMapping("/api/v1/looks")
class LookController(private val looks: LookService) {

    /** Offen lesbar: die Auswahl zeigt auch ohne Anmeldung, was es gibt. */
    @GetMapping
    fun looks(authentication: Authentication?) = looks.looks(authentication.portalPrincipal())
}
