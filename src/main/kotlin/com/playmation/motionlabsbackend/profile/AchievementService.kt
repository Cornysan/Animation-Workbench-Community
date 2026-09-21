package com.playmation.motionlabsbackend.profile

import com.playmation.motionlabsbackend.config.PortalProperties
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Eine Auszeichnung, wie die Seite sie zeigt.
 *
 * [tier] ist die erreichte Stufe (0 = noch keine), [goal] das naechste Ziel
 * oder `null`, wenn die hoechste Stufe steht. Beides steht drin, damit das
 * eigene Profil "noch 3 bis Stufe 2" zeigen kann, ohne zu rechnen.
 */
data class AchievementView(
    val key: String,
    val name: String,
    val description: String,
    val icon: String,
    val tier: Int,
    val tiers: Int,
    val progress: Long,
    val goal: Long?,
    val earned: Boolean,
)

/** Was eine Auszeichnung braucht, um sich auszurechnen - alles vorhandene Zahlen. */
data class ProfileStats(
    val clips: Long,
    val collections: Long,
    val likesReceived: Long,
    val takes: Long,
    val unlocksEarned: Long,
    val comments: Long,
    val followers: Long,
    val joinedAt: Instant,
)

/**
 * Auszeichnungen - ABGELEITET, nicht verliehen.
 *
 * Es gibt keine Tabelle, in der steht, wer was bekommen hat. Jede Auszeichnung
 * ist eine Regel auf Zahlen, die ohnehin gezaehlt werden, und wird bei jedem
 * Aufruf neu gerechnet - gegen die Summe, nicht gegen einen mitgefuehrten
 * Zaehler: ein verpasster Lauf heilt sich von selbst, und alles gilt
 * rueckwirkend.
 *
 * Der Preis dafuer steht hier, damit ihn niemand suchen muss: es gibt kein
 * Verleihungsdatum und keine "neu freigeschaltet"-Meldung. Wer die will,
 * braucht eine Tabelle mit Zeitpunkt - dann aber auch einen Pruefpunkt an
 * jedem ausloesenden Vorgang und einen Nachlauf fuer Bestandskonten.
 */
@Service
class AchievementService(
    private val properties: PortalProperties,
    private val clock: Clock,
) {
    /**
     * Eine Familie von Auszeichnungen: ein Name, mehrere Stufen.
     *
     * Stufen statt einzelner Abzeichen, weil "5 Clips geteilt" und "100 Clips
     * geteilt" dieselbe Sache in verschiedener Groesse sind. Eine Wand aus
     * zwanzig Einzelabzeichen sagt weniger als sechs, die eine Stufe tragen.
     */
    private data class Family(
        val key: String,
        val name: String,
        val icon: String,
        val thresholds: List<Long>,
        val describe: (Long) -> String,
        val value: (ProfileStats) -> Long,
    )

    private val families = listOf(
        Family("clips", "Contributor", "share", listOf(1, 5, 25, 100),
            { goal -> if (goal == 1L) "Shared a clip with the community." else "Shared $goal clips with the community." },
            { it.clips }),

        Family("likes", "Well liked", "heart", listOf(10, 100, 1000),
            { goal -> "Received $goal hearts across all clips." },
            { it.likesReceived }),

        Family("unlocks", "In use", "download", listOf(10, 100, 1000),
            { goal -> "Clips from this account were unlocked $goal times." },
            { it.unlocksEarned }),

        Family("collections", "Curator", "folder", listOf(1, 5, 20),
            { goal -> if (goal == 1L) "Published a collection." else "Published $goal collections." },
            { it.collections }),

        Family("comments", "In the conversation", "comment", listOf(10, 100),
            { goal -> "Left $goal comments under other people's clips." },
            { it.comments }),

        Family("followers", "Followed", "users", listOf(1, 10, 100),
            { goal -> if (goal == 1L) "Someone follows this account." else "$goal people follow this account." },
            { it.followers }),
    )

    /**
     * Alles, was dieses Konto tragen kann - erreichte und noch offene.
     *
     * Die offenen kommen mit, weil das eigene Profil zeigen soll, was als
     * naechstes ansteht. Fremde Profile blenden sie aus; das ist eine
     * Entscheidung der Seite, nicht dieser Rechnung.
     */
    fun forStats(stats: ProfileStats): List<AchievementView> {
        val result = families.map { family ->
            val value = family.value(stats)
            val tier = family.thresholds.count { value >= it }
            val goal = family.thresholds.getOrNull(tier)

            AchievementView(
                key = family.key,
                name = family.name,
                //  Der Satz beschreibt die ERREICHTE Stufe, solange es eine
                //  gibt - sonst die naechste. Ein Abzeichen, das "100 Clips"
                //  sagt, waehrend jemand bei 5 steht, waere eine Falschaussage.
                description = family.describe(family.thresholds.getOrNull(tier - 1) ?: family.thresholds.first()),
                icon = family.icon,
                tier = tier,
                tiers = family.thresholds.size,
                progress = value,
                goal = goal,
                earned = tier > 0,
            )
        }

        return result + beta(stats) + veteran(stats)
    }

    /**
     * Wer in der geschlossenen Beta dabei war. Kein Fortschritt, keine Stufe -
     * eine Tatsache mit Stichtag, die spaeter niemand mehr erwerben kann.
     */
    private fun beta(stats: ProfileStats): AchievementView {
        val earned = properties.betaUntil?.let { stats.joinedAt.isBefore(it) } ?: true
        return AchievementView(
            key = "beta",
            name = "Early access",
            description = "Joined while the community was in closed beta.",
            icon = "star",
            tier = if (earned) 1 else 0,
            tiers = 1,
            progress = if (earned) 1 else 0,
            goal = if (earned) null else 1,
            earned = earned,
        )
    }

    /** Ein Jahr dabei. Die einzige Auszeichnung, die von selbst kommt. */
    private fun veteran(stats: ProfileStats): AchievementView {
        val days = Duration.between(stats.joinedAt, clock.instant()).toDays().coerceAtLeast(0)
        val earned = days >= 365
        return AchievementView(
            key = "veteran",
            name = "A year in",
            description = "One year with the community.",
            icon = "clock",
            tier = if (earned) 1 else 0,
            tiers = 1,
            progress = days,
            goal = if (earned) null else 365,
            earned = earned,
        )
    }
}
