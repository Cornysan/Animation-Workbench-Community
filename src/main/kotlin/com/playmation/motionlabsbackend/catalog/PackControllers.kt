package com.playmation.motionlabsbackend.catalog

import com.playmation.motionlabsbackend.auth.portalPrincipal
import com.playmation.motionlabsbackend.auth.requirePrincipal
import com.playmation.motionlabsbackend.common.clientIp
import com.playmation.motionlabsbackend.profile.ProfileService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
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
import org.springframework.web.bind.annotation.RestController

/**
 * Die Katalogwand: Clips und Packs zusammen ([CatalogWall]). Ein eigener Pfad
 * statt eines Parameters an `/api/v1/packages` - die Workbench bis 2.5.0 liest
 * dort Clips, und nur Clips.
 *
 * `owner` ist der Handle eines Profils: dessen Wand, mit seinen Packs gefaltet.
 */
@RestController
@RequestMapping("/api/v1/catalog")
class CatalogWallController(
    private val wall: CatalogWall,
    private val profiles: ProfileService,
) {
    @GetMapping
    fun page(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) tag: String?,
        @RequestParam(required = false) author: String?,
        @RequestParam(required = false) owner: String?,
        @RequestParam(required = false, defaultValue = "new") sort: String,
        @RequestParam(required = false, defaultValue = "0") page: Int,
        @RequestParam(required = false, defaultValue = "24") size: Int,
        authentication: Authentication?,
    ) = wall.page(q, tag, sort, page, size, authentication.portalPrincipal(), author,
        owner?.takeIf { it.isNotBlank() }?.let { profiles.require(it).id })
}

/**
 * Packs. Ansehen darf jeder, bauen nur der Besitzer - und hinein kommen nur
 * seine eigenen Clips, die im Katalog stehen.
 */
@RestController
@RequestMapping("/api/v1/packs")
class PackController(
    private val packs: PackService,
    private val profiles: ProfileService,
) {
    /** Die Packs eines Profils. */
    @GetMapping
    fun ofOwner(@RequestParam owner: String, authentication: Authentication?) =
        packs.ofOwner(profiles.require(owner).id, authentication.portalPrincipal())

    @GetMapping("/{slug}")
    fun detail(@PathVariable slug: String, authentication: Authentication?) =
        packs.detail(slug, authentication.portalPrincipal())

    @PostMapping
    fun create(
        @RequestBody body: PackService.PackInput,
        authentication: Authentication?,
        request: HttpServletRequest,
    ): ResponseEntity<PackDetail> =
        ResponseEntity.status(HttpStatus.CREATED)
            .body(packs.create(authentication.requirePrincipal(), body, request.clientIp()))

    /** Titel und Beschreibung. */
    @PatchMapping("/{slug}")
    fun update(
        @PathVariable slug: String,
        @RequestBody body: PackService.PackInput,
        authentication: Authentication?,
        request: HttpServletRequest,
    ) = packs.update(authentication.requirePrincipal(), slug, body, request.clientIp())

    /** Aufloesen: der Pack geht, die Clips bleiben. */
    @DeleteMapping("/{slug}")
    fun delete(
        @PathVariable slug: String,
        authentication: Authentication?,
        request: HttpServletRequest,
    ): Map<String, String> {
        packs.delete(authentication.requirePrincipal(), slug, request.clientIp())
        return mapOf("status" to "deleted")
    }

    @PostMapping("/{slug}/clips")
    fun addClips(
        @PathVariable slug: String,
        @RequestBody body: PackService.ClipsRequest,
        authentication: Authentication?,
        request: HttpServletRequest,
    ) = packs.addClips(authentication.requirePrincipal(), slug, body.clips, request.clientIp())

    @DeleteMapping("/{slug}/clips/{clipSlug}")
    fun removeClip(
        @PathVariable slug: String,
        @PathVariable clipSlug: String,
        authentication: Authentication?,
        request: HttpServletRequest,
    ) = packs.removeClip(authentication.requirePrincipal(), slug, clipSlug, request.clientIp())

    @PatchMapping("/{slug}/order")
    fun reorder(
        @PathVariable slug: String,
        @RequestBody body: PackService.ClipsRequest,
        authentication: Authentication?,
    ) = packs.reorder(authentication.requirePrincipal(), slug, body.clips)
}

/** Die eigenen Packs - fuer "Add to a pack" in der Workbench und auf der Kontoseite. */
@RestController
@RequestMapping("/api/v1/me/packs")
class MyPacksController(private val packs: PackService) {

    @GetMapping
    fun mine(authentication: Authentication?) =
        authentication.requirePrincipal().let { packs.ofOwner(it.accountId, it) }
}
