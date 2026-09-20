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
import org.springframework.test.web.servlet.patch
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

    private fun unlock(token: String, slug: String) =
        mvc.post("/api/v1/packages/$slug/unlock") { header("Authorization", "Bearer $token") }

    private fun unlockOk(token: String, slug: String): String =
        unlock(token, slug).andExpect { status { isOk() } }.body()["url"].asString()

    private fun coins(token: String): Long =
        mvc.get("/api/v1/me") { header("Authorization", "Bearer $token") }.body()["coins"].asLong()

    private fun setEconomy(admin: String, on: Boolean) =
        adminPost("/api/v1/admin/settings", admin, """{"economyEnabled":$on}""").andExpect { status { isOk() } }

    private fun adminPost(path: String, admin: String, body: String) =
        mvc.post(path) {
            contentType = MediaType.APPLICATION_JSON
            content = body
            header("Authorization", "Bearer $admin")
        }

    private fun comment(token: String?, slug: String, text: String) =
        mvc.post("/api/v1/packages/$slug/comments") {
            contentType = MediaType.APPLICATION_JSON
            content = json.writeValueAsString(mapOf("body" to text))
            token?.let { header("Authorization", "Bearer $it") }
            with(csrf())
        }

    private fun commentOk(token: String, slug: String, text: String): String =
        comment(token, slug, text).andExpect { status { isCreated() } }.body()["id"].asString()

    private fun commentsOf(slug: String) = mvc.get("/api/v1/packages/$slug/comments").andExpect { status { isOk() } }.body()

    private fun reportComment(token: String, slug: String, commentId: String): ResultActionsDsl =
        mvc.post("/api/v1/packages/$slug/comments/$commentId/reports") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"category":"INAPPROPRIATE","message":"Not ok."}"""
            header("Authorization", "Bearer $token")
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

    /**
     * Jede Adresse, die im Fuss, in der Navigation oder in einer Takedown-Mail
     * steht, muss eine Seite liefern. Die Seiten lagen als Dateien unter
     * `static/`; seit sie aus Vorlagen kommen, haelt nur noch der
     * PageController die Zuordnung - ein vergessenes Ziel faellt sonst erst im
     * Betrieb als 404 auf.
     */
    @Test
    fun `every page url renders`() {
        val pages = listOf(
            "/", "/index.html", "/browse.html", "/clip.html", "/me.html", "/licenses.html", "/admin.html",
            "/dev.html", "/link.html", "/rules.html", "/terms.html", "/privacy.html",
            "/impressum.html", "/takedown.html",
        )

        for (page in pages) {
            mvc.get(page).andExpect {
                status { isOk() }
                content { contentTypeCompatibleWith(MediaType.TEXT_HTML) }
            }
        }
    }

    /**
     * Der Rahmen steht im AUSGELIEFERTEN HTML, nicht erst nach zwei
     * API-Aufrufen im Browser. Genau das war der Umbau, und genau das faellt
     * beim naechsten Handgriff still wieder um, wenn es niemand prueft.
     */
    @Test
    fun `the page frame is in the html, and moderation only for moderators`() {
        val anonymous = mvc.get("/").andReturn().response.contentAsString

        assertTrue(anonymous.contains("free to use"), "Seiteninhalt der Startseite fehlt")

        //  Der Katalog liegt seit der Startseite auf /browse.html. Beide
        //  Adressen muessen ihren eigenen Inhalt tragen, sonst faellt ein
        //  vertauschtes Ziel erst im Betrieb auf.
        val catalog = mvc.get("/browse.html").andReturn().response.contentAsString
        assertTrue(catalog.contains("Community clips"), "Katalog fehlt unter /browse.html")
        assertTrue(anonymous.contains("/licenses.html"), "Navigation fehlt im HTML")
        assertTrue(anonymous.contains("Report a rights violation"), "Fuss fehlt im HTML")

        //  Weggelassen, nicht versteckt: mit einer Klasse „hidden" im Dokument
        //  waere das Aufblitzen fuer alle anderen nur eine Frage des Zeitpunkts.
        assertFalse(anonymous.contains("/admin.html"), "Moderation darf anonym nicht im HTML stehen")

        val admin = login("admin")
        val asAdmin = mvc.get("/") { header("Authorization", "Bearer $admin") }
            .andReturn().response.contentAsString

        assertTrue(asAdmin.contains("/admin.html"), "Moderation fehlt fuer den Moderator")
        assertTrue(asAdmin.contains("Moderator"), "Rolle fehlt im Kontoblock")
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

        val link = mvc.post("/api/v1/packages/$slug/download-link") {
            header("Authorization", "Bearer $token")
        }.andExpect { status { isOk() } }.body()
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

    /**
     * Der Zaehler haengt an der Quittung, nicht an einem Ruf, den jeder
     * absetzen kann. Der alte anonyme `/taken` war ehrlich beschriftet, aber
     * faelschbar - dieser hier kostet ein Konto.
     */
    @Test
    fun `the counter follows the receipt, not the file fetch`() {
        val owner = login("counter-${unique()}")
        val taker = login("taker-${unique()}")
        val slug = uploadOk(owner, awclip(0.73, "Counted walk"))

        fun downloads() = mvc.get("/api/v1/packages/$slug").body()["downloads"].asLong()

        assertEquals(0L, downloads(), "a fresh clip has not been taken by anyone")

        //  Der Besitzer holt seine eigene Datei - das ist kein Vorgang.
        mvc.get(unlockOk(owner, slug)).andExpect { status { isOk() } }
        assertEquals(0L, downloads(), "the owner taking their own clip is not a take")

        val link = unlockOk(taker, slug)
        mvc.get(link).andExpect { status { isOk() } }
        assertEquals(1L, downloads(), "taking it into a project is what counts")

        //  Die Datei darf danach beliebig oft kommen, etwa beim erneuten
        //  Staging nach einem Recompile.
        mvc.get(link).andExpect { status { isOk() } }
        unlockOk(taker, slug)
        assertEquals(1L, downloads(), "a second fetch is not a second take")
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
        val link = mvc.post("/api/v1/packages/$slug/download-link") {
            header("Authorization", "Bearer $owner")
        }.body()["url"].asString()

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

    // ═════════════════════════════════════════════════════════════════════
    // KOMMENTARE
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun `anyone can read comments, only signed-in accounts can write them`() {
        val owner = login("c-owner-${unique()}")
        val reader = login("c-reader-${unique()}")
        val slug = uploadOk(owner, awclip(0.11))

        assertEquals(0, commentsOf(slug)["total"].asInt())

        commentOk(reader, slug, "Lovely arc on the arms.")
        comment(null, slug, "and me too").andExpect { status { isUnauthorized() } }

        val list = commentsOf(slug)
        assertEquals(1, list["total"].asInt())
        assertEquals("Lovely arc on the arms.", list["comments"][0]["body"].asString())
        assertFalse(list["comments"][0]["mine"].asBoolean(), "anonymous readers own nothing")

        assertEquals(1, mvc.get("/api/v1/packages/$slug").body()["comments"].asInt())
    }

    @Test
    fun `an empty comment is refused and a long one is turned away at the door`() {
        val author = login("c-empty-${unique()}")
        val slug = uploadOk(author, awclip(0.12))

        comment(author, slug, "   ").andExpect { status { isBadRequest() } }
        comment(author, slug, "x".repeat(1001)).andExpect { status { isBadRequest() } }
        assertEquals(0, commentsOf(slug)["total"].asInt())
    }

    @Test
    fun `the author can edit and delete, a stranger can do neither`() {
        val author = login("c-author-${unique()}")
        val stranger = login("c-stranger-${unique()}")
        val slug = uploadOk(author, awclip(0.13))
        val id = commentOk(author, slug, "Frist typo")

        mvc.patch("/api/v1/packages/$slug/comments/$id") {
            contentType = MediaType.APPLICATION_JSON
            content = json.writeValueAsString(mapOf("body" to "First, fixed"))
            header("Authorization", "Bearer $author")
        }.andExpect { status { isOk() } }

        val edited = commentsOf(slug)["comments"][0]
        assertEquals("First, fixed", edited["body"].asString())
        assertFalse(edited["editedAt"].isNull, "an edit must be visible as an edit")

        mvc.delete("/api/v1/packages/$slug/comments/$id") {
            header("Authorization", "Bearer $stranger")
        }.andExpect { status { isForbidden() } }

        mvc.delete("/api/v1/packages/$slug/comments/$id") {
            header("Authorization", "Bearer $author")
        }.andExpect { status { isOk() } }

        assertEquals(0, commentsOf(slug)["total"].asInt())
        assertEquals(0, mvc.get("/api/v1/packages/$slug").body()["comments"].asInt())
    }

    /**
     * Der Kern der Trennung: eine Meldung an einem Kommentar darf die
     * Animation nicht aus dem Katalog nehmen. Ein einzelner Satz ist kein
     * Grund, die Arbeit eines anderen zu verstecken.
     */
    @Test
    fun `reporting a comment hides the comment and leaves the clip alone`() {
        val owner = login("c-rep-owner-${unique()}")
        val rude = login("c-rude-${unique()}")
        val slug = uploadOk(owner, awclip(0.14))
        val id = commentOk(rude, slug, "Something unpleasant")

        reportComment(owner, slug, id).andExpect { status { isCreated() } }

        assertEquals(0, commentsOf(slug)["total"].asInt(), "the comment is gone")
        mvc.get("/api/v1/packages/$slug").andExpect { status { isOk() } }

        reportComment(owner, slug, id).andExpect { status { isConflict() } }
    }

    @Test
    fun `nobody reports their own comment`() {
        val author = login("c-self-${unique()}")
        val slug = uploadOk(author, awclip(0.15))
        val id = commentOk(author, slug, "My own words")

        reportComment(author, slug, id).andExpect { status { isBadRequest() } }
        assertEquals(1, commentsOf(slug)["total"].asInt())
    }

    @Test
    fun `dismissing a comment report brings the comment back, not the clip`() {
        val admin = login("admin")
        val owner = login("c-dis-owner-${unique()}")
        val writer = login("c-dis-writer-${unique()}")
        val slug = uploadOk(owner, awclip(0.16))
        val id = commentOk(writer, slug, "Harmless remark")

        reportComment(owner, slug, id).andExpect { status { isCreated() } }

        val case = mvc.get("/api/v1/admin/cases") { header("Authorization", "Bearer $admin") }
            .body().first { !it["commentId"].isNull && it["commentId"].asString() == id }
        assertEquals("comment-report", case["kind"].asString())
        assertTrue(case["message"].asString().contains("Harmless remark"), "the moderator must see the text")

        adminPost("/api/v1/admin/reports/${case["id"].asString()}/dismiss", admin, """{"note":"fine"}""")
            .andExpect { status { isOk() } }

        assertEquals(1, commentsOf(slug)["total"].asInt())
    }

    @Test
    fun `a hidden clip hides its comments with it`() {
        val admin = login("admin")
        val owner = login("c-hid-owner-${unique()}")
        val other = login("c-hid-other-${unique()}")
        val slug = uploadOk(owner, awclip(0.17))
        commentOk(other, slug, "Still visible")

        report(other, slug).andExpect { status { isCreated() } }

        mvc.get("/api/v1/packages/$slug/comments").andExpect { status { isNotFound() } }
        comment(other, slug, "sneaking in").andExpect { status { isNotFound() } }

        adminPost("/api/v1/admin/packages/$slug/restore", admin, """{"note":"fine"}""").andExpect { status { isOk() } }
        assertEquals(1, commentsOf(slug)["total"].asInt())
    }

    @Test
    fun `a moderator can remove a comment and the author is told`() {
        val admin = login("admin")
        val owner = login("c-mod-owner-${unique()}")
        val writer = login("c-mod-writer-${unique()}")
        val slug = uploadOk(owner, awclip(0.18))
        val id = commentOk(writer, slug, "To be removed")

        adminPost("/api/v1/admin/comments/$id/remove", admin, """{"note":"off topic"}""").andExpect { status { isOk() } }

        assertEquals(0, commentsOf(slug)["total"].asInt())
        val messages = mvc.get("/api/v1/me/notifications") { header("Authorization", "Bearer $writer") }.body()
        assertTrue(messages.any { it["message"].asString().contains("was removed") }, "the author hears about it")
    }

    // ═════════════════════════════════════════════════════════════════════
    // MUENZEN
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Das Tor ist im Auslieferungszustand ZU: die Wirtschaft laeuft mit, aber
     * Freischalten kostet nichts. So steht beim Umlegen niemand bei null.
     */
    @Test
    fun `with the gate closed unlocking is free but the owner still earns`() {
        val admin = login("admin")
        setEconomy(admin, false)

        val owner = login("e-owner-${unique()}")
        val taker = login("e-taker-${unique()}")
        val slug = uploadOk(owner, awclip(0.315))

        val ownerBefore = coins(owner)
        val takerBefore = coins(taker)

        unlockOk(taker, slug)

        assertEquals(takerBefore, coins(taker), "a closed gate costs nothing")
        assertEquals(ownerBefore + 1, coins(owner), "the credit runs either way")
    }

    @Test
    fun `an open gate charges once and the receipt lasts`() {
        val admin = login("admin")
        setEconomy(admin, true)
        try {
            val owner = login("e-once-owner-${unique()}")
            val taker = login("e-once-taker-${unique()}")
            val slug = uploadOk(owner, awclip(0.32))

            val before = coins(taker)
            unlockOk(taker, slug)
            assertEquals(before - 10, coins(taker), "ten coins for the first unlock")

            unlockOk(taker, slug)
            unlockOk(taker, slug)
            assertEquals(before - 10, coins(taker), "the receipt lasts - a second time is free")

            assertTrue(
                mvc.get("/api/v1/packages/$slug") { header("Authorization", "Bearer $taker") }
                    .body()["unlockedByMe"].asBoolean(),
                "the workbench must be able to tell",
            )
        } finally {
            setEconomy(admin, false)
        }
    }

    @Test
    fun `nobody pays for their own clip and nobody earns from it`() {
        val admin = login("admin")
        setEconomy(admin, true)
        try {
            val owner = login("e-self-${unique()}")
            val slug = uploadOk(owner, awclip(0.33))
            val before = coins(owner)

            unlockOk(owner, slug)

            assertEquals(before, coins(owner), "the own clip moves nothing in either direction")
            assertEquals(0L, mvc.get("/api/v1/packages/$slug").body()["downloads"].asLong())
        } finally {
            setEconomy(admin, false)
        }
    }

    /**
     * Ein privat geteilter Slug ist eine Einladung, keine Auslage. Wer ihn
     * bekommt, soll nicht an einer Kasse stehen.
     */
    @Test
    fun `a private clip is free to unlock and pays nothing`() {
        val admin = login("admin")
        setEconomy(admin, true)
        try {
            val owner = login("e-priv-owner-${unique()}")
            val taker = login("e-priv-taker-${unique()}")
            val slug = uploadOk(owner, awclip(0.34, license = AwclipSchema.LICENSE_PRIVATE))

            val ownerBefore = coins(owner)
            val takerBefore = coins(taker)

            unlockOk(taker, slug)

            assertEquals(takerBefore, coins(taker), "a private link is not a shop")
            assertEquals(ownerBefore, coins(owner), "and it pays nothing either")
        } finally {
            setEconomy(admin, false)
        }
    }

    @Test
    fun `an empty purse is turned away and leaves no receipt`() {
        val admin = login("admin")
        setEconomy(admin, true)
        try {
            val owner = login("e-poor-owner-${unique()}")
            val broke = login("e-poor-${unique()}")
            val slugs = (0..5).map { uploadOk(owner, awclip(0.40 + it * 0.001)) }

            //  Die Grundausstattung reicht fuer genau fuenf.
            for (slug in slugs.take(5)) unlockOk(broke, slug)
            assertEquals(0L, coins(broke), "fifty coins buy five unlocks")

            unlock(broke, slugs[5]).andExpect {
                status { isConflict() }
                jsonPath("$.error.code") { value("not-enough-coins") }
            }

            assertFalse(
                mvc.get("/api/v1/packages/${slugs[5]}") { header("Authorization", "Bearer $broke") }
                    .body()["unlockedByMe"].asBoolean(),
                "a refused unlock must not leave a receipt",
            )
            assertEquals(0L, mvc.get("/api/v1/packages/${slugs[5]}").body()["downloads"].asLong())
        } finally {
            setEconomy(admin, false)
        }
    }

    @Test
    fun `a download link needs a receipt`() {
        val admin = login("admin")
        setEconomy(admin, true)
        try {
            val owner = login("e-link-owner-${unique()}")
            val stranger = login("e-link-other-${unique()}")
            val slug = uploadOk(owner, awclip(0.36))

            mvc.post("/api/v1/packages/$slug/download-link") {
                header("Authorization", "Bearer $stranger")
            }.andExpect {
                status { isConflict() }
                jsonPath("$.error.code") { value("not-unlocked") }
            }

            mvc.post("/api/v1/packages/$slug/download-link") { with(csrf()) }
                .andExpect { status { isUnauthorized() } }

            unlockOk(stranger, slug)
            mvc.post("/api/v1/packages/$slug/download-link") {
                header("Authorization", "Bearer $stranger")
            }.andExpect { status { isOk() } }
        } finally {
            setEconomy(admin, false)
        }
    }

    /**
     * Die Wochenaufgabe ist die eigentliche Beitragspraemie - mit hartem
     * Deckel, sonst waere sie ein Kopfgeld je Upload.
     */
    @Test
    fun `sharing pays once a week, not once a clip`() {
        val sharer = login("e-quest-${unique()}")
        val start = coins(sharer)

        uploadOk(sharer, awclip(0.515))
        assertEquals(start + 30, coins(sharer), "the first clip this week pays")

        uploadOk(sharer, awclip(0.52))
        uploadOk(sharer, awclip(0.53))
        assertEquals(start + 30, coins(sharer), "the second and third do not")

        val quests = mvc.get("/api/v1/me/quests") { header("Authorization", "Bearer $sharer") }
            .andExpect { status { isOk() } }.body()
        assertTrue(quests["quests"][0]["done"].asBoolean())
        assertEquals("share-a-clip", quests["quests"][0]["key"].asString())
    }

    @Test
    fun `a milestone is credited exactly once`() {
        val admin = login("admin")
        setEconomy(admin, false)

        val owner = login("e-mile-${unique()}")
        val slug = uploadOk(owner, awclip(0.615))
        val before = coins(owner)

        repeat(10) { unlockOk(login("e-mile-taker-$it-${unique()}"), slug) }

        // Zehn Freischaltungen zu je einer Muenze, dazu der Meilenstein bei 10.
        assertEquals(before + 10 + 25, coins(owner), "ten earnings plus the first milestone")

        unlockOk(login("e-mile-extra-${unique()}"), slug)
        assertEquals(before + 10 + 25 + 1, coins(owner), "the eleventh pays one coin, not the milestone again")

        val quests = mvc.get("/api/v1/me/quests") { header("Authorization", "Bearer $owner") }.body()
        assertEquals(11L, quests["unlocksEarned"].asLong())
        assertTrue(quests["milestones"][0]["reached"].asBoolean())
    }

    /**
     * Was an einem fremden Werk verdient wurde, war nie verdient. Die Buchung
     * bleibt stehen und bekommt eine Gegenbuchung - ein anhaengendes Protokoll
     * erzaehlt auch die Korrektur.
     */
    @Test
    fun `removing a clip for a rights violation books the earnings back`() {
        val admin = login("admin")
        setEconomy(admin, false)

        val owner = login("e-rev-owner-${unique()}")
        val slug = uploadOk(owner, awclip(0.715))
        repeat(3) { unlockOk(login("e-rev-taker-$it-${unique()}"), slug) }

        val earned = coins(owner)
        adminPost("/api/v1/admin/packages/$slug/remove", admin, """{"note":"Asset Store pack","strike":false}""")
            .andExpect { status { isOk() } }

        assertEquals(earned - 3, coins(owner), "three earnings, three reversals")

        val ledger = mvc.get("/api/v1/me/coins") { header("Authorization", "Bearer $owner") }
            .andExpect { status { isOk() } }.body()
        assertTrue(ledger["recent"].any { it["reason"].asString() == "reversal" }, "the correction is on the record")
    }
}
