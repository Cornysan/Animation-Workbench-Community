package com.playmation.motionlabsbackend

import com.playmation.motionlabsbackend.catalog.Declaration
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
import org.springframework.test.web.servlet.put
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.GZIPOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Figur und Look eines Clips (looks/FigureLooks.kt).
 *
 * Festgehalten wird, was beim naechsten Handgriff still umfallen koennte:
 * welche drei Looks Lite hat, dass das Portal jeden bekannten Look annimmt
 * (Lite und Pro trennt die Workbench, nicht der Server), und dass ein
 * unbekannter scheitert, bevor irgendetwas gespeichert ist.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LookFlowTest {

    @Autowired lateinit var mvc: MockMvc

    private val json = JsonMapper.builder().build()

    // ── Hilfen ──────────────────────────────────────────────────────────

    private fun ResultActionsDsl.body(): JsonNode = json.readTree(andReturn().response.contentAsString)

    private fun unique() = UUID.randomUUID().toString().replace("-", "").take(8)

    private fun login(name: String): String =
        mvc.post("/api/v1/dev/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"$name"}"""
            with(csrf())
        }.andExpect { status { isOk() } }.body()["token"].asString()

    private fun awclip(title: String): ByteArray {
        //  Jede Bewegung einmalig, sonst faengt die Duplikatsperre sie.
        val seed = java.util.concurrent.ThreadLocalRandom.current().nextDouble()
        val doc = """
            {"format":"awclip","version":1,
             "manifest":{"title":"$title","tags":["walk"],"license":"CC0-1.0","rig":"humanoid","frameRate":30,"duration":1},
             "origin":"own",
             "curves":[{"attribute":"Head Nod Down-Up","keys":[[0,$seed,0,0],[1,0.25,0,0]]}],
             "preview":{"frameRate":15,"bones":["Hips","Spine"],"parents":[-1,0],"rest":[[0,1,0],[0,0.1,0]],
                        "hips":[[0,1,0]],"rotations":[[0,0,0,1,0,0,0,1]]}}
        """.trimIndent()
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(doc.toByteArray()) }
        return out.toByteArray()
    }

    private fun upload(token: String, title: String, figure: String? = null, look: String? = null) =
        mvc.multipart("/api/v1/packages") {
            file("file", awclip(title))
            param("declarationText", Declaration.TEXT)
            param("declarationVersion", Declaration.VERSION.toString())
            param("declarationAccepted", "true")
            figure?.let { param("figure", it) }
            look?.let { param("look", it) }
            header("Authorization", "Bearer $token")
        }

    private fun edit(token: String, slug: String, body: String) =
        mvc.patch("/api/v1/packages/$slug") {
            contentType = MediaType.APPLICATION_JSON
            content = body
            header("Authorization", "Bearer $token")
        }

    private fun looks(token: String? = null): JsonNode =
        mvc.get("/api/v1/looks") { token?.let { header("Authorization", "Bearer $it") } }
            .andExpect { status { isOk() } }.body()

    private fun look(view: JsonNode, key: String): JsonNode =
        view["looks"].first { it["key"].asString() == key }

    private fun cardPng(): ByteArray {
        val image = java.awt.image.BufferedImage(1200, 630, java.awt.image.BufferedImage.TYPE_INT_RGB)
        return ByteArrayOutputStream().also { javax.imageio.ImageIO.write(image, "png", it) }.toByteArray()
    }

    // ── Tests ───────────────────────────────────────────────────────────

    /** Die Auswahl zeigt auch ohne Konto alles, was es gibt - und was Pro ist. */
    @Test
    fun `the looks are public and say which are pro`() {
        val view = looks()

        assertEquals(listOf("default", "female"), view["figures"].map { it["key"].asString() })
        assertEquals("classic", view["defaultLook"].asString())

        val lite = view["looks"].filter { !it["pro"].asBoolean() }.map { it["key"].asString() }
        assertEquals(listOf("classic", "mint", "coral"), lite)
        assertEquals(12, view["looks"].size())

        val galaxy = look(view, "galaxy")
        assertTrue(galaxy["pro"].asBoolean())
        assertFalse(galaxy.has("unlocked"))
        assertTrue(galaxy["image"].asString().matches(Regex("/assets/v/[^/]+/looks/galaxy.png")))
    }

    /**
     * Das Portal kennt keine Lizenz: jeder bekannte Look geht durch, auch
     * ein Pro-Look ohne jede Vorgeschichte. Ein unbekannter scheitert, und
     * zwar ganz - kein Clip bleibt zurueck.
     */
    @Test
    fun `any known look is taken at upload, an unknown one is refused`() {
        val name = "looker-${unique()}"
        val token = login(name)

        upload(token, "Velvet walk", look = "velvet").andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("invalid-look") }
        }
        upload(token, "Nobody", figure = "robot").andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("invalid-figure") }
        }
        mvc.get("/api/v1/me/packages") { header("Authorization", "Bearer $token") }
            .andExpect { status { isOk() }; jsonPath("$.length()") { value(0) } }

        val slug = upload(token, "Galaxy walk", figure = "female", look = "galaxy").andExpect {
            status { isCreated() }
            jsonPath("$.figure") { value("female") }
            jsonPath("$.look") { value("galaxy") }
        }.body()["slug"].asString()

        mvc.get("/api/v1/packages/$slug").andExpect {
            jsonPath("$.figure") { value("female") }
            jsonPath("$.look") { value("galaxy") }
        }
        val card = mvc.get("/api/v1/users/$name/packages").andExpect { status { isOk() } }.body()["items"][0]
        assertEquals("galaxy", card["look"].asString())
        assertEquals("female", card["figure"].asString())

        //  Ohne Angabe: der Standard.
        upload(token, "Plain walk").andExpect {
            status { isCreated() }
            jsonPath("$.figure") { value("default") }
            jsonPath("$.look") { value("classic") }
        }
    }

    /** Ein Wechsel des Looks nimmt dem Clip sein Vorschaubild - es zeigte die alte Figur. */
    @Test
    fun `changing the look drops the old preview card`() {
        val token = login("changer-${unique()}")
        val slug = upload(token, "First share").andExpect { status { isCreated() } }.body()["slug"].asString()

        mvc.put("/api/v1/packages/$slug/card") {
            contentType = MediaType.IMAGE_PNG
            content = cardPng()
            with(csrf())
            header("Authorization", "Bearer $token")
        }.andExpect { status { isNoContent() } }
        mvc.get("/api/v1/packages/$slug").andExpect { jsonPath("$.hasCard") { value(true) } }

        //  Nur der Look: Titel und Datei bleiben, das Bild geht.
        edit(token, slug, """{"title":"First share","description":"","tags":["walk"],"license":"CC0-1.0","look":"ocean"}""")
            .andExpect {
                status { isOk() }
                jsonPath("$.look") { value("ocean") }
                jsonPath("$.figure") { value("default") }
                jsonPath("$.hasCard") { value(false) }
            }

        //  Eine aeltere Workbench schickt keinen Look - dann bleibt er.
        edit(token, slug, """{"title":"First share, renamed","description":"","tags":["walk"],"license":"CC0-1.0"}""")
            .andExpect { status { isOk() }; jsonPath("$.look") { value("ocean") } }

        edit(token, slug, """{"title":"First share","description":"","tags":["walk"],"license":"CC0-1.0","look":"velvet"}""")
            .andExpect { status { isBadRequest() }; jsonPath("$.error.code") { value("invalid-look") } }
    }
}
