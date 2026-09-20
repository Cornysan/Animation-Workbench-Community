package com.playmation.motionlabsbackend.account

import com.playmation.motionlabsbackend.common.PortalException
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.text.Normalizer
import java.util.UUID

/**
 * Der Handle: die oeffentliche Adresse eines Kontos.
 *
 * Abgeleitet aus dem Anzeigenamen, aber danach von ihm getrennt. Der
 * Anzeigename kommt von Discord und aendert sich, wann immer jemand dort etwas
 * umstellt; eine Adresse, die das mitmacht, bricht bei jeder Umbenennung jeden
 * geteilten Link. Und weil zwei Leute denselben Anzeigenamen tragen duerfen,
 * fuehrte er zwei Profile auf dieselbe Seite.
 *
 * Die Regeln sind absichtlich eng: klein, ASCII, `a-z0-9-_`, 3 bis 32 Zeichen.
 * Was nicht hineinpasst, faellt weg statt ersetzt zu werden - ein Handle aus
 * Fragezeichen waere keine bessere Antwort auf einen Namen in Kanji als
 * `user-3f9a1c`, und der ist wenigstens vorlesbar.
 */
object AccountHandles {
    const val MIN_LENGTH = 3
    const val MAX_LENGTH = 32

    private val ALLOWED = Regex("^[a-z0-9_-]{$MIN_LENGTH,$MAX_LENGTH}$")

    /** Gibt es diesen Handle schon? Die Antwort kommt von der Datenbank. */
    fun interface Taken {
        fun isTaken(candidate: String): Boolean
    }

    /**
     * Der Vorschlag aus einem Anzeigenamen - ohne Kollisionspruefung.
     * Leer, wenn nichts Brauchbares uebrig bleibt.
     */
    fun slugify(displayName: String): String {
        //  Akzente zerlegen und die Zeichen darunter behalten: aus "Renée"
        //  wird "renee", nicht "ren".
        val decomposed = Normalizer.normalize(displayName, Normalizer.Form.NFKD)

        val cleaned = buildString {
            for (char in decomposed.lowercase()) {
                when {
                    char in 'a'..'z' || char in '0'..'9' -> append(char)
                    char == '-' || char == '_' -> append(char)
                    char.isWhitespace() || char == '.' -> append('-')
                    else -> Unit
                }
            }
        }

        return cleaned.trim('-', '_')
            .replace(Regex("-{2,}"), "-")
            .take(MAX_LENGTH)
            .trim('-', '_')
    }

    /**
     * Ein freier Handle fuer dieses Konto.
     *
     * Die Kollision bekommt eine Zahl, nicht eine zweite Ableitung: `pablo`,
     * `pablo-2`, `pablo-3`. Wer als Zweiter kommt, soll sich wiedererkennen.
     * Bleibt aus dem Namen nichts uebrig (oder sind alle Zahlen vergeben),
     * traegt der Notnagel die Konto-Kennung - haesslich, aber eindeutig und
     * endlich.
     */
    fun assign(displayName: String, accountId: UUID, taken: Taken): String {
        val base = slugify(displayName).takeIf { it.length >= MIN_LENGTH }

        if (base != null && !taken.isTaken(base)) return base

        if (base != null) {
            for (suffix in 2..99) {
                val candidate = base.take(MAX_LENGTH - suffix.toString().length - 1) + "-" + suffix
                if (!taken.isTaken(candidate)) return candidate
            }
        }

        val fallback = "user-" + accountId.toString().replace("-", "").take(6)
        if (!taken.isTaken(fallback)) return fallback

        return "user-" + accountId.toString().replace("-", "").take(12)
    }

    /** Fuer die Selbstvergabe auf dem Profil: sagt, was falsch ist. */
    fun validate(handle: String): String {
        val trimmed = handle.trim().lowercase()
        if (trimmed.length < MIN_LENGTH || trimmed.length > MAX_LENGTH)
            throw PortalException.badRequest(
                "invalid-handle", "A handle is $MIN_LENGTH to $MAX_LENGTH characters long.")
        if (!ALLOWED.matches(trimmed))
            throw PortalException.badRequest(
                "invalid-handle", "Use lowercase letters, digits, '-' and '_'.")
        return trimmed
    }
}

/**
 * Vergibt fehlende Handles. Sitzt neben [AccountService], damit die Anmeldung
 * nur eine Zeile braucht - und damit der Backfill dieselbe Regel benutzt wie
 * der Login und nicht eine zweite, die auseinanderlaeuft.
 */
@Service
class AccountHandleService(private val accounts: AccountRepository) {

    /**
     * Setzt den Handle, falls er fehlt. Gibt zurueck, ob etwas zu tun war -
     * der Aufrufer speichert ohnehin.
     */
    fun ensure(account: Account): Boolean {
        if (!account.handle.isNullOrBlank()) return false
        account.handle = AccountHandles.assign(account.displayName, account.id, accounts::existsByHandle)
        return true
    }
}

/**
 * Bestandskonten bekommen ihren Handle beim Start.
 *
 * Ohne diesen Lauf haette ein Konto, das sich nie wieder anmeldet, kein Profil
 * - und alles, was auf ein Profil verlinkt (jede Karte, jede Byline, jeder
 * Kommentar), fuehrte dort ins Leere. Die Tabelle ist klein, der Lauf ist
 * einmalig: danach findet er nichts mehr.
 */
@Component
class HandleBackfill(
    private val accounts: AccountRepository,
    private val handles: AccountHandleService,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    override fun run(args: ApplicationArguments) {
        val pending = accounts.findByHandleIsNull()
        if (pending.isEmpty()) return

        for (account in pending) {
            handles.ensure(account)
            accounts.save(account)
        }
        log.info("Assigned handles to {} existing accounts", pending.size)
    }
}
