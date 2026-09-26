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
 * Nachrichten mit Absender und Ziel, und die Follower-Liste.
 *
 * Die Regeln, die hier stehen, kippen sonst still: eine Nachricht nennt den
 * Namen als Link und nicht als Text, Herz an-aus-an ist EINE Nachricht, eine
 * ungelistete Sammlung verraet nichts, und wer sein Konto schliesst, verschwindet
 * auch aus fremden Postfaechern.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NotificationFlowTest {

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

    private fun inbox(token: String): List<JsonNode> =
        mvc.get("/api/v1/me/notifications") { header("Authorization", "Bearer $token") }
            .andExpect { status { isOk() } }.body().toList()

    private fun follow(token: String, handle: String, following: Boolean) =
        mvc.post("/api/v1/users/$handle/follow") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"following":$following}"""
            header("Authorization", "Bearer $token")
        }.andExpect { status { isOk() } }

    private fun like(token: String, slug: String, liked: Boolean) =
        mvc.post("/api/v1/packages/$slug/like") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"liked":$liked}"""
            header("Authorization", "Bearer $token")
        }.andExpect { status { isOk() } }

    private fun comment(token: String, slug: String, text: String) =
        mvc.post("/api/v1/packages/$slug/comments") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"body":"$text"}"""
            header("Authorization", "Bearer $token")
        }.andExpect { status { isCreated() } }

    private fun collection(token: String, visibility: String): String =
        mvc.post("/api/v1/collections") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"title":"Picks ${unique()}","description":"Picked by hand.","visibility":"$visibility"}"""
            header("Authorization", "Bearer $token")
        }.andExpect { status { isCreated() } }.body()["slug"].asString()

    private fun addItem(token: String, collection: String, clip: String) =
        mvc.post("/api/v1/collections/$collection/items") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"slug":"$clip"}"""
            header("Authorization", "Bearer $token")
        }.andExpect { status { isOk() } }

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

    private fun upload(token: String, title: String): String =
        mvc.multipart("/api/v1/packages") {
            file("file", awclip(title))
            param("declarationText", Declaration.TEXT)
            param("declarationVersion", Declaration.VERSION.toString())
            param("declarationAccepted", "true")
            header("Authorization", "Bearer $token")
        }.andExpect { status { isCreated() } }.body()["slug"].asString()

    // ── Tests ───────────────────────────────────────────────────────────

    /**
     * Befund: "wenn mir jemand folgt, kann ich nicht auf seinen Namen klicken
     * und nicht sehen, wer mir folgt". Die Nachricht traegt jetzt den Absender
     * mit Handle, und die Liste der Follower ist abrufbar.
     */
    @Test
    fun `a follow names who it was, once, and the follower shows up in the list`() {
        val creator = "creator" + unique()
        val fan = "fan" + unique()
        val creatorToken = login(creator)
        val fanToken = login(fan)

        follow(fanToken, creator, true)
        follow(fanToken, creator, false)
        follow(fanToken, creator, true)

        val follows = inbox(creatorToken).filter { it["kind"].asString() == "follow" }
        assertEquals(1, follows.size, "unfollow and follow again is not a second first time")

        val message = follows.single()
        assertEquals(fan, message["actor"]["handle"].asString())
        assertEquals(fan, message["actor"]["displayName"].asString())
        assertEquals("follows you now.", message["text"].asString())
        assertEquals("$fan follows you now.", message["message"].asString())
        assertTrue(message["link"].isNull, "no link of its own: the row leads to the actor's profile")
        assertFalse(message["read"].asBoolean())

        //  Geholt heisst gelesen.
        assertTrue(inbox(creatorToken).first { it["kind"].asString() == "follow" }["read"].asBoolean())

        val followers = mvc.get("/api/v1/users/$creator/followers").andExpect { status { isOk() } }.body()
        assertEquals(listOf(fan), followers.map { it["handle"].asString() })
    }

    @Test
    fun `a heart tells the creator once and points at the clip`() {
        val creatorToken = login("maker" + unique())
        val liker = "liker" + unique()
        val likerToken = login(liker)
        val slug = upload(creatorToken, "Wave")

        like(likerToken, slug, true)
        like(likerToken, slug, false)
        like(likerToken, slug, true)

        val likes = inbox(creatorToken).filter { it["kind"].asString() == "like" }
        assertEquals(1, likes.size)
        assertEquals("/clip.html?p=$slug", likes.single()["link"].asString())
        assertEquals(liker, likes.single()["actor"]["handle"].asString())
        assertEquals("liked 'Wave'.", likes.single()["text"].asString())

        //  Das eigene Herz ist keine Nachricht.
        like(creatorToken, slug, true)
        assertTrue(inbox(creatorToken).none { it["kind"].asString() == "like" && !it["read"].asBoolean() })
    }

    @Test
    fun `a public collection tells the creator, an unlisted one tells nobody`() {
        val creatorToken = login("maker" + unique())
        val collectorToken = login("collector" + unique())
        val slug = upload(creatorToken, "Bow")

        val hidden = collection(collectorToken, "UNLISTED")
        addItem(collectorToken, hidden, slug)
        assertTrue(inbox(creatorToken).none { it["kind"].asString() == "collected" })

        val shown = collection(collectorToken, "PUBLIC")
        addItem(collectorToken, shown, slug)
        val collected = inbox(creatorToken).filter { it["kind"].asString() == "collected" }
        assertEquals(1, collected.size)
        assertEquals("/collection.html?c=$shown", collected.single()["link"].asString())
    }

    /**
     * Ein Kommentar erreicht den Besitzer - und die, die schon mitgeredet
     * haben, aber je Gespraech nur einmal, solange sie nicht nachgesehen haben.
     */
    @Test
    fun `a comment reaches the creator and the earlier commenters, without flooding them`() {
        val creatorToken = login("maker" + unique())
        val firstToken = login("first" + unique())
        val secondToken = login("second" + unique())
        val slug = upload(creatorToken, "Talk")

        comment(firstToken, slug, "Nice timing.")
        comment(secondToken, slug, "Agreed.")
        comment(secondToken, slug, "Also the arms.")

        val creatorInbox = inbox(creatorToken).filter { it["kind"].asString() == "comment" }
        assertEquals(3, creatorInbox.size)
        assertEquals("/clip.html?p=$slug#comments", creatorInbox.first()["link"].asString())

        val replies = inbox(firstToken).filter { it["kind"].asString() == "reply" }
        assertEquals(1, replies.size, "one unread reply notice per conversation is enough")
        assertEquals("also commented on 'Talk'.", replies.single()["text"].asString())

        //  Gelesen - dann darf die naechste Antwort wieder Bescheid sagen.
        comment(secondToken, slug, "One more thing.")
        assertEquals(2, inbox(firstToken).count { it["kind"].asString() == "reply" })

        //  Wer selbst schreibt, bekommt keine Nachricht ueber sich.
        assertTrue(inbox(secondToken).none { it["kind"].asString() == "reply" })
    }

    @Test
    fun `closing an account takes its messages out of other inboxes`() {
        val creator = "creator" + unique()
        val creatorToken = login(creator)
        val leavingToken = login("leaving" + unique())

        follow(leavingToken, creator, true)
        assertEquals(1, inbox(creatorToken).count { it["kind"].asString() == "follow" })

        mvc.delete("/api/v1/me") { header("Authorization", "Bearer $leavingToken") }.andExpect { status { isOk() } }
        assertEquals(0, inbox(creatorToken).count { it["kind"].asString() == "follow" })
    }
}
