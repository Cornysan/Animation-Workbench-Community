package com.playmation.motionlabsbackend.format

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Die T-Pose der Quellfigur, als zweites Formularfeld neben der `.awclip`.
 *
 * WARUM SIE UEBERHAUPT GEBRAUCHT WIRD. Die Vorschau traegt je Knochen
 * Versaetze (`rest`) und Drehungen je Bild - aber keine Ruhelage. Die
 * Drehungen stehen im Knochenraum der Quellfigur, und ohne deren T-Pose laesst
 * sich nicht trennen, was Haltung ist und was blosse Achsenkonvention ihres
 * Rigs. Wer aus der Vorschau eine Datei mit Skelett schreibt (die `.fbx` auf
 * der Clip-Seite), braucht aber genau das: Unity baut den Avatar einer
 * importierten Datei aus ihrer Ruhelage. Stand dort Bild 0 - eine Kampfpose -,
 * lagen die Oberarme gemessen 71 bis 76 Grad neben der echten T-Pose, und die
 * Bewegung kam auf jeder Figur verdreht an.
 *
 * WARUM NEBEN DER DATEI UND NICHT DARIN. Ein neues Feld im Vorschau-Block
 * waere ein Formatsprung: der Leser ist streng, jede laufende Installation
 * wiese die Datei ab. Neben der Datei bleibt das Format bei Version 1, und der
 * Client schickt das Feld nur, wenn `/status` `restPoseWanted` sagt.
 *
 * Gespeichert wird es IN der Vorschau, als `restRot` - dort, wo es gelesen
 * wird. Der Vorschau-Endpunkt liefert es damit ohne eine Zeile mehr aus.
 */
object RestPose {
    const val FIELD = "restRot"

    /** 55 Knochen zu je vier Zahlen sind gut 2 KB - das Achtfache reicht fuer jede Schreibweise. */
    const val MAX_TEXT_LENGTH = 16 * 1024

    /**
     * Wie weit ein Quaternion von der Laenge 1 abweichen darf. Der Client
     * schreibt `float` in kuerzester Form; mehr als Rundung ist ein Fehler.
     */
    private const val UNIT_TOLERANCE = 1e-3

    /**
     * Die Vorschau mit ihrer T-Pose. Null, wenn das Feld nicht zu ihr passt:
     * je Knochen genau ein Quaternion (x, y, z, w), in derselben Reihenfolge
     * wie `bones`, jedes mit der Laenge 1.
     */
    fun attach(preview: StrictJson.Value.Obj, text: String): StrictJson.Value.Obj? {
        if (text.length > MAX_TEXT_LENGTH) return null

        val bones = preview["bones"] as? StrictJson.Value.Arr ?: return null
        val rows = try {
            StrictJson.parse(text)
        } catch (e: Exception) {
            return null
        } as? StrictJson.Value.Arr ?: return null

        if (rows.items.size != bones.items.size) return null

        for (row in rows.items) {
            val q = (row as? StrictJson.Value.Arr)?.items ?: return null
            if (q.size != 4) return null
            val values = q.map { (it as? StrictJson.Value.Number)?.value ?: return null }
            if (values.any { !it.isFinite() }) return null
            val length = sqrt(values.sumOf { it * it })
            if (abs(length - 1.0) > UNIT_TOLERANCE) return null
        }

        return StrictJson.Value.Obj(preview.members.filter { it.first != FIELD } + (FIELD to rows))
    }
}
