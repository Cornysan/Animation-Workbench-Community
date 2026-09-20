package com.playmation.motionlabsbackend.account

import com.playmation.motionlabsbackend.common.PortalException
import com.playmation.motionlabsbackend.config.PortalProperties
import com.playmation.motionlabsbackend.economy.QuestService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

@Service
class AccountService(
    private val accounts: AccountRepository,
    private val handles: AccountHandleService,
    private val quests: QuestService,
    private val properties: PortalProperties,
    private val clock: Clock,
) {
    /**
     * Anmeldung per Discord (oder Entwickler-Login): Konto anlegen oder
     * aktualisieren. Gesperrte Konten kommen nicht herein. Admin wird, wer in
     * `portal.admin-discord-ids` steht - bei jedem Login neu ausgewertet, damit
     * das Entfernen aus der Liste auch wirkt.
     *
     * @param avatar Discords Avatar-Hash, wenn die Anmeldung einen mitbringt.
     *   `null` laesst den vorhandenen stehen - der Entwickler-Login weiss
     *   nichts ueber Bilder und soll deshalb auch keines loeschen.
     */
    @Transactional
    fun login(discordId: String, displayName: String, avatar: String? = null): Account {
        val now = clock.instant()
        val account = accounts.findByDiscordId(discordId)
            ?: Account(discordId = discordId, displayName = displayName.take(80), createdAt = now)

        if (account.status == AccountStatus.BANNED)
            throw PortalException.forbidden("This account is banned.")

        account.displayName = displayName.take(80).ifBlank { "user" }
        account.role = if (discordId in properties.adminIds()) Role.ADMIN else Role.USER
        account.lastLoginAt = now
        avatar?.let { account.avatar = it.take(64) }

        //  Der Handle wird EINMAL vergeben und danach nie wieder aus dem
        //  Anzeigenamen nachgezogen: er ist die Adresse, und eine Adresse, die
        //  sich mit dem Discord-Namen aendert, bricht jeden geteilten Link.
        handles.ensure(account)

        val saved = accounts.save(account)

        //  Die Grundausstattung haengt an der ersten Anmeldung, nicht an einem
        //  Knopf. Sie bucht genau einmal je Konto - darum kann sie bei JEDER
        //  Anmeldung versucht werden, ohne dass jemand mitzaehlen muss.
        quests.grant(saved)
        return saved
    }

    fun get(id: UUID): Account = accounts.findById(id).orElseThrow { PortalException.notFound("Account not found") }

    /** Für alles, was schreibt: gesperrte Konten dürfen nichts mehr. */
    fun requireUsable(id: UUID): Account {
        val account = get(id)
        if (account.status == AccountStatus.BANNED) throw PortalException.forbidden("This account is banned.")
        return account
    }
}
