package com.playmation.motionlabsbackend

import com.playmation.motionlabsbackend.catalog.AnimationPackage
import com.playmation.motionlabsbackend.catalog.AnimationPackageRepository
import com.playmation.motionlabsbackend.catalog.Declaration
import com.playmation.motionlabsbackend.catalog.TagText
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
import java.util.UUID
import java.util.zip.GZIPOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Das Schlagwortfeld: Nachschlagen beim Tippen und Vorschlaege zu einem Clip.
 * Die Datenbank teilen sich alle Tests - deshalb tragen die Schlagworte hier
 * eine Vorsilbe, die es nur in diesem Lauf gibt.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TagFlowTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var packages: AnimationPackageRepository

    private val json = JsonMapper.builder().build()

    private fun ResultActionsDsl.body(): JsonNode = json.readTree(andReturn().response.contentAsString)

    private fun login(name: String): String =
        mvc.post("/api/v1/dev/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"$name"}"""
            with(csrf())
        }.andExpect { status { isOk() } }.body()["token"].asString()

    private fun unique() = UUID.randomUUID().toString().replace("-", "").take(8)

    private fun awclip(
        seed: Double, tags: List<String>, license: String = AwclipSchema.LICENSE_PUBLIC,
        loops: Boolean = false, travel: Double? = null,
    ): ByteArray {
        val settings = if (loops) """"settings":{"loopTime":true},""" else ""
        val root = if (travel == null) "" else """,
             {"attribute":"RootT.x","keys":[[0,0,0,0],[1,0,0,0]]},
             {"attribute":"RootT.z","keys":[[0,0,0,0],[1,$travel,0,0]]}"""
        val doc = """
            {"format":"awclip","version":1,
             "manifest":{"title":"Tag test","tags":[${tags.joinToString(",") { "\"$it\"" }}],"license":"$license","rig":"humanoid","frameRate":30,"duration":1},
             $settings
             "origin":"own",
             "curves":[{"attribute":"Head Nod Down-Up","keys":[[0,$seed,0,0],[1,0.25,0,0]]}$root],
             "preview":{"frameRate":15,"bones":["Hips","Spine"],"parents":[-1,0],"rest":[[0,1,0],[0,0.1,0]],
                        "hips":[[0,1,0]],"rotations":[[0,0,0,1,0,0,0,1]]}}
        """.trimIndent()
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(doc.toByteArray()) }
        return out.toByteArray()
    }

    private fun uploadOk(token: String, bytes: ByteArray): String =
        mvc.multipart("/api/v1/packages") {
            file("file", bytes)
            param("declarationText", Declaration.TEXT)
            param("declarationVersion", Declaration.VERSION.toString())
            param("declarationAccepted", "true")
            header("Authorization", "Bearer $token")
        }.andExpect { status { isCreated() } }.body()["slug"].asString()

    private fun tags(q: String, token: String? = null): List<JsonNode> =
        mvc.get("/api/v1/tags") {
            param("q", q)
            param("limit", "50")
            if (token != null) header("Authorization", "Bearer $token")
        }.andExpect { status { isOk() } }.body()["tags"].toList()

    private fun suggest(token: String? = null, build: org.springframework.test.web.servlet.MockHttpServletRequestDsl.() -> Unit): List<JsonNode> =
        mvc.get("/api/v1/tags/suggest") {
            build()
            if (token != null) header("Authorization", "Bearer $token")
        }.andExpect { status { isOk() } }.body()["suggestions"].toList()

    private fun List<JsonNode>.names() = map { it["tag"].asString() }
    private fun List<JsonNode>.find(tag: String) = firstOrNull { it["tag"].asString() == tag }

    @Test
    fun `typed text becomes a tag the way the fields write it`() {
        //  Dieselbe Tabelle laeuft gegen AWTags.normalize (tag-input.js) und
        //  AWCommunityTagField.Normalize (Workbench) - am 2026-09-25 alle drei gleich.
        val table = listOf(
            "  Walk Cycle! " to "walk-cycle", "Überschlag" to "uberschlag", "Straße" to "strasse",
            "#hit__reaction" to "hit-reaction", "!!!" to null, "--Run--" to "run", "a.b.c" to "a-b-c",
            "ÉLAN" to "elan", "x".repeat(40) to "x".repeat(32),
            "abcdefghijklmnopqrstuvwxyz12345-6" to "abcdefghijklmnopqrstuvwxyz12345", "日本" to null,
            "Tänzer 2" to "tanzer-2", "##Loop" to "loop", "in place" to "in-place", "ñandú" to "nandu",
            "Ǆemal" to "emal", "a- -b" to "a-b",
        )
        for ((input, expected) in table) assertEquals(expected, TagText.normalize(input), "normalize('$input')")
        assertEquals(listOf("armature", "sneak", "walk", "loop"), TagText.words("Armature|SneakWalk_Loop01"))
        assertEquals(TagText.key("walk"), TagText.key("walking"))
        assertEquals(TagText.key("run"), TagText.key("running"))
        assertEquals(TagText.key("dance"), TagText.key("dancing"))
    }

    @Test
    fun `typing finds the tags people already use, most used first`() {
        val u = "q" + unique()
        val owner = login("tags-owner-$u")
        uploadOk(owner, awclip(0.7101, listOf("$u-walk", "$u-sneak")))
        uploadOk(owner, awclip(0.7102, listOf("$u-walk")))
        uploadOk(owner, awclip(0.7103, listOf("$u-wave")))
        uploadOk(owner, awclip(0.7104, listOf("$u-secret"), license = AwclipSchema.LICENSE_PRIVATE))

        val anonymous = tags(u)
        assertEquals("$u-walk", anonymous.first()["tag"].asString(), "the most used comes first")
        assertEquals(2, anonymous.find("$u-walk")!!["count"].asInt())
        assertTrue("$u-sneak" in anonymous.names())
        assertFalse("$u-secret" in anonymous.names(), "a private clip's tags stay with its owner")

        val own = tags(u, owner)
        assertTrue(own.find("$u-secret")!!["mine"].asBoolean(), "the owner sees their own private tag")
        assertEquals(0, own.find("$u-secret")!!["count"].asInt(), "and it counts nothing in public")

        //  Ein Teilwort findet das ganze Schlagwort.
        assertTrue("$u-sneak" in tags("sneak").names())

        //  Die Beugung findet das Grundwort, auch wenn niemand `walking` schrieb.
        assertEquals("walk", tags("walking").first()["tag"].asString())
    }

    @Test
    fun `a clip gets suggestions from its title, what it does and what goes with its tags`() {
        val u = "s" + unique()
        val owner = login("tags-suggest-$u")
        uploadOk(owner, awclip(0.7201, listOf("$u-sneak", "$u-crouch")))
        uploadOk(owner, awclip(0.7202, listOf("$u-sneak", "$u-crouch")))
        uploadOk(owner, awclip(0.7203, listOf("$u-sneak")))

        val fromTitle = suggest {
            param("title", "Zombie_SneakWalk01")
            param("loops", "true")
            param("motion", "in-place")
            param("limit", "20")
        }
        assertEquals("In the title", fromTitle.find("walk")!!["reason"].asString())
        assertEquals("In the title", fromTitle.find("sneak")!!["reason"].asString())
        assertEquals("The clip loops", fromTitle.find("loop")!!["reason"].asString())
        assertEquals("It stays in place", fromTitle.find("in-place")!!["reason"].asString())
        assertTrue("zombie" in fromTitle.names(), "a new word from the title is offered too")
        assertFalse(fromTitle.names().any { it == "01" || it.endsWith("01") }, "numbers from the file name are not tags")

        val related = suggest {
            param("tags", "$u-sneak")
            param("limit", "20")
        }
        assertEquals("Often used with $u-sneak", related.find("$u-crouch")!!["reason"].asString())
        assertFalse("$u-sneak" in related.names(), "what is already chosen is not suggested again")

        val alreadyChosen = suggest {
            param("title", "Walk")
            param("tags", "walk")
        }
        assertFalse("walk" in alreadyChosen.names())
    }

    @Test
    fun `the portal reads loop and travel off a stored clip, for its owner only`() {
        val u = "f" + unique()
        val owner = login("tags-facts-$u")
        val stranger = login("tags-stranger-$u")
        val slug = uploadOk(owner, awclip(0.7301, listOf("$u-x"), loops = true, travel = 2.0))

        val mine = suggest(owner) { param("slug", slug) }
        assertEquals("The clip loops", mine.find("loop")!!["reason"].asString())
        assertEquals("It moves through the scene", mine.find("root-motion")!!["reason"].asString())

        val theirs = suggest(stranger) { param("slug", slug) }
        assertNull(theirs.find("loop"), "a stranger's request does not open the file")
        assertNull(theirs.find("root-motion"))

        val inPlace = uploadOk(owner, awclip(0.7302, listOf("$u-y"), travel = 0.05))
        val still = suggest(owner) { param("slug", inPlace) }
        assertEquals("It stays in place", still.find("in-place")!!["reason"].asString())
        assertNull(still.find("loop"), "no loop flag, no loop tag")
    }

    /**
     * Fuenf beim Teilen, zehn im Format (2026-09-26). Die Datei darf mehr
     * tragen, damit Clips von vorher lesbar bleiben - und ein solcher Clip
     * behaelt seine Schlagworte beim Bearbeiten, bekommt aber keine dazu.
     */
    @Test
    fun `a shared clip carries at most five tags, and an older one keeps its own`() {
        val owner = login("fivetags" + unique())
        val six = listOf("one", "two", "three", "four", "five", "six")

        val refused = mvc.multipart("/api/v1/packages") {
            file("file", awclip(0.93311, six))
            param("declarationText", Declaration.TEXT)
            param("declarationVersion", Declaration.VERSION.toString())
            param("declarationAccepted", "true")
            header("Authorization", "Bearer $owner")
        }.andExpect { status { isBadRequest() } }.body()
        assertEquals("invalid-tags", refused["error"]["code"].asString())

        val slug = uploadOk(owner, awclip(0.93312, six.take(5)))

        fun edit(tags: List<String>) = mvc.patch("/api/v1/packages/$slug") {
            contentType = MediaType.APPLICATION_JSON
            content = json.writeValueAsString(mapOf(
                "title" to "Tag test", "description" to "", "tags" to tags, "license" to AwclipSchema.LICENSE_PUBLIC))
            header("Authorization", "Bearer $owner")
        }

        edit(six).andExpect { status { isBadRequest() } }

        //  Ein Clip aus der Zeit vor der Grenze - mit sieben, von Hand gesetzt.
        val seven = six + "seven"
        packages.findBySlug(slug)!!.let {
            it.tags = AnimationPackage.joinTags(seven)
            packages.save(it)
        }

        edit(seven.map { if (it == "one") "uno" else it }).andExpect { status { isOk() } }
        edit(seven + "eight").andExpect { status { isBadRequest() } }
    }
}
