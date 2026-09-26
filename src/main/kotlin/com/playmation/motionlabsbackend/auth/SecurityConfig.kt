package com.playmation.motionlabsbackend.auth

import com.playmation.motionlabsbackend.common.ApiError
import com.playmation.motionlabsbackend.common.ApiErrorResponse
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
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.security.web.csrf.CookieCsrfTokenRepository
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler
import org.springframework.security.web.header.writers.CrossOriginOpenerPolicyHeaderWriter
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
import org.springframework.security.web.util.matcher.RequestMatcher
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.json.JsonMapper

/**
 * Zwei Arten, angemeldet zu sein:
 *  - Browser: OAuth2 bei Discord, GitHub oder Google (siehe [SignInProviders]),
 *    Session-Cookie, CSRF-Schutz per Cookie + Header (die Weboberfläche liest
 *    XSRF-TOKEN und schickt X-XSRF-TOKEN zurück).
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
        portalUserService: PortalUserService,
        rememberMe: PortalRememberMeServices,
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
                //  SameSite ausdruecklich: ohne das Attribut entscheidet der
                //  Browser, und "keins" heisst bei aelteren Staenden "None".
                //  Lax, nicht Strict - sonst faehrt jemand, der einem Clip-Link
                //  aus Discord folgt, ohne Token an und das erste Herz scheitert.
                csrfTokenRepository = CookieCsrfTokenRepository.withHttpOnlyFalse()
                    .apply { setCookieCustomizer { it.sameSite("Lax") } }
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
                //  Dasselbe, einzeln nachgeschlagen: das Schlagwortfeld beim
                //  Teilen fragt schon, bevor jemand angemeldet ist. Eigene
                //  Schlagworte kommen nur mit Anmeldung dazu (TagService).
                authorize(HttpMethod.GET, "/api/v1/tags", permitAll)
                authorize(HttpMethod.GET, "/api/v1/tags/suggest", permitAll)
                authorize(HttpMethod.GET, "/api/v1/packages", permitAll)
                authorize(HttpMethod.GET, "/api/v1/packages/*", permitAll)
                authorize(HttpMethod.GET, "/api/v1/packages/*/preview", permitAll)
                //  Das Bild, das eine Link-Vorschau zeigt. Es holt kein
                //  angemeldeter Besucher, sondern ein Bot von Discord - mit
                //  einem Konto waere es nie zu sehen.
                authorize(HttpMethod.GET, "/clip-card/**", permitAll)
                //  Das Profilbild. Es geht ueber diesen Server, damit Discord
                //  nicht die IP jedes Lesers erfaehrt - siehe AvatarCache.
                authorize(HttpMethod.GET, "/avatar/**", permitAll)
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

                //  Dasselbe fuer Packs und die Katalogwand, die sie faltet.
                //  `/api/v1/me/packs` faengt mit `me` an und bleibt zu.
                authorize(HttpMethod.GET, "/api/v1/catalog", permitAll)
                authorize(HttpMethod.GET, "/api/v1/packs", permitAll)
                authorize(HttpMethod.GET, "/api/v1/packs/*", permitAll)

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

                //  Vom Actuator ist genau EINER offen, und der sagt "UP".
                //  `management.endpoints.web.exposure.include: health` haelt die
                //  anderen schon zurueck, aber das ist eine Einstellung, die
                //  jemand erweitert, um lokal etwas nachzusehen - und dann steht
                //  sie in der Produktion. Die Sperre gehoert dahin, wo sie beim
                //  Erweitern auffaellt. Der Healthcheck des Containers fragt
                //  genau diese eine Adresse.
                authorize("/actuator/health", permitAll)
                authorize("/actuator/**", denyAll)

                //  DIE KONTOSEITE NUR ANGEMELDET. Ohne Konto zeigte sie eine
                //  halbe Seite - "Sign in to see your clips." und darunter
                //  trotzdem "Close my account". Jetzt schickt Spring zur
                //  Anmeldeseite (`loginPage` unten) und merkt sich die Adresse:
                //  nach der Rueckkehr vom Anbieter geht es hierher zurueck
                //  (SignInSuccessHandler erbt das vom SavedRequest-Handler),
                //  nicht auf die Startseite. So kommt auch an, wer in der
                //  Workbench "Your account on the portal" waehlt und im Browser
                //  keine Sitzung mehr hat.
                authorize(HttpMethod.GET, "/me.html", authenticated)

                authorize(anyRequest, permitAll)
            }
            oauth2Login {
                //  Mit mehr als einem Anbieter baute Spring sonst seine eigene
                //  Auswahlseite unter /login - und schickte jeden Fehler dorthin,
                //  auch "dieses Konto ist gesperrt".
                loginPage = "/signin.html"
                userInfoEndpoint { userService = portalUserService }
                authenticationSuccessHandler = SignInSuccessHandler()
                authenticationFailureHandler = SignInFailureHandler()
            }
            //  Angemeldet bleiben: endet die Sitzung (Leerlauf, Neustart,
            //  Browser zu), meldet das Cookie den Browser wieder an - mit dem
            //  Konto, wie es jetzt ist. Siehe PortalRememberMeServices. Spring
            //  haengt den Dienst an die Anbieter-Anmeldung (loginSuccess) und
            //  ans Abmelden (er ist auch ein LogoutHandler).
            rememberMe {
                rememberMeServices = rememberMe
                key = PortalRememberMeServices.KEY
            }
            logout {
                logoutUrl = "/logout"
                logoutSuccessUrl = "/"
            }
            exceptionHandling {
                defaultAuthenticationEntryPointFor(HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED), RequestMatcher { it.requestURI.startsWith("/api/") })
                //  Eine SEITE geht zur Anmeldeseite (bisher nur /me.html).
                //  Ausdruecklich, nicht ueber `loginPage` allein: dessen
                //  Einstiegspunkt greift nur, wenn die Anfrage `text/html`
                //  verlangt, und sonst kam ein nacktes 401 - im Test sofort,
                //  im Betrieb bei jedem Werkzeug, das keinen Accept-Kopf setzt.
                //  Nur Seiten: der gesperrte Actuator soll weiter 401 sagen und
                //  nicht auf eine Anmeldung verweisen, die ihn nie oeffnet.
                defaultAuthenticationEntryPointFor(
                    LoginUrlAuthenticationEntryPoint("/signin.html"),
                    RequestMatcher { it.method == "GET" && it.requestURI.endsWith(".html") },
                )
            }
            headers {
                contentSecurityPolicy {
                    // font-src steht ausdruecklich da, obwohl default-src es abdeckt:
                    // Inter wird selbst ausgeliefert, kein Google-Fonts-Abruf.
                    //
                    // cdn.discordapp.com stand hier fuer die Profilbilder und ist
                    // wieder weg: sie gehen jetzt ueber diesen Server (AvatarCache),
                    // damit Discord nicht die IP jedes Lesers erfaehrt. Solange die
                    // Regel fehlt, kann auch kein neuer Hotlink unbemerkt einziehen.
                    //
                    // WAS DIE VIER NEUEN ZEILEN SCHLIESSEN, und warum `default-src`
                    // sie NICHT schon abdeckte:
                    //
                    //   form-action faellt auf NICHTS zurueck. Ohne die Regel darf
                    //   ein eingeschleustes <form> auf eine fremde Adresse zeigen -
                    //   und ein "dangling markup"-Schnipsel (ein offenes Attribut,
                    //   das den Rest der Seite verschluckt) braucht genau das, um
                    //   das CSRF-Token mitzunehmen. Das ist die einzige Luecke hier,
                    //   die ohne ein zweites Loch schon etwas wert waere.
                    //
                    //   frame-src erbt von default-src und stuende damit auf 'self':
                    //   ein eigener Rahmen im eigenen Rahmen ist nichts, was diese
                    //   Seite je braucht. 'none' nimmt einem Clickjacking-Versuch
                    //   von INNEN die Buehne - frame-ancestors deckt nur die
                    //   Richtung von aussen ab.
                    //
                    //   connect-src und media-src stehen als Aussage da, nicht als
                    //   Reparatur: die Seite spricht mit dieser Adresse und mit
                    //   keiner anderen, und sie spielt keinen Ton. Wer das eines
                    //   Tages aendert, aendert es hier sichtbar mit.
                    //
                    //   require-trusted-types-for ist der Riegel, nicht die
                    //   Regel: in Chromium wirft ab hier JEDE Zuweisung an
                    //   innerHTML und Verwandte, ganz gleich, was darin steht.
                    //   `trusted-types aw-icons aw-hud` zaehlt die Ausnahmen
                    //   namentlich auf. Genau zwei, je einmal vergebbar, beide
                    //   in einem Abschluss, an den von aussen niemand
                    //   herankommt - die Icon-Tabellen in `app.js` und
                    //   `viewer-ui.js`. Ein dritter Name waere nicht erlaubt,
                    //   ein zweiter mit demselben Namen auch nicht: wer Text
                    //   zu Markup machen will, kann sich die Erlaubnis dafuer
                    //   nicht selbst schreiben.
                    //
                    //   ES REICHT NICHT, innerHTML ZU MEIDEN. Der erste
                    //   Versuch hier baute die Icons mit `DOMParser` - und der
                    //   ist selbst ein solcher Einstieg, was erst der Browser
                    //   sagte. Genau dafuer ist die Regel da: sie kennt die
                    //   Liste, wir nicht.
                    //
                    //   Firefox und Safari ignorieren beide Direktiven bis
                    //   heute. Das macht sie nicht wertlos: die Luecke, die
                    //   sie schliessen, entsteht beim SCHREIBEN von Code, und
                    //   geschrieben wird er in genau einem Browser bemerkt.
                    policyDirectives = "default-src 'self'; img-src 'self' data:; " +
                        "style-src 'self'; script-src 'self'; font-src 'self'; " +
                        "connect-src 'self'; media-src 'none'; " +
                        "object-src 'none'; frame-src 'none'; frame-ancestors 'none'; " +
                        "base-uri 'self'; form-action 'self'; " +
                        "require-trusted-types-for 'script'; trusted-types aw-icons aw-hud"
                }
                frameOptions { deny = true }

                //  KEIN Referrer, nirgendwohin.
                //
                //  Die uebliche Wahl waere strict-origin-when-cross-origin. Hier
                //  ist sie falsch, und zwar wegen einer Eigenschaft dieses
                //  Portals: ein privater Clip ist "nicht gelistet, nur ueber
                //  seinen Link erreichbar" - der Link IST das Geheimnis, und er
                //  steht in der Adresse (/clip.html?p=<slug>). Jede Seite
                //  verlinkt nach draussen (creativecommons.org, Discord), und
                //  jeder dieser Klicks haette den Slug im Referer mitgenommen.
                referrerPolicy { policy = ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER }

                //  Was diese Seite nicht braucht, soll sie auch nicht fragen
                //  duerfen. `fullscreen=(self)` ist die eine Ausnahme: der
                //  Viewer hat einen Vollbildknopf (viewer-ui.js).
                permissionsPolicy {
                    policy = "accelerometer=(), autoplay=(), camera=(), display-capture=(), " +
                        "encrypted-media=(), fullscreen=(self), geolocation=(), gyroscope=(), " +
                        "magnetometer=(), microphone=(), midi=(), payment=(), " +
                        "picture-in-picture=(), publickey-credentials-get=(), " +
                        "screen-wake-lock=(), usb=(), xr-spatial-tracking=()"
                }

                //  Ein fremdes Fenster, das diese Seite aufmacht, behaelt keinen
                //  Griff auf sie. Die Anmeldung beim Anbieter ist eine Weiterleitung
                //  im selben Fenster, kein Popup - ihr nimmt das nichts.
                crossOriginOpenerPolicy {
                    policy = CrossOriginOpenerPolicyHeaderWriter.CrossOriginOpenerPolicy.SAME_ORIGIN
                }
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
