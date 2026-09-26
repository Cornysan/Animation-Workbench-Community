package com.playmation.motionlabsbackend

import com.playmation.motionlabsbackend.catalog.Declaration
import com.playmation.motionlabsbackend.catalog.StarterClips
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
 * Packs: eigene Clips, die zusammen veroeffentlicht werden - und auf der
 * Katalogwand als EINE Karte stehen, waehrend die Suche sie weiter einzeln
 * findet.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PackFlowTest {

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

    private fun awclip(title: String, license: String, tags: String, ownMotion: Boolean = true): ByteArray {
        //  Jede Bewegung einmalig - alle Testklassen teilen EINE Datenbank, und
        //  die Duplikatsperre faengt sonst die zweite.
        val seed = java.util.concurrent.ThreadLocalRandom.current().nextDouble()
        val doc = """
            {"format":"awclip","version":1,
             "manifest":{"title":"$title","tags":[$tags],"license":"$license","rig":"humanoid","frameRate":30,"duration":1},
             "origin":"${if (ownMotion) "own" else "unknown"}",
             "curves":[{"attribute":"Head Nod Down-Up","keys":[[0,$seed,0,0],[1,0.25,0,0]]}],
             "preview":{"frameRate":15,"bones":["Hips","Spine"],"parents":[-1,0],"rest":[[0,1,0],[0,0.1,0]],
                        "hips":[[0,1,0]],"rotations":[[0,0,0,1,0,0,0,1]]}}
        """.trimIndent()
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(doc.toByteArray()) }
        return out.toByteArray()
    }

    private fun upload(token: String, title: String, license: String = "CC0-1.0",
                       tags: String = "\"bow\"", notify: Boolean = true): String =
        mvc.multipart("/api/v1/packages") {
            file("file", awclip(title, license, tags))
            param("declarationText", Declaration.TEXT)
            param("declarationVersion", Declaration.VERSION.toString())
            param("declarationAccepted", "true")
            param("notifyFollowers", notify.toString())
            header("Authorization", "Bearer $token")
        }.andExpect { status { isCreated() } }.body()["slug"].asString()

    private fun createPack(token: String, title: String, clips: List<String>, starter: Boolean = false) =
        mvc.post("/api/v1/packs") {
            contentType = MediaType.APPLICATION_JSON
            content = json.writeValueAsString(mapOf(
                "title" to title, "description" to "Made in one go.", "clips" to clips, "starter" to starter))
            header("Authorization", "Bearer $token")
        }

    private fun wall(query: String, token: String? = null) =
        mvc.get("/api/v1/catalog?$query") { token?.let { header("Authorization", "Bearer $it") } }
            .andExpect { status { isOk() } }.body()

    private fun pack(slug: String, token: String? = null) =
        mvc.get("/api/v1/packs/$slug") { token?.let { header("Authorization", "Bearer $it") } }

    private fun clip(slug: String) = mvc.get("/api/v1/packages/$slug").andExpect { status { isOk() } }.body()

    /** Kein Pack - ob das Feld `null` ist oder fehlt, ist Sache des Servers. */
    private fun JsonNode.inNoPack() = path("pack").let { it.isNull || it.isMissingNode }

    // ── Tests ───────────────────────────────────────────────────────────

    /**
     * Der Fall, fuer den es Packs gibt: zwei Clips eines Satzes stehen auf der
     * Wand als eine Karte, der dritte einzeln daneben - und wer sucht, findet
     * alle drei.
     */
    @Test
    fun `a pack folds its clips into one card on the wall and search still finds them one by one`() {
        val handle = "archer" + unique()
        val maker = login(handle)
        val word = "quiver" + unique()

        val draw = upload(maker, "Longbow Draw $word")
        val release = upload(maker, "Longbow Release $word")
        val single = upload(maker, "Wave $word", tags = "\"wave\"")

        val created = createPack(maker, "Longbow Pack", listOf(draw, release))
            .andExpect { status { isCreated() } }.body()
        val packSlug = created["slug"].asString()
        assertEquals(2, created["clips"].asInt())
        assertTrue(created["isOwner"].asBoolean())
        assertEquals(listOf(draw, release), created["items"].map { it["slug"].asString() }, "in the given order")

        //  DIE WAND: der Pack als eine Karte, der dritte Clip einzeln.
        val page = wall("owner=$handle")
        assertEquals(2, page["total"].asInt())
        assertEquals(1, page["packs"].asInt())
        assertEquals(1, page["clips"].asInt())
        val kinds = page["items"].map { it["kind"].asString() }
        assertEquals(listOf("pack", "clip"), kinds, "the pack is newer than the single clip")
        val card = page["items"][0]["pack"]
        assertEquals(packSlug, card["slug"].asString())
        assertEquals(2, card["clips"].asInt())
        assertEquals(draw, card["cover"]["slug"].asString(), "the first clip is the cover")
        assertEquals(listOf("bow"), card["tags"].map { it.asString() })
        assertEquals(single, page["items"][1]["clip"]["slug"].asString())

        //  Seitenweise ergibt dieselbe Reihenfolge.
        assertEquals("pack", wall("owner=$handle&size=1&page=0")["items"][0]["kind"].asString())
        assertEquals(single, wall("owner=$handle&size=1&page=1")["items"][0]["clip"]["slug"].asString())

        //  DIE SUCHE findet die Clips einzeln - mit dem Pack, in dem sie liegen.
        val found = wall("q=$word")
        val clips = found["items"].filter { it["kind"].asString() == "clip" }.map { it["clip"] }
        assertEquals(setOf(draw, release, single), clips.map { it["slug"].asString() }.toSet())
        assertEquals(packSlug, clips.first { it["slug"].asString() == draw }["pack"]["slug"].asString())
        assertTrue(clips.first { it["slug"].asString() == single }.inNoPack(), "the single clip is in no pack")

        //  Das Schlagwort eines Pack-Clips findet den Pack mit.
        val tagged = wall("tag=bow&owner=$handle")
        assertTrue(tagged["items"].any { it["kind"].asString() == "pack" && it["pack"]["slug"].asString() == packSlug })

        //  Die alte Liste fuer die Workbench bis 2.5.0 bleibt flach.
        val flat = mvc.get("/api/v1/users/$handle/packages").andExpect { status { isOk() } }.body()
        assertEquals(3, flat["total"].asInt())

        //  Und die Clip-Seite nennt ihren Pack.
        assertEquals("Longbow Pack", clip(release)["pack"]["title"].asString())
        assertTrue(clip(single).inNoPack())

        //  Ohne Konto lesbar.
        pack(packSlug).andExpect { status { isOk() } }
    }

    @Test
    fun `only the owner's own public clips go in, and each clip into one pack`() {
        val maker = login("maker" + unique())
        val other = login("other" + unique())

        val a = upload(maker, "Own A")
        val b = upload(maker, "Own B")
        val c = upload(maker, "Own C")
        val hidden = upload(maker, "Own private", license = "ARR")
        val theirs = upload(other, "Theirs")

        createPack(maker, "Too small", listOf(a)).andExpect { status { isBadRequest() } }
        createPack(maker, "With a stranger", listOf(a, theirs)).andExpect { status { isForbidden() } }
        createPack(maker, "With a secret", listOf(a, hidden)).andExpect { status { isBadRequest() } }

        //  Ohne Konto gar nicht.
        mvc.post("/api/v1/packs") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"title":"Nobody","clips":["$a","$b"]}"""
            with(csrf())
        }.andExpect { status { isUnauthorized() } }

        //  Nichts davon hat einen halben Pack hinterlassen.
        assertTrue(clip(a).inNoPack())

        val first = createPack(maker, "First", listOf(a, b)).andExpect { status { isCreated() } }.body()["slug"].asString()

        //  Ein Clip gehoert EINEM Pack.
        val clash = createPack(maker, "Second", listOf(b, c)).andExpect { status { isConflict() } }.body()
        assertEquals("in-another-pack", clash["error"]["code"].asString())
        assertEquals(first, clash["error"]["slug"].asString())

        //  Nur der Besitzer baut daran.
        mvc.patch("/api/v1/packs/$first") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"title":"Mine now"}"""
            header("Authorization", "Bearer $other")
        }.andExpect { status { isForbidden() } }
        mvc.delete("/api/v1/packs/$first") { header("Authorization", "Bearer $other") }
            .andExpect { status { isForbidden() } }
        mvc.post("/api/v1/packs") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"title":"Starter?","clips":["$a","$b"],"starter":true}"""
            header("Authorization", "Bearer $other")
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `the owner renames, reorders, trims and dissolves a pack, and the clips stay`() {
        val handle = "editor" + unique()
        val maker = login(handle)
        val a = upload(maker, "Step A")
        val b = upload(maker, "Step B")
        val c = upload(maker, "Step C")
        val slug = createPack(maker, "Steps", listOf(a, b)).andExpect { status { isCreated() } }.body()["slug"].asString()

        val renamed = mvc.patch("/api/v1/packs/$slug") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"title":"Steps, all of them","description":"Three steps."}"""
            header("Authorization", "Bearer $maker")
        }.andExpect { status { isOk() } }.body()
        assertEquals("Steps, all of them", renamed["title"].asString())

        val grown = mvc.post("/api/v1/packs/$slug/clips") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"clips":["$c"]}"""
            header("Authorization", "Bearer $maker")
        }.andExpect { status { isOk() } }.body()
        assertEquals(listOf(a, b, c), grown["items"].map { it["slug"].asString() })

        val reordered = mvc.patch("/api/v1/packs/$slug/order") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"clips":["$c","$a"]}"""
            header("Authorization", "Bearer $maker")
        }.andExpect { status { isOk() } }.body()
        assertEquals(listOf(c, a, b), reordered["items"].map { it["slug"].asString() }, "missing ones stay at the back")

        val trimmed = mvc.delete("/api/v1/packs/$slug/clips/$a") { header("Authorization", "Bearer $maker") }
            .andExpect { status { isOk() } }.body()
        assertEquals(listOf(c, b), trimmed["items"].map { it["slug"].asString() })
        assertTrue(clip(a).inNoPack(), "a removed clip stands alone again")
        assertEquals(2, wall("owner=$handle")["total"].asInt(), "pack + the removed clip")

        mvc.delete("/api/v1/packs/$slug") { header("Authorization", "Bearer $maker") }
            .andExpect { status { isOk() } }
        pack(slug).andExpect { status { isNotFound() } }

        val after = wall("owner=$handle")
        assertEquals(3, after["total"].asInt(), "all three clips are back on the wall")
        assertEquals(0, after["packs"].asInt())
    }

    @Test
    fun `withdrawn clips drop out, and a pack with none left is gone for everyone but its owner`() {
        val handle = "leaver" + unique()
        val maker = login(handle)
        val a = upload(maker, "Gone A")
        val b = upload(maker, "Gone B")
        val slug = createPack(maker, "Going", listOf(a, b)).andExpect { status { isCreated() } }.body()["slug"].asString()

        mvc.delete("/api/v1/packages/$a") { header("Authorization", "Bearer $maker") }.andExpect { status { isOk() } }
        assertEquals(1, pack(slug).andExpect { status { isOk() } }.body()["clips"].asInt())

        mvc.delete("/api/v1/packages/$b") { header("Authorization", "Bearer $maker") }.andExpect { status { isOk() } }
        pack(slug).andExpect { status { isNotFound() } }
        assertEquals(0, wall("owner=$handle")["total"].asInt())

        //  Der Besitzer findet ihn noch - leer, aber da.
        val mine = mvc.get("/api/v1/me/packs") { header("Authorization", "Bearer $maker") }
            .andExpect { status { isOk() } }.body()
        assertEquals(0, mine.first { it["slug"].asString() == slug }["clips"].asInt())
    }

    @Test
    fun `followers hear about a pack once, not once per clip`() {
        val handle = "teller" + unique()
        val maker = login(handle)
        val fan = login("fan" + unique())
        mvc.post("/api/v1/users/$handle/follow") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"following":true}"""
            header("Authorization", "Bearer $fan")
        }.andExpect { status { isOk() } }

        val a = upload(maker, "Quiet A", notify = false)
        val b = upload(maker, "Quiet B", notify = false)
        createPack(maker, "Loud Pack", listOf(a, b)).andExpect { status { isCreated() } }

        val messages = mvc.get("/api/v1/me/notifications") { header("Authorization", "Bearer $fan") }
            .andExpect { status { isOk() } }.body().map { it["message"].asString() }
        assertEquals(1, messages.count { it.contains(handle, ignoreCase = true) || it.contains("Loud Pack") })
        assertTrue(messages.any { it.contains("shared a new pack: 'Loud Pack' (2 clips)") })
    }

    @Test
    fun `an admin makes a starter pack, and it names the source its clips share`() {
        val admin = login("admin")
        val seeded = (1..2).map { n ->
            mvc.multipart("/api/v1/admin/starter-clips") {
                file("file", awclip("Gesture_$n", "ARR", "\"gesture\"", ownMotion = false))
                param("credit", "Free Motion Pack 1")
                param("url", "https://github.com/J-Beardmore/FreeMotionPack1")
                header("Authorization", "Bearer $admin")
            }.andExpect { status { isCreated() } }.body()["slug"].asString()
        }

        val created = createPack(admin, "Free Motion Pack 1", seeded, starter = true)
            .andExpect { status { isCreated() } }.body()
        assertEquals(StarterClips.HANDLE, created["authorHandle"].asString())
        assertEquals("Free Motion Pack 1", created["source"]["credit"].asString())
        assertTrue(created["isOwner"].asBoolean(), "admins manage starter packs")

        //  Fuer alle anderen ist es ein Pack der Starter-Clips, nicht der des Admins.
        val seen = pack(created["slug"].asString(), login("visitor" + unique())).andExpect { status { isOk() } }.body()
        assertFalse(seen["isOwner"].asBoolean())
    }
}
