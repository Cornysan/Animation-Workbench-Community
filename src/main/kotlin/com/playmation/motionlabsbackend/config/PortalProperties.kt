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
    /**
     * Darf eine Suchmaschine dieses Portal aufnehmen?
     *
     * Steht auf AUS, weil die Beta geschlossen ist und nirgends angekuendigt
     * (E7): erreichbar sein und gefunden werden sind zwei verschiedene Dinge,
     * und ein Impressum mit `[ TODO: ... ]` gehoert in keinen Suchindex. Eine
     * Vorschau in einem Chat bleibt davon unberuehrt - die entsteht aus
     * `og:`-Feldern, die kein Crawler braucht.
     *
     * Beim oeffentlichen Start umlegen. Es steuert das `robots`-Meta JEDER
     * Seite und `/robots.txt` zugleich, damit die beiden nicht auseinander
     * laufen koennen.
     */
    val searchIndexing: Boolean = false,
    /**
     * Bis wann "dabei gewesen" die Auszeichnung "Early access" wert ist.
     *
     * Leer heisst: die Beta laeuft noch, also bekommt sie jedes Konto. Beim
     * oeffentlichen Start wird hier das Datum eingetragen, und ab dann kann
     * sie niemand mehr erwerben - das ist ihr ganzer Sinn. Als ISO-Zeitpunkt,
     * etwa `2026-10-01T00:00:00Z`.
     */
    val betaUntil: java.time.Instant? = null,
    val alerts: Alerts = Alerts(),
    val limits: Limits = Limits(),
    val comments: Comments = Comments(),
    val economy: Economy = Economy(),
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
        /** Folgen und Entfolgen zusammen - gegen das Durchklicken einer Liste per Skript. */
        val followsPerHour: Int = 60,
        val takedownsPerHourPerIp: Int = 5,
        val editorLinksPerHourPerIp: Int = 20,
        val downloadLinksPerHourPerIp: Int = 240,
    )

    data class Comments(
        val perHour: Int = 30,
        /** So lange darf ein Kommentar noch geaendert werden. Danach steht der Text. */
        val editMinutes: Long = 15,
        val maxLength: Int = 1000,
    )

    /**
     * Die Tauschwirtschaft. Muenzen sind nicht kaufbar und nicht uebertragbar;
     * sie entstehen nur hier und verschwinden nur beim Freischalten.
     *
     * Die Zahlen sind bewusst schwindend: 10 kosten, 1 bringt. Getragen wird
     * das von [startingGrant], [weeklyShareReward] und den [milestones].
     */
    data class Economy(
        val unlockCost: Int = 10,
        val unlockReward: Int = 1,
        val startingGrant: Int = 50,
        /** Erst ab diesem Alter des Discord-Kontos gibt es die Grundausstattung. */
        val grantMinAccountAgeDays: Long = 30,
        val weeklyShareReward: Int = 30,
        /** Hoechstens so viel Erloes je Konto und Tag. 0 = kein Deckel. */
        val dailyEarnCap: Int = 100,
        val milestones: List<Milestone> = listOf(
            Milestone(10, 25), Milestone(25, 50), Milestone(50, 100), Milestone(100, 200),
        ),
    )

    data class Milestone(val unlocks: Int = 0, val reward: Int = 0)

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
