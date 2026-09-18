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
