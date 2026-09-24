package com.playmation.motionlabsbackend

import com.playmation.motionlabsbackend.account.AccountHandles
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
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.GZIPOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Profile: Adresse, Folgen, Melden, Auszeichnungen.
 *
 * Die eine Sache, die hier anders ist als ueberall sonst im Portal: eine
 * Meldung gegen ein KONTO versteckt nichts. Genau das prueft dieser Durchlauf
 * mit - es ist die Regel, die beim naechsten Handgriff still umfaellt, wenn
 * sie niemand festhaelt.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProfileFlowTest {

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

    private fun profile(handle: String, token: String? = null) =
        mvc.get("/api/v1/users/$handle") { token?.let { header("Authorization", "Bearer $it") } }

    private fun follow(token: String, handle: String, following: Boolean) =
        mvc.post("/api/v1/users/$handle/follow") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"following":$following}"""
            header("Authorization", "Bearer $token")
        }

    private fun awclip(title: String): ByteArray {
        //  Siehe oben: jede Bewegung einmalig, sonst faengt die Duplikatsperre sie.
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

    private fun uploadOk(token: String, title: String = "Clip"): String =
        mvc.multipart("/api/v1/packages") {
            file("file", awclip(title))
            param("declarationText", Declaration.TEXT)
            param("declarationVersion", Declaration.VERSION.toString())
            param("declarationAccepted", "true")
            header("Authorization", "Bearer $token")
        }.andExpect { status { isCreated() } }.body()["slug"].asString()

    // ── Tests ───────────────────────────────────────────────────────────

    /**
     * Der Handle ist die Adresse. Zwei Konten, deren Anzeigename auf denselben
     * Handle fuehrt, duerfen nicht auf derselben Seite landen - das waere ein
     * geteiltes Profil und damit geteilte Clips, Follower und Auszeichnungen.
     */
    @Test
    fun `a handle is assigned at sign-in and stays unique`() {
        val name = "nora" + unique()
        login(name)
        //  Zwei verschiedene Konten (die Discord-Kennung unterscheidet sich),
        //  ein gemeinsamer Handle-Vorschlag: der zweite muss ausweichen.
        login(name.uppercase())

        val first = profile(name).andExpect { status { isOk() } }.body()
        val second = profile(name + "-2").andExpect { status { isOk() } }.body()

        assertEquals(name, first["handle"].asString())
        assertEquals(name + "-2", second["handle"].asString())
        assertNotEquals(first["displayName"].asString(), second["displayName"].asString())
    }

    /** Was aus einem Anzeigenamen wird, entscheidet eine Regel - keine Laune. */
    @Test
    fun `a handle keeps letters, digits and dashes and nothing else`() {
        assertEquals("renee", AccountHandles.slugify("Renée"))
        assertEquals("pablo-star", AccountHandles.slugify("Pablo Star"))
        assertEquals("a_b-c", AccountHandles.slugify("  a_b-c  "))
        assertEquals("", AccountHandles.slugify("日本語"))
    }

    /**
     * Ein Profil ist oeffentlich, Folgen braucht ein Konto. Dieselbe Trennung
     * wie bei Herzen und Kommentaren.
     */
    @Test
    fun `a profile is public, following needs an account, and the count is right`() {
        val creator = "creator" + unique()
        val fan = "fan" + unique()
        val creatorToken = login(creator)
        val fanToken = login(fan)
        uploadOk(creatorToken, "Shown on the profile")

        //  Ohne Konto: lesen ja, folgen nein.
        profile(creator).andExpect { status { isOk() } }
        mvc.post("/api/v1/users/$creator/follow") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"following":true}"""
            with(csrf())
        }.andExpect { status { isUnauthorized() } }

        follow(fanToken, creator, true).andExpect { status { isOk() } }
        assertEquals(1, profile(creator).body()["followers"].asInt())
        assertTrue(profile(creator, fanToken).body()["followedByMe"].asBoolean())

        //  Zweimal folgen bleibt einmal - der zusammengesetzte Schluessel
        //  laesst gar keine zweite Zeile zu.
        follow(fanToken, creator, true).andExpect { status { isOk() } }
        assertEquals(1, profile(creator).body()["followers"].asInt())

        follow(fanToken, creator, false).andExpect { status { isOk() } }
        assertEquals(0, profile(creator).body()["followers"].asInt())

        //  Sich selbst folgen ist keine Handlung, sondern ein Tippfehler.
        follow(creatorToken, creator, true).andExpect { status { isBadRequest() } }

        val own = mvc.get("/api/v1/users/$creator/packages").andExpect { status { isOk() } }.body()
        assertEquals(1, own["total"].asInt())
        assertEquals("Shown on the profile", own["items"][0]["title"].asString())
    }

    /**
     * MELDEN VERSTECKT HIER NICHTS.
     *
     * Bei einem Clip ist Auto-Hide richtig: er ist ersetzbar, und die Pruefung
     * kann in Ruhe stattfinden. Bei einem Konto waere derselbe Griff die
     * Fernbedienung, mit der jeder jeden Ersteller stummschaltet.
     */
    @Test
    fun `reporting an account opens a case and hides nothing`() {
        val target = "target" + unique()
        val reporter = "reporter" + unique()
        val targetToken = login(target)
        val reporterToken = login(reporter)
        val slug = uploadOk(targetToken, "Still visible after the report")

        val admin = login("admin")

        mvc.post("/api/v1/users/$target/reports") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"category":"INAPPROPRIATE","message":"Rude in the comments."}"""
            header("Authorization", "Bearer $reporterToken")
        }.andExpect { status { isCreated() } }

        //  Das Profil steht, der Clip steht, das Konto ist unveraendert.
        profile(target).andExpect { status { isOk() } }
        mvc.get("/api/v1/packages/$slug").andExpect { status { isOk() } }

        //  Zweimal dieselbe Meldung ist keine zweite Meldung.
        mvc.post("/api/v1/users/$target/reports") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"category":"INAPPROPRIATE","message":"Again."}"""
            header("Authorization", "Bearer $reporterToken")
        }.andExpect { status { isConflict() } }

        //  Das eigene Konto zu melden ergibt keinen Fall.
        mvc.post("/api/v1/users/$target/reports") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"category":"OTHER","message":"Me."}"""
            header("Authorization", "Bearer $targetToken")
        }.andExpect { status { isBadRequest() } }

        val cases = mvc.get("/api/v1/admin/cases") { header("Authorization", "Bearer $admin") }
            .andExpect { status { isOk() } }.body()

        val mine = cases.firstOrNull { it["kind"].asString() == "account-report" && it["account"]["handle"].asString() == target }
        assertTrue(mine != null, "Die Kontomeldung fehlt in der Fallliste")
        assertEquals("ACTIVE", mine!!["account"]["status"].asString())
        assertEquals(0, mine["packages"].size())
    }

    /**
     * Auszeichnungen sind ABGELEITET: keine Verleihung, keine Tabelle. Wer
     * einen Clip teilt, hat die erste Stufe - rueckwirkend und ohne Lauf.
     */
    @Test
    fun `awards come out of the numbers that are already counted`() {
        val name = "awarded" + unique()
        val token = login(name)

        val before = profile(name).body()["achievements"]
        assertFalse(before.any { it["key"].asString() == "clips" },
            "Ohne Clip darf auf einem fremden Profil keine Clip-Auszeichnung stehen")

        uploadOk(token, "First clip")

        val after = profile(name).body()["achievements"]
        val clips = after.first { it["key"].asString() == "clips" }
        assertTrue(clips["earned"].asBoolean())
        assertEquals(1, clips["tier"].asInt())
        assertEquals(1, clips["progress"].asInt())
        assertEquals(5, clips["goal"].asInt(), "Die naechste Stufe gehoert dazu, sonst fehlt der Weg")

        //  Auf dem EIGENEN Profil stehen auch die offenen - sonst weiss
        //  niemand, was als naechstes ansteht.
        val mine = profile(name, token).body()["achievements"]
        assertTrue(mine.size() > after.size())
    }

    /**
     * "Supporter" zaehlt Herzen an FREMDEN Clips.
     *
     * Das Herz am eigenen Clip darf gesetzt werden - es traegt nur keine
     * Auszeichnung. Sonst waere die erste Stufe zehn eigene Uploads und zehn
     * Klicks weit weg, und die Auszeichnung sagte nichts mehr ueber Zuspruch.
     * Es ist dieselbe Regel wie beim Holen (`UnlockService`, Regel 1), nur
     * dass sie dort schon beim Schreiben greift und hier erst beim Zaehlen.
     */
    @Test
    fun `hearts on your own clips do not make you a supporter`() {
        val owner = "selfliker" + unique()
        val ownerToken = login(owner)
        val slug = uploadOk(ownerToken, "Own clip")

        fun heart(token: String) = mvc.post("/api/v1/packages/$slug/like") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"liked":true}"""
            header("Authorization", "Bearer $token")
        }.andExpect { status { isOk() } }

        fun supporter(handle: String, token: String) =
            profile(handle, token).body()["achievements"].first { it["key"].asString() == "supporter" }

        heart(ownerToken)
        assertEquals(0, supporter(owner, ownerToken)["progress"].asInt(),
            "Das Herz am eigenen Clip darf die eigene Auszeichnung nicht fuellen")

        val fan = "fan" + unique()
        val fanToken = login(fan)
        heart(fanToken)
        assertEquals(1, supporter(fan, fanToken)["progress"].asInt(),
            "Das Herz an einem fremden Clip zaehlt")
    }

    /**
     * Das Konto schliessen: der Name geht, das Gespraech bleibt lesbar.
     *
     * Der Test haelt genau die Abwaegung fest, um die es dabei geht - was
     * verschwinden MUSS (Person) und was bleiben muss (die Saetze unter fremden
     * Clips, damit dort kein Loch entsteht).
     */
    @Test
    fun `closing an account takes the person with it and leaves the conversation`() {
        val name = "leaving" + unique()
        val other = "staying" + unique()
        val token = login(name)
        val otherToken = login(other)

        val mine = uploadOk(token, "Withdrawn on the way out")
        val theirs = uploadOk(otherToken, "Stays in the catalogue")

        //  Ein Herz, ein Folgen, ein Satz unter einem fremden Clip.
        mvc.post("/api/v1/packages/$theirs/like") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"liked":true}"""
            header("Authorization", "Bearer $token")
        }.andExpect { status { isOk() } }

        follow(token, other, true).andExpect { status { isOk() } }

        mvc.post("/api/v1/packages/$theirs/comments") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"body":"This is a good walk cycle."}"""
            header("Authorization", "Bearer $token")
        }.andExpect { status { isCreated() } }

        mvc.delete("/api/v1/me") { header("Authorization", "Bearer $token") }
            .andExpect { status { isOk() } }

        //  Die Person ist weg - Profil, Herz, Folgen, eigener Clip.
        profile(name).andExpect { status { isNotFound() } }
        assertEquals(0, profile(other).body()["followers"].asInt())
        assertEquals(0, mvc.get("/api/v1/packages/$theirs").body()["likes"].asInt())
        mvc.get("/api/v1/packages/$mine").andExpect { status { isNotFound() } }

        //  Der Satz steht noch da, ohne Namen.
        val comments = mvc.get("/api/v1/packages/$theirs/comments").andExpect { status { isOk() } }.body()
        val remaining = comments["comments"].first { it["body"].asString().startsWith("This is a good walk") }
        assertEquals("Deleted user", remaining["author"].asString())

        //  Und die Anmeldung gilt nicht mehr.
        mvc.get("/api/v1/me") { header("Authorization", "Bearer $token") }
            .andExpect { status { isUnauthorized() } }
    }

    /**
     * Der Handle laesst sich wechseln, und die alte Adresse ist danach leer.
     * Das ist der Preis, und er steht auch im Dialog - eine Weiterleitung
     * braeuchte eine Tabelle alter Handles.
     */
    @Test
    fun `the handle can be changed, and the old address is gone`() {
        val name = "renamed" + unique()
        val token = login(name)
        val wanted = "chosen" + unique()

        mvc.patch("/api/v1/me/profile") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"handle":"$wanted","bio":"I animate cats."}"""
            header("Authorization", "Bearer $token")
        }.andExpect { status { isOk() } }

        val moved = profile(wanted).andExpect { status { isOk() } }.body()
        assertEquals(wanted, moved["handle"].asString())
        assertEquals("I animate cats.", moved["bio"].asString())
        profile(name).andExpect { status { isNotFound() } }

        //  Ein vergebener Handle ist vergeben.
        val other = login("other" + unique())
        mvc.patch("/api/v1/me/profile") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"handle":"$wanted"}"""
            header("Authorization", "Bearer $other")
        }.andExpect { status { isConflict() } }

        //  Und was kein Handle sein kann, wird keiner.
        mvc.patch("/api/v1/me/profile") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"handle":"no spaces please"}"""
            header("Authorization", "Bearer $token")
        }.andExpect { status { isBadRequest() } }
    }
}
