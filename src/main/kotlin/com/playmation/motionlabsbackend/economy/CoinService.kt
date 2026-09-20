package com.playmation.motionlabsbackend.economy

import com.playmation.motionlabsbackend.account.AccountRepository
import com.playmation.motionlabsbackend.common.PortalException
import com.playmation.motionlabsbackend.config.PortalProperties
import com.playmation.motionlabsbackend.system.SystemSettingsService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class CoinEntryView(
    val amount: Long,
    val reason: String,
    val createdAt: Instant,
)

data class CoinBalance(
    val coins: Long,
    /** Was eine Freischaltung kostet. 0, solange das Tor aus ist. */
    val unlockCost: Long,
    val gateOpen: Boolean,
    val recent: List<CoinEntryView>,
)

/**
 * Der einzige Schreibweg fuer Muenzen.
 *
 * Jede Buchung traegt einen [CoinEntry.idempotencyKey], der den VORGANG
 * benennt - `unlock:<paket>:<konto>`, `milestone:<konto>:10`. Kommt derselbe
 * Vorgang ein zweites Mal an, passiert nichts. Das ist wichtiger als es
 * aussieht: die Workbench wiederholt Aufrufe nach einem Netzfehler, und ein
 * Abonnement kann nach einem Recompile ein zweites Mal losgeschickt werden.
 *
 * Der Saldo in `account.coin_balance` ist abgeleitet. Die Wahrheit steht im
 * Protokoll; der Saldo ist die schnelle Antwort und wird in derselben
 * Transaktion fortgeschrieben.
 */
@Service
class CoinService(
    private val entries: CoinEntryRepository,
    private val accounts: AccountRepository,
    private val settings: SystemSettingsService,
    private val properties: PortalProperties,
    private val clock: Clock,
) {

    /** true = es wurde gebucht. false = diesen Vorgang gab es schon. */
    @Transactional
    fun post(
        accountId: UUID,
        amount: Long,
        reason: String,
        idempotencyKey: String,
        refType: String? = null,
        refId: UUID? = null,
    ): Boolean {
        if (amount == 0L) return false
        if (entries.existsByIdempotencyKey(idempotencyKey)) return false

        val account = accounts.findById(accountId).orElseThrow { PortalException.notFound("Account not found") }

        entries.save(
            CoinEntry(
                accountId = accountId,
                amount = amount,
                reason = reason,
                refType = refType,
                refId = refId,
                idempotencyKey = idempotencyKey,
                createdAt = clock.instant(),
            )
        )

        //  Darf negativ werden. Eine Rueckbuchung nach einer Rechteverletzung
        //  ist eine Korrektur, kein Wunsch - sie darf nicht daran scheitern,
        //  dass das Konto inzwischen leer ist.
        account.coinBalance += amount
        accounts.save(account)
        return true
    }

    fun balanceOf(accountId: UUID): Long =
        accounts.findById(accountId).map { it.coinBalance }.orElse(0L)

    @Transactional(readOnly = true)
    fun overview(accountId: UUID): CoinBalance = CoinBalance(
        balanceOf(accountId),
        if (gateOpen()) properties.economy.unlockCost.toLong() else 0L,
        gateOpen(),
        entries.findTop20ByAccountIdOrderByCreatedAtDesc(accountId)
            .map { CoinEntryView(it.amount, it.reason, it.createdAt) },
    )

    /**
     * Das Tor. Steht es offen, kostet Freischalten; steht es zu, ist alles
     * umsonst - die Gutschriften laufen trotzdem weiter, damit beim Umlegen
     * niemand bei null steht.
     */
    fun gateOpen(): Boolean = settings.economyEnabled()

    /**
     * Tagesdeckel auf Einnahmen. Er bremst Ringe aus Zweitkonten, ohne den
     * Meilensteinfortschritt anzuhalten - der haengt an der Quittung, nicht
     * an der Buchung.
     */
    fun earnedToday(accountId: UUID): Long =
        entries.sumSince(accountId, CoinReason.EARN, clock.instant().minus(Duration.ofDays(1)))
}
