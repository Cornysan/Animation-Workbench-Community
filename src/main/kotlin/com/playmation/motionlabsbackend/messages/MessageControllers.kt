package com.playmation.motionlabsbackend.messages

import com.playmation.motionlabsbackend.auth.requirePrincipal
import com.playmation.motionlabsbackend.common.clientIp
import com.playmation.motionlabsbackend.moderation.ModerationService
import com.playmation.motionlabsbackend.moderation.ReportCategory
import jakarta.servlet.http.HttpServletRequest
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
import java.time.Instant
import java.util.UUID

/**
 * Direktnachrichten und Blockieren. Alles hier verlangt eine Anmeldung
 * (alles unter /api/ ausser dem ausdruecklich Freigegebenen, SecurityConfig) - auch das Lesen, denn es
 * gibt nichts Oeffentliches daran.
 *
 * Browser und Workbench nehmen dieselben Wege; die Workbench meldet sich mit
 * ihrem Bearer-Token an.
 */
@RestController
@RequestMapping("/api/v1")
class MessageController(
    private val messages: MessageService,
    private val blocks: BlockService,
    private val moderation: ModerationService,
) {
    data class SendRequest(val body: String = "")
    data class BlockRequest(val blocked: Boolean = true)
    data class MessageReportRequest(val category: ReportCategory, val message: String = "", val block: Boolean = false)

    @GetMapping("/me/conversations")
    fun list(authentication: Authentication?) = messages.list(authentication.requirePrincipal())

    /** Die Zahl am Briefsymbol - fuer eine Seite, die eine Weile offen steht, und fuer die Workbench. */
    @GetMapping("/me/conversations/unread")
    fun unread(authentication: Authentication?) =
        mapOf("conversations" to messages.unreadCount(authentication.requirePrincipal().accountId))

    @GetMapping("/me/conversations/{id}")
    fun open(
        @PathVariable id: UUID,
        @RequestParam(required = false) before: Instant?,
        authentication: Authentication?,
    ) = messages.open(authentication.requirePrincipal(), id, before)

    @PostMapping("/me/conversations/{id}/messages")
    fun send(
        @PathVariable id: UUID,
        @RequestBody body: SendRequest,
        authentication: Authentication?,
        request: HttpServletRequest,
    ) = ResponseEntity.status(HttpStatus.CREATED)
        .body(messages.send(authentication.requirePrincipal(), id, body.body, request.clientIp()))

    @PostMapping("/me/conversations/{id}/accept")
    fun accept(@PathVariable id: UUID, authentication: Authentication?) =
        messages.accept(authentication.requirePrincipal(), id)

    @PostMapping("/me/conversations/{id}/decline")
    fun decline(@PathVariable id: UUID, authentication: Authentication?): Map<String, String> {
        messages.decline(authentication.requirePrincipal(), id)
        return mapOf("status" to "declined")
    }

    /** Fuer mich leeren - die andere Seite behaelt ihre Kopie (MessageService.clear). */
    @DeleteMapping("/me/conversations/{id}")
    fun clear(@PathVariable id: UUID, authentication: Authentication?): Map<String, String> {
        messages.clear(authentication.requirePrincipal(), id)
        return mapOf("status" to "cleared")
    }

    /**
     * Eine Nachricht melden, auf Wunsch im selben Schritt blockieren. Zwei
     * Aufrufe von der Seite aus waeren zwei Gelegenheiten, dass der zweite
     * scheitert und die Person weiter schreiben kann.
     */
    @PostMapping("/me/conversations/{id}/messages/{messageId}/reports")
    fun report(
        @PathVariable id: UUID,
        @PathVariable messageId: UUID,
        @RequestBody body: MessageReportRequest,
        authentication: Authentication?,
        request: HttpServletRequest,
    ): ResponseEntity<Map<String, Any>> {
        val principal = authentication.requirePrincipal()
        val reportId = moderation.reportMessage(principal, id, messageId, body.category, body.message, request.clientIp())
        if (body.block) messages.blockOther(principal, id, request.clientIp())
        return ResponseEntity.status(HttpStatus.CREATED).body(mapOf("id" to reportId, "status" to "received"))
    }

    /** Das Gespraech mit dieser Person - oder ein leeres, wenn es noch keins gibt. */
    @GetMapping("/users/{handle}/conversation")
    fun with(@PathVariable handle: String, authentication: Authentication?) =
        messages.with(authentication.requirePrincipal(), handle)

    @PostMapping("/users/{handle}/messages")
    fun sendTo(
        @PathVariable handle: String,
        @RequestBody body: SendRequest,
        authentication: Authentication?,
        request: HttpServletRequest,
    ) = ResponseEntity.status(HttpStatus.CREATED)
        .body(messages.sendTo(authentication.requirePrincipal(), handle, body.body, request.clientIp()))

    @PostMapping("/users/{handle}/block")
    fun block(
        @PathVariable handle: String,
        @RequestBody body: BlockRequest,
        authentication: Authentication?,
        request: HttpServletRequest,
    ) = mapOf("blocked" to blocks.set(authentication.requirePrincipal(), handle, body.blocked, request.clientIp()))

    @GetMapping("/me/blocks")
    fun myBlocks(authentication: Authentication?) = blocks.mine(authentication.requirePrincipal())
}
