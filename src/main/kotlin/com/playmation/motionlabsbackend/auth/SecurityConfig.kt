package com.playmation.motionlabsbackend.auth

import com.playmation.motionlabsbackend.account.AccountService
import com.playmation.motionlabsbackend.common.ApiError
import com.playmation.motionlabsbackend.common.ApiErrorResponse
import com.playmation.motionlabsbackend.common.PortalException
import com.playmation.motionlabsbackend.system.SystemSettingsService
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.security.web.csrf.CookieCsrfTokenRepository
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler
import org.springframework.security.web.util.matcher.RequestMatcher
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.json.JsonMapper

/**
 * Zwei Arten, angemeldet zu sein:
 *  - Browser: Discord-OAuth2, Session-Cookie, CSRF-Schutz per Cookie + Header
 *    (die Weboberfläche liest XSRF-TOKEN und schickt X-XSRF-TOKEN zurück).
 *  - Workbench: `Authorization: Bearer awc_…`, ohne Session und ohne CSRF -
 *    ein Bearer-Header kann nicht von einer fremden Seite mitgeschickt werden.
 */
@Configuration
@EnableWebSecurity
class SecurityConfig {

    @Bean
    fun filterChain(
        http: HttpSecurity,
        tokenFilter: BearerTokenFilter,
        killSwitchFilter: KillSwitchFilter,
        discordUserService: DiscordUserService,
    ): SecurityFilterChain {
        val safeMethods = setOf("GET", "HEAD", "OPTIONS", "TRACE")

        //  Die Workbench ruft diese Endpunkte VOR der Anmeldung auf und hat
        //  weder Cookie noch Bearer-Header. Keiner davon ändert etwas im Namen
        //  eines angemeldeten Nutzers - ein CSRF-Angriff hätte nichts zu gewinnen.
        //  Der Entwickler-Login existiert nur mit portal.dev-login und soll aus
        //  Skripten (curl) nutzbar sein.
        val csrfExempt = Regex("^/api/v1/(auth/editor/(start|poll)|dev/login)$")

        val csrfRequired = RequestMatcher { request ->
            request.method !in safeMethods &&
                request.getHeader("Authorization")?.startsWith("Bearer ") != true &&
                !csrfExempt.matches(request.requestURI)
        }

        http {
            csrf {
                csrfTokenRepository = CookieCsrfTokenRepository.withHttpOnlyFalse()
                csrfTokenRequestHandler = CsrfTokenRequestAttributeHandler()
                requireCsrfProtectionMatcher = csrfRequired
            }
            authorizeHttpRequests {
                authorize("/api/v1/admin/**", hasRole("ADMIN"))
                authorize(HttpMethod.GET, "/api/v1/status", permitAll)
                authorize(HttpMethod.GET, "/api/v1/licenses", permitAll)
                //  Umfang und Schlagworte des Katalogs - dieselbe Auskunft, die
                //  jede Karte im Katalog ohnehin traegt, nur zusammengezaehlt.
                authorize(HttpMethod.GET, "/api/v1/overview", permitAll)
                authorize(HttpMethod.GET, "/api/v1/packages", permitAll)
                authorize(HttpMethod.GET, "/api/v1/packages/*", permitAll)
                authorize(HttpMethod.GET, "/api/v1/packages/*/preview", permitAll)
                //  Das Bild, das eine Link-Vorschau zeigt. Es holt kein
                //  angemeldeter Besucher, sondern ein Bot von Discord - mit
                //  einem Konto waere es nie zu sehen.
                authorize(HttpMethod.GET, "/clip-card/**", permitAll)
                //  Mitlesen darf jeder, schreiben nur angemeldet - dieselbe
                //  Trennung wie bei den Herzen. Der Katalog ist eine Auslage,
                //  keine geschlossene Gesellschaft.
                authorize(HttpMethod.GET, "/api/v1/packages/*/comments", permitAll)

                //  Profile sind oeffentlich: ein Ersteller, dessen Seite erst
                //  nach einer Anmeldung erscheint, kann nicht weiterempfohlen
                //  werden. Folgen und Melden fallen unter "/api/** authenticated".
                authorize(HttpMethod.GET, "/api/v1/users/*", permitAll)
                authorize(HttpMethod.GET, "/api/v1/users/*/packages", permitAll)
                authorize(HttpMethod.GET, "/api/v1/users/*/followers", permitAll)
                authorize(HttpMethod.GET, "/api/v1/users/*/following", permitAll)

                //  Sammlungen sind Auslage wie der Katalog: ansehen ohne
                //  Konto, bauen nur angemeldet. `/api/v1/me/collections`
                //  faellt nicht hierunter - es faengt mit `me` an.
                authorize(HttpMethod.GET, "/api/v1/collections", permitAll)
                authorize(HttpMethod.GET, "/api/v1/collections/*", permitAll)

                //  Freischalten und Herunterladen brauchen seit der
                //  Muenzwirtschaft ein Konto: ohne Konto gibt es keine
                //  Quittung, und ohne Quittung keine Abrechnung. Der alte
                //  anonyme Zaehler /taken ist damit entfallen - er war eine
                //  Behauptung, keine Zahl. Beide faengt die Regel
                //  "/api/** authenticated" weiter unten ein.

                //  Herzen dagegen NUR angemeldet: eine offene Zahl waere eine
                //  Einladung an jeden Skriptschreiber.
                authorize(HttpMethod.POST, "/api/v1/packages/*/like", authenticated)
                authorize(HttpMethod.GET, "/api/v1/files/**", permitAll)
                authorize(HttpMethod.POST, "/api/v1/takedowns", permitAll)
                authorize(HttpMethod.POST, "/api/v1/auth/editor/start", permitAll)
                authorize(HttpMethod.POST, "/api/v1/auth/editor/poll", permitAll)
                authorize("/api/v1/dev/**", permitAll)
                authorize("/api/**", authenticated)
                authorize("/v3/api-docs/**", permitAll)
                authorize(anyRequest, permitAll)
            }
            oauth2Login {
                userInfoEndpoint { userService = discordUserService }
                defaultSuccessUrl("/", false)
            }
            logout {
                logoutUrl = "/logout"
                logoutSuccessUrl = "/"
            }
            exceptionHandling {
                defaultAuthenticationEntryPointFor(HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED), RequestMatcher { it.requestURI.startsWith("/api/") })
            }
            headers {
                contentSecurityPolicy {
                    // font-src steht ausdruecklich da, obwohl default-src es abdeckt:
                    // Inter wird selbst ausgeliefert, kein Google-Fonts-Abruf.
                    policyDirectives = "default-src 'self'; img-src 'self' data: https://cdn.discordapp.com; " +
                        "style-src 'self'; script-src 'self'; font-src 'self'; " +
                        "object-src 'none'; frame-ancestors 'none'; base-uri 'self'"
                }
                frameOptions { deny = true }
            }
            addFilterBefore<UsernamePasswordAuthenticationFilter>(tokenFilter)
            addFilterAfter<UsernamePasswordAuthenticationFilter>(CsrfCookieFilter())
            addFilterAfter<CsrfCookieFilter>(killSwitchFilter)
        }

        return http.build()
    }
}

/**
 * Spring lädt das CSRF-Token nur bei Bedarf; ohne diesen Zugriff bekäme eine
 * reine GET-Seite nie das Cookie, und das erste POST der Weboberfläche
 * scheiterte.
 */
class CsrfCookieFilter : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        (request.getAttribute(CsrfToken::class.java.name) as? CsrfToken)?.token
        chain.doFilter(request, response)
    }
}

internal fun HttpServletResponse.writeApiError(status: HttpStatus, code: String, message: String) {
    this.status = status.value()
    contentType = MediaType.APPLICATION_JSON_VALUE
    characterEncoding = "UTF-8"
    writer.write(JsonMapper.builder().build().writeValueAsString(ApiErrorResponse(ApiError(code, message))))
}

@Component
class BearerTokenFilter(private val tokens: ApiTokenService) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val header = request.getHeader("Authorization")
        if (header == null || !header.startsWith("Bearer ")) {
            chain.doFilter(request, response)
            return
        }

        //  Ein kaputtes Token fällt NICHT still auf "anonym" zurück - die
        //  Workbench soll erfahren, dass sie sich neu anmelden muss.
        val principal = tokens.authenticate(header.removePrefix("Bearer ").trim())
        if (principal == null) {
            response.writeApiError(HttpStatus.UNAUTHORIZED, "invalid-token", "Your sign-in has expired - sign in again.")
            return
        }

        SecurityContextHolder.getContext().authentication = PortalAuthentication(principal)
        try {
            chain.doFilter(request, response)
        } finally {
            SecurityContextHolder.clearContext()
        }
    }
}

/**
 * Kill Switch (Konzept §10): ist die Community abgeschaltet, antwortet die API
 * mit 503. Erreichbar bleiben Status (damit die Workbench es erfährt),
 * Takedowns (Rechteinhaber dürfen nie vor verschlossener Tür stehen) und die
 * Administration.
 */
@Component
class KillSwitchFilter(private val settings: SystemSettingsService) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val uri = request.requestURI
        return !uri.startsWith("/api/v1/") ||
            uri == "/api/v1/status" ||
            uri.startsWith("/api/v1/admin/") ||
            uri == "/api/v1/takedowns" ||
            uri.startsWith("/api/v1/dev/") ||
            uri == "/api/v1/me"
    }

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        if (!settings.communityEnabled()) {
            response.writeApiError(HttpStatus.SERVICE_UNAVAILABLE, "community-disabled", "The community is paused right now.")
            return
        }
        chain.doFilter(request, response)
    }
}

@Component
class DiscordUserService(private val accounts: AccountService) : OAuth2UserService<OAuth2UserRequest, OAuth2User> {
    private val delegate = DefaultOAuth2UserService()

    override fun loadUser(request: OAuth2UserRequest): OAuth2User {
        val user = delegate.loadUser(request)
        val discordId = user.attributes["id"]?.toString()
            ?: throw OAuth2AuthenticationException(OAuth2Error("invalid_user"), "Discord did not return a user id")
        val name = (user.attributes["global_name"] ?: user.attributes["username"])?.toString() ?: "user"

        //  Nur der Avatar-HASH, nicht das Bild. Daraus baut die Profilseite die
        //  Adresse bei cdn.discordapp.com, die in der Content Security Policy
        //  weiter oben schon steht. Wer keines hat, behaelt den Buchstabenkreis.
        val avatar = user.attributes["avatar"]?.toString()

        val account = try {
            accounts.login(discordId, name, avatar)
        } catch (ex: PortalException) {
            throw OAuth2AuthenticationException(OAuth2Error("account_banned"), ex.message)
        }

        return PortalOAuth2User(PortalPrincipal(account.id, account.role, account.displayName), user.attributes)
    }
}
