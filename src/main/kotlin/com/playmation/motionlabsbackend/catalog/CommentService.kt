package com.playmation.motionlabsbackend.catalog

import com.playmation.motionlabsbackend.account.AccountRepository
import com.playmation.motionlabsbackend.account.Account
import com.playmation.motionlabsbackend.account.AccountService
import com.playmation.motionlabsbackend.account.avatarPath
import com.playmation.motionlabsbackend.auth.PortalPrincipal
import com.playmation.motionlabsbackend.common.PortalException
import com.playmation.motionlabsbackend.common.RateLimiter
import com.playmation.motionlabsbackend.config.PortalProperties
import com.playmation.motionlabsbackend.notification.NotificationKind
import com.playmation.motionlabsbackend.notification.NotificationLinks
import com.playmation.motionlabsbackend.notification.Notifier
import com.playmation.motionlabsbackend.system.AuditService
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class CommentView(
    val id: UUID,
    val author: String,
    val body: String,
    val createdAt: Instant,
    val editedAt: Instant?,
    /** Der Abrufende hat ihn geschrieben - die Workbench zeigt dann "Delete" statt "Report". */
    val mine: Boolean,
    /**
     * Wohin der Name fuehrt und welches Bild daneben steht. Ohne beides war
     * ein Kommentar eine Zeile von niemandem: ein Buchstabe im Kreis, kein Weg
     * zur Person, die ihn geschrieben hat.
     */
    val authorHandle: String? = null,
    val authorAvatar: String? = null,
)

data class CommentPage(val comments: List<CommentView>, val total: Long, val page: Int, val hasMore: Boolean)

/**
 * Kommentare an einer Animation. Flach, älteste zuerst - ein Gespräch, keine
 * Pinnwand.
 *
 * Sichtbarkeit erbt vom Paket: was [CatalogService.visible] nicht hergibt, hat
 * auch keine Kommentare. Damit ist ein versteckter Clip auch für seine
 * Kommentare versteckt, ohne dass hier eine zweite Regel entsteht.
 */
@Service
class CommentService(
    private val comments: PackageCommentRepository,
    private val packages: AnimationPackageRepository,
    private val catalog: CatalogService,
    private val accountRepository: AccountRepository,
    private val accounts: AccountService,
    private val notifier: Notifier,
    private val rateLimiter: RateLimiter,
    private val audit: AuditService,
    private val properties: PortalProperties,
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    fun list(slug: String, principal: PortalPrincipal?, page: Int, size: Int): CommentPage {
        val (pkg, _) = catalog.visible(slug, principal)
        val request = PageRequest.of(maxOf(page, 0), size.coerceIn(1, 100))
        val found = comments.findByPackageIdAndStatusOrderByCreatedAtAsc(pkg.id, CommentStatus.VISIBLE, request)

        val authors = authors(found.content.map { it.accountId })
        return CommentPage(
            found.content.map { view(it, authors, principal) },
            found.totalElements,
            request.pageNumber,
            found.hasNext(),
        )
    }

    @Transactional
    fun post(principal: PortalPrincipal, slug: String, body: String, ip: String): CommentView {
        val author = accounts.requireUsable(principal.accountId)
        rateLimiter.require("comment", author.id.toString(), properties.comments.perHour, Duration.ofHours(1))

        val text = clean(body)
        val (pkg, _) = catalog.visible(slug, principal)

        val now = clock.instant()
        val saved = comments.save(
            PackageComment(packageId = pkg.id, accountId = author.id, body = text, createdAt = now)
        )

        recount(pkg)
        audit.record(author.id, "comment.created", "comment", saved.id.toString(), "package=${pkg.slug}", ip)

        //  Der Besitzer soll es erfahren - aber nicht, wenn er sich selbst
        //  unter den eigenen Clip schreibt (das faengt der Notifier ab).
        val link = NotificationLinks.comments(pkg.slug)
        notifier.fromActor(pkg.ownerId, author, "commented on '${pkg.title}'.", NotificationKind.COMMENT, link)

        //  Und wer schon mitgeredet hat, erfaehrt, dass geantwortet wurde -
        //  EINMAL, solange die letzte Nachricht dazu ungelesen ist. Ein
        //  lebhaftes Gespraech ist sonst ein Postfach voller Zeilen, die alle
        //  dasselbe sagen: schau mal wieder rein.
        val others = comments.participantsOf(pkg.id, CommentStatus.VISIBLE)
            .filter { it != author.id && it != pkg.ownerId && !notifier.hasUnread(it, NotificationKind.REPLY, link) }
            .take(MAX_REPLY_NOTIFICATIONS)
        notifier.fromActorToMany(others, author, "also commented on '${pkg.title}'.", NotificationKind.REPLY, link)

        return view(saved, mapOf(author.id to author), principal)
    }

    /**
     * Ein Tippfehler darf noch weg. Danach steht der Text - wer eine Meldung
     * provoziert und den Text hinterher austauscht, macht die Meldung sinnlos.
     */
    @Transactional
    fun edit(principal: PortalPrincipal, slug: String, commentId: UUID, body: String, ip: String): CommentView {
        val author = accounts.requireUsable(principal.accountId)
        val comment = own(commentId, slug, author.id, allowAdmin = false)

        val window = Duration.ofMinutes(properties.comments.editMinutes)
        if (Duration.between(comment.createdAt, clock.instant()) > window)
            throw PortalException.forbidden("Comments can only be edited in the first ${properties.comments.editMinutes} minutes.")

        comment.body = clean(body)
        comment.editedAt = clock.instant()
        audit.record(author.id, "comment.edited", "comment", comment.id.toString(), null, ip)

        return view(comment, mapOf(author.id to author), principal)
    }

    /** Verfasser oder Admin. Status, kein Löschen - der Text bleibt für Rückfragen. */
    @Transactional
    fun delete(principal: PortalPrincipal, slug: String, commentId: UUID, ip: String) {
        val actor = accounts.requireUsable(principal.accountId)
        val comment = own(commentId, slug, actor.id, allowAdmin = principal.isAdmin)

        comment.status = CommentStatus.REMOVED
        comment.removedBy = actor.id
        comment.removedReason = if (comment.accountId == actor.id) "removed by author" else "removed by moderator"

        packages.findById(comment.packageId).ifPresent { recount(it) }
        audit.record(actor.id, "comment.removed", "comment", comment.id.toString(), comment.removedReason, ip)
    }

    // ── Hilfen ──────────────────────────────────────────────────────────

    /** Für die Moderation: sie greift über die Kennung zu, nicht über den Slug. */
    @Transactional(readOnly = true)
    fun find(commentId: UUID): PackageComment =
        comments.findById(commentId).orElseThrow { PortalException.notFound("Comment not found") }

    /** Nach einer Statusänderung neu zählen statt hoch- und runterzuzählen. */
    fun recount(pkg: AnimationPackage) {
        pkg.commentCount = comments.countByPackageIdAndStatus(pkg.id, CommentStatus.VISIBLE)
        packages.save(pkg)
    }

    private fun own(commentId: UUID, slug: String, actorId: UUID, allowAdmin: Boolean): PackageComment {
        val comment = find(commentId)
        val pkg = packages.findById(comment.packageId).orElseThrow { PortalException.notFound("Comment not found") }

        if (pkg.slug != slug) throw PortalException.notFound("Comment not found")
        if (comment.status == CommentStatus.REMOVED) throw PortalException.notFound("Comment not found")
        if (comment.accountId != actorId && !allowAdmin)
            throw PortalException.forbidden("This is not your comment.")

        return comment
    }

    private fun clean(body: String): String {
        //  Zeilenumbrüche bleiben, alle anderen Steuerzeichen nicht: die
        //  Workbench misst die Höhe mit GUIStyle.CalcHeight, und ein Tabulator
        //  misst sich dort anders, als er zeichnet.
        val text = body
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .filter { it == '\n' || !it.isISOControl() }
            .trim()

        if (text.isEmpty()) throw PortalException.badRequest("empty-comment", "Write something first.")
        if (text.length > properties.comments.maxLength)
            throw PortalException.badRequest("comment-too-long", "Comments are limited to ${properties.comments.maxLength} characters.")

        return text
    }

    private fun view(comment: PackageComment, authors: Map<UUID, Account>, principal: PortalPrincipal?): CommentView {
        val author = authors[comment.accountId]
        return CommentView(
            comment.id,
            author?.displayName ?: "unknown",
            comment.body,
            comment.createdAt,
            comment.editedAt,
            principal?.accountId == comment.accountId,
            authorHandle = author?.handle,
            authorAvatar = author?.avatarPath(),
        )
    }

    private fun authors(ids: Collection<UUID>): Map<UUID, Account> =
        accountRepository.findAllById(ids.toSet()).associateBy { it.id }

    companion object {
        /** Mehr Mitschreibende bekommen keine Nachricht - ab da ist es kein Gespraech mehr, sondern ein Forum. */
        const val MAX_REPLY_NOTIFICATIONS = 50
    }
}
