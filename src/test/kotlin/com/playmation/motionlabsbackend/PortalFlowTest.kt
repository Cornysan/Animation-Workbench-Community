package com.playmation.motionlabsbackend

import com.playmation.motionlabsbackend.catalog.Declaration
import com.playmation.motionlabsbackend.catalog.UploadDeclarationRepository
import com.playmation.motionlabsbackend.format.AwclipHash
import com.playmation.motionlabsbackend.format.AwclipReadResult
import com.playmation.motionlabsbackend.format.AwclipReader
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
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
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
import kotlin.test.assertNull
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
    private fun awclip(seed: Double, title: String = "Test Clip", license: String = AwclipSchema.LICENSE_PUBLIC, tags: String = "\"walk\""): ByteArray {
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

    /**
     * Ein GUELTIGES .awclip mit generischem Rig - eine Tuer an ihrem Scharnier.
     *
     * Der Leser nimmt diese Datei an, und das soll er: sie ist heil. Das
     * Portal nimmt sie trotzdem nicht - siehe [AwclipSchema.ACCEPTED_RIGS].
     * Genau dieser Unterschied ist das, was der Test festhaelt.
     */
    private fun genericAwclip(seed: Double): ByteArray {
        val doc = """
            {"format":"awclip","version":1,
             "manifest":{"title":"Door Swing","tags":["prop"],"license":"CC0-1.0","rig":"generic","frameRate":30,"duration":1},
             "origin":"own",
             "curves":[{"attribute":"Hinge/Panel.m_LocalRotation.y","keys":[[0,$seed,0,0],[1,0.7071068,0,0]]}],
             "preview":{"frameRate":30,"bones":["Hinge","Panel"],"parents":[-1,0],"rest":[[0,0,0],[0,0.9,0]],
                        "hips":[[0,0,0]],"rotations":[[0,0,0,1,0,0,0,1]]}}
        """.trimIndent()
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(doc.toByteArray()) }
        return out.toByteArray()
    }

    private fun upload(
        token: String, bytes: ByteArray, declarationText: String = Declaration.TEXT, accepted: Boolean = true,
        restPose: String? = null,
    ): ResultActionsDsl =
        mvc.multipart("/api/v1/packages") {
            file("file", bytes)
            param("declarationText", declarationText)
            param("declarationVersion", Declaration.VERSION.toString())
            param("declarationAccepted", accepted.toString())
            restPose?.let { param("restPose", it) }
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

    /**
     * Die T-Pose der Quellfigur reist neben der Datei und kommt mit der
     * Vorschau zurueck - ohne sie schreibt der Skelett-Export der Clip-Seite
     * eine geschaetzte Ruhelage. Eine, die nicht zur Vorschau passt, wird
     * abgewiesen statt gespeichert.
     */
    @Test
    fun `the rest pose travels next to the file and comes back with the preview`() {
        assertTrue(mvc.get("/api/v1/status").andExpect { status { isOk() } }.body()["restPoseWanted"].asBoolean())

        val token = login("rest-${unique()}")
        val slug = upload(token, awclip(0.611), restPose = "[[0,0,0,1],[0,0.7071068,0,0.7071068]]")
            .andExpect { status { isCreated() } }.body()["slug"].asString()
        mvc.get("/api/v1/packages/$slug/preview").andExpect {
            status { isOk() }
            jsonPath("$.bones[1]") { value("Spine") }
            jsonPath("$.restRot[0][3]") { value(1) }
            jsonPath("$.restRot[1][1]") { value(0.7071068) }
        }

        val without = uploadOk(token, awclip(0.612))
        mvc.get("/api/v1/packages/$without/preview").andExpect { jsonPath("$.restRot") { doesNotExist() } }

        for (bad in listOf("[[0,0,0,1]]", "[[0,0,0,1],[0,0,0,2]]", "[[0,0,0,1],[0,0,1]]", "not json")) {
            upload(token, awclip(0.613), restPose = bad).andExpect {
                status { isBadRequest() }
                jsonPath("$.error.code") { value("invalid-rest-pose") }
            }
        }
    }

    @Test
    fun `web pages are served with a strict content security policy`() {
        fun csp(contains: String) = org.hamcrest.Matchers.containsString(contains)

        mvc.get("/index.html").andExpect {
            status { isOk() }
            header { string("Content-Security-Policy", csp("script-src 'self'")) }

            //  form-action faellt auf nichts zurueck - ohne die Regel duerfte
            //  ein eingeschleustes Formular auf eine fremde Adresse zeigen und
            //  das CSRF-Token mitnehmen.
            header { string("Content-Security-Policy", csp("form-action 'self'")) }
            header { string("Content-Security-Policy", csp("frame-ancestors 'none'")) }
            header { string("Content-Security-Policy", csp("object-src 'none'")) }
            header { string("Content-Security-Policy", csp("base-uri 'self'")) }

            //  KEIN unsafe-inline, in keiner Richtung. Kaeme es je hinein,
            //  waere die ganze Policy ein Schild aus Papier - und es kommt
            //  leise hinein, naemlich als schnelle Loesung fuer ein Styling.
            header { string("Content-Security-Policy", org.hamcrest.Matchers.not(csp("unsafe-inline"))) }
            header { string("Content-Security-Policy", org.hamcrest.Matchers.not(csp("unsafe-eval"))) }

            header { string("X-Frame-Options", "DENY") }
            header { string("X-Content-Type-Options", "nosniff") }

            //  Ein privater Clip ist nur ueber seinen Link erreichbar - der
            //  Link IST das Geheimnis, und er steht in der Adresse. Er darf
            //  keinem Ausgangslink hinterherreisen.
            header { string("Referrer-Policy", "no-referrer") }

            header { string("Permissions-Policy", csp("camera=()")) }
            header { string("Cross-Origin-Opener-Policy", "same-origin") }
        }
        mvc.get("/assets/viewer.js").andExpect { status { isOk() } }
    }

    /**
     * Vom Actuator ist genau einer offen - der, den der Container fragt.
     * Alles andere ist zu, auch wenn jemand die Freigabe in der
     * `application.yaml` erweitert, um lokal etwas nachzusehen.
     */
    @Test
    fun `only the health endpoint is reachable from outside`() {
        mvc.get("/actuator/health").andExpect { status { isOk() } }

        //  4xx, nicht genau 403: ob die Kette "nicht angemeldet" (401) oder
        //  "nicht erlaubt" (403) sagt, haengt am Einstiegspunkt und ist hier
        //  gleichgueltig. Der Test haelt fest, dass nichts AUSGELIEFERT wird.
        mvc.get("/actuator/env").andExpect { status { is4xxClientError() } }
        mvc.get("/actuator/beans").andExpect { status { is4xxClientError() } }
    }

    /**
     * Die Seiten zeigen auf `/assets/v/<commit>/`, und dort liegt alles, was
     * die Skripte relativ nachladen - Module wie das Mannequin. Faellt der
     * Versionspfad um, laden die Seiten zwar weiter, aber ohne Cache; faellt
     * `models/` darunter weg, steht jede Buehne leer.
     */
    @Test
    fun `static files are linked and served under a versioned path`() {
        val page = mvc.get("/").andReturn().response.contentAsString
        assertTrue(Regex("""src="/assets/v/[^/"]+/app\.js"""").containsMatchIn(page), "app.js is not versioned")
        assertFalse(page.contains("src=\"/assets/app.js\""), "an unversioned script is left in the page")

        mvc.get("/assets/v/anything/pages/clip.js").andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith("text/javascript") }
        }
        //  Nur "da", nicht der Typ: `model/gltf-binary` setzt Tomcat aus
        //  `server.mime-mappings`, und MockMvc laeuft ohne Tomcat.
        mvc.get("/assets/v/anything/models/aw-mannequin.glb").andExpect { status { isOk() } }
        mvc.get("/assets/v/anything/nothing-here.js").andExpect { status { isNotFound() } }
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
            //  `/me.html` fehlt mit Absicht: ohne Konto schickt sie zur
            //  Anmeldeseite (SignInFlowTest).
            "/", "/index.html", "/collections.html", "/clip.html", "/licenses.html", "/admin.html",
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

        //  Der Katalog IST die Startseite (wie "Animations" bei Mixamo).
        assertTrue(anonymous.contains(">Animations</h1>"), "Katalog fehlt auf der Startseite")
        assertTrue(anonymous.contains("/collections.html"), "Navigation fehlt im HTML")

        //  Die alte Adresse des Katalogs steht in Discord und als Filter-Link
        //  in Umlauf - sie leitet weiter und behaelt ihren Filter.
        mvc.get("/browse.html?tag=walk&sort=popular").andExpect {
            status { is3xxRedirection() }
            redirectedUrl("/?tag=walk&sort=popular")
        }

        //  Solange die Indexierung aus ist, gibt es keine Sitemap - sie
        //  widerspraeche dem "Disallow: /" in robots.txt (SearchIndexingTest).
        mvc.get("/sitemap.xml").andExpect { status { isNotFound() } }
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

    /**
     * Humanoid ja, generisch noch nicht.
     *
     * DER TEST PRUEFT ZWEI DINGE AUF EINMAL, und das zweite ist das wichtigere:
     * dass die Absage NICHT `invalid-awclip` heisst. Die Datei ist in Ordnung -
     * derselbe Leser, der sie hier durchlaesst, laesst sie auch in der
     * Workbench durch, und die Testdateien unter `awclip/` bestehen darauf.
     * Abgelehnt wird sie vom Portal, nicht vom Format, und wer sie geschrieben
     * hat, soll nicht anfangen, in seiner Datei nach einem Fehler zu suchen.
     */
    @Test
    fun `a generic clip is a valid file the portal still does not take`() {
        val token = login("generic-${unique()}")

        upload(token, genericAwclip(0.41)).andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("unsupported-rig") }
        }

        //  Und der Leser? Der haette sie genommen.
        assertTrue(
            AwclipReader.readFile(genericAwclip(0.41).inputStream()) is AwclipReadResult.Ok,
            "Die Testdatei ist kaputt - dann prueft der Test oben etwas anderes als gemeint",
        )
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

    /**
     * Privat heisst "nur ich" (Schema 10). Bis dahin oeffnete der Link einen
     * privaten Clip fuer jeden, der ihn hatte. Jetzt sieht ihn nur sein
     * Besitzer - und der Admin, weil eine Loeschanfrage ihn erreichen muss.
     * Fuer alle anderen ist er auf JEDEM Weg dasselbe 404 wie ein Slug, den
     * es nie gab.
     */
    @Test
    fun `a private clip is seen by its owner and nobody else`() {
        val owner = login("owner-${unique()}")
        val stranger = login("stranger-${unique()}")
        val public = uploadOk(owner, awclip(0.95, "Shared jump"))
        val private = uploadOk(owner, awclip(0.96, "Shared jump", license = AwclipSchema.LICENSE_PRIVATE))

        val listed = mvc.get("/api/v1/packages?q=Shared jump").andExpect { status { isOk() } }.body()
        val slugs = listed["items"].map { it["slug"].asString() }
        assertTrue(public in slugs, "the public clip belongs in the catalog")
        assertFalse(private in slugs, "a private clip must not be listed")

        //  Der Besitzer sieht ihn, mit allem.
        mvc.get("/api/v1/packages/$private") { header("Authorization", "Bearer $owner") }.andExpect {
            status { isOk() }
            jsonPath("$.license") { value(AwclipSchema.LICENSE_PRIVATE) }
        }
        mvc.get("/api/v1/packages/$private/preview") { header("Authorization", "Bearer $owner") }
            .andExpect { status { isOk() } }

        //  Niemand sonst - weder ohne Konto noch mit einem fremden.
        for (who in listOf<String?>(null, stranger)) {
            fun MockHttpServletRequestDsl.as_() { if (who != null) header("Authorization", "Bearer $who") }
            mvc.get("/api/v1/packages/$private") { as_() }.andExpect { status { isNotFound() } }
            mvc.get("/api/v1/packages/$private/preview") { as_() }.andExpect { status { isNotFound() } }
            mvc.get("/api/v1/packages/$private/comments") { as_() }.andExpect { status { isNotFound() } }
            mvc.get("/clip-card/$private.png") { as_() }.andExpect { status { isNotFound() } }
        }
        for (action in listOf("download-link", "unlock"))
            mvc.post("/api/v1/packages/$private/$action") { header("Authorization", "Bearer $stranger") }
                .andExpect { status { isNotFound() } }
        mvc.post("/api/v1/packages/$private/like") {
            header("Authorization", "Bearer $stranger")
            contentType = MediaType.APPLICATION_JSON
            content = "{\"liked\":true}"
        }.andExpect { status { isNotFound() } }
        report(stranger, private).andExpect { status { isNotFound() } }

        //  Der Admin erreicht ihn - Loeschanfragen gelten auch fuer Privates.
        mvc.get("/api/v1/packages/$private") { header("Authorization", "Bearer ${login("admin")}") }
            .andExpect { status { isOk() } }
    }

    /** Neu geteilt wird nur noch unter CC0 oder privat - CC BY 4.0 ist Geschichte. */
    @Test
    fun `a new upload under the old cc by licence is refused`() {
        upload(login("ccby-${unique()}"), awclip(0.97, "Old licence", license = "CC-BY-4.0")).andExpect {
            status { isBadRequest() }
            //  Die Absage des Lesers kommt verpackt an; welche Regel griff,
            //  steht in der Nachricht.
            jsonPath("$.error.code") { value("invalid-awclip") }
            jsonPath("$.error.message") { value(org.hamcrest.Matchers.containsString("invalid-license")) }
        }
    }

    /**
     * Eine Adresse, die es nicht gibt, trifft zwei verschiedene Leute.
     *
     * Die Schnittstelle bekommt JSON, weil die Workbench nichts anderes lesen
     * kann. Ein Mensch im Browser bekommt eine Seite - er folgt vielleicht
     * einem alten Link aus Discord, und `{"error":{"code":"not-found"}}` ist
     * fuer ihn keine Auskunft, sondern der Eindruck, hier sei etwas kaputt.
     */
    @Test
    fun `an address that does not exist answers the machine and the person differently`() {
        mvc.get("/nothing-here.html").andExpect {
            status { isNotFound() }
            content { contentTypeCompatibleWith(MediaType.TEXT_HTML) }
        }.andReturn().response.contentAsString.let { page ->
            assertTrue("That page is not here." in page, "it says what happened")
            assertTrue("Browse animations" in page, "and offers a way on")
        }

        //  Unterhalb von /api/ bleibt es bei JSON - auch fuer einen Browser.
        //  Der Pfad muss einer sein, den die Sicherheitsschicht durchlaesst:
        //  sonst antwortet sie mit 401, bevor der Handler ueberhaupt drankommt,
        //  und der Test prueft nicht, was er zu pruefen meint.
        mvc.get("/api/v1/dev/nope").andExpect {
            status { isNotFound() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
        }.andReturn().response.contentAsString.let {
            assertTrue("not-found" in it, "the machine gets a code, not a page")
        }
    }

    /**
     * "Alles von dieser Person" - der Weg, ohne den jeder Clip eine Insel ist.
     *
     * Der zweite Teil ist der, der leicht schiefgeht: ein Name, den es nicht
     * gibt, muss NICHTS liefern. Ein leerer Filter, der stillschweigend zum
     * ganzen Katalog wird, ist schlimmer als eine leere Seite - er behauptet,
     * jemand habe alles hier gemacht.
     */
    @Test
    fun `the catalog can be narrowed to one person, and an unknown name finds nothing`() {
        val mine = "author-" + unique()
        val other = "other-" + unique()
        val me = login(mine)
        val them = login(other)

        uploadOk(me, awclip(0.985, "Author one"))
        uploadOk(me, awclip(0.986, "Author two"))
        uploadOk(them, awclip(0.987, "Someone else"))

        val ours = mvc.get("/api/v1/packages?author=$mine").andExpect { status { isOk() } }.body()
        assertEquals(2, ours["total"].asInt(), "both of mine, and only mine")
        assertTrue(ours["items"].all { it["author"].asString() == mine })

        val nobody = mvc.get("/api/v1/packages?author=nobody-$mine").andExpect { status { isOk() } }.body()
        assertEquals(0, nobody["total"].asInt(), "an unknown name finds nothing, not everything")
    }

    /**
     * Ein Link in Discord muss zeigen, wohin er fuehrt.
     *
     * Der zweite Teil ist der wichtigere: ein privater Clip ist "nicht gelistet
     * und nicht auffindbar". Er darf seine Bewegung nicht in eine Vorschaukarte
     * legen, die dann in jedem Kanal auftaucht, in den der Link geraet - und
     * seine Beschreibung nicht in ein `og:`-Feld, das jeder Bot mitliest.
     */
    @Test
    fun `a shared link previews itself, unless the clip is private`() {
        val owner = login("card-${unique()}")
        val public = uploadOk(owner, awclip(0.99, "Card walk"))
        val private = uploadOk(owner, awclip(0.995, "Card secret", license = AwclipSchema.LICENSE_PRIVATE))

        val page = mvc.get("/clip.html?p=$public").andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        assertTrue("""property="og:title" content="Card walk by""" in page, "the title belongs in the preview")
        assertTrue("""/clip-card/$public.png"""" in page, "and so does the image")

        //  Das Bild entsteht aus dem Vorschau-Block, ohne Browser und ohne
        //  Schrift - PNG-Dateien fangen mit diesen acht Bytes an.
        val png = mvc.get("/clip-card/$public.png").andExpect {
            status { isOk() }
            header { string("Content-Type", MediaType.IMAGE_PNG_VALUE) }
        }.andReturn().response.contentAsByteArray
        assertContentEquals(
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A), png.take(8).toByteArray(),
            "a PNG, not an error page",
        )

        val privatePage = mvc.get("/clip.html?p=$private").andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        assertFalse("Card secret" in privatePage, "a private clip keeps its title out of the preview")
        assertFalse("clip-card/$private" in privatePage, "and has no card at all")
        mvc.get("/clip-card/$private.png").andExpect { status { isNotFound() } }
    }
    /**
     * Der Ueberblick zaehlt, was im Katalog STEHT - nicht, was in der Datenbank
     * liegt. Ein privater Clip ist da, aber ungelistet; er darf weder den
     * Umfang aufblaehen noch sein Schlagwort in die Leiste bringen, sonst fuehrt
     * eine Pille auf eine leere Seite.
     *
     * Der zweite Teil prueft den Cache. Er haelt eine Minute - in einem Test
     * also ewig -, und ohne die Entwertung beim Zurueckziehen wuerde die
     * Startseite noch Clips zaehlen, die es nicht mehr gibt.
     */
    @Test
    fun `the overview counts what is listed, and forgets what was withdrawn`() {
        val owner = login("overview-${unique()}")
        val listed = uploadOk(owner, awclip(0.97, "Overview walk", tags = "\"overviewwalk\",\"shared\""))
        uploadOk(owner, awclip(0.98, "Overview secret",
            license = AwclipSchema.LICENSE_PRIVATE, tags = "\"overviewsecret\""))

        fun overview() = mvc.get("/api/v1/overview").andExpect { status { isOk() } }.body()
        fun tagsOf(node: JsonNode) = node["tags"].associate { it["tag"].asString() to it["count"].asInt() }

        val before = overview()
        assertEquals(1, tagsOf(before)["overviewwalk"], "the listed clip carries its tag into the bar")
        assertNull(tagsOf(before)["overviewsecret"], "a private clip must not put its tag in the bar")

        val clipsBefore = before["clips"].asInt()

        mvc.delete("/api/v1/packages/$listed") { header("Authorization", "Bearer $owner") }
            .andExpect { status { isOk() } }

        val after = overview()
        assertEquals(clipsBefore - 1, after["clips"].asInt(), "withdrawing lowers the count right away")
        assertNull(tagsOf(after)["overviewwalk"], "and takes the tag out of the bar with it")
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
        val slug = uploadOk(login("first-${unique()}"), bytes)

        upload(login("second-${unique()}"), awclip(0.31, "Renamed copy", license = "ARR")).andExpect {
            status { isConflict() }
            jsonPath("$.error.code") { value("duplicate") }
            jsonPath("$.error.slug") { value(slug) }
        }
    }

    /**
     * Die Duplikat-Pruefung sperrt nur, was man sehen kann. Ein fremder
     * privater Clip wirkt nach aussen nicht - sperrte er, verriete schon die
     * Absage, dass es ihn gibt.
     */
    @Test
    fun `someone else's private clip does not block the same motion`() {
        uploadOk(login("hidden-first-${unique()}"), awclip(0.5401, "Secret", license = AwclipSchema.LICENSE_PRIVATE))

        uploadOk(login("hidden-second-${unique()}"), awclip(0.5401, "Mine"))
    }

    /** Zurueckgezogen heisst weg - fuer den Besitzer wie fuer alle anderen. */
    @Test
    fun `a withdrawn clip frees its motion`() {
        val owner = login("gone-owner-${unique()}")

        val theirs = uploadOk(owner, awclip(0.5402))
        mvc.delete("/api/v1/packages/$theirs") { header("Authorization", "Bearer $owner") }.andExpect { status { isOk() } }
        uploadOk(login("gone-other-${unique()}"), awclip(0.5402, "Taken over"))

        val mine = uploadOk(owner, awclip(0.5403))
        mvc.delete("/api/v1/packages/$mine") { header("Authorization", "Bearer $owner") }.andExpect { status { isOk() } }
        uploadOk(owner, awclip(0.5403, "Back again"))
    }

    /**
     * Die eigene Kopie ist kein Duplikat, sondern ein Wegweiser: eigener
     * Code, und der Slug fuehrt die Workbench hin - auch zu einem privaten.
     */
    @Test
    fun `your own copy is pointed out, public or private`() {
        val owner = login("own-copy-${unique()}")

        val shared = uploadOk(owner, awclip(0.5404))
        upload(owner, awclip(0.5404, license = AwclipSchema.LICENSE_PRIVATE)).andExpect {
            status { isConflict() }
            jsonPath("$.error.code") { value("own-copy") }
            jsonPath("$.error.slug") { value(shared) }
        }

        val kept = uploadOk(owner, awclip(0.5405, "Kept Back", license = AwclipSchema.LICENSE_PRIVATE))
        upload(owner, awclip(0.5405)).andExpect {
            status { isConflict() }
            jsonPath("$.error.code") { value("own-copy") }
            jsonPath("$.error.slug") { value(kept) }
            jsonPath("$.error.message") { value("You already have this animation as a private clip, 'Kept Back'.") }
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
        //  Seit es Meldungen gegen KONTEN gibt, stehen in derselben Liste
        //  auch Faelle ohne Paket - die haben kein `packages[0]`.
        assertTrue(cases.any { it["packages"].size() > 0 && it["packages"][0]["slug"].asString() == slug })

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
            jsonPath("$.error.slug") { doesNotExist() }
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
        //  Der Name fuehrt zur Person: ohne Handle ist ein Kommentar eine Zeile von niemandem.
        assertTrue(list["comments"][0]["authorHandle"].asString().startsWith("c-reader-"),
            "a comment carries the address of its author")

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
    // QUITTUNGEN
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Die Quittung ist die Eintrittskarte: ohne sie gibt es keinen Link, und
     * ohne Konto gibt es keine Quittung.
     */
    @Test
    fun `a download link needs a receipt`() {
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
    }

    // ═════════════════════════════════════════════════════════════════════
    // BEARBEITEN
    // ═════════════════════════════════════════════════════════════════════

    private fun edit(token: String, slug: String, body: String): ResultActionsDsl =
        mvc.patch("/api/v1/packages/$slug") {
            contentType = MediaType.APPLICATION_JSON
            content = body
            header("Authorization", "Bearer $token")
        }

    /**
     * Der Besitzer aendert, was nicht Bewegung ist - und die Datei zieht mit:
     * wer danach herunterlaedt, bekommt den neuen Titel, aber dieselbe
     * Bewegung. Der Inhalts-Hash bleibt, also auch die Duplikat-Sperre.
     */
    @Test
    fun `the owner edits a clip and the stored file follows`() {
        val owner = login("f-edit-owner-${unique()}")
        val stranger = login("f-edit-other-${unique()}")
        val bytes = awclip(0.52)
        val slug = uploadOk(owner, bytes)

        edit(stranger, slug, """{"title":"Mine now","description":"","tags":[],"license":"CC0-1.0"}""")
            .andExpect { status { isForbidden() } }

        edit(owner, slug, """{"title":"  Sneaky Walk ","description":"Slow and low.\r\nLoops.","tags":["Walk","sneak","walk"],"license":"CC0-1.0"}""")
            .andExpect {
                status { isOk() }
                jsonPath("$.title") { value("Sneaky Walk") }
                jsonPath("$.description") { value("Slow and low.\nLoops.") }
                jsonPath("$.tags.length()") { value(2) }
                jsonPath("$.tags[0]") { value("walk") }
                jsonPath("$.tags[1]") { value("sneak") }
            }

        val link = mvc.post("/api/v1/packages/$slug/download-link") { header("Authorization", "Bearer $owner") }
            .andExpect { status { isOk() } }.body()
        val downloaded = mvc.get(link["url"].asString()).andExpect { status { isOk() } }.andReturn().response.contentAsByteArray
        val before = (AwclipReader.readFile(bytes.inputStream()) as AwclipReadResult.Ok).document
        val after = (AwclipReader.readFile(downloaded.inputStream()) as AwclipReadResult.Ok).document
        assertEquals("Sneaky Walk", after.manifest.title)
        assertEquals("Slow and low.\nLoops.", after.manifest.description)
        assertEquals(listOf("walk", "sneak"), after.manifest.tags)
        assertEquals(AwclipHash.compute(before), AwclipHash.compute(after), "the motion must not change")

        upload(owner, bytes).andExpect {
            status { isConflict() }
            jsonPath("$.error.code") { value("own-copy") }
            jsonPath("$.error.slug") { value(slug) }
        }

        //  Die Karte sagt ihrem Besitzer, dass sie ihm gehoert - daran haengt
        //  "Edit on the portal" in der Workbench. Allen anderen nicht.
        fun ownedInSearch(token: String?) = mvc.get("/api/v1/packages") {
            param("q", "Sneaky Walk")
            if (token != null) header("Authorization", "Bearer $token")
        }.andExpect { status { isOk() } }.body()["items"].first { it["slug"].asString() == slug }["isOwner"].asBoolean()
        assertTrue(ownedInSearch(owner), "the owner's card says so")
        assertFalse(ownedInSearch(stranger), "a stranger's card does not")
        assertFalse(ownedInSearch(null), "an anonymous card does not")

        edit(owner, slug, """{"title":"Sneaky Walk","description":"","tags":["Not A Tag"],"license":"CC0-1.0"}""")
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.error.code") { value("invalid-tags") }
            }
        edit(owner, slug, """{"title":"   ","description":"","tags":[],"license":"CC0-1.0"}""")
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.error.code") { value("invalid-title") }
            }
    }

    /**
     * Privat -> oeffentlich ist ein neues Teilen: dieselbe Erklaerung wie
     * beim Hochladen, und sie steht danach im Protokoll. Zurueck auf privat
     * braucht nichts - CC0 laesst sich ohnehin nicht zuruecknehmen.
     */
    @Test
    fun `making a private clip public needs the declaration`() {
        val owner = login("f-public-${unique()}")
        val slug = uploadOk(owner, awclip(0.53, license = AwclipSchema.LICENSE_PRIVATE))
        val publicDeclarations = { declarations.findAll().count { it.license == AwclipSchema.LICENSE_PUBLIC } }
        val before = publicDeclarations()

        edit(owner, slug, """{"title":"Test Clip","description":"","tags":["walk"],"license":"CC0-1.0"}""")
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.error.code") { value("declaration-required") }
            }
        mvc.get("/api/v1/packages/$slug").andExpect { status { isNotFound() } }

        edit(owner, slug, """{"title":"Test Clip","description":"","tags":["walk"],"license":"CC0-1.0",
            "declarationAccepted":true,"declarationText":"${Declaration.TEXT}","declarationVersion":${Declaration.VERSION}}""")
            .andExpect {
                status { isOk() }
                jsonPath("$.license") { value(AwclipSchema.LICENSE_PUBLIC) }
            }
        assertEquals(before + 1, publicDeclarations(), "the new share is on record")
        mvc.get("/api/v1/packages/$slug").andExpect { status { isOk() } }

        edit(owner, slug, """{"title":"Test Clip","description":"","tags":["walk"],"license":"ARR"}""")
            .andExpect { status { isOk() } }
        mvc.get("/api/v1/packages/$slug").andExpect { status { isNotFound() } }
    }

    /**
     * Ein privater Clip sperrt fremde Uploads nicht - also muss der Wechsel
     * auf oeffentlich dieselbe Frage stellen wie ein Upload. Sonst staenden
     * danach zwei oeffentliche Kopien da.
     */
    @Test
    fun `going public is refused when the motion is already public elsewhere`() {
        val owner = login("f-late-${unique()}")
        val kept = uploadOk(owner, awclip(0.5406, license = AwclipSchema.LICENSE_PRIVATE))
        val shared = uploadOk(login("f-early-${unique()}"), awclip(0.5406, "Public first"))

        edit(owner, kept, """{"title":"Test Clip","description":"","tags":["walk"],"license":"CC0-1.0",
            "declarationAccepted":true,"declarationText":"${Declaration.TEXT}","declarationVersion":${Declaration.VERSION}}""")
            .andExpect {
                status { isConflict() }
                jsonPath("$.error.code") { value("duplicate") }
                jsonPath("$.error.slug") { value(shared) }
            }
        mvc.get("/api/v1/packages/$kept").andExpect { status { isNotFound() } }

        //  Alles, was nicht sichtbar macht, geht weiter.
        edit(owner, kept, """{"title":"Renamed","description":"","tags":["walk"],"license":"ARR"}""")
            .andExpect { status { isOk() } }
    }
}
