package com.playmation.motionlabsbackend.economy

import com.playmation.motionlabsbackend.auth.requirePrincipal
import com.playmation.motionlabsbackend.common.PortalException
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Das eigene Konto: Stand, die letzten Buchungen, die offene Wochenaufgabe.
 *
 * Getrennt von `/me`, weil die Workbench den Stand bei jedem Bild braucht,
 * die Buchungen aber nur, wenn jemand hinsieht.
 */
@RestController
@RequestMapping("/api/v1/me")
class CoinController(
    private val coins: CoinService,
    private val quests: QuestService,
) {

    @GetMapping("/coins")
    fun coins(authentication: Authentication?) = coins.overview(authentication.requirePrincipal().accountId)

    @GetMapping("/quests")
    fun quests(authentication: Authentication?) = quests.overview(authentication.requirePrincipal().accountId)
}

/**
 * Eine Buchung von Hand. Fuer Stuetzfaelle - jemand hat durch einen Fehler
 * Muenzen verloren, und eine Entschuldigung allein baut sie nicht wieder auf.
 *
 * Sie traegt einen Grund, und der Grund landet im anhaengenden Protokoll.
 */
@RestController
@RequestMapping("/api/v1/admin")
class CoinAdminController(private val coins: CoinService) {

    data class GrantRequest(val amount: Long = 0, val note: String = "")

    @PostMapping("/accounts/{id}/coins")
    fun adjust(@PathVariable id: UUID, @RequestBody body: GrantRequest): Map<String, Long> {
        if (body.amount == 0L)
            throw PortalException.badRequest("invalid-amount", "Say how many coins, plus or minus.")
        if (body.note.isBlank())
            throw PortalException.badRequest("invalid-note", "A manual booking needs a reason.")

        //  Der Schluessel traegt die Begruendung: zweimal derselbe Grund ist
        //  ein Versehen, zweimal ein anderer ist zweimal gemeint.
        coins.post(id, body.amount, CoinReason.ADMIN, "admin:$id:${body.note.trim()}")
        return mapOf("coins" to coins.balanceOf(id))
    }
}
