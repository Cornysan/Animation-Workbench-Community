package com.playmation.motionlabsbackend.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "portal")
data class PortalProperties(
    val publicBaseUrl: String = "http://localhost:8080",
    val storage: Storage = Storage(),
    val downloadSecret: String = "",
    val pseudonymSecret: String = "",
    /** Komma-getrennte Discord-IDs, die beim Login Admin werden. */
    val adminDiscordIds: String = "",
    /** Entwickler-Login ohne Discord. Nur in den Profilen dev und test. */
    val devLogin: Boolean = false,
    val alerts: Alerts = Alerts(),
    val limits: Limits = Limits(),
    val moderation: Moderation = Moderation(),
    val privacy: Privacy = Privacy(),
    val tokens: Tokens = Tokens(),
) {
    data class Storage(val directory: String = "./data/blobs")

    data class Alerts(
        val discordWebhookUrl: String = "",
        val mailTo: String = "",
        val mailFrom: String = "",
    )

    /** Pro Zeitfenster; die Fenster stehen an den Namen. */
    data class Limits(
        val uploadsPerDay: Int = 10,
        val uploadsPerDayRestricted: Int = 2,
        val uploadsPerDayPerIp: Int = 30,
        val reportsPerHour: Int = 10,
        val takedownsPerHourPerIp: Int = 5,
        val editorLinksPerHourPerIp: Int = 20,
        val downloadLinksPerHourPerIp: Int = 240,
    )

    data class Moderation(
        /** Bestätigte Verstöße bis zur Sperre (Konzept §6, Wiederholungstäter). */
        val banAfterStrikes: Int = 3,
        /** Als unbegründet abgewiesene Meldungen, ab denen jemand nicht mehr melden darf. */
        val reportingBlockedAfterFalseReports: Int = 3,
    )

    data class Privacy(
        /** Nach so vielen Tagen werden gespeicherte IP-Adressen pseudonymisiert (Plan O10). */
        val ipRetentionDays: Long = 30,
    )

    data class Tokens(
        val editorTokenDays: Long = 90,
        val editorLinkMinutes: Long = 10,
        val downloadLinkSeconds: Long = 300,
    )

    fun adminIds(): Set<String> = adminDiscordIds.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
}
