package com.playmation.motionlabsbackend.format

import java.security.MessageDigest

/**
 * Inhalts-Hash - Portierung von `AWClipHash` (AWClipWriter.cs). SHA-256 über
 * die Bewegung allein; Titel, Lizenz und Vorschau zählen nicht, sonst umginge
 * ein neuer Titel die Wiederupload-Sperre.
 *
 * Zahlen sind floats, quantisiert als `floor(x · 10⁶ + 0,5)` und als Ganzzahl
 * geschrieben - Ganzzahlen, weil Dezimalformatierung zwischen Sprachen
 * unterschiedlich rundet, IEEE-Arithmetik nicht.
 */
object AwclipHash {
    fun compute(doc: AwclipDocument): String {
        if (doc.previewOnly) return previewHash(doc)

        val sb = StringBuilder("awclip-content-v1\n")

        //  Ordinal nach UTF-16-Codeeinheiten - wie string.CompareOrdinal in C#.
        for (curve in doc.curves.sortedWith { a, b -> a.attribute.compareTo(b.attribute) }) {
            sb.append(curve.attribute).append('\n')
            for (key in curve.keys) {
                quantized(sb, key.time); sb.append(',')
                quantized(sb, key.value); sb.append(',')
                quantized(sb, key.inTangent); sb.append(',')
                quantized(sb, key.outTangent)
                if (key.weightedMode != 0) {
                    sb.append(','); quantized(sb, key.inWeight)
                    sb.append(','); quantized(sb, key.outWeight)
                    sb.append(',').append(key.weightedMode)
                }
                sb.append('\n')
            }
        }

        return sha256(sb)
    }

    /**
     * Ein Clip ohne Kurven ([AwclipDocument.previewOnly]) hat seine Bewegung in
     * der Vorschau - also zaehlt die. Ueber die Kurven gerechnet haette JEDER
     * solche Clip denselben Hash, und schon der zweite stuende als Duplikat
     * des ersten da.
     *
     * Eigener Kopf (`awclip-preview-v1`): ein Hash ueber Kurven und einer ueber
     * eine Vorschau sollen nie dieselbe Eingabe haben. Die T-Pose zaehlt mit -
     * dieselben Drehungen auf einer anderen Ruhelage sind eine andere Bewegung.
     */
    private fun previewHash(doc: AwclipDocument): String {
        val sb = StringBuilder("awclip-preview-v1\n")
        val preview = doc.preview ?: return sha256(sb)

        for (name in listOf("bones", "parents", RestPose.FIELD, "hips", "rotations")) {
            sb.append(name).append('\n')
            val rows = (preview[name] as? StrictJson.Value.Arr)?.items ?: continue
            for (row in rows) {
                val values = (row as? StrictJson.Value.Arr)?.items ?: listOf(row)
                values.forEachIndexed { i, v ->
                    if (i > 0) sb.append(',')
                    when (v) {
                        is StrictJson.Value.Number -> quantized(sb, v.value.toFloat())
                        is StrictJson.Value.Str -> sb.append(v.value)
                        else -> sb.append('?')
                    }
                }
                sb.append('\n')
            }
        }

        return sha256(sb)
    }

    private fun sha256(sb: StringBuilder): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(sb.toString().toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun quantized(sb: StringBuilder, value: Float) {
        when {
            value == Float.POSITIVE_INFINITY -> sb.append("inf")
            value == Float.NEGATIVE_INFINITY -> sb.append("-inf")
            else -> sb.append(kotlin.math.floor(value.toDouble() * 1e6 + 0.5).toLong())
        }
    }
}
