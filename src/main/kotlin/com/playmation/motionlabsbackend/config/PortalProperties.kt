package com.playmation.motionlabsbackend.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "portal")
data class PortalProperties(
    val publicBaseUrl: String = "http://localhost:8080",
    val storage: Storage = Storage(),
    val downloadSecret: String = "",
    val pseudonymSecret: String = "",
    /**
     * Wer beim Login Moderator wird, komma-getrennt. Eine nackte Zahl ist eine
     * Discord-Kennung - so stand es hier, als es nur Discord gab, und so steht
     * es in der `.env` auf dem Server. Andere Anmeldungen mit Anbieter davor:
     * `github:583231`, `google:1098...`, `dev:admin`.
     *
     * Der Name bleibt, damit die `.env` auf dem Server weiter gilt. Noetig ist
     * ein zweiter Eintrag fuer dieselbe Person ohnehin selten: Moderator ist
     * das KONTO, sobald irgendeine seiner Anmeldungen hier steht.
     */
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
    val showcase: Showcase = Showcase(),
    val requests: Requests = Requests(),
    val limits: Limits = Limits(),
    val comments: Comments = Comments(),
    val messages: Messages = Messages(),
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

    /**
     * Das Discord-Schaufenster fuer neue Clips - siehe `showcase/ShowcaseService.kt`.
     * Ohne Webhook gibt es es nicht: die Workbench bietet das Haekchen dann
     * gar nicht erst an (`/api/v1/status`, `discordShowcase`).
     */
    data class Showcase(
        /** Am besten ein Forum-Kanal: dann wird jeder Clip ein eigener Thread. */
        val discordWebhookUrl: String = "",
        /** So lange wartet ein Post auf das Vorschaubild, dann geht er mit dem der Seite. */
        val waitForCard: java.time.Duration = java.time.Duration.ofMinutes(10),
        /** Kennungen von Forum-Tags, komma-getrennt - noetig, wenn das Forum Tags verlangt. */
        val forumTags: String = "",
        /**
         * Der Discord-Server des Forums - fuer die Adresse eines Threads
         * (`showcase/ShowcaseLinks.kt`). Leer = beim Webhook erfragen.
         */
        val discordGuildId: String = "",
    )

    /**
     * "Ask for it on Discord": Clip-Wuensche aus einer leeren Suche - siehe
     * `requests/ClipRequestService.kt`. Ohne Webhook gibt es sie nicht: die
     * Workbench bietet den Knopf dann gar nicht erst an (`/api/v1/status`,
     * `clipRequests`).
     */
    data class Requests(
        /** Ein eigenes Forum, nicht das Schaufenster: dort stehen fertige Clips. */
        val discordWebhookUrl: String = "",
        /** Kennungen von Forum-Tags, komma-getrennt - noetig, wenn das Forum Tags verlangt. */
        val forumTags: String = "",
        /** Leer = die des Schaufensters, und ist auch die leer, beim Webhook erfragen. */
        val discordGuildId: String = "",
        /** Wuensche je Konto und Tag. Wer etwas schon Gewuenschtes sucht, zaehlt nicht. */
        val perDay: Int = 3,
        /** So lange gilt ein Wunsch als offen - wer danach dasselbe sucht, fragt neu. */
        val openDays: Long = 30,
        val maxLength: Int = 60,
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

    /** Direktnachrichten - siehe `messages/MessageService.kt`. */
    data class Messages(
        val maxLength: Int = 2000,
        /** Nachrichten je Konto und Stunde, alle Gespraeche zusammen. */
        val perHour: Int = 60,
        /** Neue Gespraeche (Anfragen) je Konto und Tag - der Hebel gegen Massenanschreiben. */
        val newConversationsPerDay: Int = 10,
        /** So viele Nachrichten darf eine Anfrage tragen, bevor die andere Seite annimmt. */
        val pendingLimit: Int = 3,
        /** Wie viele Nachrichten vor einer gemeldeten mit in die Meldung gehen. */
        val evidenceContext: Int = 10,
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
        /** "Angemeldet bleiben" im Browser, gezaehlt ab dem letzten Besuch. */
        val browserLoginDays: Long = 30,
        /** So lange gilt der Code, mit dem die Workbench den Browser anmeldet. */
        val handoffSeconds: Long = 60,
    )

    /** Als `anbieter:kennung` - siehe [adminDiscordIds]. */
    fun adminIds(): Set<String> = adminDiscordIds.split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { if (':' in it) it else "discord:$it" }
        .toSet()
}
