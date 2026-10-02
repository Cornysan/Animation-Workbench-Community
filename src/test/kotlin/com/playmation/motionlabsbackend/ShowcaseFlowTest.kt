package com.playmation.motionlabsbackend

import com.playmation.motionlabsbackend.catalog.Declaration
import com.playmation.motionlabsbackend.showcase.DiscordShowcaseChannel
import com.playmation.motionlabsbackend.showcase.ShowcaseChannel
import com.playmation.motionlabsbackend.showcase.ShowcaseMessage
import com.playmation.motionlabsbackend.showcase.ShowcasePosted
import com.playmation.motionlabsbackend.showcase.ShowcaseService
import com.playmation.motionlabsbackend.showcase.threadName
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
import org.springframework.test.web.servlet.multipart
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.GZIPOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Das Discord-Schaufenster: ein angekreuzter Clip wartet auf sein Bild,
 * geht dann EINMAL hinaus und verschwindet wieder, wenn der Clip geht.
 * Discord selbst ersetzt ein Kanal, der nur mitschreibt.
 */
@SpringBootTest(properties = [
    "portal.showcase.discord-webhook-url=https://discord.invalid/api/webhooks/1/test",
    "portal.showcase.wait-for-card=PT10M",
])
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(ShowcaseFlowTest.Recording::class)
class ShowcaseFlowTest {

    @TestConfiguration
    class Recording {
        @Bean @Primary
        fun recordingChannel() = RecordingChannel()
    }

    class RecordingChannel : ShowcaseChannel {
        val posted = CopyOnWriteArrayList<ShowcaseMessage>()
        val deleted = CopyOnWriteArrayList<String>()

        override fun post(message: ShowcaseMessage): ShowcasePosted {
            posted += message
            return ShowcasePosted("m" + posted.size + "-" + message.title.hashCode(), "t" + posted.size)
        }

        override fun delete(messageId: String, threadId: String?): Boolean {
            deleted += messageId
            return true
        }
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var showcase: ShowcaseService
    @Autowired lateinit var channel: RecordingChannel

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

    private fun awclip(title: String, license: String, tags: String): ByteArray {
        val seed = java.util.concurrent.ThreadLocalRandom.current().nextDouble()
        val doc = """
            {"format":"awclip","version":1,
             "manifest":{"title":"$title","description":"[click me](https://evil.example)","tags":[$tags],
                         "license":"$license","rig":"humanoid","frameRate":30,"duration":2.5},
             "origin":"own",
             "curves":[{"attribute":"Head Nod Down-Up","keys":[[0,$seed,0,0],[1,0.25,0,0]]}],
             "preview":{"frameRate":15,"bones":["Hips","Spine"],"parents":[-1,0],"rest":[[0,1,0],[0,0.1,0]],
                        "hips":[[0,1,0]],"rotations":[[0,0,0,1,0,0,0,1]]}}
        """.trimIndent()
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(doc.toByteArray()) }
        return out.toByteArray()
    }

    private fun upload(token: String, title: String, announce: Boolean, license: String = "CC0-1.0",
                       notify: Boolean = true): String =
        mvc.multipart("/api/v1/packages") {
            file("file", awclip(title, license, "\"sneak\", \"walk\""))
            param("declarationText", Declaration.TEXT)
            param("declarationVersion", Declaration.VERSION.toString())
            param("declarationAccepted", "true")
            param("notifyFollowers", notify.toString())
            param("announce", announce.toString())
            header("Authorization", "Bearer $token")
        }.andExpect { status { isCreated() } }.body()["slug"].asString()

    private fun later() = Instant.now().plus(Duration.ofMinutes(11))

    private fun postsTitled(title: String) = channel.posted.filter { it.title == title }

    // ── Tests ───────────────────────────────────────────────────────────

    @Test
    fun `the status tells the workbench that the showcase exists`() {
        val status = mvc.get("/api/v1/status").andExpect { status { isOk() } }.body()
        assertTrue(status["discordShowcase"].asBoolean())
    }

    /**
     * Der Weg, fuer den es das gibt: angekreuzt, geteilt, gepostet - aber
     * erst, wenn das Bild da ist oder die Wartezeit um. Und genau einmal.
     */
    @Test
    fun `a ticked clip waits for its picture, then goes out once without the description`() {
        val name = "sneaker" + unique()
        val token = login(name)
        val title = "Sneaky Walk " + unique()
        upload(token, title, announce = true)

        showcase.runOnce(Instant.now())
        assertTrue(postsTitled(title).isEmpty(), "no picture yet and the wait is not over")

        showcase.runOnce(later())
        val post = postsTitled(title).single()
        assertEquals("$title by $name", post.threadName)
        assertTrue(post.url.contains("/clip.html?p="))
        assertEquals("#sneak #walk  ·  2.50 s", post.footer)
        assertFalse(post.description.contains("evil.example"), "the clip's own description is never posted")
        assertEquals(name, post.authorName)

        showcase.runOnce(later())
        assertEquals(1, postsTitled(title).size, "posted once, not on every tick")
    }

    @Test
    fun `an unticked or private clip never reaches discord`() {
        val token = login("quiet" + unique())
        val unticked = "Quiet Wave " + unique()
        val private = "Secret Bow " + unique()
        upload(token, unticked, announce = false)
        upload(token, private, announce = true, license = "ARR")

        showcase.runOnce(later())
        assertTrue(postsTitled(unticked).isEmpty())
        assertTrue(postsTitled(private).isEmpty())
    }

    @Test
    fun `a clip withdrawn before its post is never posted, and one withdrawn after is deleted again`() {
        val token = login("fickle" + unique())
        val early = "Early Exit " + unique()
        val late = "Late Exit " + unique()
        val earlySlug = upload(token, early, announce = true)
        val lateSlug = upload(token, late, announce = true)

        mvc.delete("/api/v1/packages/$earlySlug") { header("Authorization", "Bearer $token") }
            .andExpect { status { isOk() } }
        showcase.runOnce(later())
        assertTrue(postsTitled(early).isEmpty(), "withdrawn while it waited")
        assertEquals(1, postsTitled(late).size)

        val before = channel.deleted.size
        mvc.delete("/api/v1/packages/$lateSlug") { header("Authorization", "Bearer $token") }
            .andExpect { status { isOk() } }
        showcase.runOnce(later())
        assertEquals(before + 1, channel.deleted.size, "the post goes when the clip goes")

        showcase.runOnce(later())
        assertEquals(before + 1, channel.deleted.size, "and only once")
    }

    @Test
    fun `a ticked pack is one post for all its clips`() {
        val name = "packer" + unique()
        val token = login(name)
        val word = unique()
        val clips = listOf(
            upload(token, "Bow Draw $word", announce = false, notify = false),
            upload(token, "Bow Release $word", announce = false, notify = false),
        )
        val title = "Longbow Pack $word"
        mvc.post("/api/v1/packs") {
            contentType = MediaType.APPLICATION_JSON
            content = json.writeValueAsString(mapOf("title" to title, "description" to "", "clips" to clips,
                "announce" to true))
            header("Authorization", "Bearer $token")
        }.andExpect { status { isCreated() } }

        showcase.runOnce(later())
        val post = postsTitled(title).single()
        assertEquals("$title (2 clips) by $name", post.threadName)
        assertTrue(post.url.contains("/pack.html?k="))
        assertTrue(post.footer.startsWith("2 clips"))
        assertTrue(channel.posted.none { it.title.startsWith("Bow Draw $word") }, "the clips do not post on their own")
    }

    // ── Was an Discord geht ─────────────────────────────────────────────

    @Test
    fun `a long title is shortened, the name behind it is kept`() {
        val name = threadName("A".repeat(150), null, "Leo")
        assertEquals(100, name.length)
        assertTrue(name.endsWith("… by Leo"))
    }

    @Test
    fun `the payload pings nobody and drops the thread fields for a text channel`() {
        val properties = com.playmation.motionlabsbackend.config.PortalProperties(
            showcase = com.playmation.motionlabsbackend.config.PortalProperties.Showcase(
                discordWebhookUrl = "https://discord.invalid/api/webhooks/1/test", forumTags = "11, 22"))
        val discord = DiscordShowcaseChannel(properties)
        val message = ShowcaseMessage("@everyone by Leo", "@everyone", "https://x/clip.html?p=a", "Get it.",
            "Leo", null, null, null, "#walk")

        val forum = json.readTree(discord.payload(message, asThread = true))
        assertEquals("@everyone by Leo", forum["thread_name"].asString())
        assertEquals(listOf("11", "22"), forum["applied_tags"].map { it.asString() })
        assertEquals(0, forum["allowed_mentions"]["parse"].size())
        assertTrue(forum["embeds"][0]["image"] == null, "no image, no image field")

        val text = json.readTree(discord.payload(message, asThread = false))
        assertNull(text["thread_name"])
        assertNull(text["applied_tags"])
        assertEquals(0, text["allowed_mentions"]["parse"].size())
    }
}
