package com.playmation.motionlabsbackend

import com.playmation.motionlabsbackend.requests.ClipRequestChannel
import com.playmation.motionlabsbackend.requests.ClipRequestMessage
import com.playmation.motionlabsbackend.requests.ClipRequestService
import com.playmation.motionlabsbackend.requests.DiscordClipRequestChannel
import com.playmation.motionlabsbackend.showcase.ShowcasePosted
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
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
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * "Ask for it on Discord": ein Wunsch geht sofort hinaus, EINMAL je Suchtext,
 * und verschwindet wieder, wenn er zurueckgenommen wird. Discord selbst
 * ersetzt ein Kanal, der nur mitschreibt.
 */
@SpringBootTest(properties = [
    "portal.requests.discord-webhook-url=https://discord.invalid/api/webhooks/2/test",
    "portal.requests.per-day=3",
])
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(ClipRequestFlowTest.Recording::class)
class ClipRequestFlowTest {

    @TestConfiguration
    class Recording {
        @Bean @Primary
        fun recordingRequestChannel() = RecordingChannel()
    }

    class RecordingChannel : ClipRequestChannel {
        val posted = CopyOnWriteArrayList<ClipRequestMessage>()
        val deleted = CopyOnWriteArrayList<String>()

        override fun post(message: ClipRequestMessage): ShowcasePosted {
            posted += message
            return ShowcasePosted("m" + posted.size, (700000 + posted.size).toString())
        }

        override fun guildId() = GUILD

        override fun delete(messageId: String, threadId: String?): Boolean {
            deleted += messageId
            return true
        }
    }

    companion object {
        const val GUILD = "9876543210"
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var requests: ClipRequestService
    @Autowired lateinit var channel: RecordingChannel

    private val json = JsonMapper.builder().build()

    // ── Hilfen ──────────────────────────────────────────────────────────

    private fun ResultActionsDsl.body(): JsonNode = json.readTree(andReturn().response.contentAsString)

    private fun unique() = UUID.randomUUID().toString().replace("-", "").take(8)

    /** Ein Suchtext, den kein anderer Test benutzt - nur Buchstaben. */
    private fun phrase() = "spin " + unique().map { 'a' + (it.code % 26) }.joinToString("")

    private fun login(name: String): String =
        mvc.post("/api/v1/dev/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"$name"}"""
            with(csrf())
        }.andExpect { status { isOk() } }.body()["token"].asString()

    private fun ask(token: String, phrase: String, named: Boolean = false) =
        mvc.post("/api/v1/requests") {
            contentType = MediaType.APPLICATION_JSON
            content = json.writeValueAsString(mapOf("phrase" to phrase, "named" to named))
            header("Authorization", "Bearer $token")
        }

    /** Null, wenn es keinen offenen Wunsch gibt. */
    private fun find(token: String, phrase: String): JsonNode? =
        mvc.get("/api/v1/requests") {
            param("q", phrase)
            header("Authorization", "Bearer $token")
        }.andExpect { status { isOk() } }.body()["request"]?.takeUnless { it.isNull }

    private fun postsTitled(phrase: String) = channel.posted.filter { it.title == "Looking for: $phrase" }

    // ── Tests ───────────────────────────────────────────────────────────

    @Test
    fun `the status tells the workbench that requests exist`() {
        val status = mvc.get("/api/v1/status").andExpect { status { isOk() } }.body()
        assertTrue(status["clipRequests"].asBoolean())
    }

    @Test
    fun `a request is posted at once and comes back with its thread`() {
        val token = login("asker-" + unique())
        val phrase = phrase()

        val result = ask(token, phrase).andExpect { status { isCreated() } }.body()
        assertFalse(result["existing"].asBoolean())
        val request = result["request"]
        assertTrue(request["mine"].asBoolean())
        assertTrue(request["discordUrl"].asString().startsWith("https://discord.com/channels/$GUILD/"))

        val posts = postsTitled(phrase)
        assertEquals(1, posts.size)
        assertEquals("Looking for: $phrase", posts[0].threadName)
        assertTrue(posts[0].url.endsWith("/?q=" + phrase.replace(" ", "+")))
        assertNull(posts[0].authorName, "without the tick the post carries no name")
    }

    @Test
    fun `the same search asks nobody twice`() {
        val first = login("first-" + unique())
        val second = login("second-" + unique())
        val phrase = phrase()

        val url = ask(first, phrase).andExpect { status { isCreated() } }.body()["request"]["discordUrl"].asString()

        //  Anders geschrieben, derselbe Wunsch.
        val again = ask(second, "  " + phrase.uppercase() + " ").andExpect { status { isOk() } }.body()
        assertTrue(again["existing"].asBoolean())
        assertFalse(again["request"]["mine"].asBoolean())
        assertEquals(url, again["request"]["discordUrl"].asString())
        assertEquals(1, postsTitled(phrase).size)

        assertTrue(find(first, phrase)!!["mine"].asBoolean())
        assertFalse(find(second, phrase.uppercase())!!["mine"].asBoolean())
        assertNull(find(second, phrase()), "nobody asked for that")
    }

    @Test
    fun `the tick puts the name on the post`() {
        val name = "named-" + unique()
        val token = login(name)
        val phrase = phrase()

        ask(token, phrase, named = true).andExpect { status { isCreated() } }
        assertEquals(name, postsTitled(phrase).single().authorName)
    }

    @Test
    fun `underscores read as spaces`() {
        val token = login("under-" + unique())
        val word = phrase().removePrefix("spin ")
        val request = ask(token, "walk_$word").andExpect { status { isCreated() } }.body()["request"]
        assertEquals("walk $word", request["phrase"].asString())
    }

    @Test
    fun `links, markdown and mentions do not get in`() {
        val token = login("sneaky-" + unique())
        for (bad in listOf("https://evil.example", "[click](here)", "**bold** walk", "<@123> walk", "@everyone", "x", "1234"))
            ask(token, bad).andExpect { status { isBadRequest() } }
        assertTrue(channel.posted.none { it.title.contains("evil") || it.title.contains("everyone") })
    }

    @Test
    fun `three requests a day`() {
        val token = login("eager-" + unique())
        repeat(3) { ask(token, phrase()).andExpect { status { isCreated() } } }
        ask(token, phrase()).andExpect { status { isTooManyRequests() } }

        //  Was schon gewuenscht ist, zaehlt nicht - es wird ja nichts gepostet.
        val other = login("other-" + unique())
        val taken = phrase()
        ask(other, taken).andExpect { status { isCreated() } }
        ask(token, taken).andExpect { status { isOk() } }
    }

    @Test
    fun `taking it back deletes the post and frees the search`() {
        val token = login("regret-" + unique())
        val stranger = login("stranger-" + unique())
        val phrase = phrase()

        val id = ask(token, phrase).andExpect { status { isCreated() } }.body()["request"]["id"].asString()
        mvc.delete("/api/v1/requests/$id") { header("Authorization", "Bearer $stranger") }
            .andExpect { status { isNotFound() } }

        mvc.delete("/api/v1/requests/$id") { header("Authorization", "Bearer $token") }
            .andExpect { status { isOk() } }
        assertNull(find(token, phrase), "a request taken back is no longer open")

        val messageId = "m" + (channel.posted.indexOfFirst { it.title == "Looking for: $phrase" } + 1)
        requests.runOnce(Instant.now())
        requests.runOnce(Instant.now())
        assertEquals(1, channel.deleted.count { it == messageId }, "deleted once, not on every tick")

        //  Danach darf jemand anders neu fragen.
        ask(stranger, phrase).andExpect { status { isCreated() } }
        assertEquals(2, postsTitled(phrase).size)
    }

    @Test
    fun `the post pings nobody and carries no forum tags unless configured`() {
        val body = DiscordClipRequestChannel(com.playmation.motionlabsbackend.config.PortalProperties())
            .payload(ClipRequestMessage("Looking for: cartwheel", "Looking for: cartwheel", "https://x/?q=cartwheel",
                "text", null, null, null), asThread = true)
        val node = json.readTree(body)
        assertEquals(0, node["allowed_mentions"]["parse"].size())
        assertEquals("Looking for: cartwheel", node["thread_name"].asString())
        assertTrue(node["embeds"][0]["author"] == null || node["embeds"][0]["author"].isNull)
        assertTrue(node["applied_tags"] == null || node["applied_tags"].isNull)
    }
}
