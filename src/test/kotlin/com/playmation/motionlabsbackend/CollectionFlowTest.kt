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
import org.springframework.test.web.servlet.delete
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
 * Sammlungen: kuratieren, teilen, und was passiert, wenn ein Clip darin
 * verschwindet.
 *
 * Die zwei Regeln, die den Rest einfach halten und deshalb hier festgehalten
 * werden: hinein darf nur, was im Katalog steht, und was daraus verschwindet,
 * faellt still heraus statt eine Luecke zu hinterlassen.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CollectionFlowTest {

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

    private fun awclip(title: String, license: String = "CC-BY-4.0"): ByteArray {
        //  Der Inhalts-Hash umfasst Kurvennamen und Schluessel. Zwei Testklassen
        //  teilen sich EINE Datenbank, also muss jede Bewegung hier einmalig sein -
        //  ein fest gewaehlter Seed kollidiert sonst mit dem einer anderen Klasse,
        //  und der Upload scheitert an der Duplikatsperre.
        val seed = java.util.concurrent.ThreadLocalRandom.current().nextDouble()
        val doc = """
            {"format":"awclip","version":1,
             "manifest":{"title":"$title","tags":["attack"],"license":"$license","rig":"humanoid","frameRate":30,"duration":1},
             "origin":"own",
             "curves":[{"attribute":"Head Nod Down-Up","keys":[[0,$seed,0,0],[1,0.25,0,0]]}],
             "preview":{"frameRate":15,"bones":["Hips","Spine"],"parents":[-1,0],"rest":[[0,1,0],[0,0.1,0]],
                        "hips":[[0,1,0]],"rotations":[[0,0,0,1,0,0,0,1]]}}
        """.trimIndent()
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(doc.toByteArray()) }
        return out.toByteArray()
    }

    private fun uploadOk(token: String, title: String, license: String = "CC-BY-4.0"): String =
        mvc.multipart("/api/v1/packages") {
            file("file", awclip(title, license))
            param("declarationText", Declaration.TEXT)
            param("declarationVersion", Declaration.VERSION.toString())
            param("declarationAccepted", "true")
            header("Authorization", "Bearer $token")
        }.andExpect { status { isCreated() } }.body()["slug"].asString()

    private fun createCollection(token: String, title: String, visibility: String = "PUBLIC"): String =
        mvc.post("/api/v1/collections") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"title":"$title","description":"Picked by hand.","visibility":"$visibility"}"""
            header("Authorization", "Bearer $token")
        }.andExpect { status { isCreated() } }.body()["slug"].asString()

    private fun addItem(token: String, collection: String, clip: String) =
        mvc.post("/api/v1/collections/$collection/items") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"slug":"$clip"}"""
            header("Authorization", "Bearer $token")
        }

    private fun detail(collection: String, token: String? = null) =
        mvc.get("/api/v1/collections/$collection") { token?.let { header("Authorization", "Bearer $it") } }

    private fun clip(slug: String, token: String? = null) =
        mvc.get("/api/v1/packages/$slug") { token?.let { header("Authorization", "Bearer $it") } }

    // ── Tests ───────────────────────────────────────────────────────────

    /**
     * Der Fall, fuer den es Sammlungen gibt: eine Auswahl aus FREMDEN Clips,
     * die jemand anders oeffnen kann.
     */
    @Test
    fun `a collection holds other people's clips and is readable without an account`() {
        val maker = login("maker" + unique())
        val curator = login("curator" + unique())

        val theirs = uploadOk(maker, "Sword Slash")
        val alsoTheirs = uploadOk(maker, "Shield Bash")
        val collection = createCollection(curator, "Attack animations")

        addItem(curator, collection, theirs).andExpect { status { isOk() } }
        addItem(curator, collection, alsoTheirs).andExpect { status { isOk() } }

        //  Zweimal derselbe Clip bleibt ein Eintrag.
        addItem(curator, collection, theirs).andExpect { status { isOk() } }

        val view = detail(collection).andExpect { status { isOk() } }.body()
        assertEquals(2, view["items"].size())
        assertEquals("Sword Slash", view["items"][0]["title"].asString())
        assertTrue(view["ownerHandle"].asString().startsWith("curator"))
        assertFalse(view["isOwner"].asBoolean(), "Ohne Anmeldung ist niemand Besitzer")

        //  Der Stern am Clip zaehlt PERSONEN. Der Kurator hat ihn, sein
        //  Ersteller nicht.
        assertEquals(1, clip(theirs).body()["saves"].asInt())
        assertTrue(clip(theirs, curator).body()["savedByMe"].asBoolean())
        assertFalse(clip(theirs, maker).body()["savedByMe"].asBoolean())

        //  Und derselbe Clip in einer ZWEITEN eigenen Sammlung macht daraus
        //  keine zweite Person.
        val second = createCollection(curator, "Favourites")
        addItem(curator, second, theirs).andExpect { status { isOk() } }
        assertEquals(1, clip(theirs).body()["saves"].asInt())
    }

    /** Fremde Sammlungen baut niemand um. */
    @Test
    fun `only the owner changes a collection`() {
        val owner = login("owner" + unique())
        val stranger = login("stranger" + unique())
        val clip = uploadOk(owner, "Mine")
        val collection = createCollection(owner, "Mine only")

        addItem(stranger, collection, clip).andExpect { status { isForbidden() } }
        mvc.delete("/api/v1/collections/$collection") {
            header("Authorization", "Bearer $stranger")
        }.andExpect { status { isForbidden() } }
    }

    /**
     * Ein privater Clip ist nur ueber seinen Link zu haben. Ueber eine
     * oeffentliche Sammlung waere er es nicht mehr - also kommt er gar nicht
     * erst hinein, auch nicht vom eigenen Besitzer.
     */
    @Test
    fun `a private clip cannot go into a collection`() {
        val owner = login("private" + unique())
        val secret = uploadOk(owner, "Not for the catalogue", license = "ARR")
        val collection = createCollection(owner, "Everything of mine")

        addItem(owner, collection, secret).andExpect { status { isBadRequest() } }
        assertEquals(0, detail(collection, owner).body()["items"].size())
    }

    /**
     * Ein zurueckgezogener Clip faellt still aus der Sammlung - die Zeile
     * bleibt, damit er nach einer Rueckkehr wieder an seinem Platz steht.
     */
    @Test
    fun `a withdrawn clip disappears from the collection`() {
        val owner = login("withdrawer" + unique())
        val curator = login("keeper" + unique())
        val first = uploadOk(owner, "Stays")
        val second = uploadOk(owner, "Goes away")
        val collection = createCollection(curator, "Two clips")

        addItem(curator, collection, first).andExpect { status { isOk() } }
        addItem(curator, collection, second).andExpect { status { isOk() } }
        assertEquals(2, detail(collection).body()["items"].size())

        mvc.delete("/api/v1/packages/$second") {
            header("Authorization", "Bearer $owner")
        }.andExpect { status { isOk() } }

        val after = detail(collection).andExpect { status { isOk() } }.body()
        assertEquals(1, after["items"].size())
        assertEquals("Stays", after["items"][0]["title"].asString())

        //  Auch die Zahl auf der Karte zaehlt nur Sichtbares.
        val listed = mvc.get("/api/v1/collections?owner=" + curatorHandleOf(collection))
            .andExpect { status { isOk() } }.body()
        assertEquals(1, listed.first { it["slug"].asString() == collection }["items"].asInt())
    }

    /** Nicht gelistet heisst: nicht in der Liste, aber hinter seinem Link da. */
    @Test
    fun `an unlisted collection is reachable by link and listed for nobody else`() {
        val owner = login("quiet" + unique())
        val stranger = login("nosy" + unique())
        val clip = uploadOk(owner, "Quiet clip")
        val collection = createCollection(owner, "Just for me", visibility = "UNLISTED")
        addItem(owner, collection, clip).andExpect { status { isOk() } }

        detail(collection).andExpect { status { isOk() } }

        val handle = curatorHandleOf(collection)
        val mine = mvc.get("/api/v1/collections?owner=$handle") {
            header("Authorization", "Bearer $owner")
        }.andExpect { status { isOk() } }.body()
        assertTrue(mine.any { it["slug"].asString() == collection }, "Auf dem eigenen Profil steht sie")

        val theirs = mvc.get("/api/v1/collections?owner=$handle") {
            header("Authorization", "Bearer $stranger")
        }.andExpect { status { isOk() } }.body()
        assertFalse(theirs.any { it["slug"].asString() == collection }, "Auf einem fremden Profil nicht")

        val browse = mvc.get("/api/v1/collections").andExpect { status { isOk() } }.body()
        assertFalse(browse.any { it["slug"].asString() == collection }, "Und im Katalog der Sammlungen nicht")
    }

    /**
     * Die Liste hinter dem Stern: meine Sammlungen, jede mit der Angabe, ob
     * dieser Clip schon drin liegt. Ohne sie muesste man raten.
     */
    @Test
    fun `my collections say whether a clip is already in them`() {
        val me = login("picker" + unique())
        val clip = uploadOk(me, "Somewhere")
        val withIt = createCollection(me, "Has it")
        val without = createCollection(me, "Has it not")
        addItem(me, withIt, clip).andExpect { status { isOk() } }

        val choices = mvc.get("/api/v1/me/collections?contains=$clip") {
            header("Authorization", "Bearer $me")
        }.andExpect { status { isOk() } }.body()

        assertTrue(choices.first { it["slug"].asString() == withIt }["contains"].asBoolean())
        assertFalse(choices.first { it["slug"].asString() == without }["contains"].asBoolean())

        //  Herausnehmen nimmt den Stern zurueck.
        mvc.delete("/api/v1/collections/$withIt/items/$clip") {
            header("Authorization", "Bearer $me")
        }.andExpect { status { isOk() } }
        assertEquals(0, clip(clip).body()["saves"].asInt())
        assertFalse(clip(clip, me).body()["savedByMe"].asBoolean())
    }

    /**
     * Die zweite Reihe in der Workbench: die Sammlungen der Leute, denen man
     * folgt. Oeffentliche ja, nicht gelistete nein, fremde gar nicht - und
     * die Zahl der Gefolgten sagt, warum eine leere Liste leer ist.
     */
    @Test
    fun `following shows the public collections of the people you follow`() {
        val friend = login("friend" + unique())
        val stranger = login("stranger" + unique())
        val me = login("follower" + unique())

        val clip = uploadOk(friend, "Friendly wave")
        val shown = createCollection(friend, "Waves I like")
        val hidden = createCollection(friend, "Only by link", visibility = "UNLISTED")
        val empty = createCollection(friend, "Nothing yet")
        addItem(friend, shown, clip).andExpect { status { isOk() } }
        addItem(friend, hidden, clip).andExpect { status { isOk() } }

        val elsewhere = createCollection(stranger, "Not followed")
        addItem(stranger, elsewhere, uploadOk(stranger, "Stranger clip")).andExpect { status { isOk() } }

        fun following() = mvc.get("/api/v1/me/collections/following") {
            header("Authorization", "Bearer $me")
        }.andExpect { status { isOk() } }.body()

        val before = following()
        assertEquals(0, before["following"].asInt(), "Noch folgt niemand")
        assertEquals(0, before["collections"].size())

        mvc.post("/api/v1/users/${curatorHandleOf(shown)}/follow") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"following":true}"""
            header("Authorization", "Bearer $me")
        }.andExpect { status { isOk() } }

        val after = following()
        val slugs = after["collections"].map { it["slug"].asString() }
        assertEquals(1, after["following"].asInt())
        assertTrue(shown in slugs, "Die oeffentliche Sammlung steht da")
        assertFalse(hidden in slugs, "Folgen ist kein Link - nicht gelistete bleiben draussen")
        assertFalse(empty in slugs, "Leere Sammlungen auch, wie im Katalog")
        assertFalse(elsewhere in slugs, "Und wem man nicht folgt, der fehlt")

        mvc.get("/api/v1/me/collections/following").andExpect { status { isUnauthorized() } }
    }

    /** Ohne Konto gibt es keine Sammlung - Bauen ist ein Handgriff, kein Ansehen. */
    @Test
    fun `building a collection needs an account`() {
        mvc.post("/api/v1/collections") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"title":"Anonymous","description":"","visibility":"PUBLIC"}"""
            with(csrf())
        }.andExpect { status { isUnauthorized() } }
    }

    /** Der Titel ist Nutzertext - zu kurz, zu lang und Steuerzeichen fallen durch. */
    @Test
    fun `a collection needs a name that is a name`() {
        val token = login("namer" + unique())

        for (title in listOf("ab", "x".repeat(61))) {
            mvc.post("/api/v1/collections") {
                contentType = MediaType.APPLICATION_JSON
                content = json.writeValueAsString(mapOf("title" to title, "description" to "", "visibility" to "PUBLIC"))
                header("Authorization", "Bearer $token")
            }.andExpect { status { isBadRequest() } }
        }
    }

    private fun curatorHandleOf(collection: String): String =
        detail(collection).body()["ownerHandle"].asString()
}
