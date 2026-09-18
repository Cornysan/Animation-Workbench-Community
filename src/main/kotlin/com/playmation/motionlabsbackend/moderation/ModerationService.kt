package com.playmation.motionlabsbackend.moderation

import com.playmation.motionlabsbackend.account.AccountRepository
import com.playmation.motionlabsbackend.account.AccountService
import com.playmation.motionlabsbackend.account.AccountStatus
import com.playmation.motionlabsbackend.auth.ApiTokenService
import com.playmation.motionlabsbackend.auth.PortalPrincipal
import com.playmation.motionlabsbackend.catalog.AnimationPackage
import com.playmation.motionlabsbackend.catalog.AnimationPackageRepository
import com.playmation.motionlabsbackend.catalog.PackageStatus
import com.playmation.motionlabsbackend.catalog.PackageVersionRepository
import com.playmation.motionlabsbackend.catalog.VersionStatus
import com.playmation.motionlabsbackend.common.PortalException
import com.playmation.motionlabsbackend.common.RateLimiter
import com.playmation.motionlabsbackend.config.PortalProperties
import com.playmation.motionlabsbackend.system.AlertService
import com.playmation.motionlabsbackend.system.AuditService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Melden, Takedown, Entscheidungen. Der Kern (Konzept §5):
 *
 * Jede Meldung und jeder Takedown setzt das Paket SOFORT auf AUTO_HIDDEN -
 * ohne dass ein Mensch beteiligt ist. "Ich muss innerhalb von 24 h reagieren"
 * wird zu "es ist längst unten, ich entscheide in Ruhe". Die Gegenseite dieser
 * Macht schützen Konto-Pflicht, Rate Limit und der Zähler für unbegründete
 * Meldungen.
 */
@Service
class ModerationService(
    private val packages: AnimationPackageRepository,
    private val versions: PackageVersionRepository,
    private val reports: ReportRepository,
    private val takedowns: TakedownRequestRepository,
    private val actions: ModerationActionRepository,
    private val notifications: NotificationRepository,
    private val accountRepository: AccountRepository,
    private val accounts: AccountService,
    private val tokens: ApiTokenService,
    private val alerts: AlertService,
    private val audit: AuditService,
    private val rateLimiter: RateLimiter,
    private val properties: PortalProperties,
    private val clock: Clock,
) {
    // ════════════════════════════════════════════════════════════════════
    // MELDEN
    // ════════════════════════════════════════════════════════════════════

    @Transactional
    fun report(principal: PortalPrincipal, slug: String, category: ReportCategory, message: String, ip: String): UUID {
        val reporter = accounts.requireUsable(principal.accountId)

        if (reporter.falseReports >= properties.moderation.reportingBlockedAfterFalseReports)
            throw PortalException.forbidden("Reporting is disabled for this account.")
        rateLimiter.require("report", reporter.id.toString(), properties.limits.reportsPerHour, Duration.ofHours(1))

        val pkg = packages.findBySlug(slug) ?: throw PortalException.notFound("Package not found")
        if (pkg.status != PackageStatus.PUBLISHED && pkg.status != PackageStatus.AUTO_HIDDEN)
            throw PortalException.notFound("Package not found")
        if (pkg.ownerId == reporter.id)
            throw PortalException.badRequest("own-package", "You cannot report your own clip - withdraw it instead.")
        if (reports.existsByPackageIdAndReporterIdAndStatus(pkg.id, reporter.id, CaseStatus.OPEN))
            throw PortalException.conflict("already-reported", "You already reported this clip.")

        val now = clock.instant()
        val report = reports.save(
            Report(packageId = pkg.id, reporterId = reporter.id, category = category,
                message = message.trim().take(2000), createdAt = now)
        )

        val hidden = autoHide(pkg, "report ${report.id}")
        audit.record(reporter.id, "report.created", "package", pkg.slug, "category=$category report=${report.id}", ip)

        alerts.send(
            "Report: ${pkg.title}",
            "Category: $category\nPackage: ${packageUrl(pkg)}\nHidden now: $hidden\nMessage: ${report.message}"
        )

        return report.id
    }

    // ════════════════════════════════════════════════════════════════════
    // TAKEDOWN
    // ════════════════════════════════════════════════════════════════════

    data class TakedownInput(
        val contactName: String,
        val contactEmail: String,
        val rightsHolder: String,
        val claimedWork: String,
        val packages: String,
        val goodFaith: Boolean,
        val accurate: Boolean,
    )

    data class TakedownReceipt(val id: UUID, val hiddenPackages: Int, val unknownReferences: List<String>)

    @Transactional
    fun takedown(input: TakedownInput, ip: String): TakedownReceipt {
        rateLimiter.require("takedown", ip, properties.limits.takedownsPerHourPerIp, Duration.ofHours(1))

        fun required(value: String, field: String, max: Int): String {
            val trimmed = value.trim()
            if (trimmed.isEmpty() || trimmed.length > max) throw PortalException.badRequest("invalid-$field", "Please fill in $field.")
            return trimmed
        }

        val name = required(input.contactName, "contactName", 120)
        val email = required(input.contactEmail, "contactEmail", 254)
        if (!Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(email)) throw PortalException.badRequest("invalid-contactEmail", "Please enter a valid email address.")
        val holder = required(input.rightsHolder, "rightsHolder", 200)
        val work = required(input.claimedWork, "claimedWork", 2000)
        if (!input.goodFaith || !input.accurate) throw PortalException.badRequest("statements-required", "Both statements are required.")

        val references = input.packages.split(Regex("[\\s,;]+")).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (references.isEmpty() || references.size > 20) throw PortalException.badRequest("invalid-packages", "List between 1 and 20 clip links.")

        val slugs = references.map { slugFromReference(it) }
        val found = slugs.mapNotNull { packages.findBySlug(it) }
        val unknown = references.filterIndexed { i, _ -> found.none { it.slug == slugs[i] } }

        val request = takedowns.save(
            TakedownRequest(
                contactName = name, contactEmail = email, rightsHolder = holder, claimedWork = work,
                packageSlugs = found.joinToString(",") { it.slug }.ifEmpty { slugs.joinToString(",").take(1000) },
                goodFaith = true, accurate = true, ipAddress = ip, createdAt = clock.instant(),
            )
        )

        val hidden = found.count { autoHide(it, "takedown ${request.id}") }
        audit.record(null, "takedown.received", "takedown", request.id.toString(), "packages=${request.packageSlugs}", ip)

        alerts.send(
            "Takedown request from $holder",
            "Contact: $name <$email>\nPackages: ${found.joinToString { packageUrl(it) }}\nUnknown: $unknown\nHidden now: $hidden\nWork: $work"
        )

        return TakedownReceipt(request.id, hidden, unknown)
    }

    /** Akzeptiert Slug, Paket-URL (`…/clip.html?p=slug`) oder API-URL. */
    private fun slugFromReference(reference: String): String {
        Regex("[?&]p=([a-z0-9]+)").find(reference)?.let { return it.groupValues[1] }
        return reference.trimEnd('/').substringAfterLast('/').substringBefore('?').lowercase()
    }

    // ════════════════════════════════════════════════════════════════════
    // ENTSCHEIDEN
    // ════════════════════════════════════════════════════════════════════

    data class CaseView(
        val kind: String,
        val id: UUID,
        val createdAt: Instant,
        val packages: List<PackageRef>,
        val category: String?,
        val message: String,
        val reporter: String?,
        val contact: String?,
    )

    data class PackageRef(val slug: String, val title: String, val status: String, val owner: String, val ownerStrikes: Int)

    @Transactional(readOnly = true)
    fun openCases(): List<CaseView> {
        val result = mutableListOf<CaseView>()

        for (report in reports.findByStatusOrderByCreatedAtAsc(CaseStatus.OPEN)) {
            val pkg = packages.findById(report.packageId).orElse(null) ?: continue
            result += CaseView("report", report.id, report.createdAt, listOf(ref(pkg)), report.category.name, report.message,
                accountRepository.findById(report.reporterId).map { it.displayName }.orElse("?"), null)
        }

        for (request in takedowns.findByStatusOrderByCreatedAtAsc(CaseStatus.OPEN)) {
            result += CaseView("takedown", request.id, request.createdAt,
                request.slugs().mapNotNull { packages.findBySlug(it) }.map { ref(it) },
                null, "${request.rightsHolder}: ${request.claimedWork}", null, "${request.contactName} <${request.contactEmail}>")
        }

        return result.sortedBy { it.createdAt }
    }

    /** Unbegründet: wieder sichtbar, alle offenen Meldungen zum Paket abgewiesen. */
    @Transactional
    fun restore(admin: PortalPrincipal, slug: String, note: String, ip: String) {
        val pkg = packages.findBySlug(slug) ?: throw PortalException.notFound("Package not found")
        if (openTakedownsFor(pkg).isNotEmpty())
            throw PortalException.conflict("open-takedown", "Resolve the open takedown request for this clip first.")

        val now = clock.instant()
        for (report in reports.findByPackageIdAndStatus(pkg.id, CaseStatus.OPEN)) {
            close(report, CaseStatus.DISMISSED, admin, note, now)
            notify(report.reporterId, "Your report about '${pkg.title}' was reviewed. The clip stays available.")
        }

        if (pkg.status == PackageStatus.AUTO_HIDDEN) {
            pkg.status = PackageStatus.PUBLISHED
            pkg.updatedAt = now
            notify(pkg.ownerId, "'${pkg.title}' was reviewed and is visible again.")
        }

        action(admin.accountId, "restore", pkg.id, note)
        audit.record(admin.accountId, "moderation.restore", "package", slug, note, ip)
    }

    /** Begründet: entfernt (Status, kein Löschen), optional ein Strike beim Besitzer. */
    @Transactional
    fun remove(admin: PortalPrincipal, slug: String, note: String, strike: Boolean, ip: String) {
        val pkg = packages.findBySlug(slug) ?: throw PortalException.notFound("Package not found")
        val now = clock.instant()

        pkg.status = PackageStatus.REMOVED
        pkg.updatedAt = now
        versions.findByPackageIdOrderByVersionNumberDesc(pkg.id).forEach { it.status = VersionStatus.REMOVED }

        for (report in reports.findByPackageIdAndStatus(pkg.id, CaseStatus.OPEN)) {
            close(report, CaseStatus.UPHELD, admin, note, now)
            notify(report.reporterId, "Thank you - '${pkg.title}' was removed after your report.")
        }

        notify(pkg.ownerId, "'${pkg.title}' was removed from the community. Reason: $note")
        action(admin.accountId, "remove", pkg.id, note)
        audit.record(admin.accountId, "moderation.remove", "package", slug, "strike=$strike $note", ip)

        if (strike) addStrike(admin, pkg.ownerId, "removed '${pkg.slug}'", ip)
    }

    @Transactional
    fun dismissReport(admin: PortalPrincipal, reportId: UUID, note: String, falseReport: Boolean, ip: String) {
        val report = reports.findById(reportId).orElseThrow { PortalException.notFound("Report not found") }
        if (report.status != CaseStatus.OPEN) throw PortalException.conflict("case-closed", "This report is already closed.")

        close(report, CaseStatus.DISMISSED, admin, note, clock.instant())

        if (falseReport) {
            accountRepository.findById(report.reporterId).ifPresent { it.falseReports++ }
        }

        val pkg = packages.findById(report.packageId).orElse(null)
        if (pkg != null) {
            notify(report.reporterId, "Your report about '${pkg.title}' was reviewed. The clip stays available.")
            restoreIfNoOpenCases(pkg, admin, note)
        }

        action(admin.accountId, if (falseReport) "dismiss-false-report" else "dismiss-report", report.id, note)
        audit.record(admin.accountId, "moderation.dismiss-report", "report", reportId.toString(), "false=$falseReport $note", ip)
    }

    @Transactional
    fun resolveTakedown(admin: PortalPrincipal, requestId: UUID, upheld: Boolean, note: String, strike: Boolean, ip: String) {
        val request = takedowns.findById(requestId).orElseThrow { PortalException.notFound("Takedown request not found") }
        if (request.status != CaseStatus.OPEN) throw PortalException.conflict("case-closed", "This request is already closed.")

        request.status = if (upheld) CaseStatus.UPHELD else CaseStatus.DISMISSED
        request.resolvedAt = clock.instant()
        request.resolvedBy = admin.accountId
        request.resolutionNote = note.take(2000)

        val affected = request.slugs().mapNotNull { packages.findBySlug(it) }
        if (upheld) {
            affected.forEach { remove(admin, it.slug, "Takedown request: $note", strike, ip) }
        } else {
            affected.forEach { restoreIfNoOpenCases(it, admin, note) }
        }

        action(admin.accountId, if (upheld) "takedown-upheld" else "takedown-dismissed", request.id, note)
        audit.record(admin.accountId, "moderation.takedown", "takedown", requestId.toString(), "upheld=$upheld $note", ip)
    }

    @Transactional
    fun setAccountStatus(admin: PortalPrincipal, accountId: UUID, status: AccountStatus, note: String, ip: String) {
        val account = accounts.get(accountId)
        account.status = status
        if (status == AccountStatus.BANNED) tokens.revokeAll(account.id)
        action(admin.accountId, "account-${status.name.lowercase()}", account.id, note)
        audit.record(admin.accountId, "moderation.account-status", "account", accountId.toString(), "$status $note", ip)
    }

    // ── Hilfen ──────────────────────────────────────────────────────────

    /** true, wenn das Paket dadurch unsichtbar wurde. */
    private fun autoHide(pkg: AnimationPackage, reason: String): Boolean {
        if (pkg.status != PackageStatus.PUBLISHED) return false
        pkg.status = PackageStatus.AUTO_HIDDEN
        pkg.updatedAt = clock.instant()
        action(null, "auto-hide", pkg.id, reason)
        notify(pkg.ownerId, "'${pkg.title}' was reported and is hidden until a moderator has looked at it.")
        return true
    }

    private fun restoreIfNoOpenCases(pkg: AnimationPackage, admin: PortalPrincipal, note: String) {
        if (pkg.status != PackageStatus.AUTO_HIDDEN) return
        if (reports.findByPackageIdAndStatus(pkg.id, CaseStatus.OPEN).isNotEmpty() || openTakedownsFor(pkg).isNotEmpty()) return

        pkg.status = PackageStatus.PUBLISHED
        pkg.updatedAt = clock.instant()
        action(admin.accountId, "restore", pkg.id, note)
        notify(pkg.ownerId, "'${pkg.title}' was reviewed and is visible again.")
    }

    private fun openTakedownsFor(pkg: AnimationPackage) =
        takedowns.findByStatusOrderByCreatedAtAsc(CaseStatus.OPEN).filter { pkg.slug in it.slugs() }

    /**
     * Wiederholungstäter (Konzept §6): ab dem ersten Strike eingeschränkt
     * (engeres Upload-Limit), ab [PortalProperties.Moderation.banAfterStrikes]
     * gesperrt samt Widerruf aller Workbench-Anmeldungen.
     */
    private fun addStrike(admin: PortalPrincipal, accountId: UUID, reason: String, ip: String) {
        val account = accounts.get(accountId)
        account.strikes++
        account.status = if (account.strikes >= properties.moderation.banAfterStrikes) AccountStatus.BANNED else AccountStatus.RESTRICTED
        if (account.status == AccountStatus.BANNED) tokens.revokeAll(account.id)

        action(admin.accountId, "strike", account.id, reason)
        audit.record(admin.accountId, "moderation.strike", "account", accountId.toString(), "strikes=${account.strikes} $reason", ip)
    }

    private fun close(report: Report, status: CaseStatus, admin: PortalPrincipal, note: String, now: Instant) {
        report.status = status
        report.resolvedAt = now
        report.resolvedBy = admin.accountId
        report.resolutionNote = note.take(2000)
    }

    private fun action(actorId: UUID?, action: String, targetId: UUID, reason: String?) {
        val targetType = when {
            action.startsWith("account") || action == "strike" -> "account"
            action.contains("report") -> "report"
            action.startsWith("takedown") -> "takedown"
            else -> "package"
        }
        actions.save(ModerationAction(actorId = actorId, action = action, targetType = targetType, targetId = targetId,
            reason = reason?.take(2000), createdAt = clock.instant()))
    }

    private fun notify(accountId: UUID, message: String) {
        notifications.save(Notification(accountId = accountId, message = message.take(1000), createdAt = clock.instant()))
    }

    private fun ref(pkg: AnimationPackage): PackageRef {
        val owner = accountRepository.findById(pkg.ownerId).orElse(null)
        return PackageRef(pkg.slug, pkg.title, pkg.status.name, owner?.displayName ?: "?", owner?.strikes ?: 0)
    }

    private fun packageUrl(pkg: AnimationPackage) = "${properties.publicBaseUrl.trimEnd('/')}/clip.html?p=${pkg.slug}"
}
