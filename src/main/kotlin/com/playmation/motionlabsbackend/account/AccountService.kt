package com.playmation.motionlabsbackend.account

import com.playmation.motionlabsbackend.common.PortalException
import com.playmation.motionlabsbackend.config.PortalProperties
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Was eine Anmeldung ueber eine Person weiss - schon auf das gekuerzt, was das
 * Portal behaelt. Keine E-Mail: kein Anbieter wird danach gefragt.
 */
data class SignIn(
    /** `discord`, `github`, `google` oder `dev`. */
    val provider: String,
    val subject: String,
    val displayName: String,
    /** Bildadresse beim Anbieter, schon durch [AvatarSources] gegangen - oder null. */
    val avatarUrl: String? = null,
    /**
     * Vorschlag fuer den Handle, wenn der Anbieter einen Benutzernamen kennt.
     * Bei GitHub ist das der Login: unter dem kennen die Leute einander dort,
     * und ein Handle aus dem Klarnamen waere eine schlechtere Adresse.
     */
    val handleHint: String? = null,
)

/** Eine verbundene Anmeldung, so wie die Kontoseite sie zeigt - ohne die Kennung beim Anbieter. */
data class ConnectedSignIn(
    val provider: String,
    val connectedAt: Instant,
    val lastUsedAt: Instant?,
    /** Kommen Name und Bild von hier? Siehe [AccountService.profileSource]. */
    val profileSource: Boolean,
)

@Service
class AccountService(
    private val accounts: AccountRepository,
    private val identities: AccountIdentityRepository,
    private val handles: AccountHandleService,
    private val properties: PortalProperties,
    private val clock: Clock,
) {
    /**
     * Anmeldung ueber einen Anbieter (oder den Entwickler-Login): Konto zu
     * dieser Anmeldung finden oder anlegen. Gesperrte Konten kommen nicht
     * herein.
     *
     * Name und Bild zieht NUR die Anmeldung nach, mit der das Konto angelegt
     * wurde - so wie bisher Discord. Eine spaeter verbundene laesst beides
     * stehen: wer sein Konto zusaetzlich mit Google verbindet, hat damit nicht
     * darum gebeten, dass dort sein Klarname erscheint.
     *
     * Moderator wird, wessen Konto IRGENDEINE Anmeldung aus
     * `portal.admin-discord-ids` traegt - bei jedem Login neu ausgewertet,
     * damit das Entfernen aus der Liste auch wirkt.
     */
    @Transactional
    fun login(signIn: SignIn): Account {
        val now = clock.instant()
        val identity = identities.findByProviderAndSubject(signIn.provider, signIn.subject)

        if (identity == null) {
            val account = accounts.save(Account(displayName = nameOf(signIn), createdAt = now))
            identities.save(AccountIdentity(
                accountId = account.id, provider = signIn.provider, subject = signIn.subject,
                createdAt = now, lastLoginAt = now,
            ))
            return finishLogin(account, signIn, now, fromProfileSource = true)
        }

        val account = get(identity.accountId)
        if (account.status == AccountStatus.BANNED)
            throw PortalException.forbidden("This account is banned.")

        identity.lastLoginAt = now
        return finishLogin(account, signIn, now, fromProfileSource = profileSource(account.id)?.id == identity.id)
    }

    private fun finishLogin(account: Account, signIn: SignIn, now: Instant, fromProfileSource: Boolean): Account {
        if (fromProfileSource) {
            account.displayName = nameOf(signIn)
            //  Auch null wird uebernommen: wer beim Anbieter sein Bild entfernt,
            //  soll es hier nicht behalten.
            account.avatarUrl = signIn.avatarUrl?.takeIf { AvatarSources.isAllowed(it) }
        }

        account.role = roleOf(account.id)
        account.lastLoginAt = now

        //  Der Handle wird EINMAL vergeben und danach nie wieder aus dem
        //  Anzeigenamen nachgezogen: er ist die Adresse, und eine Adresse, die
        //  sich mit dem Namen beim Anbieter aendert, bricht jeden geteilten Link.
        handles.ensure(account, signIn.handleHint)

        return accounts.save(account)
    }

    /**
     * Eine weitere Anmeldung an ein bestehendes Konto haengen. Der Anstoss kommt
     * von der Kontoseite; hier landet die Rueckkehr vom Anbieter.
     *
     * Hoechstens eine je Anbieter. Zwei GitHub-Konten an einem Portal-Konto
     * waeren moeglich, aber die Kontoseite muesste sie dann auseinanderhalten
     * - mit Namen, die das Portal absichtlich nicht speichert.
     */
    @Transactional
    fun connect(accountId: UUID, signIn: SignIn): Account {
        val account = requireUsable(accountId)
        val now = clock.instant()
        val existing = identities.findByProviderAndSubject(signIn.provider, signIn.subject)

        when {
            existing == null -> {
                if (identities.findByAccountIdOrderByCreatedAtAsc(accountId).any { it.provider == signIn.provider })
                    throw PortalException.conflict("provider-connected",
                        "A different account of this provider is already connected. Disconnect it first.")

                identities.save(AccountIdentity(
                    accountId = accountId, provider = signIn.provider, subject = signIn.subject,
                    createdAt = now, lastLoginAt = now,
                ))
            }

            //  Zusammenlegen waere der Wunsch dahinter und kann hier nicht
            //  passieren: das andere Konto hat eigene Clips, Kommentare und
            //  Folgende. Der Weg ist, dort die Anmeldung zu loesen - oder das
            //  andere Konto zu schliessen, wenn es ein Versehen war.
            existing.accountId != accountId -> throw PortalException.conflict("identity-taken",
                "This sign-in already belongs to a different account here.")

            else -> existing.lastLoginAt = now
        }

        account.role = roleOf(accountId)
        account.lastLoginAt = now
        return accounts.save(account)
    }

    /**
     * Eine Anmeldung loesen. Die letzte bleibt: ohne sie kaeme niemand mehr in
     * dieses Konto, auch nicht, um es zu schliessen.
     */
    @Transactional
    fun disconnect(accountId: UUID, provider: String): Account {
        val account = get(accountId)
        val all = identities.findByAccountIdOrderByCreatedAtAsc(accountId)
        val target = all.firstOrNull { it.provider == provider }
            ?: throw PortalException.notFound("This sign-in is not connected.")

        if (all.size == 1)
            throw PortalException.conflict("last-sign-in",
                "This is the only way into your account. Connect another one first.")

        identities.delete(target)

        //  Kamen Name und Bild von hier, geht das Bild mit - es waere das Bild
        //  eines Kontos, das nicht mehr dazugehoert. Der Name bleibt stehen,
        //  bis sich die naechste Quelle (jetzt die aelteste verbliebene)
        //  anmeldet; ein Konto ohne Namen gibt es nicht.
        if (all.first().id == target.id) account.avatarUrl = null

        account.role = roleOf(accountId)
        return accounts.save(account)
    }

    fun signIns(accountId: UUID): List<ConnectedSignIn> {
        val all = identities.findByAccountIdOrderByCreatedAtAsc(accountId)
        return all.mapIndexed { index, it -> ConnectedSignIn(it.provider, it.createdAt, it.lastLoginAt, index == 0) }
    }

    /** Die aelteste Anmeldung: aus ihr kommen Name und Bild. */
    fun profileSource(accountId: UUID): AccountIdentity? =
        identities.findByAccountIdOrderByCreatedAtAsc(accountId).firstOrNull()

    private fun roleOf(accountId: UUID): Role {
        val admins = properties.adminIds()
        if (admins.isEmpty()) return Role.USER
        val isAdmin = identities.findByAccountIdOrderByCreatedAtAsc(accountId)
            .any { "${it.provider}:${it.subject}" in admins }
        return if (isAdmin) Role.ADMIN else Role.USER
    }

    private fun nameOf(signIn: SignIn) = signIn.displayName.trim().take(80).ifBlank { "user" }

    fun get(id: UUID): Account = accounts.findById(id).orElseThrow { PortalException.notFound("Account not found") }

    /** Für alles, was schreibt: gesperrte Konten dürfen nichts mehr. */
    fun requireUsable(id: UUID): Account {
        val account = get(id)
        if (account.status == AccountStatus.BANNED) throw PortalException.forbidden("This account is banned.")
        return account
    }
}
