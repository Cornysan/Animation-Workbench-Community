package com.playmation.motionlabsbackend.showcase

import com.playmation.motionlabsbackend.account.AccountRepository
import com.playmation.motionlabsbackend.account.avatarPath
import com.playmation.motionlabsbackend.auth.PortalPrincipal
import com.playmation.motionlabsbackend.catalog.AnimationPackage
import com.playmation.motionlabsbackend.catalog.AnimationPackageRepository
import com.playmation.motionlabsbackend.catalog.ClipPackRepository
import com.playmation.motionlabsbackend.catalog.PackageStatus
import com.playmation.motionlabsbackend.catalog.PackageVersionRepository
import com.playmation.motionlabsbackend.config.PortalProperties
import com.playmation.motionlabsbackend.format.AwclipSchema
import com.playmation.motionlabsbackend.notification.NotificationLinks
import com.playmation.motionlabsbackend.web.PreviewCards
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.UUID

/**
 * Neue Clips im Discord-Schaufenster - nur, wenn der Ersteller es beim
 * Teilen angekreuzt hat.
 *
 * WARUM ES DAS GIBT: Wer teilt, will gesehen werden, und der Discord ist der
 * Ort, an dem die Leute sind. Ein Post je Clip gibt dem Ersteller Publikum
 * und dem Server etwas, worueber geredet wird.
 *
 * WARUM NICHT SOFORT: Das Vorschaubild rendert der Browser des Besitzers
 * ([PreviewCards]), und ein Clip aus Unity hat beim Hochladen noch keins. Ein
 * Post ohne Bild ist in einem Forum fast unsichtbar. Darum ist das Ankreuzen
 * ein Auftrag, und ein Takt postet, sobald das Bild da ist - oder nach
 * [PortalProperties.Showcase.waitForCard] mit dem Bild der Seite.
 *
 * WAS NICHT IM POST STEHT: die Beschreibung. Sie ist fremder Text, und Discord
 * macht in einem Embed aus `[hier](...)` einen Link - der Webhook des Portals
 * wuerde ihn mit seinem Namen posten. Titel, Name und Thread-Titel rendert
 * Discord als blossen Text, Schlagworte sind normalisiert.
 *
 * WAS VERSCHWINDET: Wird ein Clip zurueckgezogen, privat, von der Moderation
 * versteckt, oder hat ein Pack keinen sichtbaren Clip mehr, loescht derselbe
 * Takt den Post wieder ([ShowcasePostRepository.findGone]). Der Thread selbst
 * bleibt stehen - ein Webhook darf keine Threads loeschen.
 */
@Service
class ShowcaseService(
    private val posts: ShowcasePostRepository,
    private val packages: AnimationPackageRepository,
    private val packs: ClipPackRepository,
    private val versions: PackageVersionRepository,
    private val accounts: AccountRepository,
    private val cards: PreviewCards,
    private val channel: ShowcaseChannel,
    /** Die Thread-Adressen brauchen die Guild - der Takt holt sie ([ShowcaseLinks.refreshGuild]). */
    private val links: ShowcaseLinks,
    private val properties: PortalProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /** Danach gilt ein Post als gescheitert und wird nicht mehr versucht. */
        const val MAX_ATTEMPTS = 5

        /** Mehr als das je Takt nicht - ein Webhook darf etwa 30 je Minute. */
        const val PER_TICK = 10

        const val OUTCOME_NOT_PUBLIC = "not-public"
        const val OUTCOME_GONE = "gone"
        const val OUTCOME_FAILED = "failed"
        const val OUTCOME_RETRACT_FAILED = "retract-failed"
    }

    val enabled get() = properties.showcase.discordWebhookUrl.isNotBlank()

    private val base get() = properties.publicBaseUrl.trimEnd('/')

    // ════════════════════════════════════════════════════════════════════
    // AUFTRAEGE
    // ════════════════════════════════════════════════════════════════════

    /**
     * Ein gerade geteilter Clip soll ins Schaufenster. Still ohne Wirkung,
     * wenn es keins gibt, der Clip privat ist oder schon einmal angemeldet
     * wurde - das Haekchen ist ein Wunsch, kein Grund, den Upload scheitern
     * zu lassen.
     */
    fun requestClip(slug: String, principal: PortalPrincipal) {
        if (!enabled) return
        val pkg = packages.findBySlug(slug) ?: return
        if (pkg.ownerId != principal.accountId && !principal.isAdmin) return
        if (!visible(pkg) || posts.existsByPackageId(pkg.id)) return
        posts.save(ShowcasePost(packageId = pkg.id, accountId = principal.accountId, requestedAt = clock.instant()))
    }

    /** Dasselbe fuer einen Pack - EIN Post fuer alle seine Clips. */
    fun requestPack(slug: String, principal: PortalPrincipal) {
        if (!enabled) return
        val pack = packs.findBySlug(slug) ?: return
        if (pack.ownerId != principal.accountId && !principal.isAdmin) return
        if (cover(pack.id) == null || posts.existsByPackId(pack.id)) return
        posts.save(ShowcasePost(packId = pack.id, accountId = principal.accountId, requestedAt = clock.instant()))
    }

    // ════════════════════════════════════════════════════════════════════
    // DER TAKT
    // ════════════════════════════════════════════════════════════════════

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    fun tick() {
        if (!enabled) return
        try {
            runOnce(clock.instant())
        } catch (ex: Exception) {
            log.warn("Showcase tick failed: {}", ex.message)
        }
    }

    /**
     * Ein Durchgang: faellige Auftraege posten, verschwundene Ziele abraeumen.
     * Ohne Transaktion um das Ganze - jeder Aufruf an Discord darf zehn
     * Sekunden dauern, und so lange soll keine Datenbankverbindung warten.
     */
    fun runOnce(now: Instant) {
        postDue(now)
        retractGone(now)
        links.refreshGuild()
    }

    private fun postDue(now: Instant) {
        var sent = 0
        for (post in posts.findByPostedAtIsNullAndOutcomeIsNullOrderByRequestedAtAsc()) {
            if (sent >= PER_TICK) return

            val message = when (val target = target(post)) {
                is Target.Gone -> { finish(post, OUTCOME_GONE); continue }
                is Target.Hidden -> { finish(post, OUTCOME_NOT_PUBLIC); continue }
                is Target.Ready -> target
            }

            val ownImage = cards.ownImage(message.coverSlug)
            val waited = Duration.between(post.requestedAt, now) >= properties.showcase.waitForCard
            if (ownImage == null && !waited) continue

            sent++
            val result = channel.post(message.toMessage(ownImage ?: cards.siteImage()))
            if (result != null) {
                post.postedAt = now
                post.messageId = result.messageId
                post.threadId = result.threadId
            } else {
                post.attempts++
                if (post.attempts >= MAX_ATTEMPTS) post.outcome = OUTCOME_FAILED
            }
            posts.save(post)
        }
    }

    private fun retractGone(now: Instant) {
        for (post in posts.findGone(PackageStatus.PUBLISHED, AwclipSchema.LICENSE_PUBLIC)) {
            val messageId = post.messageId
            if (messageId == null || channel.delete(messageId, post.threadId)) {
                post.retractedAt = now
            } else {
                post.attempts++
                if (post.attempts >= MAX_ATTEMPTS * 2) {
                    post.retractedAt = now
                    post.outcome = OUTCOME_RETRACT_FAILED
                }
            }
            posts.save(post)
        }
    }

    private fun finish(post: ShowcasePost, outcome: String) {
        post.outcome = outcome
        posts.save(post)
    }

    // ════════════════════════════════════════════════════════════════════
    // WAS GEPOSTET WIRD
    // ════════════════════════════════════════════════════════════════════

    private sealed interface Target {
        data object Gone : Target
        data object Hidden : Target

        data class Ready(
            val title: String,
            val link: String,
            val coverSlug: String,
            val authorName: String,
            val authorLink: String?,
            val authorAvatar: String?,
            val details: String,
            val clipCount: Int?,
        ) : Target {
            fun toMessage(image: String?) = ShowcaseMessage(
                threadName = threadName(title, clipCount, authorName),
                title = title,
                url = link,
                description = if (clipCount == null)
                    "Get it in the Animation Workbench under Community, or open it on the portal."
                else
                    "Get all $clipCount clips in the Animation Workbench under Community, or open the pack on the portal.",
                authorName = authorName,
                authorUrl = authorLink,
                authorIcon = authorAvatar,
                imageUrl = image,
                footer = details,
            )
        }
    }

    private fun target(post: ShowcasePost): Target {
        post.packageId?.let { id ->
            val pkg = packages.findById(id).orElse(null) ?: return Target.Gone
            if (!visible(pkg)) return Target.Hidden
            val version = pkg.currentVersionId?.let { versions.findById(it).orElse(null) }
            val details = listOfNotNull(
                pkg.tagList().takeIf { it.isNotEmpty() }?.joinToString(" ") { "#$it" },
                version?.let { duration(it.durationSeconds) },
            ).joinToString("  ·  ")
            return ready(pkg.ownerId, pkg.title, base + NotificationLinks.clip(pkg.slug), pkg.slug, details, null)
        }

        post.packId?.let { id ->
            val pack = packs.findById(id).orElse(null) ?: return Target.Gone
            val clips = packages.findByPackIdOrderByPackPositionAsc(pack.id).filter { visible(it) }
            val cover = clips.firstOrNull() ?: return Target.Hidden
            val tags = clips.flatMap { it.tagList() }.groupingBy { it }.eachCount()
                .entries.sortedByDescending { it.value }.take(5).map { it.key }
            val details = listOfNotNull(
                "${clips.size} clips",
                tags.takeIf { it.isNotEmpty() }?.joinToString(" ") { "#$it" },
            ).joinToString("  ·  ")
            return ready(pack.ownerId, pack.title, base + NotificationLinks.pack(pack.slug), cover.slug, details, clips.size)
        }

        return Target.Gone
    }

    private fun ready(ownerId: UUID, title: String, link: String, coverSlug: String,
                      details: String, clipCount: Int?): Target {
        val owner = accounts.findById(ownerId).orElse(null) ?: return Target.Gone
        return Target.Ready(
            title = title,
            link = link,
            coverSlug = coverSlug,
            authorName = owner.displayName,
            authorLink = owner.handle?.let { "$base/u.html?u=" + URLEncoder.encode(it, Charsets.UTF_8) },
            authorAvatar = owner.avatarPath()?.let { base + it },
            details = details,
            clipCount = clipCount,
        )
    }

    private fun cover(packId: UUID) =
        packages.findByPackIdOrderByPackPositionAsc(packId).firstOrNull { visible(it) }

    private fun visible(pkg: AnimationPackage) =
        pkg.status == PackageStatus.PUBLISHED && pkg.license == AwclipSchema.LICENSE_PUBLIC
}

/** Wie die Seite eine Dauer schreibt (app.js): unter 10 s zwei Stellen, sonst eine. */
internal fun duration(seconds: Float): String =
    if (seconds < 10f) String.format(Locale.ROOT, "%.2f s", seconds) else String.format(Locale.ROOT, "%.1f s", seconds)

/**
 * Der Titel des Forum-Threads, hoechstens 100 Zeichen. Gekuerzt wird der
 * Clip-Titel, nicht der Name dahinter - wer etwas teilt, soll im Forum
 * stehen.
 */
internal fun threadName(title: String, clipCount: Int?, author: String): String {
    val tail = (if (clipCount != null) " ($clipCount clips)" else "") + " by " + author.take(32)
    val room = (100 - tail.length).coerceAtLeast(10)
    val head = if (title.length <= room) title else title.take(room - 1).trimEnd() + "…"
    return (head + tail).take(100)
}

/** Ein Post, wie er an Discord geht - ohne zu wissen, ob Forum oder Textkanal. */
data class ShowcaseMessage(
    val threadName: String,
    val title: String,
    val url: String,
    val description: String,
    val authorName: String,
    val authorUrl: String?,
    val authorIcon: String?,
    val imageUrl: String?,
    val footer: String,
)

/** Was Discord zum Post zurueckgab. [threadId] nur bei einem Forum-Thread. */
data class ShowcasePosted(val messageId: String, val threadId: String?)

/** Der Kanal, in den gepostet wird - eine Naht, damit die Tests ohne Discord laufen. */
interface ShowcaseChannel {
    /** Null = nicht gepostet, beim naechsten Takt noch einmal. */
    fun post(message: ShowcaseMessage): ShowcasePosted?

    /** true = weg (auch: war schon weg). */
    fun delete(messageId: String, threadId: String?): Boolean

    /**
     * Der Discord-Server, in dem gepostet wird - fuer die Adresse eines
     * Threads ([ShowcaseLinks]). Null = unbekannt.
     */
    fun guildId(): String? = null
}

/**
 * Discord ueber einen Webhook. Zeigt er auf einen Forum-Kanal, wird jeder
 * Post ein eigener Thread (`thread_name`); zeigt er auf einen Textkanal,
 * antwortet Discord darauf mit Code 220003, und der Post geht ohne
 * Thread-Felder noch einmal hinaus. Derselbe Weg wie beim Feedback-Dienst.
 */
@Component
class DiscordShowcaseChannel(private val properties: PortalProperties) : ShowcaseChannel {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val json = JsonMapper.builder().build()

    private val webhook get() = properties.showcase.discordWebhookUrl.trim().substringBefore('?')

    override fun post(message: ShowcaseMessage): ShowcasePosted? = try {
        val forum = send(payload(message, asThread = true))
        when {
            forum.statusCode() in 200..299 -> posted(forum.body(), asThread = true)
            forum.statusCode() == 400 && discordCode(forum.body()) == 220003 -> {
                val plain = send(payload(message, asThread = false))
                if (plain.statusCode() in 200..299) posted(plain.body(), asThread = false)
                else null.also { log.warn("Showcase post failed: HTTP {} {}", plain.statusCode(), plain.body().take(300)) }
            }
            else -> null.also { log.warn("Showcase post failed: HTTP {} {}", forum.statusCode(), forum.body().take(300)) }
        }
    } catch (ex: Exception) {
        log.warn("Showcase post failed: {}", ex.message)
        null
    }

    override fun delete(messageId: String, threadId: String?): Boolean = try {
        val url = "$webhook/messages/$messageId" + (threadId?.let { "?thread_id=$it" } ?: "")
        val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).DELETE().build()
        val status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
        //  404: schon von Hand geloescht - auch gut.
        (status in 200..299 || status == 404).also { if (!it) log.warn("Showcase delete failed: HTTP {}", status) }
    } catch (ex: Exception) {
        log.warn("Showcase delete failed: {}", ex.message)
        false
    }

    /**
     * Ein GET auf den Webhook selbst liefert ihn als Objekt, samt `guild_id`.
     * Kostet nichts, darf aber dauern - deshalb nur aus dem Takt.
     */
    override fun guildId(): String? = try {
        val request = HttpRequest.newBuilder(URI.create(webhook)).timeout(Duration.ofSeconds(10)).GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() in 200..299) json.readTree(response.body()).path("guild_id").asString("").ifEmpty { null }
        else null.also { log.warn("Showcase: reading the webhook failed: HTTP {}", response.statusCode()) }
    } catch (ex: Exception) {
        log.warn("Showcase: reading the webhook failed: {}", ex.message)
        null
    }

    private fun send(body: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("$webhook?wait=true"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun posted(body: String, asThread: Boolean): ShowcasePosted? {
        val node = json.readTree(body)
        val id = node.path("id").asString("").ifEmpty { return null }
        return ShowcasePosted(id, if (asThread) node.path("channel_id").asString("").ifEmpty { null } else null)
    }

    private fun discordCode(body: String): Int? = try {
        json.readTree(body).path("code").takeIf { it.isNumber }?.asInt()
    } catch (ex: Exception) {
        null
    }

    internal fun payload(message: ShowcaseMessage, asThread: Boolean): String {
        val embed = linkedMapOf<String, Any>(
            "title" to message.title.take(256),
            "url" to message.url,
            "description" to message.description,
            "author" to linkedMapOf<String, Any>("name" to message.authorName.take(256)).apply {
                message.authorUrl?.let { put("url", it) }
                message.authorIcon?.let { put("icon_url", it) }
            },
            "footer" to mapOf("text" to message.footer.take(2048)),
        )
        message.imageUrl?.let { embed["image"] = mapOf("url" to it) }

        val body = linkedMapOf<String, Any>(
            "embeds" to listOf(embed),
            //  Niemand wird angepingt - auch nicht durch einen Titel, der
            //  "@everyone" heisst.
            "allowed_mentions" to mapOf("parse" to emptyList<String>()),
        )
        if (asThread) {
            body["thread_name"] = message.threadName
            val tags = properties.showcase.forumTags.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (tags.isNotEmpty()) body["applied_tags"] = tags
        }
        return json.writeValueAsString(body)
    }
}
