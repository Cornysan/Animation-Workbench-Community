package com.playmation.motionlabsbackend

import com.playmation.motionlabsbackend.catalog.Declaration
import com.playmation.motionlabsbackend.web.Seo
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.multipart
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.GZIPOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Was eine Suchmaschine OHNE JavaScript sieht (2026-09-26).
 *
 * Bis hierher sah sie auf "/" ein leeres Gitter, auf einer Clip-Seite eine
 * Ueberschrift in einem unsichtbaren Kasten, und jede Schlagwortseite nannte
 * sich "/". Diese Pruefungen halten fest, dass das so nicht wiederkommt.
 */
@SpringBootTest(properties = ["portal.search-indexing=true", "portal.public-base-url=https://portal.test"])
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SeoPagesTest {

    @Autowired lateinit var mvc: MockMvc

    private val json = JsonMapper.builder().build()

    private fun unique() = UUID.randomUUID().toString().replace("-", "").take(8)

    /** Nur Buchstaben: Schlagworte trennen Ziffern von Buchstaben mit einem Strich (Tags.normalize). */
    private fun letters() = unique().map { if (it.isDigit()) 'g' + (it - '0') else it }.joinToString("")

    private fun login(name: String): String {
        val body = mvc.post("/api/v1/dev/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"$name"}"""
            with(csrf())
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        return json.readTree(body)["token"].asString()
    }

    private fun upload(token: String, title: String, tag: String): String {
        val seed = java.util.concurrent.ThreadLocalRandom.current().nextDouble()
        val doc = """
            {"format":"awclip","version":1,
             "manifest":{"title":"$title","description":"A calm stroll.","tags":["$tag"],"license":"CC0-1.0","rig":"humanoid","frameRate":30,"duration":1},
             "origin":"own",
             "curves":[{"attribute":"Head Nod Down-Up","keys":[[0,$seed,0,0],[1,0.25,0,0]]}],
             "preview":{"frameRate":15,"bones":["Hips","Spine"],"parents":[-1,0],"rest":[[0,1,0],[0,0.1,0]],
                        "hips":[[0,1,0]],"rotations":[[0,0,0,1,0,0,0,1]]}}
        """.trimIndent()
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(doc.toByteArray()) }

        val body = mvc.multipart("/api/v1/packages") {
            file("file", out.toByteArray())
            param("declarationText", Declaration.TEXT)
            param("declarationVersion", Declaration.VERSION.toString())
            param("declarationAccepted", "true")
            header("Authorization", "Bearer $token")
        }.andExpect { status { isCreated() } }.andReturn().response.contentAsString
        return json.readTree(body)["slug"].asString()
    }

    private fun html(path: String) = mvc.get(path).andExpect { status { isOk() } }.andReturn().response.contentAsString

    private fun jsonLdOf(page: String) =
        json.readTree(page.substringAfter("<script type=\"application/ld+json\">").substringBefore("</script>"))

    @Test
    fun `a clip page carries its content, a searchable title and structured data`() {
        val author = "stroller" + unique()
        val tag = "stroll" + letters()
        val slug = upload(login(author), "Evening Stroll", tag)

        val page = html("/clip.html?p=$slug")

        assertTrue("<title>Evening Stroll – Free Animation (FBX, GLB, Unity) | Animation Workbench Community</title>" in page)
        assertTrue("""<div id="clip" class="clip-layout">""" in page, "the clip is not hidden in the delivered HTML")
        assertTrue("""href="/u.html?u=$author"""" in page, "the author is a link")
        assertTrue("""href="/?tag=$tag"""" in page, "the tag leads to its page")
        assertTrue("<dt>Duration</dt>" in page, "the facts are in the HTML")
        assertTrue("A calm stroll. Free humanoid animation under CC0" in page, "description with the pitch")

        val ld = jsonLdOf(page)["@graph"]
        val work = ld[0]
        assertEquals("CreativeWork", work["@type"].asString())
        assertEquals(Seo.CC0, work["license"].asString())
        assertEquals("https://portal.test/clip.html?p=$slug", work["url"].asString())
        assertEquals("BreadcrumbList", ld[1]["@type"].asString())
        assertEquals(3, ld[1]["itemListElement"].size())
    }

    @Test
    fun `the start page lists clips without javascript and names the site`() {
        val slug = upload(login("lister" + unique()), "Listed Walk", "walk")
        val page = html("/")

        assertTrue("""href="/clip.html?p=$slug"""" in page, "the first page of the wall is in the HTML")
        assertTrue("<title>Free Humanoid Animations – FBX, GLB & Unity | Animation Workbench Community</title>"
            .replace("&", "&amp;") in page, page.substringAfter("<title>").substringBefore("</title>"))
        assertEquals("WebSite", jsonLdOf(page)["@graph"][0]["@type"].asString())
    }

    @Test
    fun `a tag is its own page, an empty tag and a search are not indexed`() {
        val tag = "sneak" + letters()
        val token = login("sneaker" + unique())
        upload(token, "Sneak One", tag)
        upload(token, "Sneak Two", tag)

        val page = html("/?tag=$tag&sort=popular")
        assertTrue("""<link rel="canonical" href="https://portal.test/?tag=$tag">""" in page, "the tag page is its own address")
        assertTrue("Animations – FBX, GLB &amp; Unity" in page && "<title>Free Sneak" in page, "the title names the tag")
        assertFalse("noindex" in page)

        val sitemap = html("/sitemap.xml")
        assertTrue("<loc>https://portal.test/?tag=$tag</loc>" in sitemap, "tag pages with two clips are listed")

        assertTrue("""content="noindex, follow"""" in html("/?tag=nothing${letters()}"), "an empty tag page stays out")
        assertTrue("""content="noindex, follow"""" in html("/?q=sneak"), "search results stay out, their clips do not")
    }

    @Test
    fun `a pack page is its own address`() {
        val page = html("/pack.html?k=abcdefgh")
        assertTrue("""<link rel="canonical" href="https://portal.test/pack.html?k=abcdefgh">""" in page,
            "before, every pack called itself /pack.html")
    }

    @Test
    fun `structured data cannot close its script element`() {
        val ld = Seo.jsonLd(Seo.obj("@type" to "CreativeWork", "name" to "</script><script>alert(1)</script> & co"))
        assertFalse("</script>" in ld)
        assertFalse("<" in ld || ">" in ld || "&" in ld)
        assertEquals("</script><script>alert(1)</script> & co", json.readTree(ld)["@graph"][0]["name"].asString())
    }
}
