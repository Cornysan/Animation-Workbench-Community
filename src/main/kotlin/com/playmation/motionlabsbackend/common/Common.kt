package com.playmation.motionlabsbackend.common

import com.fasterxml.jackson.annotation.JsonInclude
import jakarta.servlet.http.HttpServletRequest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Fachlicher Fehler mit stabilem Code. Die Workbench zeigt `message` an und
 * entscheidet über `code` - Codes sind Teil der Schnittstelle, Texte nicht.
 *
 * [slug] nennt den Clip, um den es geht, wenn der Fehler auf einen anderen
 * zeigt ("das gibt es schon - hier"). Nur setzen, wenn der Fragende diesen
 * Clip auch sehen darf.
 */
class PortalException(
    val status: HttpStatus, val code: String, message: String, val slug: String? = null,
) : RuntimeException(message) {
    companion object {
        fun notFound(what: String = "Not found") = PortalException(HttpStatus.NOT_FOUND, "not-found", what)
        fun forbidden(message: String) = PortalException(HttpStatus.FORBIDDEN, "forbidden", message)
        fun badRequest(code: String, message: String) = PortalException(HttpStatus.BAD_REQUEST, code, message)
        fun conflict(code: String, message: String, slug: String? = null) =
            PortalException(HttpStatus.CONFLICT, code, message, slug)
        fun rateLimited() = PortalException(HttpStatus.TOO_MANY_REQUESTS, "rate-limited", "Too many requests - try again later.")
        fun unavailable(message: String) = PortalException(HttpStatus.SERVICE_UNAVAILABLE, "unavailable", message)
    }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ApiError(val code: String, val message: String, val slug: String? = null)
data class ApiErrorResponse(val error: ApiError)

@Configuration
class CommonConfig {
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}

object Crypto {
    private val random = SecureRandom()

    fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).toHex()

    fun hmacHex(secret: String, value: String): String {
        require(secret.isNotBlank()) { "HMAC secret is not configured" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(value.toByteArray(Charsets.UTF_8)).toHex()
    }

    /** URL-sicherer Zufallswert mit [bytes] Bytes Entropie. */
    fun randomToken(bytes: Int = 32): String {
        val buffer = ByteArray(bytes)
        random.nextBytes(buffer)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer)
    }

    /** Zeichen ohne Verwechslungsgefahr (kein 0/O, 1/I/L) - für Codes, die Menschen abtippen. */
    fun randomCode(length: Int, alphabet: String = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"): String =
        buildString { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }

    fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}

/**
 * Client-IP hinter Caddy. `server.forward-headers-strategy: framework` setzt
 * `remoteAddr` bereits aus X-Forwarded-For - nur vertrauenswürdig, weil das
 * Backend an 127.0.0.1 gebunden ist und niemand sonst den Header setzen kann
 * (server/docker-compose.yml).
 */
fun HttpServletRequest.clientIp(): String = remoteAddr ?: "unknown"

/**
 * Zeichen, die man nicht sieht, die aber veraendern, was man sieht.
 *
 * Die Steuerzeichen-Pruefungen hier (`isISOControl`, `hasControl`) kennen nur
 * C0/C1 und DEL. Durch ging bis 2026-09-27 alles aus der Unicode-Kategorie
 * "Format": U+202E dreht den Rest eines Titels um ("Walk" + U+202E + "gpj.exe"
 * liest sich als "Walkexe.jpg"), U+200B macht zwei gleich aussehende Namen
 * verschieden. Weg damit, still - niemand tippt so etwas absichtlich.
 *
 * NICHT entfernt: U+200C/U+200D (Zero-Width Non-Joiner/Joiner). Ohne sie
 * zerfallen zusammengesetzte Emojis in Einzelteile, und mehrere Schriften
 * brauchen sie fuer ihre Buchstabenformen.
 */
private val INVISIBLE = Regex("[\\u061C\\u200B\\u200E\\u200F\\u2028\\u2029\\u202A-\\u202E\\u2060-\\u2064\\u2066-\\u2069\\uFEFF]")

fun String.withoutInvisible(): String = INVISIBLE.replace(this, "")
