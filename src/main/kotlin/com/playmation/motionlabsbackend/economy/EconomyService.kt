package com.playmation.motionlabsbackend.economy

import com.playmation.motionlabsbackend.account.Account
import com.playmation.motionlabsbackend.catalog.AnimationPackage
import com.playmation.motionlabsbackend.catalog.AnimationPackageRepository
import com.playmation.motionlabsbackend.common.PortalException
import com.playmation.motionlabsbackend.config.PortalProperties
import com.playmation.motionlabsbackend.format.AwclipSchema
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * Freischalten und Zurueckbuchen.
 *
 * Vier Regeln, die dem Missbrauch die Luft nehmen, und die jede fuer sich
 * einen Grund haben:
 *
 *  1. Am eigenen Clip verdient niemand. Er ist auch kostenlos - und er zaehlt
 *     nicht mit, sonst waere die Zahl unter jedem Clip eine Behauptung.
 *  2. Eine Quittung je Konto und Paket, fuer immer. Abbestellen und erneut
 *     abonnieren kostet nie ein zweites Mal.
 *  3. Private Clips sind kostenlos und zahlen nichts. Wer einen Slug privat
 *     weitergereicht bekommt, soll nicht an einer Kasse stehen.
 *  4. Ein Tagesdeckel auf Einnahmen. Er bremst Ringe aus Zweitkonten, ohne
 *     den Meilensteinfortschritt anzuhalten - der haengt an der Quittung.
 */
@Service
class EconomyService(
    private val unlocks: PackageUnlockRepository,
    private val entries: CoinEntryRepository,
    private val packages: AnimationPackageRepository,
    private val coins: CoinService,
    private val quests: QuestService,
    private val properties: PortalProperties,
    private val clock: Clock,
) {

    /** true = jetzt gerade freigeschaltet. false = gab es schon, oder es ist der eigene Clip. */
    @Transactional
    fun unlock(account: Account, pkg: AnimationPackage): Boolean {
        //  Regel 1: der eigene Clip ist kein Vorgang.
        if (pkg.ownerId == account.id) return false

        //  Regel 2: die Quittung gilt fuer immer.
        if (unlocks.existsByPackageIdAndAccountId(pkg.id, account.id)) return false

        //  Regel 3: privat heisst kostenlos und ohne Erloes.
        val listed = pkg.license == AwclipSchema.LICENSE_PUBLIC
        val cost = if (coins.gateOpen() && listed) properties.economy.unlockCost.toLong() else 0L

        if (cost > 0 && coins.balanceOf(account.id) < cost)
            throw PortalException.conflict(
                "not-enough-coins",
                "You need $cost coins to unlock this clip - share one of your own to earn more.",
            )

        unlocks.save(
            PackageUnlock(
                packageId = pkg.id,
                accountId = account.id,
                costPaid = cost,
                earned = listed,
                createdAt = clock.instant(),
            )
        )

        //  Neu zaehlen statt hochzaehlen - der abgeleitete Wert darf nicht
        //  auseinanderlaufen, und die Abfrage ist ein Index-Zugriff.
        pkg.takeCount = unlocks.countByPackageId(pkg.id)
        packages.save(pkg)

        if (cost > 0)
            coins.post(account.id, -cost, CoinReason.UNLOCK, "unlock:${pkg.id}:${account.id}", "package", pkg.id)

        if (listed) {
            payOwner(pkg, account.id)
            quests.checkMilestones(pkg.ownerId)
        }

        return true
    }

    fun hasUnlocked(packageId: UUID, accountId: UUID): Boolean =
        unlocks.existsByPackageIdAndAccountId(packageId, accountId)

    fun unlockedAmong(accountId: UUID, packageIds: Collection<UUID>): Set<UUID> =
        if (packageIds.isEmpty()) emptySet() else unlocks.unlockedAmong(accountId, packageIds).toSet()

    /**
     * Ein Paket verschwindet, weil es jemandem anderen gehoerte. Was daran
     * verdient wurde, war nie verdient - aber die Buchung bleibt stehen und
     * bekommt eine Gegenbuchung. Ein anhaengendes Protokoll erzaehlt auch die
     * Korrektur, statt sie verschwinden zu lassen.
     */
    @Transactional
    fun reverseEarnings(pkg: AnimationPackage) {
        for (entry in entries.findByRefTypeAndRefIdAndReason("package", pkg.id, CoinReason.EARN)) {
            coins.post(
                entry.accountId, -entry.amount, CoinReason.REVERSAL,
                "reversal:${entry.id}", "package", pkg.id,
            )
        }
    }

    private fun payOwner(pkg: AnimationPackage, buyerId: UUID) {
        val reward = properties.economy.unlockReward.toLong()
        if (reward <= 0) return

        val cap = properties.economy.dailyEarnCap
        if (cap > 0 && coins.earnedToday(pkg.ownerId) >= cap) return

        coins.post(pkg.ownerId, reward, CoinReason.EARN, "earn:${pkg.id}:$buyerId", "package", pkg.id)
    }
}
