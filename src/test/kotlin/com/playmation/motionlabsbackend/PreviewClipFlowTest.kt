package com.playmation.motionlabsbackend

import com.playmation.motionlabsbackend.catalog.Declaration
import com.playmation.motionlabsbackend.system.SystemSettingsService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.multipart
import org.springframework.test.web.servlet.patch
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
 * Ein Clip aus dem Browser: Format 2, keine Kurven, die Bewegung und die
 * T-Pose ihrer Quelle stehen in der Vorschau. Die Workbench backt die
 * Muskelkurven erst beim Import - das Portal muss ihn nur annehmen, zeigen,
 * und der Workbench bis 2.5.0 vorenthalten.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PreviewClipFlowTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var settings: SystemSettingsService

    /** Der Schalter der Admin-Seite steht von Haus aus auf aus. */
    @BeforeEach
    fun switchOn() = settings.set(SystemSettingsService.WEB_UPLOAD_ENABLED, true)

    private val json = JsonMapper.builder().build()

    private fun ResultActionsDsl.body(): JsonNode = json.readTree(andReturn().response.contentAsString)

    private fun unique() = UUID.randomUUID().toString().replace("-", "").take(8)

    private fun login(name: String): String =
        mvc.post("/api/v1/dev/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"$name"}"""
            with(csrf())
        }.andExpect { status { isOk() } }.body()["token"].asString()

    /** Eine Datei nach Format 2. [turn] macht die Bewegung einmalig. */
    private fun previewClip(title: String, turn: Double, restRot: Boolean = true, license: String = "CC0-1.0"): ByteArray {
        val s = kotlin.math.sin(turn / 2)
        val c = kotlin.math.cos(turn / 2)
        val rest = if (restRot) ""","restRot":[[0,0,0,1],[0,0,0,1],[0,0,0,1]]""" else ""
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
                        "rotations":[[0,0,0,1,0,0,0,1,0,0,0,1],[0,$s,0,$c,0,0,0,1,0,0,0,1]]$rest}}
        """.trimIndent()
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(doc.toByteArray()) }
        return out.toByteArray()
    }

    private fun upload(token: String, bytes: ByteArray) =
        mvc.multipart("/api/v1/packages") {
            file("file", bytes)
            param("declarationText", Declaration.TEXT)
            param("declarationVersion", Declaration.VERSION.toString())
            param("declarationAccepted", "true")
            header("Authorization", "Bearer $token")
        }

    private fun titlesIn(url: String, field: String = "items"): List<String> =
        mvc.get(url).andExpect { status { isOk() } }.body()[field].map {
            (it["clip"] ?: it)["title"]?.asString() ?: ""
        }

    @Test
    fun `switched off on the admin page, a clip without curves is turned away`() {
        val token = login("web-off-${unique()}")
        settings.set(SystemSettingsService.WEB_UPLOAD_ENABLED, false)
        try {
            val status = mvc.get("/api/v1/status").andExpect { status { isOk() } }.body()
            assertFalse(status["webUploadEnabled"].asBoolean())

            val answer = upload(token, previewClip("Web Off ${unique()}", 0.8)).andExpect { status { isForbidden() } }.body()
            assertEquals("web-upload-off", answer["error"]["code"].asString())
        } finally {
            settings.set(SystemSettingsService.WEB_UPLOAD_ENABLED, true)
        }
        assertTrue(mvc.get("/api/v1/status").andExpect { status { isOk() } }.body()["webUploadEnabled"].asBoolean())
    }

    @Test
    fun `the pages follow the switch`() {
        val token = login("web-page-${unique()}")
        fun page(path: String) = mvc.get(path) { header("Authorization", "Bearer $token") }
            .andExpect { status { isOk() } }.andReturn().response.contentAsString

        assertTrue(page("/upload.html").contains("id=\"drop\""))
        assertTrue(page("/share.html").contains("href=\"/upload.html\""))

        settings.set(SystemSettingsService.WEB_UPLOAD_ENABLED, false)
        try {
            val off = page("/upload.html")
            assertTrue(off.contains("not available right now"))
            assertFalse(off.contains("id=\"drop\""))
            assertFalse(page("/share.html").contains("href=\"/upload.html\""))
        } finally {
            settings.set(SystemSettingsService.WEB_UPLOAD_ENABLED, true)
        }
    }

    @Test
    fun `a clip from the browser goes up and carries its rest pose in the preview`() {
        val token = login("web-${unique()}")
        val title = "Web Wave ${unique()}"

        val clip = upload(token, previewClip(title, 0.2)).andExpect { status { isCreated() } }.body()
        val slug = clip["slug"].asString()
        assertEquals(0, clip["curveCount"].asInt(), "the curves are baked by the Workbench, not uploaded")

        val preview = mvc.get("/api/v1/packages/$slug/preview").andExpect { status { isOk() } }.body()
        assertEquals(3, preview["restRot"].size(), "the rest pose travels with the preview")
    }

    @Test
    fun `the Workbench up to 2_5_0 does not see it, the portal does`() {
        val token = login("web-list-${unique()}")
        val word = unique()
        val title = "Web Bow $word"
        upload(token, previewClip(title, 0.3)).andExpect { status { isCreated() } }

        //  Die flache Liste liest die Workbench bis 2.5.0 - und die kann ihn
        //  nicht importieren.
        assertFalse(title in titlesIn("/api/v1/packages?q=$word"))
        assertTrue(title in titlesIn("/api/v1/packages?q=$word&previewClips=true"))
        assertTrue(title in titlesIn("/api/v1/catalog?q=$word"))
    }

    @Test
    fun `two different motions are no duplicates, the same one is`() {
        val token = login("web-dup-${unique()}")
        val other = login("web-dup-other-${unique()}")

        upload(token, previewClip("Web A ${unique()}", 0.4)).andExpect { status { isCreated() } }
        //  Ueber die Kurven gerechnet hiessen beide gleich - es gibt ja keine.
        upload(token, previewClip("Web B ${unique()}", 0.5)).andExpect { status { isCreated() } }

        val again = upload(other, previewClip("Web A copy ${unique()}", 0.4)).andExpect { status { isConflict() } }.body()
        assertEquals("duplicate", again["error"]["code"].asString())
    }

    @Test
    fun `without its rest pose a clip without curves is turned down`() {
        val token = login("web-norest-${unique()}")
        val answer = upload(token, previewClip("Web No Rest ${unique()}", 0.6, restRot = false))
            .andExpect { status { isBadRequest() } }.body()
        assertEquals("invalid-awclip", answer["error"]["code"].asString())
        assertTrue(answer["error"]["message"].asString().contains("restRot"))
    }

    @Test
    fun `the owner edits it and the stored file follows`() {
        val token = login("web-edit-${unique()}")
        val slug = upload(token, previewClip("Web Edit ${unique()}", 0.7)).andExpect { status { isCreated() } }
            .body()["slug"].asString()

        val renamed = "Web Edited ${unique()}"
        mvc.patch("/api/v1/packages/$slug") {
            contentType = MediaType.APPLICATION_JSON
            content = json.writeValueAsString(mapOf("title" to renamed, "description" to "from the browser",
                "tags" to listOf("wave"), "license" to "CC0-1.0"))
            header("Authorization", "Bearer $token")
        }.andExpect { status { isOk() } }

        assertEquals(renamed, mvc.get("/api/v1/packages/$slug").andExpect { status { isOk() } }.body()["title"].asString())
    }
}
