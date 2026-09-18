package com.playmation.motionlabsbackend.format

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.zip.GZIPInputStream
import java.util.zip.ZipException

data class AwclipError(val code: String, val path: String, val message: String) {
    override fun toString() = if (path.isEmpty()) "$code: $message" else "$code at $path: $message"
}

data class AwclipKey(
    val time: Float,
    val value: Float,
    val inTangent: Float,
    val outTangent: Float,
    val weightedMode: Int = 0,
    val inWeight: Float = 0f,
    val outWeight: Float = 0f,
)

data class AwclipCurve(val attribute: String, val keys: List<AwclipKey>)

data class AwclipManifest(
    val title: String,
    val description: String,
    val tags: List<String>,
    val license: String,
    val rig: String,
    val frameRate: Float,
    val duration: Float,
    val tool: String,
)

/** Die Vorschau bleibt als geprüfter JSON-Baum erhalten - der Server reicht sie nur an den Viewer weiter. */
data class AwclipDocument(
    val manifest: AwclipManifest,
    val origin: String,
    val curves: List<AwclipCurve>,
    val hasSettings: Boolean,
    val preview: StrictJson.Value.Obj?,
)

sealed class AwclipReadResult {
    data class Ok(val document: AwclipDocument) : AwclipReadResult()
    data class Rejected(val error: AwclipError) : AwclipReadResult()
}

/**
 * Liest und prüft `.awclip` - Portierung von `AWClipReader.cs`, gleiche
 * Reihenfolge, gleiche Codes. Der erste Verstoß beendet das Lesen.
 */
object AwclipReader {
    private val TOP_LEVEL_FIELDS = setOf("format", "version", "manifest", "settings", "origin", "curves", "preview")
    private val MANIFEST_FIELDS = setOf("title", "description", "tags", "license", "rig", "frameRate", "duration", "tool")
    private val SETTINGS_BOOL_FIELDS = setOf(
        "loopTime", "loopBlend", "loopBlendOrientation", "loopBlendPositionY", "loopBlendPositionXZ",
        "keepOriginalOrientation", "keepOriginalPositionY", "keepOriginalPositionXZ", "heightFromFeet", "mirror",
    )
    private val SETTINGS_NUMBER_FIELDS = setOf("cycleOffset", "orientationOffsetY", "level")
    private val CURVE_FIELDS = setOf("attribute", "keys")
    private val PREVIEW_FIELDS = setOf("frameRate", "bones", "parents", "rest", "hips", "rotations")

    private class Reject(code: String, path: String, message: String) : RuntimeException(message) {
        val error = AwclipError(code, path, message)
    }

    fun readFile(input: InputStream): AwclipReadResult = try {
        readJson(decompress(input))
    } catch (reject: Reject) {
        AwclipReadResult.Rejected(reject.error)
    }

    fun readJson(json: String): AwclipReadResult = try {
        val root = try {
            StrictJson.parse(json)
        } catch (ex: StrictJson.JsonException) {
            throw Reject(ex.code, "", ex.message ?: "")
        }
        AwclipReadResult.Ok(readDocument(root))
    } catch (reject: Reject) {
        AwclipReadResult.Rejected(reject.error)
    }

    // ── Container ─────────────────────────────────────────────────────────

    private fun decompress(input: InputStream): String {
        val bounded = BoundedInputStream(input, AwclipSchema.MAX_COMPRESSED_BYTES)
        val magic = ByteArray(2)
        if (bounded.readNBytes(magic, 0, 2) != 2 || magic[0] != 0x1f.toByte() || magic[1] != 0x8b.toByte())
            throw Reject("not-gzip", "", "Not a gzip stream")

        val raw = ByteArrayOutputStream()
        try {
            GZIPInputStream(java.io.SequenceInputStream(magic.inputStream(), bounded)).use { gzip ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val n = gzip.read(buffer)
                    if (n < 0) break
                    raw.write(buffer, 0, n)
                    if (raw.size() > AwclipSchema.MAX_UNCOMPRESSED_BYTES)
                        throw Reject("too-large", "", "Uncompressed content larger than ${AwclipSchema.MAX_UNCOMPRESSED_BYTES} bytes")
                }
            }
        } catch (ex: ZipException) {
            throw Reject("not-gzip", "", ex.message ?: "Corrupt gzip stream")
        } catch (ex: java.io.EOFException) {
            throw Reject("not-gzip", "", "Truncated gzip stream")
        } catch (ex: IOException) {
            if (ex.cause is Reject) throw ex.cause as Reject
            throw Reject("not-gzip", "", ex.message ?: "Unreadable gzip stream")
        }

        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(raw.toByteArray()))
                .toString()
        } catch (ex: CharacterCodingException) {
            throw Reject("not-utf8", "", "Content is not valid UTF-8")
        }
    }

    private class BoundedInputStream(private val inner: InputStream, private val limit: Long) : InputStream() {
        private var count = 0L

        override fun read(): Int {
            val b = inner.read()
            if (b >= 0 && ++count > limit) throw Reject("too-large", "", "File larger than $limit bytes")
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = inner.read(b, off, len)
            if (n > 0) {
                count += n
                if (count > limit) throw Reject("too-large", "", "File larger than $limit bytes")
            }
            return n
        }
    }

    // ── Dokument ──────────────────────────────────────────────────────────

    private fun readDocument(root: StrictJson.Value): AwclipDocument {
        val obj = requireObject(root, "", TOP_LEVEL_FIELDS)

        if (requireString(obj, "format", "") != AwclipSchema.FORMAT_NAME)
            throw Reject("unsupported-format", "format", "Not an awclip document")

        val version = requireNumber(obj, "version", "")
        if (version != AwclipSchema.FORMAT_VERSION.toDouble())
            throw Reject("unsupported-version", "version", "Unsupported version $version")

        val manifest = readManifest(require(obj, "manifest", ""))

        val settings = obj["settings"]
        if (settings != null) readSettings(settings)

        val origin = requireString(obj, "origin", "")
        if (origin !in AwclipSchema.ORIGINS) throw Reject("invalid-origin", "origin", "Unknown origin '$origin'")

        val curves = readCurves(require(obj, "curves", ""), manifest)

        val preview = obj["preview"]?.let { readPreview(it) }

        return AwclipDocument(manifest, origin, curves, settings != null, preview)
    }

    private fun readManifest(node: StrictJson.Value): AwclipManifest {
        val at = "manifest"
        val obj = requireObject(node, at, MANIFEST_FIELDS)

        val title = requireString(obj, "title", at)
        if (title.trim().isEmpty() || title.length > AwclipSchema.MAX_TITLE_LENGTH || title != title.trim() ||
            hasControl(title, allowNewline = false)
        ) throw Reject("invalid-title", "$at.title", "Title must be 1-80 characters without line breaks")

        var description = ""
        obj["description"]?.let {
            description = expectString(it, "$at.description")
            if (description.length > AwclipSchema.MAX_DESCRIPTION_LENGTH || hasControl(description, allowNewline = true))
                throw Reject("invalid-description", "$at.description", "Description too long or malformed")
        }

        val tags = ArrayList<String>()
        obj["tags"]?.let { node ->
            val arr = expectArray(node, "$at.tags")
            if (arr.items.size > AwclipSchema.MAX_TAGS) throw Reject("invalid-tags", "$at.tags", "More than ${AwclipSchema.MAX_TAGS} tags")
            val seen = HashSet<String>()
            arr.items.forEachIndexed { i, v ->
                val tag = expectString(v, "$at.tags[$i]")
                if (!AwclipSchema.isTag(tag) || !seen.add(tag))
                    throw Reject("invalid-tags", "$at.tags[$i]", "Tags are lowercase letters, digits and '-', unique")
                tags.add(tag)
            }
        }

        val license = requireString(obj, "license", at)
        if (license !in AwclipSchema.LICENSES) throw Reject("invalid-license", "$at.license", "Unknown license '$license'")

        val rig = requireString(obj, "rig", at)
        if (rig !in AwclipSchema.RIGS) throw Reject("invalid-rig", "$at.rig", "Unsupported rig '$rig'")

        val frameRate = requireNumber(obj, "frameRate", at)
        if (frameRate < AwclipSchema.MIN_FRAME_RATE || frameRate > AwclipSchema.MAX_FRAME_RATE)
            throw Reject("invalid-frame-rate", "$at.frameRate", "Frame rate out of range")

        val duration = requireNumber(obj, "duration", at)
        if (!(duration > 0) || duration > AwclipSchema.MAX_DURATION)
            throw Reject("invalid-duration", "$at.duration", "Duration out of range")

        var tool = ""
        obj["tool"]?.let {
            tool = expectString(it, "$at.tool")
            if (tool.length > AwclipSchema.MAX_TOOL_LENGTH || hasControl(tool, false))
                throw Reject("invalid-tool", "$at.tool", "Tool name too long or malformed")
        }

        return AwclipManifest(title, description, tags, license, rig, frameRate.toFloat(), duration.toFloat(), tool)
    }

    private fun readSettings(node: StrictJson.Value) {
        val at = "settings"
        val obj = requireObject(node, at, SETTINGS_BOOL_FIELDS + SETTINGS_NUMBER_FIELDS)
        for ((key, value) in obj.members) {
            val path = "$at.$key"
            if (key in SETTINGS_BOOL_FIELDS) {
                if (value !is StrictJson.Value.Bool) throw Reject("invalid-setting", path, "Expected true or false")
            } else if (value !is StrictJson.Value.Number || kotlin.math.abs(value.value) > AwclipSchema.MAX_ABS_VALUE) {
                throw Reject("invalid-setting", path, "Expected a number")
            }
        }
    }

    private fun readCurves(node: StrictJson.Value, manifest: AwclipManifest): List<AwclipCurve> {
        val arr = expectArray(node, "curves")
        if (arr.items.isEmpty() || arr.items.size > AwclipSchema.MAX_CURVES)
            throw Reject("invalid-curve-count", "curves", "Between 1 and ${AwclipSchema.MAX_CURVES} curves required")

        val seen = HashSet<String>()
        val maxTime = manifest.duration.toDouble() + AwclipSchema.KEY_TIME_TOLERANCE
        var totalKeys = 0L
        val curves = ArrayList<AwclipCurve>(arr.items.size)

        arr.items.forEachIndexed { c, curveNode ->
            val at = "curves[$c]"
            val obj = requireObject(curveNode, at, CURVE_FIELDS)

            val attribute = requireString(obj, "attribute", at)
            if (attribute !in AwclipSchema.HUMANOID_ATTRIBUTES)
                throw Reject("unknown-attribute", "$at.attribute", "Not a humanoid curve: '$attribute'")
            if (!seen.add(attribute)) throw Reject("duplicate-attribute", "$at.attribute", "Curve appears twice")

            val keysNode = expectArray(require(obj, "keys", at), "$at.keys")
            if (keysNode.items.isEmpty() || keysNode.items.size > AwclipSchema.MAX_KEYS_PER_CURVE)
                throw Reject("invalid-key-count", "$at.keys", "Between 1 and ${AwclipSchema.MAX_KEYS_PER_CURVE} keys per curve")

            totalKeys += keysNode.items.size
            if (totalKeys > AwclipSchema.MAX_KEYS_TOTAL)
                throw Reject("invalid-key-count", "$at.keys", "More than ${AwclipSchema.MAX_KEYS_TOTAL} keys in total")

            var previousTime = Float.NEGATIVE_INFINITY
            val keys = ArrayList<AwclipKey>(keysNode.items.size)

            keysNode.items.forEachIndexed { k, keyNode ->
                val keyAt = "$at.keys[$k]"
                val key = readKey(keyNode, keyAt)

                if (key.time < -AwclipSchema.KEY_TIME_TOLERANCE || key.time > maxTime)
                    throw Reject("key-time-range", keyAt, "Key outside the clip duration")
                if (!(key.time > previousTime)) throw Reject("key-order", keyAt, "Key times must strictly increase")

                previousTime = key.time
                keys.add(key)
            }

            curves.add(AwclipCurve(attribute, keys))
        }

        return curves
    }

    private fun readKey(node: StrictJson.Value, at: String): AwclipKey {
        if (node !is StrictJson.Value.Arr || (node.items.size != 4 && node.items.size != 7))
            throw Reject("invalid-key", at, "A key is [time, value, inTangent, outTangent] or with [inWeight, outWeight, weightedMode] appended")

        val time = finiteNumber(node.items[0], "$at[0]")
        val value = finiteNumber(node.items[1], "$at[1]")
        val inTangent = tangent(node.items[2], "$at[2]")
        val outTangent = tangent(node.items[3], "$at[3]")

        if (node.items.size == 4) return AwclipKey(time, value, inTangent, outTangent)

        val inWeight = weight(node.items[4], "$at[4]")
        val outWeight = weight(node.items[5], "$at[5]")
        val mode = node.items[6]
        if (mode !is StrictJson.Value.Number || mode.value != kotlin.math.floor(mode.value) || mode.value < 0 || mode.value > 3)
            throw Reject("invalid-key", "$at[6]", "weightedMode is 0, 1, 2 or 3")

        return AwclipKey(time, value, inTangent, outTangent, mode.value.toInt(), inWeight, outWeight)
    }

    private fun readPreview(node: StrictJson.Value): StrictJson.Value.Obj {
        val at = "preview"
        val obj = requireObject(node, at, PREVIEW_FIELDS)

        val frameRate = requireNumber(obj, "frameRate", at)
        if (frameRate < AwclipSchema.MIN_FRAME_RATE || frameRate > AwclipSchema.MAX_PREVIEW_FRAME_RATE)
            throw Reject("invalid-preview", "$at.frameRate", "Preview frame rate out of range")

        val bones = expectArray(require(obj, "bones", at), "$at.bones")
        if (bones.items.isEmpty() || bones.items.size > AwclipSchema.PREVIEW_BONES.size)
            throw Reject("invalid-preview", "$at.bones", "Invalid bone count")

        val seen = HashSet<String>()
        bones.items.forEachIndexed { i, v ->
            val bone = expectString(v, "$at.bones[$i]")
            if (bone !in AwclipSchema.PREVIEW_BONES || !seen.add(bone))
                throw Reject("invalid-preview", "$at.bones[$i]", "Unknown or repeated bone '$bone'")
        }

        val count = bones.items.size
        val parents = expectArray(require(obj, "parents", at), "$at.parents")
        if (parents.items.size != count) throw Reject("invalid-preview", "$at.parents", "One parent per bone")

        parents.items.forEachIndexed { i, v ->
            val ok = v is StrictJson.Value.Number && v.value == kotlin.math.floor(v.value) &&
                (if (i == 0) v.value == -1.0 else v.value >= 0 && v.value < i)
            if (!ok) throw Reject("invalid-preview", "$at.parents[$i]", "The first bone is the root (-1), every other parent comes before its child")
        }

        vectorList(require(obj, "rest", at), "$at.rest", 3, count, count)

        val hips = expectArray(require(obj, "hips", at), "$at.hips")
        val frames = hips.items.size
        if (frames == 0 || frames > AwclipSchema.MAX_PREVIEW_FRAMES) throw Reject("invalid-preview", "$at.hips", "Invalid frame count")

        vectorList(hips, "$at.hips", 3, frames, frames)
        vectorList(require(obj, "rotations", at), "$at.rotations", 4 * count, frames, frames)

        return obj
    }

    private fun vectorList(node: StrictJson.Value, at: String, width: Int, minCount: Int, maxCount: Int) {
        if (node !is StrictJson.Value.Arr) throw Reject("wrong-type", at, "Expected array")
        if (node.items.size < minCount || node.items.size > maxCount)
            throw Reject("invalid-preview", at, "Unexpected number of entries")

        node.items.forEachIndexed { i, row ->
            if (row !is StrictJson.Value.Arr || row.items.size != width)
                throw Reject("invalid-preview", "$at[$i]", "Expected $width numbers")
            row.items.forEachIndexed { j, v ->
                if (v !is StrictJson.Value.Number || kotlin.math.abs(v.value) > AwclipSchema.MAX_ABS_VALUE)
                    throw Reject("invalid-preview", "$at[$i][$j]", "Expected a finite number")
            }
        }
    }

    // ── Bausteine ─────────────────────────────────────────────────────────

    private fun requireObject(node: StrictJson.Value, at: String, allowed: Set<String>): StrictJson.Value.Obj {
        if (node !is StrictJson.Value.Obj) throw Reject("wrong-type", at, "Expected object")
        for ((key, _) in node.members)
            if (key !in allowed) throw Reject("unknown-field", join(at, key), "Unknown field '$key'")
        return node
    }

    private fun require(obj: StrictJson.Value.Obj, field: String, at: String): StrictJson.Value =
        obj[field] ?: throw Reject("missing-field", join(at, field), "Missing field '$field'")

    private fun requireString(obj: StrictJson.Value.Obj, field: String, at: String) =
        expectString(require(obj, field, at), join(at, field))

    private fun requireNumber(obj: StrictJson.Value.Obj, field: String, at: String): Double {
        val value = require(obj, field, at)
        if (value !is StrictJson.Value.Number) throw Reject("wrong-type", join(at, field), "Expected number")
        return value.value
    }

    private fun expectString(value: StrictJson.Value, at: String): String =
        (value as? StrictJson.Value.Str)?.value ?: throw Reject("wrong-type", at, "Expected string")

    private fun expectArray(value: StrictJson.Value, at: String): StrictJson.Value.Arr =
        value as? StrictJson.Value.Arr ?: throw Reject("wrong-type", at, "Expected array")

    private fun finiteNumber(value: StrictJson.Value, at: String): Float {
        if (value !is StrictJson.Value.Number || kotlin.math.abs(value.value) > AwclipSchema.MAX_ABS_VALUE)
            throw Reject("invalid-key", at, "Expected a number within ±${AwclipSchema.MAX_ABS_VALUE}")
        return value.value.toFloat()
    }

    private fun tangent(value: StrictJson.Value, at: String): Float = when {
        value is StrictJson.Value.Str && value.value == "inf" -> Float.POSITIVE_INFINITY
        value is StrictJson.Value.Str && value.value == "-inf" -> Float.NEGATIVE_INFINITY
        value is StrictJson.Value.Str -> throw Reject("invalid-key", at, "A tangent is a number, \"inf\" or \"-inf\"")
        else -> finiteNumber(value, at)
    }

    private fun weight(value: StrictJson.Value, at: String): Float {
        if (value !is StrictJson.Value.Number || value.value < 0 || value.value > 1)
            throw Reject("invalid-key", at, "A weight lies between 0 and 1")
        return value.value.toFloat()
    }

    private fun hasControl(text: String, allowNewline: Boolean) =
        text.any { (it == '\n' && !allowNewline) || (it != '\n' && (it.code < 0x20 || it.code == 0x7f)) }

    private fun join(at: String, field: String) = if (at.isEmpty()) field else "$at.$field"
}
