package com.playmation.motionlabsbackend.economy

import com.playmation.motionlabsbackend.account.Account
import com.playmation.motionlabsbackend.config.PortalProperties
import com.playmation.motionlabsbackend.moderation.Notification
import com.playmation.motionlabsbackend.moderation.NotificationRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.WeekFields
import java.util.UUID

data class QuestView(
    val key: String,
    val title: String,
    val description: String,
    val progress: Int,
    val goal: Int,
    val reward: Int,
    val done: Boolean,
    /** Wann die Aufgabe neu anfaengt. null = ohne Frist. */
    val resetsAt: Instant?,
)

data class MilestoneView(val unlocks: Int, val reward: Int, val reached: Boolean)

data class QuestOverview(
    val quests: List<QuestView>,
    val milestones: List<MilestoneView>,
    /** Wie oft die eigenen Clips insgesamt freigeschaltet wurden. */
    val unlocksEarned: Long,
)

/**
 * Discord-IDs sind Snowflakes: die oberen Bits tragen den Zeitpunkt, zu dem
 * das Konto entstanden ist. Das Alter kostet deshalb keinen zusaetzlichen
 * Abruf bei Discord - es steht in der Kennung, die ohnehin gespeichert ist.
 */
object DiscordSnowflake {
    private const val EPOCH_MILLIS = 1_420_070_400_000L

    /** null, wenn die Kennung kein Snowflake ist - etwa beim Entwickler-Login. */
    fun createdAt(discordId: String): Instant? {
        val id = discordId.toLongOrNull() ?: return null
        if (id <= 0) return null
        return Instant.ofEpochMilli((id shr 22) + EPOCH_MILLIS)
    }
}

/**
 * Die Quellen, aus denen Muenzen entstehen.
 *
 * Pablos Zahlen sind bewusst schwindend: Freischalten kostet 10, eine
 * Freischaltung des eigenen Clips bringt 1. Pro Vorgang verschwinden also
 * netto 9 Muenzen. Die Wirtschaft traegt sich nicht aus sich selbst, sondern
 * aus drei Quellen - der Grundausstattung, der Wochenaufgabe und den
 * Meilensteinen. Die Einnahme je Freischaltung ist vor allem ein
 * Beliebtheitssignal.
 *
 * Es gibt keinen Abholschritt. Wer eine Aufgabe erfuellt, hat die Muenzen -
 * ein Knopf "Belohnung abholen" waere eine Huerde ohne Gegenwert.
 */
@Service
class QuestService(
    private val coins: CoinService,
    private val progress: QuestProgressRepository,
    private val unlocks: PackageUnlockRepository,
    private val notifications: NotificationRepository,
    private val properties: PortalProperties,
    private val clock: Clock,
) {
    companion object {
        const val SHARE_A_CLIP = "share-a-clip"
        const val LIFETIME = "lifetime"
    }

    /**
     * Die Grundausstattung. Einmal je Konto, und erst ab einem Mindestalter
     * des Discord-Kontos: ein frisch angelegtes Zweitkonto soll nicht fuenf
     * Freischaltungen wert sein.
     */
    @Transactional
    fun grant(account: Account): Boolean {
        val amount = properties.economy.startingGrant
        if (amount <= 0) return false

        val born = DiscordSnowflake.createdAt(account.discordId)
        if (born != null) {
            val days = java.time.Duration.between(born, clock.instant()).toDays()
            if (days < properties.economy.grantMinAccountAgeDays) return false
        }

        return coins.post(account.id, amount.toLong(), CoinReason.GRANT, "grant:${account.id}")
    }

    /**
     * Ein Clip wurde geteilt. Die Wochenaufgabe ist damit erfuellt - einmal
     * pro Woche, egal wie viele Clips danach noch kommen. Sie ist die
     * eigentliche Beitragspraemie, nur mit hartem Deckel statt als Kopfgeld
     * je Upload.
     */
    @Transactional
    fun onClipShared(accountId: UUID) {
        val reward = properties.economy.weeklyShareReward
        if (reward <= 0) return

        val period = weekKey()
        val row = progress.findByAccountIdAndQuestKeyAndPeriodKey(accountId, SHARE_A_CLIP, period)
            ?: QuestProgress(accountId = accountId, questKey = SHARE_A_CLIP, periodKey = period)

        row.progress += 1
        if (row.completedAt == null) row.completedAt = clock.instant()
        progress.save(row)

        coins.post(accountId, reward.toLong(), CoinReason.QUEST, "quest:$accountId:$SHARE_A_CLIP:$period")
    }

    /**
     * Ein eigener Clip wurde freigeschaltet. Geprueft wird gegen die Summe
     * aller zugerechneten Freischaltungen, nicht gegen einen mitgefuehrten
     * Zaehler - so heilt ein verpasster Meilenstein sich beim naechsten Mal
     * von selbst.
     */
    @Transactional
    fun checkMilestones(ownerId: UUID) {
        val reached = unlocks.countEarnedForOwner(ownerId)
        for (milestone in properties.economy.milestones) {
            if (reached < milestone.unlocks) continue
            val booked = coins.post(
                ownerId, milestone.reward.toLong(), CoinReason.MILESTONE,
                "milestone:$ownerId:${milestone.unlocks}",
            )

            //  Nur beim ersten Mal - `post` sagt, ob es eine Buchung war.
            if (booked) notifications.save(
                Notification(
                    accountId = ownerId,
                    message = "Milestone reached: your clips were unlocked ${milestone.unlocks} times. " +
                        "${milestone.reward} coins are yours.",
                    createdAt = clock.instant(),
                )
            )
        }
    }

    @Transactional(readOnly = true)
    fun overview(accountId: UUID): QuestOverview {
        val period = weekKey()
        val weekly = progress.findByAccountIdAndQuestKeyAndPeriodKey(accountId, SHARE_A_CLIP, period)
        val reached = unlocks.countEarnedForOwner(accountId)

        return QuestOverview(
            listOf(
                QuestView(
                    SHARE_A_CLIP,
                    "Share a clip",
                    "Upload one animation this week.",
                    if (weekly?.completedAt != null) 1 else 0,
                    1,
                    properties.economy.weeklyShareReward,
                    weekly?.completedAt != null,
                    nextWeek(),
                )
            ),
            properties.economy.milestones.map { MilestoneView(it.unlocks, it.reward, reached >= it.unlocks) },
            reached,
        )
    }

    // ── Hilfen ──────────────────────────────────────────────────────────

    private fun today(): LocalDate = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)

    private fun weekKey(): String {
        val date = today()
        val week = date.get(WeekFields.ISO.weekOfWeekBasedYear())
        val year = date.get(WeekFields.ISO.weekBasedYear())
        return "weekly:%d-W%02d".format(year, week)
    }

    /** Montag 00:00 UTC. Die Workbench zeigt daraus "resets in 3 days". */
    private fun nextWeek(): Instant =
        today().with(WeekFields.ISO.dayOfWeek(), 1).plusWeeks(1).atStartOfDay(ZoneOffset.UTC).toInstant()
}
