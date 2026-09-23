package com.playmation.motionlabsbackend.system

import com.playmation.motionlabsbackend.catalog.UploadDeclarationRepository
import com.playmation.motionlabsbackend.common.Crypto
import com.playmation.motionlabsbackend.config.PortalProperties
import com.playmation.motionlabsbackend.moderation.TakedownRequestRepository
import org.slf4j.LoggerFactory
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.beans.factory.ObjectProvider
import org.springframework.scheduling.annotation.Async
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.util.UUID

@Service
class AuditService(private val repository: AuditEntryRepository, private val clock: Clock) {
    fun record(actorId: UUID?, action: String, targetType: String?, targetId: String?, detail: String?, ip: String?) {
        repository.save(
            AuditEntry(
                actorId = actorId,
                action = action,
                targetType = targetType,
                targetId = targetId,
                detail = detail?.take(2000),
                ipAddress = ip,
                createdAt = clock.instant(),
            )
        )
    }
}

/**
 * Kill Switch (Konzept §10) und Upload-Stopp - als Datenbankeinstellung,
 * damit ein Admin sie ohne Neustart umlegen kann. Der Wert wird bei jedem
 * Lesen aus der Datenbank geholt; die Tabelle hat zwei Zeilen.
 */
@Service
class SystemSettingsService(private val repository: SystemSettingRepository, private val clock: Clock) {
    companion object {
        const val COMMUNITY_ENABLED = "community.enabled"
        const val UPLOADS_ENABLED = "uploads.enabled"
        const val CHARACTERS_ENABLED = "characters.enabled"

        private val KEYS = setOf(COMMUNITY_ENABLED, UPLOADS_ENABLED, CHARACTERS_ENABLED)
    }

    fun communityEnabled() = flag(COMMUNITY_ENABLED, default = true)
    fun uploadsEnabled() = communityEnabled() && flag(UPLOADS_ENABLED, default = true)

    /**
     * Die eigenen Figuren - vorerst aus.
     *
     * ZWEI UNTERSCHIEDE ZU DEN ANDEREN BEIDEN, beide mit Absicht:
     *
     * `default = false`. Die anderen Schalter sichern etwas Laufendes ab und
     * stehen darum auf an; dieser hier verdeckt etwas Unfertiges. Waere die
     * Vorgabe `true`, brauchte jede frische Datenbank einen Handgriff, damit
     * die Seite wieder verschwindet - und genau der wird vergessen.
     *
     * NICHT an `communityEnabled()` gekettet. `uploadsEnabled` ist es, weil
     * ein Upload eine Handlung IN der Community ist. Figuren sind das nicht:
     * sie liegen in der IndexedDB des Browsers, gehen nie an den Server und
     * funktionieren auch dann, wenn hier nichts geteilt werden darf. Die
     * beiden Fragen haben nichts miteinander zu tun, also haengen sie auch
     * nicht aneinander.
     *
     * Was der Schalter NICHT tut: etwas loeschen. Abgelegte Figuren bleiben
     * im Browser liegen, wo sie liegen - sie werden nur nirgends mehr
     * angeboten. Wird wieder eingeschaltet, sind sie unveraendert da.
     */
    fun charactersEnabled() = flag(CHARACTERS_ENABLED, default = false)

    @Transactional
    fun set(key: String, enabled: Boolean) {
        require(key in KEYS)
        repository.save(SystemSetting(key, enabled.toString(), clock.instant()))
    }

    private fun flag(key: String, default: Boolean) =
        repository.findById(key).map { it.settingValue == "true" }.orElse(default)
}

/**
 * Der eine Alarm, den man nie verpassen darf (Konzept §11): jede Meldung und
 * jeder Takedown geht an Discord UND per Mail, sofern konfiguriert. Scheitert
 * beides, steht es im Log - die Auto-Hide-Wirkung hängt nicht davon ab.
 */
@Service
class AlertService(
    private val properties: PortalProperties,
    private val mailSender: ObjectProvider<JavaMailSender>,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    @Async
    fun send(title: String, body: String) {
        val alerts = properties.alerts
        var delivered = false

        if (alerts.discordWebhookUrl.isNotBlank()) {
            delivered = postToDiscord(alerts.discordWebhookUrl, title, body) || delivered
        }

        if (alerts.mailTo.isNotBlank()) {
            delivered = sendMail(alerts, title, body) || delivered
        }

        if (!delivered) log.warn("ALERT (no channel delivered): {} - {}", title, body)
    }

    private fun postToDiscord(url: String, title: String, body: String): Boolean = try {
        val content = "**${title.take(200)}**\n${body.take(1700)}"
        val json = buildString {
            append("{\"content\":")
            com.playmation.motionlabsbackend.format.StrictJson.writeString(this, content)
            append(",\"allowed_mentions\":{\"parse\":[]}}")
        }
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.discarding())
        (response.statusCode() in 200..299).also { if (!it) log.warn("Discord alert failed: HTTP {}", response.statusCode()) }
    } catch (ex: Exception) {
        log.warn("Discord alert failed: {}", ex.message)
        false
    }

    private fun sendMail(alerts: PortalProperties.Alerts, title: String, body: String): Boolean {
        val sender = mailSender.ifAvailable ?: return false
        return try {
            sender.send(SimpleMailMessage().apply {
                setTo(*alerts.mailTo.split(',').map { it.trim() }.toTypedArray())
                if (alerts.mailFrom.isNotBlank()) from = alerts.mailFrom
                //  Eine Betreffzeile ist EINE Zeile. In `title` steckt unter
                //  anderem ein Clip-Titel, also fremder Text - und ein
                //  Zeilenumbruch darin ist der klassische Weg, einem Mail-Kopf
                //  weitere Felder unterzuschieben. JavaMail kodiert das heute
                //  weg; darauf zu bauen heisst, die Sicherheit einer fremden
                //  Bibliothek zu ueberlassen, die davon nichts weiss.
                subject = "[AW Community] " + title.replace(Regex("[\r\n]+"), " ").take(200)
                text = body
            })
            true
        } catch (ex: Exception) {
            log.warn("Mail alert failed: {}", ex.message)
            false
        }
    }
}

/**
 * Plan O10: gespeicherte IP-Adressen werden nach [PortalProperties.Privacy.ipRetentionDays]
 * pseudonymisiert. HMAC statt Hash, damit sich das Pseudonym nicht durch
 * Durchprobieren aller IPv4-Adressen umkehren lässt; gleiche IP ergibt
 * gleiches Pseudonym, Missbrauchsmuster bleiben also erkennbar.
 */
@Service
class IpRetentionJob(
    private val properties: PortalProperties,
    private val declarations: UploadDeclarationRepository,
    private val takedowns: TakedownRequestRepository,
    private val audit: AuditEntryRepository,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 17 3 * * *", zone = "UTC")
    @Transactional
    fun run(): Int {
        val cutoff = clock.instant().minus(Duration.ofDays(properties.privacy.ipRetentionDays))
        var count = 0

        declarations.findByIpPseudonymizedFalseAndCreatedAtBefore(cutoff).forEach {
            it.ipAddress = pseudonym(it.ipAddress); it.ipPseudonymized = true; count++
        }
        takedowns.findByIpPseudonymizedFalseAndCreatedAtBefore(cutoff).forEach {
            it.ipAddress = pseudonym(it.ipAddress); it.ipPseudonymized = true; count++
        }
        audit.findByIpPseudonymizedFalseAndCreatedAtBefore(cutoff).forEach {
            it.ipAddress = pseudonym(it.ipAddress); it.ipPseudonymized = true; count++
        }

        if (count > 0) log.info("Pseudonymized {} stored IP addresses older than {} days", count, properties.privacy.ipRetentionDays)
        return count
    }

    private fun pseudonym(ip: String?): String? =
        ip?.let { "p:" + Crypto.hmacHex(properties.pseudonymSecret, it).take(24) }
}
