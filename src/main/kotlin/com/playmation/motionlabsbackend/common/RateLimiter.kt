package com.playmation.motionlabsbackend.common

import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Feste Zeitfenster im Speicher, pro (Eimer, Schlüssel).
 *
 * Bewusst einfach: eine Instanz, kein Redis. Ein Neustart setzt die Zähler
 * zurück - das kostet einen Missbraucher höchstens ein zweites Fenster, und
 * die harten Grenzen (Tages-Uploads pro Konto) zählt die Datenbank ohnehin.
 */
@Component
class RateLimiter(private val clock: Clock) {
    private data class Window(val startedAt: Long, val count: Int)

    private val windows = ConcurrentHashMap<String, Window>()

    /** true = erlaubt und gezählt. */
    fun tryAcquire(bucket: String, key: String, limit: Int, window: Duration): Boolean {
        if (limit <= 0) return false
        val now = clock.millis()
        var allowed = false

        windows.compute("$bucket|$key") { _, current ->
            if (current == null || now - current.startedAt >= window.toMillis()) {
                allowed = true
                Window(now, 1)
            } else if (current.count < limit) {
                allowed = true
                current.copy(count = current.count + 1)
            } else {
                current
            }
        }

        if (windows.size > 50_000) prune(now)
        return allowed
    }

    fun require(bucket: String, key: String, limit: Int, window: Duration) {
        if (!tryAcquire(bucket, key, limit, window)) throw PortalException.rateLimited()
    }

    private fun prune(now: Long) {
        val day = Duration.ofDays(1).toMillis()
        windows.entries.removeIf { now - it.value.startedAt > day }
    }
}
