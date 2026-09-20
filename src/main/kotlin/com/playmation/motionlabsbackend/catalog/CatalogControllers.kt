package com.playmation.motionlabsbackend.catalog

import com.playmation.motionlabsbackend.auth.portalPrincipal
import com.playmation.motionlabsbackend.auth.requirePrincipal
import com.playmation.motionlabsbackend.common.clientIp
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.CacheControl
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.time.Duration
import java.util.UUID

@RestController
@RequestMapping("/api/v1")
class PackageController(private val catalog: CatalogService) {

    @GetMapping("/packages")
    fun search(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) tag: String?,
        /** Alles von einer Person, ueber ihren Anzeigenamen. */
        @RequestParam(required = false) author: String?,
        @RequestParam(required = false, defaultValue = "new") sort: String,
        @RequestParam(required = false, defaultValue = "0") page: Int,
        @RequestParam(required = false, defaultValue = "24") size: Int,
        authentication: Authentication?,
    ) = catalog.search(q, tag, sort, page, size, authentication.portalPrincipal(), author)

    /** Ein Herz setzen (true) oder zurücknehmen (false). */
    data class LikeRequest(val liked: Boolean = true)

    @GetMapping("/packages/{slug}")
    fun detail(@PathVariable slug: String, authentication: Authentication?) =
        catalog.detail(slug, authentication.portalPrincipal())

    @GetMapping("/packages/{slug}/preview", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun preview(@PathVariable slug: String, authentication: Authentication?): ResponseEntity<ByteArray> =
        ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePrivate())
            .contentType(MediaType.APPLICATION_JSON)
            .body(catalog.previewJson(slug, authentication.portalPrincipal()))

    @PostMapping("/packages/{slug}/like")
    fun like(
        @PathVariable slug: String,
        @RequestBody body: LikeRequest,
        authentication: Authentication?,
    ) = mapOf("likes" to catalog.like(slug, authentication.requirePrincipal(), body.liked))

    /**
     * Freischalten: bucht ab, schreibt die Quittung, schreibt dem Besitzer gut
     * und liefert den Link. Ist die Quittung schon da, kommt nur der Link -
     * ein zweites Mal kostet nie.
     */
    @PostMapping("/packages/{slug}/unlock")
    fun unlock(@PathVariable slug: String, authentication: Authentication?, request: HttpServletRequest) =
        catalog.unlock(slug, authentication.requirePrincipal(), request.clientIp())

    /** Nur fuer schon freigeschaltete Clips - sonst fuehrt der Weg ueber [unlock]. */
    @PostMapping("/packages/{slug}/download-link")
    fun downloadLink(@PathVariable slug: String, authentication: Authentication?, request: HttpServletRequest) =
        catalog.downloadLink(slug, authentication.requirePrincipal(), request.clientIp())

    /**
     * Upload eines neuen Pakets. Multipart: `file` (.awclip) plus die Erklärung
     * als einzelne Felder - so bleibt der Aufruf aus Unity ein schlichtes
     * WWWForm ohne JSON-Teil.
     */
    @PostMapping("/packages", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun upload(
        @RequestPart("file") file: MultipartFile,
        @RequestParam("declarationText", required = false) declarationText: String?,
        @RequestParam("declarationVersion", required = false) declarationVersion: Int?,
        @RequestParam("declarationAccepted", required = false, defaultValue = "false") declarationAccepted: Boolean,
        authentication: Authentication?,
        request: HttpServletRequest,
    ): ResponseEntity<PackageDetail> =
        ResponseEntity.status(HttpStatus.CREATED).body(
            catalog.upload(authentication.requirePrincipal(), file.bytes, declarationText, declarationVersion,
                declarationAccepted, request.clientIp(), null)
        )

    @PostMapping("/packages/{slug}/versions", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun uploadVersion(
        @PathVariable slug: String,
        @RequestPart("file") file: MultipartFile,
        @RequestParam("declarationText", required = false) declarationText: String?,
        @RequestParam("declarationVersion", required = false) declarationVersion: Int?,
        @RequestParam("declarationAccepted", required = false, defaultValue = "false") declarationAccepted: Boolean,
        authentication: Authentication?,
        request: HttpServletRequest,
    ): ResponseEntity<PackageDetail> =
        ResponseEntity.status(HttpStatus.CREATED).body(
            catalog.upload(authentication.requirePrincipal(), file.bytes, declarationText, declarationVersion,
                declarationAccepted, request.clientIp(), slug)
        )

    @DeleteMapping("/packages/{slug}")
    fun withdraw(@PathVariable slug: String, authentication: Authentication?, request: HttpServletRequest): Map<String, String> {
        catalog.withdraw(authentication.requirePrincipal(), slug, request.clientIp())
        return mapOf("status" to "withdrawn")
    }

    @GetMapping("/me/packages")
    fun mine(authentication: Authentication?) = catalog.ownPackages(authentication.requirePrincipal())
}

@RestController
@RequestMapping("/api/v1/files")
class FileController(private val catalog: CatalogService) {

    @GetMapping("/{versionId}")
    fun download(
        @PathVariable versionId: UUID,
        @RequestParam exp: Long,
        @RequestParam sig: String,
    ): ResponseEntity<ByteArray> {
        val file = catalog.download(versionId, exp, sig)
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.fileName).build().toString())
            .header("X-Content-License", file.license)
            .cacheControl(CacheControl.noStore())
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(file.bytes)
    }
}

/**
 * Kommentare an einer Animation. Lesen darf jeder, schreiben nur angemeldet -
 * dieselbe Trennung wie bei den Herzen.
 *
 * Das Melden liegt bei der Moderation, nicht hier: eine Meldung ist kein
 * Katalogvorgang, und sie soll durch dieselbe Tuer wie die Paketmeldung.
 */
@RestController
@RequestMapping("/api/v1/packages/{slug}/comments")
class CommentController(private val comments: CommentService) {

    data class CommentRequest(val body: String = "")

    @GetMapping
    fun list(
        @PathVariable slug: String,
        @RequestParam(required = false, defaultValue = "0") page: Int,
        @RequestParam(required = false, defaultValue = "20") size: Int,
        authentication: Authentication?,
    ) = comments.list(slug, authentication.portalPrincipal(), page, size)

    @PostMapping
    fun post(
        @PathVariable slug: String,
        @RequestBody body: CommentRequest,
        authentication: Authentication?,
        request: HttpServletRequest,
    ): ResponseEntity<CommentView> =
        ResponseEntity.status(HttpStatus.CREATED)
            .body(comments.post(authentication.requirePrincipal(), slug, body.body, request.clientIp()))

    @PatchMapping("/{id}")
    fun edit(
        @PathVariable slug: String,
        @PathVariable id: UUID,
        @RequestBody body: CommentRequest,
        authentication: Authentication?,
        request: HttpServletRequest,
    ) = comments.edit(authentication.requirePrincipal(), slug, id, body.body, request.clientIp())

    @DeleteMapping("/{id}")
    fun delete(
        @PathVariable slug: String,
        @PathVariable id: UUID,
        authentication: Authentication?,
        request: HttpServletRequest,
    ): Map<String, String> {
        comments.delete(authentication.requirePrincipal(), slug, id, request.clientIp())
        return mapOf("status" to "removed")
    }
}
