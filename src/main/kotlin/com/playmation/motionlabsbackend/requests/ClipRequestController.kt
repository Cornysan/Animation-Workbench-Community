package com.playmation.motionlabsbackend.requests

import com.playmation.motionlabsbackend.auth.requirePrincipal
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Clip-Wuensche ([ClipRequestService]). Alles hier verlangt eine Anmeldung -
 * auch das Nachsehen, denn es sagt, ob der Wunsch der eigene ist.
 */
@RestController
@RequestMapping("/api/v1/requests")
class ClipRequestController(private val requests: ClipRequestService) {
    data class AskRequest(val phrase: String = "", val named: Boolean = false)

    /** Der offene Wunsch zu diesem Suchtext - `{"request": null}`, wenn es keinen gibt. */
    @GetMapping
    fun find(@RequestParam q: String, authentication: Authentication?): Map<String, ClipRequestService.RequestView?> =
        mapOf("request" to requests.find(authentication.requirePrincipal(), q))

    /** 201 mit dem neuen Wunsch, 200 mit dem, der schon offen war (`existing`). */
    @PostMapping
    fun ask(@RequestBody body: AskRequest, authentication: Authentication?): ResponseEntity<ClipRequestService.AskResult> {
        val result = requests.ask(authentication.requirePrincipal(), body.phrase, body.named)
        return ResponseEntity.status(if (result.existing) HttpStatus.OK else HttpStatus.CREATED).body(result)
    }

    @DeleteMapping("/{id}")
    fun takeBack(@PathVariable id: UUID, authentication: Authentication?): Map<String, String> {
        requests.takeBack(authentication.requirePrincipal(), id)
        return mapOf("status" to "taken-back")
    }
}
