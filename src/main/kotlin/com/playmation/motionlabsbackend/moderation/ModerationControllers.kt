package com.playmation.motionlabsbackend.moderation

import com.playmation.motionlabsbackend.account.AccountStatus
import com.playmation.motionlabsbackend.auth.requirePrincipal
import com.playmation.motionlabsbackend.common.clientIp
import com.playmation.motionlabsbackend.system.AuditEntryRepository
import com.playmation.motionlabsbackend.system.SystemSettingsService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1")
class ReportController(private val moderation: ModerationService) {

    data class ReportRequest(val category: ReportCategory, val message: String = "")

    @PostMapping("/packages/{slug}/reports")
    fun report(
        @PathVariable slug: String,
        @RequestBody body: ReportRequest,
        authentication: Authentication?,
        request: HttpServletRequest,
    ): ResponseEntity<Map<String, Any>> {
        val id = moderation.report(authentication.requirePrincipal(), slug, body.category, body.message, request.clientIp())
        return ResponseEntity.status(HttpStatus.CREATED).body(mapOf("id" to id, "status" to "received"))
    }

    /** Ein Kommentar, nicht der Clip. Versteckt wird entsprechend nur der Kommentar. */
    @PostMapping("/packages/{slug}/comments/{commentId}/reports")
    fun reportComment(
        @PathVariable slug: String,
        @PathVariable commentId: UUID,
        @RequestBody body: ReportRequest,
        authentication: Authentication?,
        request: HttpServletRequest,
    ): ResponseEntity<Map<String, Any>> {
        val id = moderation.reportComment(
            authentication.requirePrincipal(), slug, commentId, body.category, body.message, request.clientIp())
        return ResponseEntity.status(HttpStatus.CREATED).body(mapOf("id" to id, "status" to "received"))
    }

    /**
     * Ein KONTO melden - dieselbe Tuer, andere Folge: hier wird nichts
     * versteckt (die Begruendung steht bei [ModerationService.reportAccount]).
     */
    @PostMapping("/users/{handle}/reports")
    fun reportAccount(
        @PathVariable handle: String,
        @RequestBody body: ReportRequest,
        authentication: Authentication?,
        request: HttpServletRequest,
    ): ResponseEntity<Map<String, Any>> {
        val id = moderation.reportAccount(
            authentication.requirePrincipal(), handle, body.category, body.message, request.clientIp())
        return ResponseEntity.status(HttpStatus.CREATED).body(mapOf("id" to id, "status" to "received"))
    }

    /** Öffentlich, ohne Konto - Rechteinhaber dürfen nie vor verschlossener Tür stehen. */
    @PostMapping("/takedowns")
    fun takedown(@RequestBody body: ModerationService.TakedownInput, request: HttpServletRequest) =
        ResponseEntity.status(HttpStatus.CREATED).body(moderation.takedown(body, request.clientIp()))
}

@RestController
@RequestMapping("/api/v1/admin")
class AdminController(
    private val moderation: ModerationService,
    private val settings: SystemSettingsService,
    private val audit: AuditEntryRepository,
) {
    data class Decision(val note: String = "", val strike: Boolean = false, val falseReport: Boolean = false, val upheld: Boolean = false)
    data class AccountStatusRequest(val status: AccountStatus, val note: String = "")
    data class SettingsRequest(
        val communityEnabled: Boolean? = null,
        val uploadsEnabled: Boolean? = null,
        val charactersEnabled: Boolean? = null,
    )

    @GetMapping("/cases")
    fun cases() = moderation.openCases()

    @PostMapping("/packages/{slug}/restore")
    fun restore(@PathVariable slug: String, @RequestBody body: Decision, authentication: Authentication?, request: HttpServletRequest): Map<String, String> {
        moderation.restore(authentication.requirePrincipal(), slug, body.note, request.clientIp())
        return mapOf("status" to "restored")
    }

    @PostMapping("/packages/{slug}/remove")
    fun remove(@PathVariable slug: String, @RequestBody body: Decision, authentication: Authentication?, request: HttpServletRequest): Map<String, String> {
        moderation.remove(authentication.requirePrincipal(), slug, body.note, body.strike, request.clientIp())
        return mapOf("status" to "removed")
    }

    @PostMapping("/comments/{id}/remove")
    fun removeComment(@PathVariable id: UUID, @RequestBody body: Decision, authentication: Authentication?, request: HttpServletRequest): Map<String, String> {
        moderation.removeComment(authentication.requirePrincipal(), id, body.note, body.strike, request.clientIp())
        return mapOf("status" to "removed")
    }

    @PostMapping("/comments/{id}/restore")
    fun restoreComment(@PathVariable id: UUID, @RequestBody body: Decision, authentication: Authentication?, request: HttpServletRequest): Map<String, String> {
        moderation.restoreComment(authentication.requirePrincipal(), id, body.note, request.clientIp())
        return mapOf("status" to "restored")
    }

    @PostMapping("/reports/{id}/dismiss")
    fun dismiss(@PathVariable id: UUID, @RequestBody body: Decision, authentication: Authentication?, request: HttpServletRequest): Map<String, String> {
        moderation.dismissReport(authentication.requirePrincipal(), id, body.note, body.falseReport, request.clientIp())
        return mapOf("status" to "dismissed")
    }

    @PostMapping("/takedowns/{id}/resolve")
    fun resolveTakedown(@PathVariable id: UUID, @RequestBody body: Decision, authentication: Authentication?, request: HttpServletRequest): Map<String, String> {
        moderation.resolveTakedown(authentication.requirePrincipal(), id, body.upheld, body.note, body.strike, request.clientIp())
        return mapOf("status" to if (body.upheld) "upheld" else "dismissed")
    }

    @PostMapping("/accounts/{id}/status")
    fun accountStatus(@PathVariable id: UUID, @RequestBody body: AccountStatusRequest, authentication: Authentication?, request: HttpServletRequest): Map<String, String> {
        moderation.setAccountStatus(authentication.requirePrincipal(), id, body.status, body.note, request.clientIp())
        return mapOf("status" to body.status.name)
    }

    @PostMapping("/settings")
    fun updateSettings(@RequestBody body: SettingsRequest): Map<String, Boolean> {
        body.communityEnabled?.let { settings.set(SystemSettingsService.COMMUNITY_ENABLED, it) }
        body.uploadsEnabled?.let { settings.set(SystemSettingsService.UPLOADS_ENABLED, it) }
        body.charactersEnabled?.let { settings.set(SystemSettingsService.CHARACTERS_ENABLED, it) }
        return mapOf(
            "communityEnabled" to settings.communityEnabled(),
            "uploadsEnabled" to settings.uploadsEnabled(),
            "charactersEnabled" to settings.charactersEnabled(),
        )
    }

    @GetMapping("/audit")
    fun auditLog() = audit.findTop200ByOrderByCreatedAtDesc()
}
