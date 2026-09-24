package com.playmation.motionlabsbackend.catalog

import java.util.UUID

/**
 * Das Konto der Starter-Clips (2026-09-24).
 *
 * Clips aus oeffentlichen CC0-Sammlungen, die ein Admin einspielt, damit der
 * Katalog zum Start nicht leer ist. Sie gehoeren keinem Nutzer: die
 * Upload-Erklaerung "I created this animation myself" waere fuer sie falsch,
 * und die Regeln fuer alle anderen ("nur, was du selbst gemacht hast") bleiben
 * so, wie sie sind.
 *
 * Das Konto legt Migration V12 an - mit dieser Kennung und dem Handle
 * [HANDLE], ohne eine einzige Anmeldung. Verwaltet werden seine Clips von
 * Admins (CatalogService.canManage).
 */
object StarterClips {
    val ACCOUNT_ID: UUID = UUID.fromString("00000000-0000-0000-0000-00000000057a")
    const val HANDLE = "starter-clips"

    const val MAX_CREDIT_LENGTH = 200
    const val MAX_URL_LENGTH = 500
}
