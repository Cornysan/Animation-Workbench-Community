package com.playmation.motionlabsbackend.system

import com.playmation.motionlabsbackend.format.AwclipSchema
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.sql.ResultSet
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Kennzahlen fuer die Admin-Seite "Stats" (`/admin-stats.html`, seit 2026-09-27).
 *
 * NUR AUS DEM, WAS OHNEHIN GESPEICHERT IST. Diese Seite fuehrt nichts Neues
 * ein - kein Besuchsprotokoll, keine Zeitreihe -, sie zaehlt die Tabellen aus,
 * die das Portal fuer seine Funktionen schon hat: Konten, Anmeldungen, Clips,
 * Sammlungen, Kommentare. Deshalb braucht sie auch keine neue Zeile in der
 * Datenschutzerklaerung.
 *
 * Eine Handvoll gruppierter Abfragen statt einer je Konto: die Tabelle der
 * Mitglieder waere sonst bei ein paar hundert Konten ein paar tausend Aufrufe.
 * Standard-SQL, damit H2 (Tests) und Postgres dasselbe antworten.
 */
@Service
class AdminStatsService(private val jdbc: JdbcTemplate, private val clock: Clock) {

    data class Stats(
        val generatedAt: Instant,
        val accounts: Accounts,
        val clips: Clips,
        val activity: Activity,
        val topClips: List<TopClip>,
        val members: List<Member>,
    )

    data class Accounts(
        /** Ohne geschlossene Konten. */
        val total: Int,
        val new7: Int,
        val new30: Int,
        /** Irgendeine Anmeldung, Browser-Sitzung oder Workbench-Nutzung im Zeitraum. */
        val active7: Int,
        val active30: Int,
        /** Mindestens ein Clip, oeffentlich oder privat. */
        val sharing: Int,
        /** Eine gueltige Workbench-Anmeldung (nicht widerrufen, nicht abgelaufen). */
        val workbench: Int,
        val restricted: Int,
        val banned: Int,
        val closed: Int,
        /** Verbundene Anmeldungen je Anbieter - ein Konto kann mehrere haben. */
        val providers: Map<String, Int>,
    )

    data class Clips(
        val public: Int,
        val private: Int,
        /** Nach Meldungen automatisch versteckt, wartet auf eine Entscheidung. */
        val hidden: Int,
        /** Entfernt (Moderation) oder vom Besitzer zurueckgezogen. */
        val removed: Int,
        val new7: Int,
        val new30: Int,
        val packs: Int,
        val versions: Int,
        val storageBytes: Long,
    )

    data class Activity(
        val views: Long,
        val downloads: Long,
        /** Als .awclip: die Quittungen plus jeder weitere Import aus der Workbench. */
        val takes: Long,
        val fileDownloads: Long,
        val likes: Long,
        val comments: Long,
        val collections: Int,
        val follows: Long,
    )

    data class TopClip(val slug: String, val title: String, val author: String, val views: Long, val downloads: Long, val likes: Long)

    data class Member(
        val name: String,
        val handle: String?,
        val admin: Boolean,
        /** `active`, `restricted`, `banned` oder `closed`. */
        val state: String,
        val providers: List<String>,
        val createdAt: Instant,
        val lastSeen: Instant?,
        val publicClips: Int,
        val privateClips: Int,
        val hiddenClips: Int,
        val packs: Int,
        val collections: Int,
        val comments: Int,
        val likesGiven: Int,
        val followers: Long,
        val following: Int,
        val views: Long,
        val downloads: Long,
        val likesReceived: Long,
        val workbench: Boolean,
        val strikes: Int,
    )

    private class ClipTally {
        var public = 0
        var private = 0
        var hidden = 0
        var removed = 0
        var views = 0L
        var downloads = 0L
        var likes = 0L
    }

    @Transactional(readOnly = true)
    fun stats(): Stats {
        val now = clock.instant()
        val week = now.minus(Duration.ofDays(7))
        val month = now.minus(Duration.ofDays(30))

        // ── Konten und alles, was "zuletzt gesehen" ausmacht ────────────
        data class Row(
            val id: UUID, val name: String, val handle: String?, val admin: Boolean, val status: String,
            val strikes: Int, val createdAt: Instant, val lastLogin: Instant?, val followers: Long,
        )
        val accounts = jdbc.query(
            "select id, display_name, handle, role, status, strikes, created_at, last_login_at, follower_count from account"
        ) { rs, _ ->
            Row(rs.uuid("id"), rs.getString("display_name"), rs.getString("handle"), rs.getString("role") == "ADMIN",
                rs.getString("status"), rs.getInt("strikes"), rs.instant("created_at")!!, rs.instant("last_login_at"),
                rs.getLong("follower_count"))
        }

        val providers = mutableMapOf<UUID, MutableList<String>>()
        val seen = mutableMapOf<UUID, Instant>()
        fun saw(id: UUID, at: Instant?) {
            if (at != null) seen.merge(id, at) { a, b -> maxOf(a, b) }
        }
        jdbc.query("select account_id, provider, last_login_at from account_identity", RowCallbackHandler { rs ->
            val id = rs.uuid("account_id")
            providers.getOrPut(id) { mutableListOf() }.add(rs.getString("provider"))
            saw(id, rs.instant("last_login_at"))
        })
        jdbc.query("select account_id, last_used_at from browser_login", RowCallbackHandler { rs ->
            saw(rs.uuid("account_id"), rs.instant("last_used_at"))
        })
        val workbench = mutableSetOf<UUID>()
        jdbc.query("select account_id, expires_at, last_used_at, revoked_at from api_token", RowCallbackHandler { rs ->
            val id = rs.uuid("account_id")
            saw(id, rs.instant("last_used_at"))
            val expires = rs.instant("expires_at")
            if (rs.instant("revoked_at") == null && expires != null && expires.isAfter(now)) workbench += id
        })
        accounts.forEach { saw(it.id, it.lastLogin) }

        // ── Clips je Besitzer ───────────────────────────────────────────
        val tallies = mutableMapOf<UUID, ClipTally>()
        jdbc.query(
            "select owner_id, license, status, count(*) n, coalesce(sum(view_count), 0) v, " +
                "coalesce(sum(take_count + repeat_take_count + file_download_count), 0) d, coalesce(sum(like_count), 0) l " +
                "from animation_package group by owner_id, license, status",
            RowCallbackHandler { rs ->
                val t = tallies.getOrPut(rs.uuid("owner_id")) { ClipTally() }
                val n = rs.getInt("n")
                when (rs.getString("status")) {
                    "PUBLISHED" -> if (rs.getString("license") == AwclipSchema.LICENSE_PUBLIC) t.public += n else t.private += n
                    "AUTO_HIDDEN" -> t.hidden += n
                    else -> t.removed += n
                }
                t.views += rs.getLong("v")
                t.downloads += rs.getLong("d")
                t.likes += rs.getLong("l")
            },
        )

        val packs = countBy("select owner_id k, count(*) n from clip_pack group by owner_id")
        val collections = countBy("select owner_id k, count(*) n from collection where status <> 'REMOVED' group by owner_id")
        val comments = countBy("select account_id k, count(*) n from package_comment where status = 'VISIBLE' group by account_id")
        val likesGiven = countBy("select account_id k, count(*) n from package_like group by account_id")
        val following = countBy("select follower_id k, count(*) n from account_follow group by follower_id")

        fun stateOf(row: Row) = when {
            //  Ein geschlossenes Konto bleibt als Zeile, anonym und gesperrt
            //  (AccountDeletionService) - erkennbar an seiner Adresse.
            row.handle?.startsWith("deleted-") == true -> "closed"
            row.status == "BANNED" -> "banned"
            row.status == "RESTRICTED" -> "restricted"
            else -> "active"
        }

        val members = accounts.map { row ->
            val t = tallies[row.id] ?: ClipTally()
            Member(
                name = row.name, handle = row.handle, admin = row.admin, state = stateOf(row),
                providers = providers[row.id].orEmpty().sorted(),
                createdAt = row.createdAt, lastSeen = seen[row.id],
                publicClips = t.public, privateClips = t.private, hiddenClips = t.hidden,
                packs = packs[row.id] ?: 0, collections = collections[row.id] ?: 0,
                comments = comments[row.id] ?: 0, likesGiven = likesGiven[row.id] ?: 0,
                followers = row.followers, following = following[row.id] ?: 0,
                views = t.views, downloads = t.downloads, likesReceived = t.likes,
                workbench = row.id in workbench, strikes = row.strikes,
            )
        }.sortedWith(compareByDescending<Member> { it.lastSeen ?: Instant.EPOCH }.thenByDescending { it.createdAt })

        val open = members.filter { it.state != "closed" }
        val allTallies = tallies.values

        // ── Summen ueber alles ──────────────────────────────────────────
        val totals = jdbc.queryForMap(
            "select coalesce(sum(view_count), 0) v, coalesce(sum(take_count + repeat_take_count), 0) t, " +
                "coalesce(sum(file_download_count), 0) f from animation_package"
        )
        val versions = jdbc.queryForMap("select count(*) n, coalesce(sum(size_bytes), 0) s from package_version")

        return Stats(
            generatedAt = now,
            accounts = Accounts(
                total = open.size,
                new7 = open.count { it.createdAt.isAfter(week) },
                new30 = open.count { it.createdAt.isAfter(month) },
                active7 = open.count { it.lastSeen?.isAfter(week) == true },
                active30 = open.count { it.lastSeen?.isAfter(month) == true },
                sharing = open.count { it.publicClips + it.privateClips + it.hiddenClips > 0 },
                workbench = open.count { it.workbench },
                restricted = open.count { it.state == "restricted" },
                banned = open.count { it.state == "banned" },
                closed = members.size - open.size,
                providers = providers.values.flatten().groupingBy { it }.eachCount().toSortedMap(),
            ),
            clips = Clips(
                public = allTallies.sumOf { it.public },
                private = allTallies.sumOf { it.private },
                hidden = allTallies.sumOf { it.hidden },
                removed = allTallies.sumOf { it.removed },
                new7 = createdSince(week),
                new30 = createdSince(month),
                packs = packs.values.sum(),
                versions = (versions["n"] as Number).toInt(),
                storageBytes = (versions["s"] as Number).toLong(),
            ),
            activity = Activity(
                views = (totals["v"] as Number).toLong(),
                downloads = (totals["t"] as Number).toLong() + (totals["f"] as Number).toLong(),
                takes = (totals["t"] as Number).toLong(),
                fileDownloads = (totals["f"] as Number).toLong(),
                likes = likesGiven.values.sumOf { it.toLong() },
                comments = comments.values.sumOf { it.toLong() },
                collections = collections.values.sum(),
                follows = following.values.sumOf { it.toLong() },
            ),
            topClips = topClips(),
            members = members,
        )
    }

    /** Die meistgesehenen oeffentlichen Clips - was die Leute tatsaechlich ansehen. */
    private fun topClips(): List<TopClip> = jdbc.query(
        "select p.slug, p.title, a.display_name, p.view_count, p.take_count + p.repeat_take_count + p.file_download_count d, p.like_count " +
            "from animation_package p join account a on a.id = p.owner_id " +
            "where p.status = 'PUBLISHED' and p.license = ? " +
            "order by p.view_count desc, d desc, p.like_count desc limit 10",
        { rs, _ ->
            TopClip(rs.getString("slug"), rs.getString("title"), rs.getString("display_name"),
                rs.getLong("view_count"), rs.getLong("d"), rs.getLong("like_count"))
        },
        AwclipSchema.LICENSE_PUBLIC,
    )

    private fun createdSince(since: Instant): Int =
        jdbc.queryForObject(
            "select count(*) from animation_package where created_at >= ?",
            Int::class.java,
            OffsetDateTime.ofInstant(since, java.time.ZoneOffset.UTC),
        ) ?: 0

    private fun countBy(sql: String): Map<UUID, Int> {
        val out = mutableMapOf<UUID, Int>()
        jdbc.query(sql, RowCallbackHandler { rs -> out[rs.uuid("k")] = rs.getInt("n") })
        return out
    }

    private fun ResultSet.uuid(column: String): UUID = getObject(column, UUID::class.java)

    private fun ResultSet.instant(column: String): Instant? = getObject(column, OffsetDateTime::class.java)?.toInstant()
}

/** Nur fuer Admins: alles unter `/api/v1/admin/` verlangt die Rolle (SecurityConfig). */
@RestController
@RequestMapping("/api/v1/admin")
class AdminStatsController(private val stats: AdminStatsService) {

    @GetMapping("/stats")
    fun stats() = stats.stats()
}
