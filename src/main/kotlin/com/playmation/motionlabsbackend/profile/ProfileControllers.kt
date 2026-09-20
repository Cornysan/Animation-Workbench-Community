package com.playmation.motionlabsbackend.profile

import com.playmation.motionlabsbackend.auth.portalPrincipal
import com.playmation.motionlabsbackend.auth.requirePrincipal
import com.playmation.motionlabsbackend.catalog.CatalogService
import com.playmation.motionlabsbackend.common.clientIp
import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Profile. Lesen darf jeder, handeln nur angemeldet - dieselbe Trennung wie
 * bei Herzen und Kommentaren: der Katalog ist eine Auslage, keine geschlossene
 * Gesellschaft.
 *
 * Die Clips eines Profils kommen aus der KATALOGSUCHE, nicht aus einer zweiten
 * Abfrage. Sonst gaebe es zwei Stellen, die entscheiden, was oeffentlich
 * sichtbar ist, und die laufen irgendwann auseinander.
 */
@RestController
@RequestMapping("/api/v1/users")
class ProfileController(
    private val profiles: ProfileService,
    private val catalog: CatalogService,
) {
    data class FollowRequest(val following: Boolean = true)

    @GetMapping("/{handle}")
    fun profile(@PathVariable handle: String, authentication: Authentication?) =
        profiles.profile(handle, authentication.portalPrincipal())

    @GetMapping("/{handle}/packages")
    fun packages(
        @PathVariable handle: String,
        @RequestParam(required = false, defaultValue = "new") sort: String,
        @RequestParam(required = false, defaultValue = "0") page: Int,
        @RequestParam(required = false, defaultValue = "24") size: Int,
        authentication: Authentication?,
    ) = catalog.search(
        q = null, tag = null, sort = sort, page = page, size = size,
        principal = authentication.portalPrincipal(),
        ownerId = profiles.require(handle).id,
    )

    @GetMapping("/{handle}/followers")
    fun followers(@PathVariable handle: String) = profiles.followers(handle)

    @GetMapping("/{handle}/following")
    fun following(@PathVariable handle: String) = profiles.following(handle)

    @PostMapping("/{handle}/follow")
    fun follow(
        @PathVariable handle: String,
        @RequestBody body: FollowRequest,
        authentication: Authentication?,
    ) = mapOf("followers" to profiles.follow(authentication.requirePrincipal(), handle, body.following))
}

/** Das eigene Profil aendern. Liegt unter `/me`, weil es kein fremdes sein kann. */
@RestController
@RequestMapping("/api/v1/me")
class MyProfileController(private val profiles: ProfileService) {

    @PatchMapping("/profile")
    fun edit(
        @RequestBody body: ProfileService.ProfileEdit,
        authentication: Authentication?,
        request: HttpServletRequest,
    ) = profiles.edit(authentication.requirePrincipal(), body, request.clientIp())
}
