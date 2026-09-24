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
@SpringBootTest(properties = ["portal.search-indexing=true", "portal.public-base-url=https://portal.test"])
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

    @Test
    fun `the sitemap lists the tabs and the legal pages`() {
        val sitemap = mvc.get("/sitemap.xml").andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        assertTrue("<loc>https://portal.test/</loc>" in sitemap, sitemap)
        assertTrue("<loc>https://portal.test/collections.html</loc>" in sitemap, sitemap)
        assertTrue("<loc>https://portal.test/impressum.html</loc>" in sitemap, sitemap)
    }

    @Test
    fun `a page names its canonical address and is not noindex`() {
        val page = mvc.get("/?tag=walk&sort=popular").andReturn().response.contentAsString
        assertTrue("""<link rel="canonical" href="https://portal.test/">""" in page, "filters are views of the same page")
        assertFalse("noindex" in page, "indexing is on")

        val terms = mvc.get("/terms.html").andReturn().response.contentAsString
        assertTrue("""<link rel="canonical" href="https://portal.test/terms.html">""" in terms,
            "each page its own address, not the start page")
    }
}
