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

    /** Discord-Nutzer-ID; beim Entwickler-Login "dev:<name>". */
    var discordId: String,

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
     * Discords Avatar-HASH, nicht das Bild. Daraus baut die Seite
     * `https://cdn.discordapp.com/avatars/<discordId>/<avatar>.png` - das Bild
     * bleibt bei Discord, wir speichern nur, wie es heisst. Ohne Hash steht
     * der Buchstabenkreis da, den es ohnehin schon gibt.
     */
    var avatar: String? = null,

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

    /**
     * Muenzstand. Abgeleitet aus `coin_entry` und in derselben Transaktion
     * fortgeschrieben; die Wahrheit steht im Protokoll, das hier ist die
     * schnelle Antwort. Darf negativ werden - eine Rueckbuchung ist eine
     * Korrektur und scheitert nicht an einem leeren Konto.
     */
    var coinBalance: Long = 0,
)

interface AccountRepository : JpaRepository<Account, UUID> {
    fun findByDiscordId(discordId: String): Account?

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
