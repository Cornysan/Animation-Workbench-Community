package com.playmation.motionlabsbackend.account

import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

enum class Role { USER, ADMIN }

/**
 * ACTIVE darf alles, RESTRICTED lädt mit engerem Limit hoch (nach einem
 * bestätigten Verstoß), BANNED kommt nicht mehr herein.
 */
enum class AccountStatus { ACTIVE, RESTRICTED, BANNED }

@Entity
@Table(name = "account")
class Account(
    @Id
    var id: UUID = UUID.randomUUID(),

    var displayName: String,

    /**
     * Die oeffentliche Adresse dieses Kontos: `/u.html?u=<handle>`.
     *
     * Eindeutig, klein geschrieben, aus dem Anzeigenamen abgeleitet - und
     * danach unabhaengig von ihm. Wer bei Discord seinen Namen aendert, behaelt
     * seine Adresse; zwei Gleichnamige bekommen zwei verschiedene. Keins von
     * beidem kann der Anzeigename, und an genau ihm haengt sonst jeder
     * geteilte Profil-Link.
     *
     * Nullbar, weil Flyway vor dem ersten Login laeuft: [AccountHandles]
     * vergibt beim Anmelden, [HandleBackfill] holt Bestandskonten beim Start
     * nach. Wer ein Profil zeigen will, darf sich trotzdem auf ihn verlassen.
     */
    var handle: String? = null,

    /** Ein Satz ueber sich, auf dem Profil unter dem Namen. */
    var bio: String? = null,

    /**
     * Wo das Profilbild beim Anbieter liegt - eine Adresse bei Discord, GitHub
     * oder Google, nie das Bild selbst. Ausgeliefert wird es trotzdem nur ueber
     * diesen Server ([avatarPath]); die Adresse verlaesst ihn nicht.
     *
     * Sie kommt von der Anmeldung, mit der das Konto angelegt wurde (siehe
     * `AccountService.login`), und wird beim Speichern gegen `AvatarSources`
     * geprueft. Ohne Bild steht der Buchstabenkreis da.
     */
    var avatarUrl: String? = null,

    /**
     * Follower. Abgeleitet aus `account_follow` und in derselben Transaktion
     * fortgeschrieben - dieselbe Rollenverteilung wie bei
     * `AnimationPackage.likeCount`: die Tabelle ist die Wahrheit, die Spalte
     * die schnelle Antwort.
     */
    var followerCount: Long = 0,

    @Enumerated(EnumType.STRING)
    var role: Role = Role.USER,

    @Enumerated(EnumType.STRING)
    var status: AccountStatus = AccountStatus.ACTIVE,

    /** Bestätigte Verstöße (entfernte Uploads). */
    var strikes: Int = 0,

    /** Als unbegründet abgewiesene Meldungen - schützt Auto-Hide vor Griefing. */
    var falseReports: Int = 0,

    var createdAt: Instant = Instant.now(),
    var lastLoginAt: Instant? = null,
)

interface AccountRepository : JpaRepository<Account, UUID> {
    /** Die Adresse eines Profils - eindeutig, anders als der Anzeigename. */
    fun findByHandle(handle: String): Account?

    fun existsByHandle(handle: String): Boolean

    /** Konten ohne Handle - die Arbeitsliste von [HandleBackfill]. */
    fun findByHandleIsNull(): List<Account>

    /**
     * Konten zu einem Anzeigenamen - fuer "alles von dieser Person" im Katalog.
     *
     * Eine LISTE, kein einzelnes Konto: der Anzeigename ist nicht eindeutig.
     * Das Profil geht deshalb ueber [findByHandle]; dieser Weg bleibt, weil
     * `/browse.html?author=<Name>` in Umlauf ist und ein toter Filter
     * schlimmer waere als zwei Gleichnamige in einer Liste.
     */
    fun findByDisplayName(displayName: String): List<Account>
}

/**
 * Wo das Profilbild dieses Kontos liegt - immer ueber den eigenen Server
 * (`AvatarCache`), nie beim Anbieter direkt: sonst stuende dessen Kennung im
 * Quelltext, und der Anbieter saehe die IP-Adresse jedes Besuchers.
 *
 * `null`, wenn es kein Bild gibt (und fuer jeden Entwickler-Login); die Seite
 * zeigt dann den Buchstabenkreis. Stand vorher privat im ProfileService - seit
 * auch Kommentare und die Clip-Seite Bilder zeigen, braucht es EINE Regel.
 */
fun Account.avatarPath(): String? {
    val handle = handle ?: return null
    if (avatarUrl.isNullOrBlank()) return null
    return "/avatar/$handle.png"
}
