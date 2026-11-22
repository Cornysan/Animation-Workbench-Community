package com.playmation.motionlabsbackend

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import kotlin.test.assertEquals

/**
 * Die alten Adressen mit `.html` (bis 2026-11-22) leiten DAUERHAFT weiter -
 * jede ausgelieferte Workbench, jeder Discord-Post und jede alte
 * Benachrichtigung zeigt noch dorthin. Faellt hier eine weg, ist das ein
 * toter Link bei Kunden, nicht ein roter Test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LegacyPageAddressesTest {

    @Autowired lateinit var mvc: MockMvc

    private fun movedTo(old: String): String {
        val response = mvc.get(old).andReturn().response
        assertEquals(301, response.status, "$old moves permanently")
        return response.getHeader("Location")!!
    }

    @Test
    fun `every old page address moves permanently to its new one`() {
        val moves = mapOf(
            "/index.html" to "/",
            "/browse.html" to "/",
            "/collections.html" to "/collections",
            "/characters.html" to "/characters",
            "/share.html" to "/share",
            "/upload.html" to "/upload",
            "/me.html" to "/account",
            "/messages.html" to "/messages",
            "/signin.html" to "/signin",
            "/dev.html" to "/dev",
            "/link.html" to "/link",
            "/admin.html" to "/admin",
            "/admin-stats.html" to "/admin/stats",
            "/licenses.html" to "/licenses",
            "/rules.html" to "/rules",
            "/terms.html" to "/terms",
            "/privacy.html" to "/privacy",
            "/impressum.html" to "/impressum",
            "/takedown.html" to "/takedown",
        )
        for ((old, new) in moves) assertEquals(new, movedTo(old), old)
    }

    /** Was die Workbench oeffnet: der Schluessel wandert in den Pfad. */
    @Test
    fun `the key moves into the path`() {
        assertEquals("/clip/abc123", movedTo("/clip.html?p=abc123"))
        assertEquals("/pack/abcdefgh", movedTo("/pack.html?k=abcdefgh"))
        assertEquals("/collection/xyz", movedTo("/collection.html?c=xyz"))
        assertEquals("/u/cornysan", movedTo("/u.html?u=cornysan"))
    }

    /** Der Rest der Abfrage zieht mit - "Edit on the portal", ein Reiter, ein Filter. */
    @Test
    fun `the rest of the query comes along`() {
        assertEquals("/clip/abc123?edit=1", movedTo("/clip.html?p=abc123&edit=1"))
        assertEquals("/clip/abc123?edit=1", movedTo("/clip.html?edit=1&p=abc123"))
        assertEquals("/u/cornysan?tab=collections", movedTo("/u.html?u=cornysan&tab=collections"))
        assertEquals("/?tag=walk&sort=popular", movedTo("/browse.html?tag=walk&sort=popular"))
        assertEquals("/account?connected=google", movedTo("/me.html?connected=google"))
        assertEquals("/messages?to=leo", movedTo("/messages.html?to=leo"))
        assertEquals("/link?code=ABCD", movedTo("/link.html?code=ABCD"))
    }

    /** Ohne Schluessel gab es nichts zu sehen - dann die naechste sinnvolle Seite. */
    @Test
    fun `without a key the way leads somewhere sensible`() {
        assertEquals("/", movedTo("/clip.html"))
        assertEquals("/", movedTo("/clip.html?p="))
        assertEquals("/", movedTo("/pack.html"))
        assertEquals("/", movedTo("/u.html"))
        assertEquals("/collections", movedTo("/collection.html"))
    }

    /** Ein Schluessel bleibt EIN Pfadstueck - aus der Abfrage wird kein anderer Weg. */
    @Test
    fun `a key never becomes a second path segment`() {
        assertEquals("/clip/a%2Fb", movedTo("/clip.html?p=a/b"))
        assertEquals("/u/%2F%2Fevil.example", movedTo("/u.html?u=//evil.example"))
    }

    /**
     * `/me.html` leitet ohne Konto erst weiter und schickt nicht selbst zur
     * Anmeldung: die Sperre sitzt an der neuen Adresse, und nur dort merkt
     * sich Spring den Rueckweg.
     */
    @Test
    fun `signed-in pages move first and ask for an account at the new address`() {
        assertEquals("/account", movedTo("/me.html"))
        val target = mvc.get("/account").andReturn().response.redirectedUrl!!
        assertEquals(true, target.endsWith("/signin"), target)
    }
}
