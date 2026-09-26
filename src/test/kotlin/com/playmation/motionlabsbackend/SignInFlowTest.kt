package com.playmation.motionlabsbackend

import com.playmation.motionlabsbackend.account.AccountIdentityRepository
import com.playmation.motionlabsbackend.account.AccountRepository
import com.playmation.motionlabsbackend.account.AccountService
import com.playmation.motionlabsbackend.account.AvatarSources
import com.playmation.motionlabsbackend.account.Role
import com.playmation.motionlabsbackend.account.SignIn
import com.playmation.motionlabsbackend.auth.ConnectIntent
import com.playmation.motionlabsbackend.auth.PortalAuthentication
import com.playmation.motionlabsbackend.auth.PortalOAuth2User
import com.playmation.motionlabsbackend.auth.PortalPrincipal
import com.playmation.motionlabsbackend.auth.PortalUserService
import com.playmation.motionlabsbackend.auth.SignInFailureHandler
import com.playmation.motionlabsbackend.auth.SignInSuccessHandler
import com.playmation.motionlabsbackend.auth.SignInProfiles
import com.playmation.motionlabsbackend.common.PortalException
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Mehrere Wege in ein Konto: Discord, GitHub, Google.
 *
 * Die Regeln, die hier festgehalten werden, fallen sonst beim naechsten
 * Handgriff still um: eine verbundene Anmeldung aendert weder Namen noch Bild,
 * die letzte laesst sich nicht loesen, eine fremde laesst sich nicht verbinden,
 * und kein Anbieter wird nach der E-Mail gefragt.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SignInFlowTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var accounts: AccountService
    @Autowired lateinit var accountRepository: AccountRepository
    @Autowired lateinit var identities: AccountIdentityRepository
    @Autowired lateinit var registrations: ClientRegistrationRepository
    @Autowired lateinit var userService: PortalUserService

    private val json = JsonMapper.builder().build()

    private fun unique() = UUID.randomUUID().toString().replace("-", "").take(8)

    private fun github(id: String, name: String = "Octo Cat", login: String = "octo-$id") =
        SignIn("github", id, name, AvatarSources.github(id), handleHint = login)

    @AfterEach
    fun clearContext() {
        SecurityContextHolder.clearContext()
        RequestContextHolder.resetRequestAttributes()
    }

    // ── Was bei den Anbietern erfragt wird ────────────────────────────────

    @Test
    fun `no provider is asked for more than name and picture`() {
        assertEquals(setOf("identify"), registrations.findByRegistrationId("discord")!!.scopes)
        //  Leer heisst: GitHubs oeffentliches Profil. Die Vorgabe waere read:user.
        //  Spring fuehrt "keine" als null, nicht als leere Menge.
        assertTrue(registrations.findByRegistrationId("github")!!.scopes.isNullOrEmpty())
        assertEquals(setOf("profile"), registrations.findByRegistrationId("google")!!.scopes)

        //  Und so kommt es beim Anbieter an: die Weiterleitung selbst.
        fun redirect(provider: String) = mvc.get("/oauth2/authorization/$provider")
            .andExpect { status { is3xxRedirection() } }.andReturn().response.redirectedUrl!!

        val github = redirect("github")
        assertTrue(github.startsWith("https://github.com/login/oauth/authorize?"), github)
        assertFalse("scope=" in github, github)

        val google = redirect("google")
        assertTrue(google.startsWith("https://accounts.google.com/o/oauth2/v2/auth?"), google)
        assertTrue("scope=profile&" in google || google.endsWith("scope=profile"), google)
        assertFalse("email" in google, google)
    }

    @Test
    fun `each provider's answer becomes the same sign-in, without the email`() {
        val discord = SignInProfiles.from("discord", mapOf(
            "id" to "80351110224678912", "username" to "nelly", "global_name" to "Nelly", "avatar" to "a_8342729096ea3675442027381ff50dfe"))
        assertEquals("80351110224678912", discord.subject)
        assertEquals("Nelly", discord.displayName)
        assertEquals("https://cdn.discordapp.com/avatars/80351110224678912/a_8342729096ea3675442027381ff50dfe.png?size=128", discord.avatarUrl)
        assertNull(discord.handleHint)

        //  GitHub liefert die Kennung als Zahl und die E-Mail, wenn sie dort
        //  oeffentlich ist. Die Zahl wird Text, die E-Mail faellt weg.
        val github = SignInProfiles.from("github", mapOf(
            "id" to 583231, "login" to "octocat", "name" to null, "email" to "octocat@github.com"))
        assertEquals("583231", github.subject)
        assertEquals("octocat", github.displayName)
        assertEquals("octocat", github.handleHint)
        assertEquals("https://avatars.githubusercontent.com/u/583231?s=128", github.avatarUrl)
        assertFalse(github.toString().contains("@"))

        val google = SignInProfiles.from("google", mapOf(
            "sub" to "109876543210987654321", "name" to "Ada L", "picture" to "https://lh3.googleusercontent.com/a/ACg8ocK-x_y=s96-c"))
        assertEquals("109876543210987654321", google.subject)
        assertEquals("https://lh3.googleusercontent.com/a/ACg8ocK-x_y=s96-c", google.avatarUrl)

        //  Ein Bild von anderswo wird nicht uebernommen - der Buchstabe tut es.
        assertNull(SignInProfiles.from("google", mapOf("sub" to "1", "picture" to "https://evil.example/x.png")).avatarUrl)
        assertNull(SignInProfiles.from("discord", mapOf("id" to "1", "avatar" to "../../x?y")).avatarUrl)

        assertFailsWith<OAuth2AuthenticationException> { SignInProfiles.from("github", mapOf("login" to "no-id")) }
    }

    @Test
    fun `the avatar proxy only fetches from the three providers`() {
        assertTrue(AvatarSources.isAllowed("https://avatars.githubusercontent.com/u/1?s=128"))
        assertFalse(AvatarSources.isAllowed("http://avatars.githubusercontent.com/u/1"))
        assertFalse(AvatarSources.isAllowed("https://example.com/u/1"))
        assertFalse(AvatarSources.isAllowed("https://user@cdn.discordapp.com/avatars/1/a.png"))
        assertFalse(AvatarSources.isAllowed("https://cdn.discordapp.com:8443/avatars/1/a.png"))
        assertFalse(AvatarSources.isAllowed("https://cdn.discordapp.com/avatars/1/%2e%2e/a.png"))
    }

    // ── Konten ────────────────────────────────────────────────────────────

    @Test
    fun `the same sign-in finds the same account`() {
        val id = unique()
        val first = accounts.login(github(id))
        val second = accounts.login(github(id, name = "Renamed"))

        assertEquals(first.id, second.id)
        assertEquals("Renamed", second.displayName)
        //  Der Handle kommt aus dem GitHub-Login, nicht aus dem Klarnamen - und bleibt.
        assertEquals("octo-$id", second.handle)
    }

    @Test
    fun `a connected sign-in gets you in but does not change name or picture`() {
        val discordId = (100_000_000_000L + (Math.random() * 1e9).toLong()).toString()
        val account = accounts.login(SignIn("discord", discordId, "Nickname", AvatarSources.discord(discordId, "abc123")))

        val googleSub = unique()
        accounts.connect(account.id, SignIn("google", googleSub, "Real Full Name", "https://lh3.googleusercontent.com/a/x"))

        val viaGoogle = accounts.login(SignIn("google", googleSub, "Real Full Name", "https://lh3.googleusercontent.com/a/x"))
        assertEquals(account.id, viaGoogle.id)
        assertEquals("Nickname", viaGoogle.displayName)
        assertEquals(AvatarSources.discord(discordId, "abc123"), viaGoogle.avatarUrl)

        val rows = accounts.signIns(account.id)
        assertEquals(listOf("discord", "google"), rows.map { it.provider })
        assertEquals(listOf(true, false), rows.map { it.profileSource })
    }

    @Test
    fun `someone else's sign-in cannot be connected, and neither can a second of the same kind`() {
        val mine = accounts.login(github(unique()))
        val theirsId = unique()
        accounts.login(SignIn("google", theirsId, "Them"))

        val taken = assertFailsWith<PortalException> { accounts.connect(mine.id, SignIn("google", theirsId, "Them")) }
        assertEquals("identity-taken", taken.code)

        val second = assertFailsWith<PortalException> { accounts.connect(mine.id, github(unique())) }
        assertEquals("provider-connected", second.code)
    }

    @Test
    fun `the last sign-in stays, and losing the profile source takes the picture along`() {
        val id = unique()
        val account = accounts.login(github(id))

        val last = assertFailsWith<PortalException> { accounts.disconnect(account.id, "github") }
        assertEquals("last-sign-in", last.code)

        accounts.connect(account.id, SignIn("google", unique(), "Other"))
        val after = accounts.disconnect(account.id, "github")

        assertNull(after.avatarUrl)
        assertEquals("Octo Cat", after.displayName)
        assertEquals(listOf("google"), accounts.signIns(account.id).map { it.provider })
        assertTrue(accounts.signIns(account.id).single().profileSource)
    }

    /**
     * "Use for profile": die Quelle fuer Name und Bild ist waehlbar - aber nur
     * unter den EIGENEN Anmeldungen, und nur mit dem Konto, das dort
     * verbunden ist. Wird die gewaehlte geloest, gilt wieder die aelteste.
     */
    @Test
    fun `name and picture can come from a chosen sign-in, and fall back when it goes`() {
        val githubId = unique()
        val account = accounts.login(github(githubId))
        val discordId = (100_000_000_000L + (Math.random() * 1e9).toLong()).toString()
        val discord = SignIn("discord", discordId, "Nickname", AvatarSources.discord(discordId, "abc123"))
        accounts.connect(account.id, discord)

        val chosen = accounts.useForProfile(account.id, discord)
        assertEquals("Nickname", chosen.displayName)
        assertEquals(AvatarSources.discord(discordId, "abc123"), chosen.avatarUrl)
        assertEquals(listOf(false, true), accounts.signIns(account.id).map { it.profileSource })

        //  Die aeltere Anmeldung meldet weiter an, aendert aber nichts mehr ...
        assertEquals("Nickname", accounts.login(github(githubId)).displayName)
        //  ... die gewaehlte dagegen schon.
        assertEquals("Renamed", accounts.login(discord.copy(displayName = "Renamed")).displayName)

        //  Ein anderes Discord-Konto als das verbundene ist keine Wahl, sondern ein Versehen.
        val other = assertFailsWith<PortalException> {
            accounts.useForProfile(account.id, SignIn("discord", "999${unique().filter { it.isDigit() }}1", "Someone"))
        }
        assertEquals("other-identity", other.code)

        val after = accounts.disconnect(account.id, "discord")
        assertNull(after.avatarUrl)
        assertNull(after.profileProvider)
        assertTrue(accounts.signIns(account.id).single().profileSource)
    }

    @Test
    fun `returning from the provider with the profile intent switches the source`() {
        val account = accounts.login(github(unique()))
        val googleSub = unique()
        accounts.connect(account.id, SignIn("google", googleSub, "Full Name"))
        val principal = PortalPrincipal(account.id, account.role, account.displayName)
        SecurityContextHolder.getContext().authentication = PortalAuthentication(principal)

        val session = MockHttpSession().apply {
            setAttribute(ConnectIntent.SESSION_KEY,
                ConnectIntent(account.id, "google", Instant.now().plusSeconds(60), ConnectIntent.PROFILE))
        }
        val request = MockHttpServletRequest().apply { setSession(session) }
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(request))

        val back = userService.complete("google", mapOf("sub" to googleSub, "name" to "Full Name")) as PortalOAuth2User
        assertEquals(account.id, back.portalPrincipal.accountId)
        assertEquals("google", request.getAttribute(ConnectIntent.PROFILE_SET))
        assertNull(request.getAttribute(ConnectIntent.CONNECTED))
        assertEquals("Full Name", accountRepository.findById(account.id).get().displayName)

        val response = MockHttpServletResponse()
        SignInSuccessHandler().onAuthenticationSuccess(request, response, PortalAuthentication(principal))
        assertEquals("/me.html?profile=google", response.redirectedUrl)
    }

    @Test
    fun `moderator is the account, whichever of its sign-ins is on the list`() {
        //  Im Testprofil steht `dev:admin` auf der Liste - und nichts von GitHub.
        assertEquals(Role.USER, accounts.login(github(unique())).role)

        val admin = accounts.login(SignIn("dev", "admin", "admin"))
        assertEquals(Role.ADMIN, admin.role)

        val githubId = unique()
        accounts.connect(admin.id, github(githubId))
        assertEquals(Role.ADMIN, accounts.login(github(githubId)).role)
    }

    @Test
    fun `a bare number on the moderator list is a discord id`() {
        val properties = com.playmation.motionlabsbackend.config.PortalProperties(adminDiscordIds = " 123 , github:5,,dev:x ")
        assertEquals(setOf("discord:123", "github:5", "dev:x"), properties.adminIds())
    }

    // ── Rueckkehr vom Anbieter ────────────────────────────────────────────

    @Test
    fun `returning from the provider connects only with the intent from the account page`() {
        val account = accounts.login(github(unique()))
        val principal = PortalPrincipal(account.id, account.role, account.displayName)

        //  Ohne Vermerk: eine gewoehnliche Anmeldung - ein NEUES Konto.
        SecurityContextHolder.getContext().authentication = PortalAuthentication(principal)
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(MockHttpServletRequest()))
        val stranger = userService.complete("google", mapOf("sub" to unique(), "name" to "X")) as PortalOAuth2User
        assertNotEquals(account.id, stranger.portalPrincipal.accountId)

        //  Mit Vermerk: dieselbe Rueckkehr verbindet.
        val session = MockHttpSession().apply {
            setAttribute(ConnectIntent.SESSION_KEY, ConnectIntent(account.id, "discord", Instant.now().plusSeconds(60)))
        }
        val request = MockHttpServletRequest().apply { setSession(session) }
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(request))

        val connected = userService.complete("discord", mapOf("id" to "4242${(Math.random() * 1e6).toLong()}", "global_name" to "Y")) as PortalOAuth2User
        assertEquals(account.id, connected.portalPrincipal.accountId)
        assertEquals("discord", request.getAttribute(ConnectIntent.CONNECTED))
        assertNull(session.getAttribute(ConnectIntent.SESSION_KEY))
        assertEquals(listOf("github", "discord"), accounts.signIns(account.id).map { it.provider })

        //  Die Sitzung traegt nichts vom Anbieter - auch keine E-Mail.
        assertEquals(setOf("account"), connected.attributes.keys)
    }

    @Test
    fun `a failed return lands where it started, with a reason from a fixed list`() {
        fun redirectFor(error: String, connecting: Boolean): String {
            val request = MockHttpServletRequest()
            if (connecting) request.setSession(MockHttpSession().apply {
                setAttribute(ConnectIntent.SESSION_KEY, ConnectIntent(UUID.randomUUID(), "github", Instant.now()))
            })
            val response = MockHttpServletResponse()
            SignInFailureHandler().onAuthenticationFailure(request, response,
                OAuth2AuthenticationException(OAuth2Error(error), "<script>"))
            return response.redirectedUrl!!
        }

        assertEquals("/signin.html?error=cancelled", redirectFor("access_denied", false))
        assertEquals("/signin.html?error=banned", redirectFor("forbidden", false))
        assertEquals("/signin.html?error=failed", redirectFor("invalid_token_response", false))
        assertEquals("/me.html?connect-error=taken", redirectFor("identity_taken", true))
        assertEquals("/me.html?connect-error=cancelled", redirectFor("access_denied", true))
        assertEquals("/me.html?connect-error=other-account", redirectFor("other_identity", true))
    }

    // ── Seiten und Schnittstelle ──────────────────────────────────────────

    private fun devSession(name: String): Pair<MockHttpSession, String> {
        val result = mvc.post("/api/v1/dev/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"$name"}"""
            with(csrf())
        }.andExpect { status { isOk() } }.andReturn()
        val token = json.readTree(result.response.contentAsString)["token"].asString()
        return (result.request.session as MockHttpSession) to token
    }

    @Test
    fun `the sign-in page explains a failure in words and never echoes the address`() {
        mvc.get("/signin.html") { param("error", "banned") }.andExpect {
            status { isOk() }
            content { string(org.hamcrest.Matchers.containsString("This account is banned.")) }
            content { string(org.hamcrest.Matchers.containsString("Developer sign-in")) }
        }
        mvc.get("/signin.html") { param("error", "<b>boom</b>") }.andExpect {
            content { string(org.hamcrest.Matchers.containsString("Sign-in did not work.")) }
            content { string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("boom"))) }
        }
    }

    /**
     * Die Kontoseite ohne Sitzung: frueher eine halbe Seite mit "Sign in to
     * see your clips." - und darunter "Close my account". Jetzt geht es zur
     * Anmeldeseite, und nach der Rueckkehr vom Anbieter hierher zurueck statt
     * auf die Startseite. Genau so kommt an, wer in der Workbench "Your
     * account on the portal" waehlt und im Browser nicht angemeldet ist.
     */
    @Test
    fun `the account page sends a visitor without a session to sign in, and back`() {
        val result = mvc.get("/me.html").andExpect { status { is3xxRedirection() } }.andReturn()
        val target = result.response.redirectedUrl!!
        assertTrue(target.endsWith("/signin.html"), target)

        //  Die Rueckkehr vom Anbieter, in derselben Sitzung.
        val back = MockHttpServletRequest("GET", "/login/oauth2/code/github")
        back.setSession(result.request.session as MockHttpSession)
        val response = MockHttpServletResponse()
        SignInSuccessHandler().onAuthenticationSuccess(back, response, TestingAuthenticationToken("someone", ""))
        val home = response.redirectedUrl!!
        assertTrue("/me.html" in home, home)
    }

    @Test
    fun `the account page lists the ways in, and the api guards the last one`() {
        val (session, token) = devSession("signin-" + unique())

        mvc.get("/me.html") { this.session = session }.andExpect {
            status { isOk() }
            content { string(org.hamcrest.Matchers.containsString("Ways to sign in")) }
        }

        val rows: JsonNode = json.readTree(mvc.get("/api/v1/me/sign-ins") {
            header("Authorization", "Bearer $token")
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString)
        assertEquals(1, rows.size())
        assertEquals("dev", rows[0]["provider"].asString())
        assertTrue(rows[0]["profileSource"].asBoolean())
        assertFalse(rows[0]["canDisconnect"].asBoolean())
        assertFalse(rows[0].has("subject"))

        mvc.delete("/api/v1/me/sign-ins/dev") { header("Authorization", "Bearer $token") }
            .andExpect { status { isConflict() } }

        //  Im Testprofil ist kein Anbieter eingerichtet - verbinden gibt es dann nicht.
        mvc.post("/api/v1/me/sign-ins/github/connect") { header("Authorization", "Bearer $token") }
            .andExpect { status { isNotFound() } }
    }

    @Test
    fun `closing an account lets the same person come back to a fresh one`() {
        val name = "closer-" + unique()
        val (_, token) = devSession(name)
        val before = identities.findByProviderAndSubject("dev", name)!!.accountId

        mvc.delete("/api/v1/me") {
            header("Authorization", "Bearer $token")
        }.andExpect { status { isOk() } }

        assertNull(identities.findByProviderAndSubject("dev", name))
        devSession(name)
        assertNotEquals(before, identities.findByProviderAndSubject("dev", name)!!.accountId)
        assertEquals("Deleted user", accountRepository.findById(before).get().displayName)
    }

    // ── Migration ─────────────────────────────────────────────────────────

    /**
     * V8 zieht die Discord-Kennung aus dem Konto in die neue Tabelle. Das laeuft
     * genau einmal, auf dem Server, gegen echte Konten - und die Tests fahren
     * sonst nur leere Datenbanken hoch. Hier wird der Bestand vorher angelegt.
     */
    @Test
    fun `V8 moves discord ids and pictures, skips closed accounts`() {
        val url = "jdbc:h2:mem:v8-${unique()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
        fun flyway(target: String) = Flyway.configure().dataSource(url, "sa", "")
            .locations("classpath:db/migration").target(target).load()

        flyway("7").migrate()

        val discord = UUID.randomUUID()
        val dev = UUID.randomUUID()
        val closed = UUID.randomUUID()

        DriverManager.getConnection(url, "sa", "").use { c ->
            fun insert(id: UUID, key: String, avatar: String?) = c.prepareStatement(
                "insert into account (id, discord_id, display_name, role, status, created_at, handle, avatar) " +
                    "values (?, ?, 'n', 'USER', 'ACTIVE', current_timestamp, ?, ?)").use {
                it.setObject(1, id); it.setString(2, key); it.setString(3, "h-" + id.toString().take(8)); it.setString(4, avatar)
                it.executeUpdate()
            }
            insert(discord, "123456789012345678", "abcdef")
            insert(dev, "dev:alice", null)
            insert(closed, "deleted:1a2b3c4d", null)
        }

        flyway("8").migrate()

        DriverManager.getConnection(url, "sa", "").use { c ->
            val rows = c.createStatement().executeQuery(
                "select account_id, provider, subject from account_identity order by provider").use { rs ->
                generateSequence { if (rs.next()) Triple(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3)) else null }.toList()
            }
            assertEquals(listOf(
                Triple(dev, "dev", "alice"),
                Triple(discord, "discord", "123456789012345678"),
            ), rows)

            val avatar = c.prepareStatement("select avatar_url from account where id = ?").use {
                it.setObject(1, discord)
                it.executeQuery().use { rs -> rs.next(); rs.getString(1) }
            }
            assertEquals("https://cdn.discordapp.com/avatars/123456789012345678/abcdef.png?size=128", avatar)

            val columns = c.metaData.getColumns(null, null, "account", null).use { rs ->
                generateSequence { if (rs.next()) rs.getString("COLUMN_NAME") else null }.toSet()
            }
            assertFalse("discord_id" in columns)
            assertFalse("avatar" in columns)
        }
    }
}
