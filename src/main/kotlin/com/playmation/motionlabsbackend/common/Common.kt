package com.playmation.motionlabsbackend.common

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
 */
class PortalException(val status: HttpStatus, val code: String, message: String) : RuntimeException(message) {
    companion object {
        fun notFound(what: String = "Not found") = PortalException(HttpStatus.NOT_FOUND, "not-found", what)
        fun forbidden(message: String) = PortalException(HttpStatus.FORBIDDEN, "forbidden", message)
        fun badRequest(code: String, message: String) = PortalException(HttpStatus.BAD_REQUEST, code, message)
        fun conflict(code: String, message: String) = PortalException(HttpStatus.CONFLICT, code, message)
        fun rateLimited() = PortalException(HttpStatus.TOO_MANY_REQUESTS, "rate-limited", "Too many requests - try again later.")
        fun unavailable(message: String) = PortalException(HttpStatus.SERVICE_UNAVAILABLE, "unavailable", message)
    }
}

data class ApiError(val code: String, val message: String)
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
 * Client-IP hinter nginx. `server.forward-headers-strategy: framework` setzt
 * `remoteAddr` bereits aus X-Forwarded-For - nur vertrauenswürdig, weil das
 * Backend keine öffentlichen Ports hat (docker-compose).
 */
fun HttpServletRequest.clientIp(): String = remoteAddr ?: "unknown"
