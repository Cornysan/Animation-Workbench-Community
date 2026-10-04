package com.playmation.motionlabsbackend.unity

import com.playmation.motionlabsbackend.auth.ApiTokenService
import com.playmation.motionlabsbackend.auth.PortalPrincipal
import com.playmation.motionlabsbackend.auth.requirePrincipal
import com.playmation.motionlabsbackend.catalog.CatalogService
import com.playmation.motionlabsbackend.catalog.PackService
import com.playmation.motionlabsbackend.common.PortalException
import com.playmation.motionlabsbackend.format.AwclipSchema
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * "Open in Unity" - ein Clip von der Webseite in die Animation Workbench.
 *
 * WIE ER DORTHIN KOMMT. Eine Webseite kann Unity weder starten noch nach vorne
 * holen. Ein eigenes Link-Schema (`playmations://`) hiesse, auf jedem
 * Betriebssystem einen Handler samt Hilfsprogramm zu registrieren; ein Server
 * im Editor, den die Seite ueber `localhost` ruft, scheitert an den Browsern
 * (Zugriff aufs lokale Netz) und an Firewalls. Also ueber das Konto: der
 * Knopf legt hier eine Bitte ab, und die Workbench, mit demselben Konto
 * angemeldet, holt sie, sobald Unity nach vorne kommt
 * (`AWCommunityCatalogView.OpenInUnity.cs`).
 *
 * NUR DIE LETZTE ZAEHLT, und nur eine Weile ([TTL]): wer zweimal klickt, will
 * den zweiten Clip; wer Unity erst morgen oeffnet, will nicht, dass dann
 * ueberraschend ein Clip aufgeht.
 *
 * IM SPEICHER, nicht in der Datenbank: eine Bitte lebt Minuten, und ein
 * Neustart des Portals verliert hoechstens eine - dann klickt man noch
 * einmal. Das Portal laeuft als EINE Instanz (docker-compose); mit einer
 * zweiten gehoert das hier in eine Tabelle.
 */
@Service
class OpenInUnityService(
    private val catalog: CatalogService,
    private val packs: PackService,
    private val tokens: ApiTokenService,
    private val clock: Clock,
) {
    companion object {
        val TTL: Duration = Duration.ofMinutes(15)

        /** So lange gilt eine Workbench als eine, die abholt ([ready]). */
        val ASKED_WITHIN: Duration = Duration.ofDays(7)
    }

    /** [open] = jeder darf ihn sehen; sonst ist es ein eigener privater Clip. */
    data class Pending(val kind: String, val slug: String, val title: String, val open: Boolean, val at: Instant)

    private val pending = ConcurrentHashMap<UUID, Pending>()

    /** Wann die Workbench eines Kontos zuletzt gefragt hat ([take]). */
    private val asked = ConcurrentHashMap<UUID, Instant>()

    /**
     * Ob eine Workbench dieses Kontos die Bitte abholen wuerde - nur dann
     * steht der Knopf da (`workbench` in `/api/v1/me`).
     *
     * ANGEMELDET REICHT NICHT. Die Workbench bis 2.5.0 ist auch angemeldet,
     * fragt aber nie nach; dort liefe der Knopf ins Leere. Also zaehlt, ob in
     * den letzten [ASKED_WITHIN] eine gefragt hat - UND ob noch eine angemeldet
     * ist, damit "Sign out" in Unity den Knopf sofort nimmt.
     *
     * Nach einem Neustart des Portals ist die Liste leer, bis die Workbench
     * wieder fragt: sofort, wenn Unity das naechste Mal nach vorne kommt.
     */
    fun ready(accountId: UUID): Boolean {
        val at = asked[accountId] ?: return false
        return Duration.between(at, clock.instant()) <= ASKED_WITHIN && tokens.workbenchSignedIn(accountId)
    }

    /**
     * Wer den Knopf von Hand ruft, ohne dass eine Workbench abholt, bekommt
     * das als Fehler - eine Bitte, die niemand abholt, waere ein Klick ins Leere.
     */
    fun request(principal: PortalPrincipal, kind: String, slug: String): Pending {
        if (!ready(principal.accountId))
            throw PortalException.conflict("no-workbench", "Open the Community in the Animation Workbench, signed in to this account, first.")

        val entry = when (kind) {
            "clip" -> catalog.detail(slug, principal).let {
                Pending(kind, it.slug, it.title, it.license == AwclipSchema.LICENSE_PUBLIC, clock.instant())
            }
            "pack" -> packs.detail(slug, principal).let { Pending(kind, it.slug, it.title, true, clock.instant()) }
            else -> throw PortalException.badRequest("invalid-kind", "Only a clip or a pack opens in Unity.")
        }

        val now = clock.instant()
        pending.values.removeIf { Duration.between(it.at, now) > TTL }
        pending[principal.accountId] = entry
        return entry
    }

    /** Einmal abholen - danach ist die Bitte weg, auch wenn sie schon zu alt war. */
    fun take(accountId: UUID): Pending? {
        asked[accountId] = clock.instant()
        return pending.remove(accountId)?.takeIf { Duration.between(it.at, clock.instant()) <= TTL }
    }
}

@RestController
@RequestMapping("/api/v1/me/unity")
class OpenInUnityController(private val service: OpenInUnityService) {

    data class OpenRequest(val kind: String = "clip", val slug: String = "")
    data class OpenView(val kind: String, val slug: String, val title: String, val open: Boolean)

    private fun view(entry: OpenInUnityService.Pending) = OpenView(entry.kind, entry.slug, entry.title, entry.open)

    /** Der Knopf auf der Clip-Seite. */
    @PostMapping("/open")
    fun open(@RequestBody body: OpenRequest, authentication: Authentication?): OpenView =
        view(service.request(authentication.requirePrincipal(), body.kind, body.slug.trim().lowercase()))

    /**
     * Die Workbench fragt, ob etwas wartet. Nichts: 204. Nur mit ihrem Token -
     * die Frage selbst zaehlt als "hier holt jemand ab" ([OpenInUnityService.ready]),
     * und das darf keine Browser-Sitzung behaupten.
     */
    @GetMapping("/open")
    fun take(authentication: Authentication?, request: HttpServletRequest): ResponseEntity<OpenView> {
        val principal = authentication.requirePrincipal()
        if (request.getHeader("Authorization")?.startsWith("Bearer ") != true)
            throw PortalException.badRequest("workbench-only", "Only the Animation Workbench picks up clips to open.")
        val entry = service.take(principal.accountId) ?: return ResponseEntity.noContent().build()
        return ResponseEntity.ok(view(entry))
    }
}
