package com.playmation.motionlabsbackend.web

import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.info.BuildProperties
import org.springframework.stereotype.Component
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Welcher Stand laeuft hier gerade?
 *
 * Ohne diese Angabe endet jedes Ausrollen mit derselben Unsicherheit: zeigt die
 * Seite den neuen Stand oder einen zwischengespeicherten alten? Die Antwort
 * bestand bisher darin, ausgelieferte Dateien zu greppen.
 *
 * Die Zahlen stammen aus `META-INF/build-info.properties`, das der
 * Spring-Boot-Gradle-Plugin beim Bauen schreibt (siehe build.gradle.kts).
 * Laeuft der Dienst aus einem Build ohne CI - also von der eigenen Platte -,
 * steht ueberall `dev`, und das ist die ehrliche Auskunft.
 */
@Component
class BuildStamp(builds: ObjectProvider<BuildProperties>) {

    private val props: BuildProperties? = builds.getIfAvailable()

    /** Zaehlt je CI-Lauf hoch (GITHUB_RUN_NUMBER), sonst `dev`. */
    val number: String = props?.get("number") ?: "dev"

    /** Die ersten sieben Zeichen des Commits, sonst `dev`. */
    val commit: String = props?.get("commit") ?: "dev"

    /** Zeitpunkt des Builds, maschinenlesbar (ISO-8601, UTC). */
    val time: String = props?.time?.let { DateTimeFormatter.ISO_INSTANT.format(it) } ?: "unknown"

    /** Derselbe Zeitpunkt zum Hinsehen. */
    val shortTime: String = props?.time?.let {
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC).format(it) + " UTC"
    } ?: "unknown"

    /** `Build 42 - 1cd09c3 - 2026-09-20 01:24 UTC`, oder `dev build`. */
    val label: String =
        if (number == "dev") "dev build" else "Build $number - $commit - $shortTime"
}
