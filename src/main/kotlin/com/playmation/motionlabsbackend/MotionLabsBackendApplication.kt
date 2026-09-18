package com.playmation.motionlabsbackend

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Community-Portal der Animation Workbench: Clips hochladen, finden, laden,
 * melden. Konzept und Plan liegen im MotionLabs-Vault
 * ("3. Feature Planning/Community-Portal - Plan").
 *
 * Pakete sind nach Fachlichkeit geschnitten: `format` (Validator, deckungsgleich
 * mit dem Unity-Client), `account`/`auth`, `catalog` (Upload, Suche, Download),
 * `moderation` (Melden, Auto-Hide, Takedown, Admin), `system` (Kill Switch,
 * Status, Datenschutz-Job), `storage`, `common`.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableAsync
@EnableScheduling
class MotionLabsBackendApplication

fun main(args: Array<String>) {
    runApplication<MotionLabsBackendApplication>(*args)
}
