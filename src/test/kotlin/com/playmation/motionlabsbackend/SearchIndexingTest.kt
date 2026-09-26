package com.playmation.motionlabsbackend

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Mit eingeschalteter Indexierung - so, wie das Portal seit dem oeffentlichen
 * Start laeuft. Der Normalfall der uebrigen Tests ist "aus".
 *
 * Geprueft wird, dass die drei Auskuenfte an Suchmaschinen zusammenpassen:
 * `robots.txt` gibt frei und nennt die Sitemap, die Sitemap antwortet, und
 * jede Seite traegt ihre kanonische Adresse statt `noindex`.
 */
@SpringBootTest(properties = ["portal.search-indexing=true", "portal.public-base-url=https://portal.test",
    "portal.dev-login=false"])
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SearchIndexingTest {

    @Autowired lateinit var mvc: MockMvc

    @Test
    fun `robots points to the sitemap and keeps private pages out`() {
        val robots = mvc.get("/robots.txt").andReturn().response.contentAsString
        assertTrue("Sitemap: https://portal.test/sitemap.xml" in robots, robots)
        assertTrue("Disallow: /avatar/" in robots, "profile pictures stay out of image search")
        assertFalse("Disallow: /\n" in robots, "the whole site is not blocked")
        assertFalse("Disallow: /api/" in robots, "pages load their content from /api/ - blocked, a crawler sees them empty")
    }

    /** Wie in Produktion: ohne Entwickler-Login gibt es weder die Schnittstelle noch die Seite dazu. */
    @Test
    fun `without developer sign-in its page is gone too`() {
        mvc.get("/dev.html").andExpect { status { isNotFound() } }
        mvc.get("/api/v1/dev/login").andExpect { status { isNotFound() } }
    }

    @Test
    fun `the sitemap lists the catalog and the legal pages`() {
        val sitemap = mvc.get("/sitemap.xml").andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        assertTrue("<loc>https://portal.test/</loc>" in sitemap, sitemap)
        assertFalse("<loc>https://portal.test/collections.html</loc>" in sitemap, "without an account the page only asks to sign in")
        assertTrue("<loc>https://portal.test/impressum.html</loc>" in sitemap, sitemap)
    }

    @Test
    fun `a page names its canonical address and is not noindex`() {
        val page = mvc.get("/?sort=popular").andReturn().response.contentAsString
        assertTrue("""<link rel="canonical" href="https://portal.test/">""" in page, "a sort order is a view of the same page")
        assertFalse("noindex" in page, "indexing is on")

        val terms = mvc.get("/terms.html").andReturn().response.contentAsString
        assertTrue("""<link rel="canonical" href="https://portal.test/terms.html">""" in terms,
            "each page its own address, not the start page")
    }
}
