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
        @RequestParam(required = false, defaultValue = "new") sort: String,
        @RequestParam(required = false, defaultValue = "0") page: Int,
        @RequestParam(required = false, defaultValue = "24") size: Int,
        authentication: Authentication?,
    ) = catalog.search(q, tag, sort, page, size, authentication.portalPrincipal())

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

    /**
     * Der Clip wurde in ein Projekt übernommen. Getrennt vom Dateiabruf, weil
     * die Workbench zum Stöbern herunterlädt.
     */
    @PostMapping("/packages/{slug}/taken")
    fun taken(@PathVariable slug: String, request: HttpServletRequest): Map<String, String> {
        catalog.taken(slug, request.clientIp())
        return mapOf("status" to "ok")
    }

    @PostMapping("/packages/{slug}/like")
    fun like(
        @PathVariable slug: String,
        @RequestBody body: LikeRequest,
        authentication: Authentication?,
    ) = mapOf("likes" to catalog.like(slug, authentication.requirePrincipal(), body.liked))

    @PostMapping("/packages/{slug}/download-link")
    fun downloadLink(@PathVariable slug: String, request: HttpServletRequest) =
        catalog.downloadLink(slug, request.clientIp())

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
