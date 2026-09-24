package com.playmation.motionlabsbackend

import com.playmation.motionlabsbackend.account.AccountRepository
import com.playmation.motionlabsbackend.account.AccountStatus
import com.playmation.motionlabsbackend.auth.PortalRememberMeServices
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Der Browser bleibt angemeldet, und die Workbench nimmt ihn mit (Schema 9).
 *
 * Vorher: die Browser-Sitzung lebte 30 Minuten und keinen Neustart lang, und
 * "Your account on the portal" in der Workbench fuehrte fast immer vor die
 * Anmeldeseite. Hier steht fest, dass die Uebergabe genau einmal gilt, nur auf
 * Seiten dieses Portals fuehrt und von fremden Seiten nicht ausgeloest werden
 * kann - und dass "angemeldet bleiben" an Sperre und Abmelden endet.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BrowserSignInFlowTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var accounts: AccountRepository

    private val json = JsonMapper.builder().build()

    private fun unique() = UUID.randomUUID().toString().replace("-", "").take(8)

    private data class Workbench(val accountId: UUID, val token: String)

    /** Ein Konto, angemeldet wie die Workbench: nur mit Token. */
    private fun workbench(): Workbench {
        val result = mvc.post("/api/v1/dev/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"browser-${unique()}"}"""
            with(csrf())
        }.andExpect { status { isOk() } }.andReturn()
        val body = json.readTree(result.response.contentAsString)
        return Workbench(UUID.fromString(body["accountId"].asString()), body["token"].asString())
    }

    private fun handoff(workbench: Workbench, next: String = "/me.html"): String {
        val result = mvc.post("/api/v1/auth/handoff") {
            header("Authorization", "Bearer ${workbench.token}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"next":"$next"}"""
        }.andExpect { status { isOk() } }.andReturn()
        val url = json.readTree(result.response.contentAsString)["url"].asString()
        assertTrue("/signin/handoff?code=" in url, url)
        return url.substringAfter("code=")
    }

    private data class Browser(val session: MockHttpSession, val cookie: Cookie)

    /** Den Code einloesen, wie es der Browser tut, den die Workbench oeffnet. */
    private fun redeem(code: String): Browser {
        val result = mvc.get("/signin/handoff") {
            param("code", code)
            header("Sec-Fetch-Site", "none")
        }.andExpect {
            status { is3xxRedirection() }
            redirectedUrl("/me.html")
        }.andReturn()
        val cookie = assertNotNull(result.response.getCookie(PortalRememberMeServices.COOKIE))
        assertTrue(cookie.value.isNotBlank())
        return Browser(result.request.session as MockHttpSession, Cookie(cookie.name, cookie.value))
    }

    @Test
    fun `the workbench hands its sign-in to the browser, exactly once`() {
        val workbench = workbench()
        val code = handoff(workbench)
        val browser = redeem(code)

        mvc.get("/me.html") { session = browser.session }.andExpect {
            status { isOk() }
            content { string(org.hamcrest.Matchers.containsString("Ways to sign in")) }
        }

        //  Ein zweites Mal gilt derselbe Code nicht mehr.
        mvc.get("/signin/handoff") { param("code", code); header("Sec-Fetch-Site", "none") }.andExpect {
            redirectedUrl("/signin.html?error=handoff")
        }
        mvc.get("/signin.html") { param("error", "handoff") }.andExpect {
            content { string(org.hamcrest.Matchers.containsString("run out or was already used")) }
        }
    }

    @Test
    fun `a handoff started from another site is refused and stays unused`() {
        val code = handoff(workbench())

        mvc.get("/signin/handoff") { param("code", code); header("Sec-Fetch-Site", "cross-site") }.andExpect {
            redirectedUrl("/signin.html?error=handoff")
        }

        //  Nicht angefasst: aus der Workbench geoeffnet, geht es weiter.
        redeem(code)
    }

    @Test
    fun `a handoff only leads to pages of this portal`() {
        val workbench = workbench()
        for (target in listOf("https://evil.example/", "//evil.example/x", "/me.html\" onload=\"x", "javascript:alert(1)")) {
            mvc.post("/api/v1/auth/handoff") {
                header("Authorization", "Bearer ${workbench.token}")
                contentType = MediaType.APPLICATION_JSON
                content = json.writeValueAsString(mapOf("next" to target))
            }.andExpect { status { isBadRequest() } }
        }

        //  Ohne Konto gibt es keinen Code.
        mvc.post("/api/v1/auth/handoff") {
            contentType = MediaType.APPLICATION_JSON
            content = "{}"
            with(csrf())
        }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `the browser stays signed in without its session, until the account is banned`() {
        val workbench = workbench()
        val browser = redeem(handoff(workbench))

        //  Neue Anfrage OHNE Sitzung - wie nach einem Neustart des Servers.
        mvc.get("/me.html") { cookie(browser.cookie) }.andExpect {
            status { isOk() }
            content { string(org.hamcrest.Matchers.containsString("Ways to sign in")) }
        }

        val account = accounts.findById(workbench.accountId).orElseThrow()
        account.status = AccountStatus.BANNED
        accounts.save(account)

        val refused = mvc.get("/me.html") { cookie(browser.cookie) }.andExpect {
            status { is3xxRedirection() }
        }.andReturn()
        assertTrue(refused.response.redirectedUrl!!.endsWith("/signin.html"), refused.response.redirectedUrl)
        assertEquals(0, refused.response.getCookie(PortalRememberMeServices.COOKIE)?.maxAge)
    }

    @Test
    fun `signing out ends staying signed in`() {
        val browser = redeem(handoff(workbench()))

        val out = mvc.post("/logout") {
            session = browser.session
            cookie(browser.cookie)
            with(csrf())
        }.andExpect { status { is3xxRedirection() } }.andReturn()
        assertEquals(0, out.response.getCookie(PortalRememberMeServices.COOKIE)?.maxAge)

        //  Auch wer den alten Wert noch hat, kommt damit nicht mehr herein.
        mvc.get("/me.html") { cookie(browser.cookie) }.andExpect { status { is3xxRedirection() } }
    }
}
