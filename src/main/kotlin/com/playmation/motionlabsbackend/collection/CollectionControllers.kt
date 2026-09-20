package com.playmation.motionlabsbackend.collection

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
 * Sammlungen. Lesen darf jeder, bauen nur der Besitzer - und hineinlegen darf
 * man nur, was im Katalog steht.
 */
@RestController
@RequestMapping("/api/v1/collections")
class CollectionController(
    private val collections: CollectionService,
    private val profiles: ProfileService,
) {
    data class ItemRequest(val slug: String = "")
    data class OrderRequest(val slugs: List<String> = emptyList())

    /** Alles Oeffentliche - die Sammlungswand, auch in der Workbench. */
    @GetMapping
    fun browse(
        @RequestParam(required = false) owner: String?,
        @RequestParam(required = false, defaultValue = "48") limit: Int,
        authentication: Authentication?,
    ): List<CollectionSummary> {
        val principal = authentication.portalPrincipal()
        return if (owner.isNullOrBlank()) collections.browse(principal, limit)
        else collections.ofOwner(profiles.require(owner).id, principal)
    }

    @GetMapping("/{slug}")
    fun detail(@PathVariable slug: String, authentication: Authentication?) =
        collections.detail(slug, authentication.portalPrincipal())

    @PostMapping
    fun create(
        @RequestBody body: CollectionService.CollectionInput,
        authentication: Authentication?,
        request: HttpServletRequest,
    ): ResponseEntity<CollectionSummary> =
        ResponseEntity.status(HttpStatus.CREATED)
            .body(collections.create(authentication.requirePrincipal(), body, request.clientIp()))

    @PatchMapping("/{slug}")
    fun update(
        @PathVariable slug: String,
        @RequestBody body: CollectionService.CollectionInput,
        authentication: Authentication?,
        request: HttpServletRequest,
    ) = collections.update(authentication.requirePrincipal(), slug, body, request.clientIp())

    @DeleteMapping("/{slug}")
    fun delete(
        @PathVariable slug: String,
        authentication: Authentication?,
        request: HttpServletRequest,
    ): Map<String, String> {
        collections.delete(authentication.requirePrincipal(), slug, request.clientIp())
        return mapOf("status" to "deleted")
    }

    @PostMapping("/{slug}/items")
    fun addItem(
        @PathVariable slug: String,
        @RequestBody body: ItemRequest,
        authentication: Authentication?,
        request: HttpServletRequest,
    ) = collections.addItem(authentication.requirePrincipal(), slug, body.slug, request.clientIp())

    @DeleteMapping("/{slug}/items/{packageSlug}")
    fun removeItem(
        @PathVariable slug: String,
        @PathVariable packageSlug: String,
        authentication: Authentication?,
        request: HttpServletRequest,
    ) = collections.removeItem(authentication.requirePrincipal(), slug, packageSlug, request.clientIp())

    @PatchMapping("/{slug}/order")
    fun reorder(
        @PathVariable slug: String,
        @RequestBody body: OrderRequest,
        authentication: Authentication?,
    ) = collections.reorder(authentication.requirePrincipal(), slug, body.slugs)
}

/**
 * Die eigenen Sammlungen - fuer den Stern an einem Clip. Liegt unter `/me`,
 * weil die Liste nur mit der Angabe "enthaelt diesen Clip schon" brauchbar ist,
 * und die gibt es nur fuer den Angemeldeten.
 */
@RestController
@RequestMapping("/api/v1/me/collections")
class MyCollectionsController(private val collections: CollectionService) {

    @GetMapping
    fun mine(
        @RequestParam(required = false) contains: String?,
        authentication: Authentication?,
    ) = collections.choices(authentication.requirePrincipal(), contains)
}
