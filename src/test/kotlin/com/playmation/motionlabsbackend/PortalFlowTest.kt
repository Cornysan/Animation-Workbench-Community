package com.playmation.motionlabsbackend

import com.playmation.motionlabsbackend.catalog.Declaration
import com.playmation.motionlabsbackend.catalog.UploadDeclarationRepository
import com.playmation.motionlabsbackend.format.AwclipSchema
import com.playmation.motionlabsbackend.system.IpRetentionJob
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
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.delete
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.UUID
import java.util.zip.GZIPOutputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Der Kern des Portals als Durchlauf gegen den echten Spring-Kontext (H2).
 * Jeder Test benutzt eigene Konten und eigene Bewegungsdaten - die Datenbank
 * ist für alle Tests dieselbe.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PortalFlowTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var declarations: UploadDeclarationRepository
    @Autowired lateinit var ipRetention: IpRetentionJob

    private val json = JsonMapper.builder().build()

    // ── Hilfen ──────────────────────────────────────────────────────────

    private fun ResultActionsDsl.body(): JsonNode = json.readTree(andReturn().response.contentAsString)

    private fun login(name: String): String =
        mvc.post("/api/v1/dev/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"$name"}"""
            with(csrf())
        }.andExpect { status { isOk() } }.body()["token"].asString()

    private fun unique() = UUID.randomUUID().toString().replace("-", "").take(10)

    /** Ein gültiges .awclip mit Bewegung, die es sonst nirgends gibt. */
    private fun awclip(seed: Double, title: String = "Test Clip", license: String = "CC-BY-4.0", tags: String = "\"walk\""): ByteArray {
        val doc = """
            {"format":"awclip","version":1,
             "manifest":{"title":"$title","tags":[$tags],"license":"$license","rig":"humanoid","frameRate":30,"duration":1},
             "origin":"own",
             "curves":[{"attribute":"Head Nod Down-Up","keys":[[0,$seed,0,0],[1,0.25,0,0]]}],
             "preview":{"frameRate":15,"bones":["Hips","Spine"],"parents":[-1,0],"rest":[[0,1,0],[0,0.1,0]],
                        "hips":[[0,1,0]],"rotations":[[0,0,0,1,0,0,0,1]]}}
        """.trimIndent()
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(doc.toByteArray()) }
        return out.toByteArray()
    }

    private fun upload(token: String, bytes: ByteArray, declarationText: String = Declaration.TEXT, accepted: Boolean = true): ResultActionsDsl =
        mvc.multipart("/api/v1/packages") {
            file("file", bytes)
            param("declarationText", declarationText)
            param("declarationVersion", Declaration.VERSION.toString())
            param("declarationAccepted", accepted.toString())
            header("Authorization", "Bearer $token")
        }

    private fun uploadOk(token: String, bytes: ByteArray): String =
        upload(token, bytes).andExpect { status { isCreated() } }.body()["slug"].asString()

    private fun report(token: String, slug: String): ResultActionsDsl =
        mvc.post("/api/v1/packages/$slug/reports") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"category":"COPYRIGHT","message":"This is from a paid pack."}"""
            header("Authorization", "Bearer $token")
        }

    private fun adminPost(path: String, admin: String, body: String) =
        mvc.post(path) {
            contentType = MediaType.APPLICATION_JSON
            content = body
            header("Authorization", "Bearer $admin")
        }

    // ── Tests ───────────────────────────────────────────────────────────

    @Test
    fun `status is public and carries the declaration the workbench must show`() {
        val status = mvc.get("/api/v1/status").andExpect { status { isOk() } }.body()
        assertEquals(Declaration.TEXT, status["declarationText"].asString())
        assertEquals(2, status["licenses"].size())

        // Ohne konfigurierte Discord-App darf die Oberflaeche den Knopf nicht
        // anbieten - er landet sonst auf Discords Fehlerseite.
        assertFalse(status["discordSignIn"].asBoolean())
    }

    @Test
    fun `web pages are served with a strict content security policy`() {
        mvc.get("/index.html").andExpect {
            status { isOk() }
            header { string("Content-Security-Policy", org.hamcrest.Matchers.containsString("script-src 'self'")) }
            header { string("X-Frame-Options", "DENY") }
        }
        mvc.get("/assets/viewer.js").andExpect { status { isOk() } }
    }

    @Test
    fun `upload needs sign-in, the exact declaration and a valid file`() {
        //  Ohne CSRF-Token scheitert ein Browser-POST schon am CSRF-Schutz (403);
        //  mit Token, aber ohne Anmeldung, an der Anmeldung (401).
        mvc.multipart("/api/v1/packages") { file("file", awclip(0.11)) }.andExpect { status { isForbidden() } }
        mvc.multipart("/api/v1/packages") { file("file", awclip(0.11)); with(csrf()) }.andExpect { status { isUnauthorized() } }

        val token = login("uploader-${unique()}")
        upload(token, awclip(0.12), declarationText = "I made it").andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("declaration-required") }
        }
        upload(token, awclip(0.13), accepted = false).andExpect { status { isBadRequest() } }
        upload(token, "not a clip".toByteArray()).andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("invalid-awclip") }
        }
    }

    @Test
    fun `published clip can be found, previewed and downloaded through a signed link`() {
        val token = login("author-${unique()}")
        val title = "Sneak ${unique()}"
        val bytes = awclip(0.21, title)
        val slug = uploadOk(token, bytes)

        val found = mvc.get("/api/v1/packages") { param("q", title.lowercase()) }.andExpect { status { isOk() } }.body()
        assertEquals(slug, found["items"][0]["slug"].asString())

        mvc.get("/api/v1/packages") { param("tag", "walk") }.andExpect { status { isOk() } }
        mvc.get("/api/v1/packages/$slug").andExpect {
            status { isOk() }
            jsonPath("$.status") { doesNotExist() }
        }
        mvc.get("/api/v1/packages/$slug/preview").andExpect {
            status { isOk() }
            jsonPath("$.bones[0]") { value("Hips") }
        }

        val link = mvc.post("/api/v1/packages/$slug/download-link").andExpect { status { isOk() } }.body()
        val downloaded = mvc.get(link["url"].asString()).andExpect { status { isOk() } }.andReturn().response.contentAsByteArray
        assertContentEquals(bytes, downloaded)

        mvc.get(link["url"].asString().replace(Regex("sig=[0-9a-f]+"), "sig=" + "0".repeat(64)))
            .andExpect { status { isForbidden() } }
    }

    @Test
    fun `a private clip is not in the catalog but works with its link`() {
        val owner = login("owner-${unique()}")
        val public = uploadOk(owner, awclip(0.95, "Shared jump"))
        val private = uploadOk(owner, awclip(0.96, "Shared jump", license = AwclipSchema.LICENSE_PRIVATE))

        val listed = mvc.get("/api/v1/packages?q=Shared jump").andExpect { status { isOk() } }.body()
        val slugs = listed["items"].map { it["slug"].asString() }
        assertTrue(public in slugs, "the public clip belongs in the catalog")
        assertFalse(private in slugs, "a private clip must not be listed")

        // Der Link bleibt der Weg zu einem privaten Clip - auch fuer andere.
        mvc.get("/api/v1/packages/$private").andExpect {
            status { isOk() }
            jsonPath("$.license") { value(AwclipSchema.LICENSE_PRIVATE) }
        }
    }

    @Test
    fun `browsing does not count as a download - taking into a project does`() {
        val owner = login("counter-${unique()}")
        val slug = uploadOk(owner, awclip(0.73, "Counted walk"))

        fun downloads() = mvc.get("/api/v1/packages/$slug").body()["downloads"].asLong()

        assertEquals(0L, downloads(), "a fresh clip has not been taken by anyone")

        //  Die Workbench laedt beim Abonnieren herunter - und laedt auch beim
        //  Stoebern. Der Abruf allein darf nichts zaehlen.
        val link = mvc.post("/api/v1/packages/$slug/download-link") { with(csrf()) }
            .andExpect { status { isOk() } }.body()["url"].asString()
        mvc.get(link).andExpect { status { isOk() } }

        assertEquals(0L, downloads(), "fetching the file is browsing, not taking")

        mvc.post("/api/v1/packages/$slug/taken") { with(csrf()) }.andExpect { status { isOk() } }

        assertEquals(1L, downloads(), "taking it into a project is what counts")
    }

    @Test
    fun `a like needs an account, counts once, and can be taken back`() {
        val owner = login("liker-${unique()}")
        val slug = uploadOk(owner, awclip(0.72, "Liked walk"))

        fun likes() = mvc.get("/api/v1/packages/$slug").body()["likes"].asLong()

        //  Ohne Anmeldung gar nicht erst: eine offene Zahl waere eine Einladung.
        mvc.post("/api/v1/packages/$slug/like") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"liked":true}"""
            with(csrf())
        }.andExpect { status { isUnauthorized() } }

        assertEquals(0L, likes())

        val fan = login("fan-${unique()}")

        repeat(2) {
            mvc.post("/api/v1/packages/$slug/like") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"liked":true}"""
                header("Authorization", "Bearer $fan")
                with(csrf())
            }.andExpect { status { isOk() } }
        }

        assertEquals(1L, likes(), "the same person liking twice is still one like")

        mvc.get("/api/v1/packages/$slug") {
            header("Authorization", "Bearer $fan")
        }.andExpect { jsonPath("$.likedByMe") { value(true) } }

        mvc.post("/api/v1/packages/$slug/like") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"liked":false}"""
            header("Authorization", "Bearer $fan")
            with(csrf())
        }.andExpect { status { isOk() } }

        assertEquals(0L, likes(), "taking the like back removes it")
    }

    @Test
    fun `the same motion cannot be uploaded twice`() {
        val bytes = awclip(0.31, "Original")
        uploadOk(login("first-${unique()}"), bytes)

        upload(login("second-${unique()}"), awclip(0.31, "Renamed copy", license = "ARR")).andExpect {
            status { isConflict() }
            jsonPath("$.error.code") { value("duplicate") }
        }
    }

    @Test
    fun `a report hides the clip at once, even for links handed out before`() {
        val owner = login("owner-${unique()}")
        val slug = uploadOk(owner, awclip(0.41))
        val link = mvc.post("/api/v1/packages/$slug/download-link").body()["url"].asString()

        report(login("reporter-${unique()}"), slug).andExpect { status { isCreated() } }

        mvc.get("/api/v1/packages/$slug").andExpect { status { isNotFound() } }
        mvc.get(link).andExpect { status { isNotFound() } }
        mvc.get("/api/v1/packages/$slug") { header("Authorization", "Bearer $owner") }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("AUTO_HIDDEN") }
        }
    }

    @Test
    fun `removal strikes the owner and blocks the motion from coming back`() {
        val admin = login("admin")
        val owner = login("owner-${unique()}")
        val slug = uploadOk(owner, awclip(0.51))
        report(login("reporter-${unique()}"), slug).andExpect { status { isCreated() } }

        val cases = mvc.get("/api/v1/admin/cases") { header("Authorization", "Bearer $admin") }.andExpect { status { isOk() } }.body()
        assertTrue(cases.any { it["packages"][0]["slug"].asString() == slug })

        mvc.get("/api/v1/admin/cases") { header("Authorization", "Bearer $owner") }.andExpect { status { isForbidden() } }

        adminPost("/api/v1/admin/packages/$slug/remove", admin, """{"note":"Asset Store pack","strike":true}""")
            .andExpect { status { isOk() } }

        mvc.get("/api/v1/me") { header("Authorization", "Bearer $owner") }.andExpect {
            jsonPath("$.status") { value("RESTRICTED") }
            jsonPath("$.unreadNotifications") { value(2) }
        }

        upload(login("reuploader-${unique()}"), awclip(0.51, "Totally new")).andExpect {
            status { isConflict() }
            jsonPath("$.error.code") { value("removed-content") }
        }
    }

    @Test
    fun `an unfounded report restores the clip`() {
        val admin = login("admin")
        val slug = uploadOk(login("owner-${unique()}"), awclip(0.61))
        val reporter = login("reporter-${unique()}")
        report(reporter, slug)

        adminPost("/api/v1/admin/packages/$slug/restore", admin, """{"note":"Made by the author"}""").andExpect { status { isOk() } }

        mvc.get("/api/v1/packages/$slug").andExpect { status { isOk() } }
        val notifications = mvc.get("/api/v1/me/notifications") { header("Authorization", "Bearer $reporter") }.body()
        assertTrue(notifications[0]["message"].asString().contains("stays available"))
    }

    @Test
    fun `takedown form works without an account and hides the listed clips`() {
        val slug = uploadOk(login("owner-${unique()}"), awclip(0.71))

        mvc.post("/api/v1/takedowns") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"contactName":"Jane","contactEmail":"jane@example.com","rightsHolder":"Studio X",
                "claimedWork":"Pack Y","packages":"https://community.example/clip.html?p=$slug unknown-thing",
                "goodFaith":true,"accurate":true}"""
        }.andExpect { status { isForbidden() } }   // ohne CSRF-Token aus dem Browser

        mvc.post("/api/v1/takedowns") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"contactName":"Jane","contactEmail":"jane@example.com","rightsHolder":"Studio X",
                "claimedWork":"Pack Y","packages":"https://community.example/clip.html?p=$slug unknown-thing",
                "goodFaith":true,"accurate":true}"""
            with(csrf())
        }.andExpect {
            status { isCreated() }
            jsonPath("$.hiddenPackages") { value(1) }
            jsonPath("$.unknownReferences[0]") { value("unknown-thing") }
        }

        mvc.get("/api/v1/packages/$slug").andExpect { status { isNotFound() } }
    }

    @Test
    fun `kill switch pauses the api but keeps status and takedowns reachable`() {
        val admin = login("admin")
        try {
            adminPost("/api/v1/admin/settings", admin, """{"communityEnabled":false}""").andExpect { status { isOk() } }

            mvc.get("/api/v1/packages").andExpect {
                status { isServiceUnavailable() }
                jsonPath("$.error.code") { value("community-disabled") }
            }
            mvc.get("/api/v1/status").andExpect { jsonPath("$.communityEnabled") { value(false) } }
            mvc.post("/api/v1/takedowns") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"contactName":"A","contactEmail":"a@b.co","rightsHolder":"B","claimedWork":"C","packages":"x","goodFaith":true,"accurate":true}"""
                with(csrf())
            }.andExpect { status { isCreated() } }
        } finally {
            adminPost("/api/v1/admin/settings", admin, """{"communityEnabled":true}""").andExpect { status { isOk() } }
        }
    }

    @Test
    fun `workbench signs in through the device flow`() {
        //  Wie aus Unity: kein Cookie, kein CSRF-Token, kein Bearer.
        val start = mvc.post("/api/v1/auth/editor/start").andExpect { status { isOk() } }.body()
        val secret = start["pollSecret"].asString()
        val code = start["userCode"].asString()

        val poll = { mvc.post("/api/v1/auth/editor/poll") { contentType = MediaType.APPLICATION_JSON; content = """{"pollSecret":"$secret"}""" } }
        poll().andExpect { status { isAccepted() } }

        val user = login("editor-${unique()}")
        mvc.post("/api/v1/auth/editor/approve") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"userCode":"${code.lowercase()}"}"""
            header("Authorization", "Bearer $user")
        }.andExpect { status { isOk() } }

        val token = poll().andExpect { status { isOk() } }.body()["token"].asString()
        mvc.get("/api/v1/me") { header("Authorization", "Bearer $token") }.andExpect { status { isOk() } }
        poll().andExpect { status { isGone() } }

        mvc.get("/api/v1/me") { header("Authorization", "Bearer awc_not-a-real-token") }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.error.code") { value("invalid-token") }
        }
    }

    @Test
    fun `withdrawn clips disappear and banned accounts lose their sign-in`() {
        val admin = login("admin")
        val owner = login("owner-${unique()}")
        val slug = uploadOk(owner, awclip(0.81))

        mvc.delete("/api/v1/packages/$slug") { header("Authorization", "Bearer $owner") }.andExpect { status { isOk() } }
        mvc.get("/api/v1/packages/$slug").andExpect { status { isNotFound() } }

        val ownerId = mvc.get("/api/v1/me") { header("Authorization", "Bearer $owner") }.body()["id"].asString()
        adminPost("/api/v1/admin/accounts/$ownerId/status", admin, """{"status":"BANNED","note":"test"}""").andExpect { status { isOk() } }
        mvc.get("/api/v1/me") { header("Authorization", "Bearer $owner") }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `stored ip addresses are pseudonymized after the retention period`() {
        val slug = uploadOk(login("privacy-${unique()}"), awclip(0.91))
        val declaration = declarations.findAll().last { it.ipPseudonymized.not() }
        declaration.createdAt = Instant.now().minusSeconds(60L * 60 * 24 * 365)
        declarations.save(declaration)

        ipRetention.run()

        val after = declarations.findById(declaration.id).get()
        assertTrue(after.ipPseudonymized, "slug $slug")
        assertTrue(after.ipAddress!!.startsWith("p:"))
    }
}
