package com.playmation.motionlabsbackend

import com.playmation.motionlabsbackend.catalog.StarterClips
import com.playmation.motionlabsbackend.catalog.UploadDeclarationRepository
import com.playmation.motionlabsbackend.format.AwclipReadResult
import com.playmation.motionlabsbackend.format.AwclipReader
import com.playmation.motionlabsbackend.format.AwclipSchema
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
import java.util.zip.GZIPOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Starter-Clips: ein Admin spielt Clips aus oeffentlichen CC0-Sammlungen ein,
 * sie gehoeren dem Starter-Konto, tragen ihre Quelle und sind immer CC0 -
 * auch wenn die Datei etwas anderes sagt (ein Export aus der Workbench
 * schreibt "ARR" hinein).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StarterClipsTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var declarations: UploadDeclarationRepository

    private val json = JsonMapper.builder().build()

    private fun ResultActionsDsl.body(): JsonNode = json.readTree(andReturn().response.contentAsString)

    private fun login(name: String): String =
        mvc.post("/api/v1/dev/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"$name"}"""
            with(csrf())
        }.andExpect { status { isOk() } }.body()["token"].asString()

    /** Wie ein Export aus der Workbench: Lizenz ARR, Clipname mit Unterstrich. */
    private fun exported(seed: Double, title: String = "Armature|Walk_Loop", preview: Boolean = true): ByteArray {
        val previewBlock = if (!preview) "" else """,
             "preview":{"frameRate":15,"bones":["Hips","Spine"],"parents":[-1,0],"rest":[[0,1,0],[0,0.1,0]],
                        "hips":[[0,1,0]],"rotations":[[0,0,0,1,0,0,0,1]]}"""
        val doc = """
            {"format":"awclip","version":1,
             "manifest":{"title":"$title","tags":["walk"],"license":"ARR","rig":"humanoid","frameRate":30,"duration":1},
             "origin":"unknown",
             "curves":[{"attribute":"Head Nod Down-Up","keys":[[0,$seed,0,0],[1,0.25,0,0]]}]$previewBlock}
        """.trimIndent()
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(doc.toByteArray()) }
        return out.toByteArray()
    }

    private fun seed(token: String, bytes: ByteArray, credit: String = "Quaternius - Universal Animation Library",
                     url: String = "https://quaternius.com", tags: String = "starter"): ResultActionsDsl =
        mvc.multipart("/api/v1/admin/starter-clips") {
            file("file", bytes)
            param("credit", credit)
            param("url", url)
            param("tags", tags)
            header("Authorization", "Bearer $token")
        }

    private fun edit(token: String, slug: String, body: String): ResultActionsDsl =
        mvc.patch("/api/v1/packages/$slug") {
            contentType = MediaType.APPLICATION_JSON
            content = body
            header("Authorization", "Bearer $token")
        }

    @Test
    fun `an admin seeds a clip that belongs to the starter account and names its source`() {
        val admin = login("admin")

        val clip = seed(admin, exported(0.96137)).andExpect { status { isCreated() } }.body()
        val slug = clip["slug"].asString()

        assertEquals("Walk Loop", clip["title"].asString(), "skeleton prefix and underscores tidied")
        assertEquals(AwclipSchema.LICENSE_PUBLIC, clip["license"].asString(), "always CC0, whatever the file says")
        assertEquals(StarterClips.HANDLE, clip["authorHandle"].asString())
        assertEquals("Starter Clips", clip["author"].asString())
        assertEquals("Quaternius - Universal Animation Library", clip["source"]["credit"].asString())
        assertEquals("https://quaternius.com", clip["source"]["url"].asString())
        assertTrue(clip["tags"].any { it.asString() == "starter" } && clip["tags"].any { it.asString() == "walk" },
            "the file's tags and the batch tags")

        //  Die Datei, die jemand bekommt, sagt dasselbe - sonst hiesse der
        //  Clip in Unity "private, no rights granted".
        val link = mvc.post("/api/v1/packages/$slug/unlock") { header("Authorization", "Bearer ${login("taker")}") }
            .andExpect { status { isOk() } }.body()
        val downloaded = mvc.get(link["url"].asString()).andExpect { status { isOk() } }.andReturn().response.contentAsByteArray
        val manifest = (AwclipReader.readFile(downloaded.inputStream()) as AwclipReadResult.Ok).document.manifest
        assertEquals(AwclipSchema.LICENSE_PUBLIC, manifest.license)
        assertEquals("Walk Loop", manifest.title)

        //  Keine "I created this"-Erklaerung - ein eigener Eintrag mit Version 0.
        val seeded = declarations.findAll().filter { it.declarationVersion == 0 }
        assertTrue(seeded.isNotEmpty(), "the seed is on record")
        assertTrue(seeded.all { it.declarationText.contains("starter clip") && !it.declarationText.contains("I created") },
            "never the declaration a user confirms")

        //  Im Katalog steht er wie jeder andere Clip, die Quelle auf der Seite.
        val anonymous = mvc.get("/api/v1/packages/$slug").andExpect { status { isOk() } }.body()
        assertEquals("Quaternius - Universal Animation Library", anonymous["source"]["credit"].asString())
        assertFalse(anonymous["isOwner"].asBoolean(), "a visitor does not manage it")

        //  Das Starter-Konto hat ein Profil - und keine Anmeldung.
        mvc.get("/api/v1/users/${StarterClips.HANDLE}").andExpect { status { isOk() } }
    }

    @Test
    fun `admins manage starter clips, and they stay public`() {
        val admin = login("admin")
        val slug = seed(admin, exported(0.96237, "Run_Loop")).andExpect { status { isCreated() } }.body()["slug"].asString()

        val detail = mvc.get("/api/v1/packages/$slug") { header("Authorization", "Bearer $admin") }.body()
        assertTrue(detail["isOwner"].asBoolean(), "the admin gets the owner's buttons")

        edit(admin, slug, """{"title":"Run","description":"A light jog.","tags":["run"],"license":"CC0-1.0"}""")
            .andExpect { status { isOk() } }
        edit(admin, slug, """{"title":"Run","description":"","tags":["run"],"license":"ARR"}""")
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.error.code") { value("starter-stays-public") }
            }

        val stranger = login("stranger-" + slug)
        edit(stranger, slug, """{"title":"Mine now","description":"","tags":[],"license":"CC0-1.0"}""")
            .andExpect { status { isForbidden() } }
    }

    @Test
    fun `only admins seed, only with a preview, and never twice`() {
        val user = login("seeder-user")
        seed(user, exported(0.96337)).andExpect { status { isForbidden() } }

        val admin = login("admin")
        seed(admin, exported(0.96437, preview = false)).andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("no-preview") }
        }
        seed(admin, exported(0.96537), credit = "").andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("invalid-source") }
        }
        seed(admin, exported(0.96637), url = "http://insecure.example").andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("invalid-source-url") }
        }

        seed(admin, exported(0.96737)).andExpect { status { isCreated() } }
        seed(admin, exported(0.96737)).andExpect {
            status { isConflict() }
            jsonPath("$.error.code") { value("duplicate") }
        }
    }
}
