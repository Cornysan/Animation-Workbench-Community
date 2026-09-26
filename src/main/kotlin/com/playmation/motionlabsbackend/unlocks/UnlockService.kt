package com.playmation.motionlabsbackend.unlocks

import com.playmation.motionlabsbackend.account.Account
import com.playmation.motionlabsbackend.catalog.AnimationPackage
import com.playmation.motionlabsbackend.catalog.AnimationPackageRepository
import com.playmation.motionlabsbackend.format.AwclipSchema
import com.playmation.motionlabsbackend.notification.NotificationKind
import com.playmation.motionlabsbackend.notification.NotificationLinks
import com.playmation.motionlabsbackend.notification.Notifier
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * Wer welchen Clip geholt hat.
 *
 * Der Vorgang hat einmal Muenzen bewegt; die sind weg, die Quittung ist
 * geblieben. Sie ist der Zaehler unter jedem Clip und die Grundlage der
 * Auszeichnungen - beides braucht eine Zeile je Konto und Paket, sonst ist
 * die Zahl nur eine Behauptung.
 *
 * Drei Regeln, die jede fuer sich einen Grund haben:
 *
 *  1. Am eigenen Clip zaehlt nichts. Sonst waere die Zahl unter jedem Clip
 *     das, was der Besitzer daraus macht.
 *  2. Eine Quittung je Konto und Paket, fuer immer. Abbestellen und erneut
 *     abonnieren zaehlt nie ein zweites Mal.
 *  3. Private Clips zaehlen nicht fuer den Besitzer. Wer einen Slug privat
 *     weitergereicht bekommt, fuellt damit kein Profil.
 */
@Service
class UnlockService(
    private val unlocks: PackageUnlockRepository,
    private val packages: AnimationPackageRepository,
    private val notifier: Notifier,
    private val clock: Clock,
) {

    /** true = jetzt gerade zum ersten Mal geholt. false = gab es schon, oder es ist der eigene Clip. */
    @Transactional
    fun unlock(account: Account, pkg: AnimationPackage): Boolean {
        //  Regel 1: der eigene Clip ist kein Vorgang.
        if (pkg.ownerId == account.id) return false

        //  Regel 2: die Quittung gilt fuer immer.
        if (unlocks.existsByPackageIdAndAccountId(pkg.id, account.id)) return false

        //  Regel 3: privat zaehlt nicht fuer den Besitzer.
        val listed = pkg.license == AwclipSchema.LICENSE_PUBLIC

        unlocks.save(
            PackageUnlock(
                packageId = pkg.id,
                accountId = account.id,
                earned = listed,
                createdAt = clock.instant(),
            )
        )

        //  Neu zaehlen statt hochzaehlen - der abgeleitete Wert darf nicht
        //  auseinanderlaufen, und die Abfrage ist ein Index-Zugriff.
        pkg.takeCount = unlocks.countByPackageId(pkg.id)
        packages.save(pkg)

        //  Eine runde Zahl ist eine Nachricht wert - ohne Namen: wer einen
        //  Clip holt, hat damit niemandem etwas mitgeteilt.
        if (listed && pkg.takeCount in MILESTONES) {
            notifier.send(pkg.ownerId, "'${pkg.title}' is now used in ${pkg.takeCount} projects.",
                NotificationKind.MILESTONE, NotificationLinks.clip(pkg.slug))
        }

        return true
    }

    companion object {
        val MILESTONES = setOf(10L, 25L, 50L, 100L, 250L, 500L, 1000L, 2500L, 5000L, 10000L)
    }

    fun hasUnlocked(packageId: UUID, accountId: UUID): Boolean =
        unlocks.existsByPackageIdAndAccountId(packageId, accountId)

    fun unlockedAmong(accountId: UUID, packageIds: Collection<UUID>): Set<UUID> =
        if (packageIds.isEmpty()) emptySet() else unlocks.unlockedAmong(accountId, packageIds).toSet()
}
