package com.playmation.motionlabsbackend

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
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Direktnachrichten: Anfrage, Annahme, Blockieren, Melden, Leeren, Konto
 * schliessen.
 *
 * Was hier steht, sind die Versprechen aus der Datenschutzerklaerung und den
 * Regeln - wer eins davon bricht, soll es an einem roten Test merken und nicht
 * an einer Beschwerde: niemand Drittes liest mit, eine Anfrage bleibt klein,
 * eine Sperre wirkt in beide Richtungen, und die Moderation sieht nur, was ihr
 * vorgelegt wird.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MessageFlowTest {

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

    private fun sendTo(token: String, handle: String, text: String) =
        mvc.post("/api/v1/users/$handle/messages") {
            contentType = MediaType.APPLICATION_JSON
            content = json.writeValueAsString(mapOf("body" to text))
            header("Authorization", "Bearer $token")
        }

    private fun send(token: String, conversation: String, text: String) =
        mvc.post("/api/v1/me/conversations/$conversation/messages") {
            contentType = MediaType.APPLICATION_JSON
            content = json.writeValueAsString(mapOf("body" to text))
            header("Authorization", "Bearer $token")
        }

    private fun list(token: String): List<JsonNode> =
        mvc.get("/api/v1/me/conversations") { header("Authorization", "Bearer $token") }
            .andExpect { status { isOk() } }.body().toList()

    private fun unread(token: String): Long =
        mvc.get("/api/v1/me/conversations/unread") { header("Authorization", "Bearer $token") }
            .andExpect { status { isOk() } }.body()["conversations"].asLong()

    private fun open(token: String, conversation: String) =
        mvc.get("/api/v1/me/conversations/$conversation") { header("Authorization", "Bearer $token") }

    private fun follow(token: String, handle: String, following: Boolean) =
        mvc.post("/api/v1/users/$handle/follow") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"following":$following}"""
            header("Authorization", "Bearer $token")
        }

    private fun block(token: String, handle: String, blocked: Boolean) =
        mvc.post("/api/v1/users/$handle/block") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"blocked":$blocked}"""
            header("Authorization", "Bearer $token")
        }.andExpect { status { isOk() } }

    // ── Tests ───────────────────────────────────────────────────────────

    @Test
    fun `a first message is a request that stays small until it is answered`() {
        val alice = "alice" + unique()
        val bob = "bob" + unique()
        val aliceToken = login(alice)
        val bobToken = login(bob)

        val first = sendTo(aliceToken, bob, "Hi! How did you rig the tail?")
            .andExpect { status { isCreated() } }.body()
        assertEquals("request-out", first["state"].asString())
        val conversation = first["conversationId"].asString()

        //  Bob sieht eine Anfrage, und sie zaehlt am Briefsymbol.
        val bobsRow = list(bobToken).single()
        assertEquals("request-in", bobsRow["state"].asString())
        assertEquals(alice, bobsRow["with"]["handle"].asString())
        assertTrue(bobsRow["unread"].asBoolean())
        assertEquals(1, unread(bobToken))
        assertEquals(0, unread(aliceToken))

        //  Zwei duerfen nachkommen, die vierte nicht (pending-limit = 3).
        send(aliceToken, conversation, "Second").andExpect { status { isCreated() } }
        send(aliceToken, conversation, "Third").andExpect { status { isCreated() } }
        send(aliceToken, conversation, "Fourth").andExpect {
            status { isConflict() }
            jsonPath("$.error.code") { value("waiting") }
        }
        val aliceView = open(aliceToken, conversation).andExpect { status { isOk() } }.body()
        assertEquals("waiting", aliceView["cannotSend"].asString())
        assertEquals(0, aliceView["pendingLeft"].asInt())

        //  Oeffnen heisst gelesen - die Zahl geht weg.
        val bobView = open(bobToken, conversation).andExpect { status { isOk() } }.body()
        assertEquals(3, bobView["messages"].size())
        assertFalse(bobView["messages"][0]["mine"].asBoolean())
        assertEquals(0, unread(bobToken))

        //  Antworten ist annehmen, und danach gibt es keine Grenze mehr.
        assertEquals("accepted", send(bobToken, conversation, "With a spline.")
            .andExpect { status { isCreated() } }.body()["state"].asString())
        send(aliceToken, conversation, "Fourth").andExpect { status { isCreated() } }
        send(aliceToken, conversation, "Fifth").andExpect { status { isCreated() } }
        assertEquals(1, unread(bobToken))

        //  Ein zweites "Message" auf dem Profil landet im selben Gespraech.
        val again = sendTo(aliceToken, bob, "Same thread?").andExpect { status { isCreated() } }.body()
        assertEquals(conversation, again["conversationId"].asString())
    }

    @Test
    fun `nobody else can read a conversation`() {
        val alice = "alice" + unique()
        val bob = "bob" + unique()
        val aliceToken = login(alice)
        login(bob)
        val carolToken = login("carol" + unique())

        val conversation = sendTo(aliceToken, bob, "Just for Bob.")
            .andExpect { status { isCreated() } }.body()["conversationId"].asString()

        open(carolToken, conversation).andExpect { status { isNotFound() } }
        send(carolToken, conversation, "Hello?").andExpect { status { isNotFound() } }
        mvc.get("/api/v1/me/conversations/$conversation").andExpect { status { isUnauthorized() } }

        //  Auch ein Admin hat keinen Weg hinein - es gibt keinen Admin-Endpunkt.
        val adminToken = login("admin")
        open(adminToken, conversation).andExpect { status { isNotFound() } }
    }

    @Test
    fun `someone you follow skips the request, and a declined request disappears`() {
        val alice = "alice" + unique()
        val bob = "bob" + unique()
        val carol = "carol" + unique()
        val aliceToken = login(alice)
        val bobToken = login(bob)
        val carolToken = login(carol)

        //  Bob folgt Alice - also ist Alice' Nachricht keine Anfrage.
        follow(bobToken, alice, true).andExpect { status { isOk() } }
        assertEquals("accepted", sendTo(aliceToken, bob, "Thanks for following!")
            .andExpect { status { isCreated() } }.body()["state"].asString())

        //  Carol folgt niemand: Anfrage, Bob lehnt ab, weg ist sie.
        val request = sendTo(carolToken, bob, "Buy my course")
            .andExpect { status { isCreated() } }.body()["conversationId"].asString()
        assertEquals(2, unread(bobToken))
        mvc.post("/api/v1/me/conversations/$request/decline") { header("Authorization", "Bearer $bobToken") }
            .andExpect { status { isOk() } }
        assertEquals(listOf(alice), list(bobToken).map { it["with"]["handle"].asString() })
        assertEquals(1, unread(bobToken))

        //  Carol erfaehrt nichts davon - fuer sie wartet die Anfrage weiter.
        assertEquals("request-out", list(carolToken).single()["state"].asString())
    }

    @Test
    fun `a block works both ways and also stops following`() {
        val alice = "alice" + unique()
        val bob = "bob" + unique()
        val aliceToken = login(alice)
        val bobToken = login(bob)

        follow(aliceToken, bob, true).andExpect { status { isOk() } }
        val conversation = sendTo(aliceToken, bob, "Hey")
            .andExpect { status { isCreated() } }.body()["conversationId"].asString()

        block(bobToken, alice, true)

        //  Alice kommt nicht mehr durch - und Bob auch nicht, solange er blockiert.
        sendTo(aliceToken, bob, "Hello?").andExpect {
            status { isForbidden() }
            jsonPath("$.error.code") { value("unavailable") }
        }
        send(bobToken, conversation, "Actually...").andExpect {
            status { isForbidden() }
            jsonPath("$.error.code") { value("blocked-by-you") }
        }

        //  Das Folgen ist weg und kommt nicht wieder.
        val profile = mvc.get("/api/v1/users/$bob") { header("Authorization", "Bearer $aliceToken") }
            .andExpect { status { isOk() } }.body()
        assertFalse(profile["followedByMe"].asBoolean())
        follow(aliceToken, bob, true).andExpect { status { isForbidden() } }

        //  Bob sieht, wen er blockiert hat, und eine Anfrage von dort zaehlt nicht.
        assertEquals(alice, mvc.get("/api/v1/me/blocks") { header("Authorization", "Bearer $bobToken") }
            .andExpect { status { isOk() } }.body()[0]["handle"].asString())
        assertEquals(0, unread(bobToken))
        assertTrue(mvc.get("/api/v1/users/$alice") { header("Authorization", "Bearer $bobToken") }
            .andExpect { status { isOk() } }.body()["blockedByMe"].asBoolean())

        //  Aufheben - dann geht es wieder.
        block(bobToken, alice, false)
        send(bobToken, conversation, "Sorry, misclick.").andExpect { status { isCreated() } }
    }

    @Test
    fun `a report hands over the message and what came before, and nothing else`() {
        val alice = "alice" + unique()
        val bob = "bob" + unique()
        val aliceToken = login(alice)
        val bobToken = login(bob)

        val conversation = sendTo(aliceToken, bob, "Nice clip!")
            .andExpect { status { isCreated() } }.body()["conversationId"].asString()
        send(bobToken, conversation, "Thanks :)").andExpect { status { isCreated() } }
        val nasty = send(aliceToken, conversation, "Something nasty")
            .andExpect { status { isCreated() } }.body()["message"]["id"].asString()

        //  Die eigene Nachricht meldet man nicht.
        mvc.post("/api/v1/me/conversations/$conversation/messages/$nasty/reports") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"category":"INAPPROPRIATE"}"""
            header("Authorization", "Bearer $aliceToken")
        }.andExpect { status { isBadRequest() } }

        mvc.post("/api/v1/me/conversations/$conversation/messages/$nasty/reports") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"category":"INAPPROPRIATE","message":"Out of nowhere.","block":true}"""
            header("Authorization", "Bearer $bobToken")
        }.andExpect { status { isCreated() } }

        //  Im Fall steht der Verlauf bis zur gemeldeten Nachricht.
        val adminToken = login("admin")
        val case = mvc.get("/api/v1/admin/cases") { header("Authorization", "Bearer $adminToken") }
            .andExpect { status { isOk() } }.body()
            .first { it["kind"].asString() == "message-report" && it["account"]["handle"].asString() == alice }
        val text = case["message"].asString()
        assertTrue(text.contains("Nice clip!"))
        assertTrue(text.contains("Thanks :)"))
        assertTrue(text.contains(">> "))
        assertTrue(text.contains("Something nasty"))
        assertTrue(text.endsWith("-- reported as: Out of nowhere."))

        //  Und der Haken hat blockiert.
        sendTo(aliceToken, bob, "Why?").andExpect { status { isForbidden() } }

        //  Geleert wird nur bei Bob - die Meldung behaelt ihre Kopie.
        mvc.delete("/api/v1/me/conversations/$conversation") { header("Authorization", "Bearer $bobToken") }
            .andExpect { status { isOk() } }
        assertTrue(mvc.get("/api/v1/admin/cases") { header("Authorization", "Bearer $adminToken") }
            .andExpect { status { isOk() } }.body().any { it["message"].asString().contains("Something nasty") })
    }

    @Test
    fun `clearing hides it for me, and once both cleared it is gone`() {
        val alice = "alice" + unique()
        val bob = "bob" + unique()
        val aliceToken = login(alice)
        val bobToken = login(bob)

        val conversation = sendTo(aliceToken, bob, "One")
            .andExpect { status { isCreated() } }.body()["conversationId"].asString()
        send(bobToken, conversation, "Two").andExpect { status { isCreated() } }

        mvc.delete("/api/v1/me/conversations/$conversation") { header("Authorization", "Bearer $aliceToken") }
            .andExpect { status { isOk() } }
        assertTrue(list(aliceToken).isEmpty())
        assertEquals(2, open(bobToken, conversation).andExpect { status { isOk() } }.body()["messages"].size())

        //  Schreibt Bob wieder, erscheint es bei Alice - nur mit dem Neuen.
        send(bobToken, conversation, "Three").andExpect { status { isCreated() } }
        val aliceView = open(aliceToken, conversation).andExpect { status { isOk() } }.body()
        assertEquals(listOf("Three"), aliceView["messages"].map { it["body"].asString() })

        //  Beide leeren: die Zeilen gehen wirklich.
        mvc.delete("/api/v1/me/conversations/$conversation") { header("Authorization", "Bearer $aliceToken") }
            .andExpect { status { isOk() } }
        mvc.delete("/api/v1/me/conversations/$conversation") { header("Authorization", "Bearer $bobToken") }
            .andExpect { status { isOk() } }
        open(bobToken, conversation).andExpect { status { isNotFound() } }
    }

    @Test
    fun `closing an account keeps accepted conversations for the other side and drops requests`() {
        val alice = "alice" + unique()
        val bob = "bob" + unique()
        val carol = "carol" + unique()
        val aliceToken = login(alice)
        val bobToken = login(bob)
        login(carol)

        val talk = sendTo(aliceToken, bob, "Hello Bob")
            .andExpect { status { isCreated() } }.body()["conversationId"].asString()
        send(bobToken, talk, "Hello Alice").andExpect { status { isCreated() } }
        sendTo(aliceToken, carol, "Hello Carol").andExpect { status { isCreated() } }

        //  Der Export traegt beide Gespraeche mit Text.
        val zip = mvc.get("/api/v1/me/export") { header("Authorization", "Bearer $aliceToken") }
            .andExpect { status { isOk() } }.andReturn().response.contentAsByteArray
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(zip.inputStream()).use { z -> generateSequence { z.nextEntry }.forEach { entries[it.name] = z.readBytes() } }
        val exported = json.readTree(entries.getValue("data.json"))["directMessages"]
        assertEquals(2, exported.size())
        assertTrue(exported.any { c -> c["messages"].any { it["text"].asString() == "Hello Alice" && it["from"].asString() == "them" } })

        mvc.delete("/api/v1/me") { header("Authorization", "Bearer $aliceToken") }.andExpect { status { isOk() } }

        //  Bob behaelt das Gespraech, ohne Profil auf der anderen Seite, und
        //  kann nicht mehr antworten.
        val row = list(bobToken).single()
        assertEquals("Deleted user", row["with"]["displayName"].asString())
        val handle = row["with"]["handle"]
        assertTrue(handle == null || handle.isNull)
        val view = open(bobToken, talk).andExpect { status { isOk() } }.body()
        assertEquals("unavailable", view["cannotSend"].asString())
        send(bobToken, talk, "Still there?").andExpect { status { isForbidden() } }
    }
}
