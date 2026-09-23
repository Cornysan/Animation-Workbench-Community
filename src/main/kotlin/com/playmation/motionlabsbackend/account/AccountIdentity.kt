package com.playmation.motionlabsbackend.account

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.net.URI
import java.time.Instant
import java.util.UUID

/**
 * Ein Weg in ein Konto: Anbieter plus die Kennung, die er fuer diese Person
 * nennt. Ein Konto hat einen oder mehrere, hoechstens einen je Anbieter.
 *
 * Die AELTESTE Anmeldung eines Kontos ist die, aus der Name und Bild kommen
 * (`AccountService.profileSource`). Eine spaeter verbundene aendert daran
 * nichts - sonst stuende nach dem ersten Google-Login ploetzlich der volle
 * Klarname auf einem Profil, das bisher einen Discord-Spitznamen trug.
 */
@Entity
@Table(name = "account_identity")
class AccountIdentity(
    @Id
    var id: UUID = UUID.randomUUID(),
    var accountId: UUID,
    /** `discord`, `github`, `google` - der Name der Registrierung in application.yaml. Oder `dev`. */
    var provider: String,
    /** Die Kennung beim Anbieter. Nie der Name: der aendert sich, die Kennung nicht. */
    var subject: String,
    var createdAt: Instant,
    var lastLoginAt: Instant? = null,
)

interface AccountIdentityRepository : JpaRepository<AccountIdentity, UUID> {
    fun findByProviderAndSubject(provider: String, subject: String): AccountIdentity?

    /** Aelteste zuerst - die erste ist die Quelle fuer Name und Bild. */
    fun findByAccountIdOrderByCreatedAtAsc(accountId: UUID): List<AccountIdentity>

    fun deleteByAccountId(accountId: UUID)
}

/**
 * Welche Bildadressen dieser Server abholen darf - und wie sie aus dem, was
 * ein Anbieter nennt, gebaut werden.
 *
 * KEIN OFFENER PROXY: `/avatar/<handle>.png` holt nur, was in `account.avatar_url`
 * steht, und dort kommt nur hinein, was hier durchgeht. Beim Abholen wird es
 * noch einmal geprueft ([isAllowed]), weil die Datenbank nicht die einzige
 * Quelle ist, der man glauben muss - die Migration V8 hat Adressen aus
 * Discord-Hashes zusammengesetzt, ohne sie zu pruefen.
 */
object AvatarSources {
    val HOSTS = setOf("cdn.discordapp.com", "avatars.githubusercontent.com", "lh3.googleusercontent.com")

    /** Was in Pfad und Abfrage stehen darf. Kein `%`, kein `@`, kein `\` - nichts, das umlenkt. */
    private val SAFE = Regex("^[A-Za-z0-9/_.=~?&-]+$")

    const val MAX_LENGTH = 512

    /**
     * Discord nennt einen Hash; die Adresse baut der Server. Kennung und Hash
     * werden beide VERLANGT, nicht vorausgesetzt: ein `/` oder `?` im Hash waere
     * kein Hash mehr, sondern ein Wegweiser. Animierte Avatare beginnen mit
     * `a_`; als .png liefert Discord davon das Standbild.
     */
    fun discord(id: String, hash: String?): String? {
        if (hash.isNullOrBlank()) return null
        if (id.isEmpty() || id.length > 32 || !id.all { it.isDigit() }) return null
        if (hash.length > 64 || !hash.all { it in 'a'..'f' || it in '0'..'9' || it == '_' }) return null
        return "https://cdn.discordapp.com/avatars/$id/$hash.png?size=128"
    }

    /**
     * GitHub braucht nur die Kennung. Jedes Konto hat dort ein Bild - wer keins
     * hochgeladen hat, bekommt ein erzeugtes Muster, und das ist allemal
     * persoenlicher als ein Buchstabe.
     */
    fun github(id: String): String? {
        if (id.isEmpty() || id.length > 32 || !id.all { it.isDigit() }) return null
        return "https://avatars.githubusercontent.com/u/$id?s=128"
    }

    /** Google nennt eine fertige Adresse. Sie wird genommen, wenn sie dorthin zeigt, wo Google Bilder ablegt. */
    fun google(picture: String?): String? {
        val url = picture?.trim() ?: return null
        return url.takeIf { isAllowed(it) && URI(it).host == "lh3.googleusercontent.com" }
    }

    fun isAllowed(url: String): Boolean {
        if (url.length > MAX_LENGTH) return false
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (uri.scheme != "https" || uri.host !in HOSTS) return false
        if (uri.port != -1 || uri.rawUserInfo != null || uri.rawFragment != null) return false
        val rest = uri.rawPath + (uri.rawQuery?.let { "?$it" } ?: "")
        return rest.startsWith("/") && SAFE.matches(rest)
    }
}
