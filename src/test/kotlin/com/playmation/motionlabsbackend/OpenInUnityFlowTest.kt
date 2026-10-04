package com.playmation.motionlabsbackend

import com.playmation.motionlabsbackend.catalog.Declaration
import com.playmation.motionlabsbackend.system.SystemSettingsService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.multipart
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.GZIPOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * "Open in Unity": die Clip-Seite legt eine Bitte ab, die Workbench desselben
 * Kontos holt sie ab - einmal. Den Knopf (`workbench` in /api/v1/me) und die
 * Bitte gibt es nur, wenn eine angemeldete Workbench schon einmal gefragt hat:
 * die bis 2.5.0 ist auch angemeldet, fragt aber nie.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenInUnityFlowTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var settings: SystemSettingsService

    @BeforeEach
    fun switchOn() = settings.set(SystemSettingsService.WEB_UPLOAD_ENABLED, true)

    private val json = JsonMapper.builder().build()

    private fun ResultActionsDsl.body(): JsonNode = json.readTree(andReturn().response.contentAsString)

    private fun unique() = UUID.randomUUID().toString().replace("-", "").take(8)

    /** Der Entwickler-Login: ein Token wie das der Workbench UND eine Browser-Sitzung. */
    private data class Login(val token: String, val session: MockHttpSession)

    private fun login(name: String): Login {
        val result = mvc.post("/api/v1/dev/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"$name"}"""
            with(csrf())
        }.andExpect { status { isOk() } }.andReturn()
        val token = json.readTree(result.response.contentAsString)["token"].asString()
        return Login(token, result.request.session as MockHttpSession)
    }

    /** Ein Clip nach Format 2, [turn] macht ihn einmalig. */
    private fun upload(token: String, title: String, turn: Double, license: String = "CC0-1.0"): String {
        val s = kotlin.math.sin(turn / 2)
        val c = kotlin.math.cos(turn / 2)
        val doc = """
            {"format":"awclip","version":2,
             "manifest":{"title":"$title","tags":["wave"],"license":"$license","rig":"humanoid",
                         "frameRate":30,"duration":0.1,"tool":"Playmations upload"},
             "settings":{"loopTime":true},
             "origin":"unknown",
             "curves":[],
             "preview":{"frameRate":30,"bones":["Hips","Spine","Head"],"parents":[-1,0,1],
                        "rest":[[0,0.95,0],[0,0.1,0],[0,0.5,0]],
                        "hips":[[0,0.95,0],[0,0.96,0.01]],
                        "rotations":[[0,0,0,1,0,0,0,1,0,0,0,1],[0,$s,0,$c,0,0,0,1,0,0,0,1]],
                        "restRot":[[0,0,0,1],[0,0,0,1],[0,0,0,1]]}}
        """.trimIndent()
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(doc.toByteArray()) }

        return mvc.multipart("/api/v1/packages") {
            file("file", out.toByteArray())
            param("declarationText", Declaration.TEXT)
            param("declarationVersion", Declaration.VERSION.toString())
            param("declarationAccepted", "true")
            header("Authorization", "Bearer $token")
        }.andExpect { status { isCreated() } }.body()["slug"].asString()
    }

    private fun open(login: Login, slug: String, kind: String = "clip") =
        mvc.post("/api/v1/me/unity/open") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"kind":"$kind","slug":"$slug"}"""
            session = login.session
            with(csrf())
        }

    private fun take(token: String) = mvc.get("/api/v1/me/unity/open") { header("Authorization", "Bearer $token") }

    @Test
    fun `a clip opened on the web waits for the Workbench, and only once`() {
        val me = login("unity-${unique()}")
        val title = "Unity Wave ${unique()}"
        val slug = upload(me.token, title, 0.31)

        //  Die Workbench fragt, sobald Unity vorne ist - noch wartet nichts.
        take(me.token).andExpect { status { isNoContent() } }

        //  Die Seite fragt mit der Browser-Sitzung - jetzt holt jemand ab.
        val profile = mvc.get("/api/v1/me") { session = me.session }.andExpect { status { isOk() } }.body()
        assertTrue(profile["workbench"].asBoolean())

        val asked = open(me, slug).andExpect { status { isOk() } }.body()
        assertEquals(title, asked["title"].asString())

        val got = take(me.token).andExpect { status { isOk() } }.body()
        assertEquals("clip", got["kind"].asString())
        assertEquals(slug, got["slug"].asString())
        assertTrue(got["open"].asBoolean())

        take(me.token).andExpect { status { isNoContent() } }
    }

    @Test
    fun `the last click wins, and an own private clip says it is private`() {
        val me = login("unity-last-${unique()}")
        val first = upload(me.token, "Unity First ${unique()}", 0.32)
        val secret = upload(me.token, "Unity Secret ${unique()}", 0.33, license = "ARR")
        take(me.token).andExpect { status { isNoContent() } }

        open(me, first).andExpect { status { isOk() } }
        open(me, secret).andExpect { status { isOk() } }

        val got = take(me.token).andExpect { status { isOk() } }.body()
        assertEquals(secret, got["slug"].asString())
        assertFalse(got["open"].asBoolean(), "the Workbench finds it under My clips, not on the wall")
    }

    @Test
    fun `someone else's private clip does not open`() {
        val owner = login("unity-owner-${unique()}")
        val secret = upload(owner.token, "Unity Hidden ${unique()}", 0.34, license = "ARR")

        val other = login("unity-other-${unique()}")
        take(other.token).andExpect { status { isNoContent() } }
        open(other, secret).andExpect { status { isNotFound() } }
        open(other, secret, kind = "collection").andExpect { status { isBadRequest() } }
    }

    @Test
    fun `signing out in the Workbench takes the button away`() {
        val me = login("unity-out-${unique()}")
        val slug = upload(me.token, "Unity Out ${unique()}", 0.35)
        take(me.token).andExpect { status { isNoContent() } }
        assertTrue(mvc.get("/api/v1/me") { session = me.session }.andExpect { status { isOk() } }.body()["workbench"].asBoolean())

        mvc.post("/api/v1/me/tokens/revoke-current") { header("Authorization", "Bearer ${me.token}") }
            .andExpect { status { isOk() } }

        //  Das Token gilt nicht mehr ...
        mvc.get("/api/v1/me") { header("Authorization", "Bearer ${me.token}") }.andExpect { status { isUnauthorized() } }

        //  ... und die Seite weiss, dass keine Workbench mehr da ist.
        val profile = mvc.get("/api/v1/me") { session = me.session }.andExpect { status { isOk() } }.body()
        assertFalse(profile["workbench"].asBoolean())

        val refused = open(me, slug).andExpect { status { isConflict() } }.body()
        assertEquals("no-workbench", refused["error"]["code"].asString())

        //  Eine Browser-Sitzung hat kein Token, das sie abgeben koennte.
        mvc.post("/api/v1/me/tokens/revoke-current") { session = me.session; with(csrf()) }
            .andExpect { status { isBadRequest() } }
    }

    @Test
    fun `a Workbench that never asks gets no button`() {
        //  Angemeldet wie die Workbench bis 2.5.0 - die fragt nie nach.
        val me = login("unity-old-${unique()}")
        val slug = upload(me.token, "Unity Old ${unique()}", 0.36)

        assertFalse(mvc.get("/api/v1/me") { session = me.session }.andExpect { status { isOk() } }.body()["workbench"].asBoolean())
        open(me, slug).andExpect { status { isConflict() } }

        //  Und eine Browser-Sitzung kann nicht behaupten, sie hole ab.
        mvc.get("/api/v1/me/unity/open") { session = me.session }.andExpect { status { isBadRequest() } }
        assertFalse(mvc.get("/api/v1/me") { session = me.session }.andExpect { status { isOk() } }.body()["workbench"].asBoolean())
    }
}
