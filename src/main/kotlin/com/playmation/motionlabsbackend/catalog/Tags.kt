package com.playmation.motionlabsbackend.catalog

import com.playmation.motionlabsbackend.auth.PortalPrincipal
import com.playmation.motionlabsbackend.auth.portalPrincipal
import com.playmation.motionlabsbackend.format.AwclipDocument
import com.playmation.motionlabsbackend.format.AwclipReadResult
import com.playmation.motionlabsbackend.format.AwclipReader
import com.playmation.motionlabsbackend.format.AwclipSchema
import com.playmation.motionlabsbackend.storage.BlobStore
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.min

/**
 * Wie aus getipptem Text ein Schlagwort wird - und wie ein Wort aus einem
 * Clipnamen mit einem Schlagwort verglichen wird.
 *
 * Dieselbe Umformung steht in `tag-input.js` und in `AWCommunityTagField.cs`:
 * die Felder formen beim Tippen um, der Server nimmt beim Speichern weiter nur
 * fertige Schlagworte an ([AwclipSchema.isTag]). Was hier `walk-cycle` wird,
 * muss dort auch `walk-cycle` werden, sonst schlaegt die Leiste etwas vor, das
 * das Feld anders schreibt.
 */
object TagText {

    /** `"  Walk Cycle! "` -> `walk-cycle`, `"Überschlag"` -> `uberschlag`; null, wenn nichts bleibt. */
    fun normalize(raw: String?): String? {
        if (raw == null) return null
        val folded = Normalizer.normalize(raw.trim().trimStart('#').replace("ß", "ss"), Normalizer.Form.NFD)
            .replace(COMBINING, "")
            .lowercase(Locale.ROOT)

        val out = StringBuilder()
        for (c in folded) {
            when {
                c in 'a'..'z' || c in '0'..'9' -> out.append(c)
                c == '-' || c == '_' || c == '.' || c.isWhitespace() ->
                    if (out.isNotEmpty() && out.last() != '-') out.append('-')
                //  Alles andere faellt weg: ein Ausrufezeichen ist kein Grund,
                //  ein Schlagwort abzulehnen, das sonst passt.
            }
        }

        var tag = out.toString().trim('-')
        if (tag.length > AwclipSchema.MAX_TAG_LENGTH) tag = tag.take(AwclipSchema.MAX_TAG_LENGTH).trimEnd('-')
        return tag.takeIf { AwclipSchema.isTag(it) }
    }

    /**
     * Die Woerter eines Titels, klein und ohne Ziffern: `Armature|SneakWalk_Loop01`
     * -> `armature`, `sneak`, `walk`, `loop`. Clipnamen sind selten Saetze - sie
     * kommen aus Blender, Mixamo und Maya, mit Binnenmajuskeln, Unterstrichen,
     * Senkrechtstrichen und Nummern.
     */
    fun words(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        val spaced = text
            .replace(LOWER_UPPER, "$1 $2")
            .replace(UPPER_WORD, "$1 $2")
            .replace(LETTER_DIGIT, "$1 $2")
            .replace(DIGIT_LETTER, "$1 $2")
        return spaced.split(NON_WORD)
            .mapNotNull { normalize(it) }
            .filter { word -> word.any { it in 'a'..'z' } }
    }

    /**
     * Der Vergleichsschluessel: `walking`, `walks`, `walked` -> `walk`;
     * `dancing` und `dance` -> `danc`. Kein richtiger Stemmer - er muss nur
     * zwei Schreibweisen desselben Worts zusammenbringen, und lieber eine zu
     * wenig als `sing` und `sin`.
     */
    fun key(word: String): String {
        var s = word
        when {
            s.length > 5 && s.endsWith("ing") -> s = s.dropLast(3)
            s.length > 4 && s.endsWith("ies") -> s = s.dropLast(3) + "y"
            s.length > 4 && s.endsWith("ed") -> s = s.dropLast(2)
            s.length > 3 && s.endsWith("s") && !s.endsWith("ss") -> s = s.dropLast(1)
        }
        //  runn -> run, hopp -> hop; roll und pass bleiben.
        if (s.length >= 4 && s[s.length - 1] == s[s.length - 2] && s.last() !in "lsz") s = s.dropLast(1)
        return s.trimEnd('e').ifEmpty { s }
    }

    private val COMBINING = Regex("\\p{M}+")
    private val LOWER_UPPER = Regex("([a-z])([A-Z])")
    private val UPPER_WORD = Regex("([A-Z]+)([A-Z][a-z])")
    private val LETTER_DIGIT = Regex("(\\p{L})(\\p{N})")
    private val DIGIT_LETTER = Regex("(\\p{N})(\\p{L})")
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
}

/**
 * Die Grundwoerter fuer Bewegung, jeweils mit den Schreibweisen, die in
 * Clipnamen stehen. Sie sorgen dafuer, dass `Die_01` `death` vorschlaegt und
 * `GetHit` `hit-reaction` - und dass ein junger Katalog, in dem es noch kaum
 * Schlagworte gibt, trotzdem dieselben Woerter anbietet, statt jeden Upload
 * seine eigenen erfinden zu lassen.
 *
 * Beugungen (`walking`, `jumps`) braucht die Liste nicht, die faengt
 * [TagText.key]. Hier stehen nur echte andere Woerter.
 */
object TagVocabulary {
    private val ENTRIES: Map<String, List<String>> = linkedMapOf(
        "idle" to listOf("breathe", "breathing"),
        "walk" to emptyList(),
        "run" to listOf("ran"),
        "sprint" to emptyList(),
        "jog" to emptyList(),
        "crouch" to emptyList(),
        "sneak" to emptyList(),
        "stealth" to emptyList(),
        "crawl" to emptyList(),
        "climb" to emptyList(),
        "swim" to emptyList(),
        "fly" to emptyList(),
        "jump" to listOf("hop", "leap"),
        "fall" to emptyList(),
        "land" to emptyList(),
        "turn" to emptyList(),
        "strafe" to emptyList(),
        "roll" to emptyList(),
        "dodge" to listOf("evade"),
        "combat" to listOf("fight", "fighting", "battle"),
        "attack" to listOf("atk"),
        "punch" to listOf("jab"),
        "kick" to emptyList(),
        "slash" to emptyList(),
        "stab" to emptyList(),
        "block" to emptyList(),
        "parry" to emptyList(),
        "hit-reaction" to listOf("hit", "hurt", "damage", "flinch", "gethit", "impact", "react", "reaction"),
        "death" to listOf("die", "died", "dies", "dying", "dead"),
        "cast" to listOf("spell", "spellcast"),
        "shoot" to listOf("shot"),
        "aim" to emptyList(),
        "reload" to emptyList(),
        "throw" to listOf("toss"),
        "pickup" to listOf("pick"),
        "sit" to listOf("seated"),
        "dance" to emptyList(),
        "wave" to emptyList(),
        "talk" to listOf("speak", "conversation"),
        "gesture" to listOf("emote"),
        "cheer" to listOf("celebrate", "celebration", "victory"),
        "push" to emptyList(),
        "pull" to emptyList(),
        "carry" to emptyList(),
        "loop" to listOf("cycle", "looping", "loopable"),
        "in-place" to listOf("inplace"),
        "root-motion" to listOf("rootmotion"),
    )

    val canonical: Set<String> = ENTRIES.keys

    private val byWord: Map<String, String> = buildMap {
        for ((tag, aliases) in ENTRIES) {
            put(tag, tag)
            for (alias in aliases) put(alias, tag)
        }
    }

    private val byKey: Map<String, String> = buildMap {
        for ((word, tag) in byWord) if (!word.contains('-')) putIfAbsent(TagText.key(word), tag)
    }

    /** Das Grundwort zu einem Wort aus einem Titel, oder null. */
    fun resolve(word: String): String? = byWord[word] ?: byKey[TagText.key(word)]

    /**
     * Woerter, die in Clipnamen stehen, aber nichts ueber die Bewegung sagen:
     * Werkzeug, Rig, Seite, Richtung, Arbeitsstand. `left`/`right` gehoeren
     * dazu - ein Katalog, in dem jeder Seitenschritt `left` heisst, findet mit
     * `left` nichts mehr.
     */
    private val STOP = setOf(
        "a", "an", "the", "and", "or", "of", "with", "to", "in", "on", "at", "for", "from", "by", "my",
        "anim", "anims", "animation", "animations", "clip", "clips", "take", "mocap", "mixamo", "com",
        "armature", "rig", "root", "skeleton", "humanoid", "generic", "character", "char", "default",
        "new", "final", "copy", "test", "tmp", "temp", "untitled", "version", "ver", "v",
        "left", "right", "lft", "rgt", "fwd", "bwd", "forward", "forwards", "backward", "backwards", "back",
        "front", "side", "start", "end", "begin", "unity", "fbx", "glb", "motion", "base", "layer", "full",
    )

    fun isStop(word: String) = word in STOP
}

/**
 * Das Schlagwortfeld beim Teilen (Workbench) und Bearbeiten (Portal): welche
 * Schlagworte es schon gibt, und welche zu einem Clip passen.
 *
 * WARUM DER SERVER VORSCHLAEGT und nicht jedes Feld selbst: nur er kennt den
 * Katalog - welche Woerter schon benutzt werden, wie oft, und welche
 * zusammen. Ein Vorschlag, der das nicht weiss, erfindet `walking` neben
 * `walk`, und die Schlagwortleiste zerfaellt in Schreibweisen.
 *
 * Gezaehlt wird nur, was oeffentlich gelistet ist ([CatalogOverviewService.tagStats]).
 * Die eigenen Schlagworte - auch die privater Clips - kommen nur beim
 * Besitzer selbst dazu.
 */
@Service
class TagService(
    private val overview: CatalogOverviewService,
    private val packages: AnimationPackageRepository,
    private val versions: PackageVersionRepository,
    private val blobs: BlobStore,
) {
    data class TagHit(val tag: String, val count: Int, val mine: Boolean)

    data class Suggestion(val tag: String, val count: Int, val reason: String)

    data class SuggestInput(
        val title: String? = null,
        val text: String? = null,
        val tags: List<String> = emptyList(),
        /** Vom Client, der den Clip in der Hand hat (Workbench). */
        val loops: Boolean? = null,
        /** `in-place` oder `travels`. */
        val motion: String? = null,
        /** Ein gespeicherter Clip - dann liest der Server loops/motion selbst (nur fuer den Besitzer). */
        val slug: String? = null,
        val limit: Int = 8,
    )

    /** Was der Clip selbst ueber sich verraet. */
    data class ClipFacts(val loops: Boolean?, val motion: String?)

    // ── Nachschlagen ────────────────────────────────────────────────────

    /**
     * Die Liste unter dem Feld, waehrend jemand tippt. Reihenfolge: genau das
     * Wort, dann sein Grundwort (`walking` -> `walk`), dann was damit
     * anfaengt, dann was ein Teilwort damit anfaengt (`reaction` ->
     * `hit-reaction`), dann dieselbe Wurzel. Innerhalb eines Rangs die eigenen
     * zuerst, dann die haeufigen.
     */
    @Transactional(readOnly = true)
    fun autocomplete(query: String?, limit: Int, principal: PortalPrincipal?): List<TagHit> {
        val stats = overview.tagStats()
        val mine = ownTags(principal)
        val max = limit.coerceIn(1, MAX_LIST)
        val q = TagText.normalize(query?.take(MAX_QUERY))

        if (q == null) {
            //  Ohne Eingabe: was tatsaechlich benutzt wird. Die Grundwoerter
            //  ohne einen einzigen Clip gehoeren nicht in eine Liste "beliebt".
            return (stats.counts.keys + mine.keys)
                .sortedWith(compareByDescending<String> { stats.count(it) + (mine[it] ?: 0) }.thenBy { it })
                .take(max)
                .map { TagHit(it, stats.count(it), it in mine) }
        }

        val qKey = TagText.key(q)
        val alias = TagVocabulary.resolve(q)
        val universe = LinkedHashSet<String>().apply {
            addAll(stats.counts.keys)
            addAll(mine.keys)
            addAll(TagVocabulary.canonical)
        }

        return universe
            .mapNotNull { tag ->
                val rank = when {
                    tag == q -> 0
                    tag == alias -> 1
                    tag.startsWith(q) -> 2
                    tag.split('-').any { it.startsWith(q) } -> 3
                    qKey.length >= 3 && TagText.key(tag).startsWith(qKey) -> 4
                    else -> return@mapNotNull null
                }
                tag to rank
            }
            .sortedWith(
                compareBy<Pair<String, Int>> { it.second }
                    .thenByDescending { if (it.first in mine) 1 else 0 }
                    .thenByDescending { stats.count(it.first) }
                    .thenBy { it.first }
            )
            .take(max)
            .map { (tag, _) -> TagHit(tag, stats.count(tag), tag in mine) }
    }

    // ── Vorschlagen ─────────────────────────────────────────────────────

    /**
     * Die Reihe "Suggested" unter dem Feld. Punkte, nicht Regeln - ein
     * Schlagwort kann aus mehreren Gruenden passen, gezeigt wird der
     * staerkste:
     *
     *  - im Titel, und schon ein Schlagwort (oder ein Grundwort): 100+
     *  - der Clip laeuft als Schleife: 90; bleibt auf der Stelle / wandert: 70
     *  - in der Beschreibung, und schon ein Schlagwort: 60+
     *  - steht oft neben einem gewaehlten: 30-80, je nach Anteil
     *  - im Titel, aber noch nirgends benutzt: 40 (`zombie` aus `Zombie_Walk`)
     *  - selbst schon einmal benutzt: 15-25
     *
     * Die Haeufigkeit im Katalog gibt bis zu 20 Punkte dazu: bei zwei
     * gleich guten Gruenden gewinnt das Wort, das Leute auch suchen.
     */
    @Transactional(readOnly = true)
    fun suggest(input: SuggestInput, principal: PortalPrincipal?): List<Suggestion> {
        val stats = overview.tagStats()
        val current = input.tags.mapNotNull { TagText.normalize(it) }.toSet()
        val byKey = stats.counts.keys.groupBy { TagText.key(it) }

        val best = HashMap<String, Pair<Double, String>>()
        fun offer(tag: String?, score: Double, reason: String) {
            if (tag == null || tag in current || !AwclipSchema.isTag(tag)) return
            val had = best[tag]
            if (had == null || had.first < score) best[tag] = score to reason
        }
        fun popularity(tag: String) = min(20.0, ln(1.0 + stats.count(tag)) * 6.0)

        //  Das Schlagwort zu einem Wort: von allen Schreibweisen, die es schon
        //  gibt, die haeufigste - bei Gleichstand das Grundwort. So kippt der
        //  Vorschlag zu `walk`, auch wenn irgendwer einmal `walking` schrieb.
        fun known(word: String): String? {
            val options = buildSet {
                if (stats.count(word) > 0) add(word)
                TagVocabulary.resolve(word)?.let { add(it) }
                byKey[TagText.key(word)]?.let { addAll(it) }
            }
            return options.maxWithOrNull(
                compareBy<String> { stats.count(it) }.thenBy { if (it in TagVocabulary.canonical) 1 else 0 })
        }

        val fromText = LinkedHashSet<String>()

        val titleWords = TagText.words(input.title?.take(MAX_TEXT))
        for ((i, word) in titleWords.withIndex()) {
            //  Zwei Woerter, ein Schlagwort: `Hit Reaction` -> `hit-reaction`.
            if (i + 1 < titleWords.size) {
                val pair = word + "-" + titleWords[i + 1]
                if (stats.count(pair) > 0 || pair in TagVocabulary.canonical) {
                    offer(pair, 110 + popularity(pair), IN_TITLE)
                    fromText += pair
                }
            }
            if (TagVocabulary.isStop(word)) continue

            val tag = known(word)
            if (tag != null) {
                offer(tag, 100 + popularity(tag), IN_TITLE)
                fromText += tag
            } else if (word.length >= 3 && word.all { it in 'a'..'z' }) {
                offer(word, 40.0, IN_TITLE)
            }
        }

        //  Die Beschreibung ist Prosa: nur, was schon ein Schlagwort ist.
        for (word in TagText.words(input.text?.take(MAX_TEXT))) {
            if (TagVocabulary.isStop(word)) continue
            val tag = known(word) ?: continue
            offer(tag, 60 + popularity(tag), IN_DESCRIPTION)
            fromText += tag
        }

        val facts = factsFor(input, principal)
        if (facts.loops == true) offer("loop", 90.0, "The clip loops")
        when (facts.motion) {
            MOTION_IN_PLACE -> offer("in-place", 70.0, "It stays in place")
            MOTION_TRAVELS -> offer("root-motion", 70.0, "It moves through the scene")
        }

        for (seed in current + fromText) {
            val n = stats.count(seed)
            if (n == 0) continue
            for ((other, both) in stats.related(seed)) {
                val share = both.toDouble() / n
                offer(other, 30 + 40 * share + min(10, both), "Often used with $seed")
            }
        }

        for ((tag, n) in ownTags(principal)) offer(tag, 15.0 + min(10, n), "You used it before")

        return best.entries
            .sortedWith(compareByDescending<Map.Entry<String, Pair<Double, String>>> { it.value.first }.thenBy { it.key })
            .take(input.limit.coerceIn(1, MAX_SUGGESTIONS))
            .map { Suggestion(it.key, stats.count(it.key), it.value.second) }
    }

    // ── Helfer ──────────────────────────────────────────────────────────

    /** Die Schlagworte meiner veroeffentlichten Clips, oeffentlich wie privat, mit Zahl. */
    private fun ownTags(principal: PortalPrincipal?): Map<String, Int> {
        if (principal == null) return emptyMap()
        val counts = HashMap<String, Int>()
        for (pkg in packages.findByOwnerIdOrderByCreatedAtDesc(principal.accountId)) {
            if (pkg.status != PackageStatus.PUBLISHED) continue
            for (tag in pkg.tagList()) counts[tag] = (counts[tag] ?: 0) + 1
        }
        return counts
    }

    /**
     * Die Workbench schickt, was sie am Clip sieht. Das Portal hat nur die
     * gespeicherte Datei - die liest es, aber nur fuer den, der den Clip auch
     * bearbeiten darf, und nur einmal je Fassung.
     */
    private fun factsFor(input: SuggestInput, principal: PortalPrincipal?): ClipFacts {
        val given = ClipFacts(input.loops, input.motion?.takeIf { it == MOTION_IN_PLACE || it == MOTION_TRAVELS })
        val slug = input.slug ?: return given
        if (given.loops != null && given.motion != null) return given
        if (principal == null) return given

        val pkg = packages.findBySlug(slug) ?: return given
        if (pkg.ownerId != principal.accountId && !principal.isAdmin) return given
        val versionId = pkg.currentVersionId ?: return given

        val stored = synchronized(factCache) { factCache[versionId] }
            ?: readFacts(versionId)?.also { synchronized(factCache) { factCache[versionId] = it } }
            ?: return given
        return ClipFacts(given.loops ?: stored.loops, given.motion ?: stored.motion)
    }

    private fun readFacts(versionId: UUID): ClipFacts? {
        val version = versions.findById(versionId).orElse(null) ?: return null
        val doc = try {
            blobs.open(version.blobKey).use { (AwclipReader.readFile(it) as? AwclipReadResult.Ok)?.document }
        } catch (_: Exception) {
            null
        } ?: return null
        return ClipFacts(doc.loops, motionOf(doc))
    }

    /** Eine Fassung aendert sich nie - was einmal gelesen ist, gilt. */
    private val factCache = object : LinkedHashMap<UUID, ClipFacts>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<UUID, ClipFacts>?) = size > 256
    }

    companion object {
        const val MOTION_IN_PLACE = "in-place"
        const val MOTION_TRAVELS = "travels"

        private const val IN_TITLE = "In the title"
        private const val IN_DESCRIPTION = "In the description"
        private const val MAX_QUERY = 64
        private const val MAX_TEXT = 400
        private const val MAX_LIST = 50
        private const val MAX_SUGGESTIONS = 20

        /**
         * Ab dieser Strecke zwischen erstem und letztem Bild wandert der Clip.
         * `RootT` eines Humanoiden steht in Koerpermassen (1 = etwa Hueft-
         * hoehe), nicht in Metern: ein Drittel davon ist mehr, als ein
         * Wiegen auf der Stelle je driftet, und weniger als ein einziger
         * Schritt.
         */
        private const val TRAVEL_THRESHOLD = 0.3

        /** Bleibt er auf der Stelle oder wandert er - null, wenn der Clip keine Wurzelkurve hat. */
        fun motionOf(doc: AwclipDocument): String? {
            fun travel(axis: String): Float? = doc.curves.firstOrNull { it.attribute == "RootT.$axis" }
                ?.keys?.takeIf { it.isNotEmpty() }?.let { it.last().value - it.first().value }

            val dx = travel("x")
            val dz = travel("z")
            if (dx == null && dz == null) return null
            val distance = hypot((dx ?: 0f).toDouble(), (dz ?: 0f).toDouble())
            return if (distance > TRAVEL_THRESHOLD) MOTION_TRAVELS else MOTION_IN_PLACE
        }
    }
}

@RestController
@RequestMapping("/api/v1/tags")
class TagController(private val tags: TagService) {

    /** Nachschlagen beim Tippen: `?q=wal` -> `walk`, `walk-cycle`, … Ohne `q` die meistbenutzten. */
    @GetMapping
    fun search(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false, defaultValue = "8") limit: Int,
        authentication: Authentication?,
    ) = mapOf("tags" to tags.autocomplete(q, limit, authentication.portalPrincipal()))

    /**
     * Vorschlaege zu einem Clip. `tags` ist die aktuelle Auswahl, durch Komma
     * getrennt - die kommt nicht in die Vorschlaege, zieht aber verwandte nach.
     */
    @GetMapping("/suggest")
    fun suggest(
        @RequestParam(required = false) title: String?,
        @RequestParam(required = false) text: String?,
        @RequestParam(required = false) tags: String?,
        @RequestParam(required = false) loops: Boolean?,
        @RequestParam(required = false) motion: String?,
        @RequestParam(required = false) slug: String?,
        @RequestParam(required = false, defaultValue = "8") limit: Int,
        authentication: Authentication?,
    ) = mapOf(
        "suggestions" to this.tags.suggest(
            TagService.SuggestInput(
                title = title,
                text = text,
                tags = tags.orEmpty().split(',', ';', ' ').filter { it.isNotBlank() },
                loops = loops,
                motion = motion,
                slug = slug,
                limit = limit,
            ),
            authentication.portalPrincipal(),
        )
    )
}
