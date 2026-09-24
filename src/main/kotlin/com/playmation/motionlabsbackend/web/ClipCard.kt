package com.playmation.motionlabsbackend.web

import com.playmation.motionlabsbackend.catalog.CatalogService
import com.playmation.motionlabsbackend.common.PortalException
import com.playmation.motionlabsbackend.format.AwclipSchema
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.awt.BasicStroke
import java.awt.Color
import java.awt.GradientPaint
import java.awt.RadialGradientPaint
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.GeneralPath
import java.awt.geom.Line2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Collections
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Das Bild, das erscheint, wenn jemand einen Clip in Discord verlinkt.
 *
 * WARUM ES DAS GEBEN MUSS. Die Adressen der Clips stehen laut Entwurf "in
 * Discord-Vorschauen" - dort stand bis hierher die nackte Adresse. Ein Portal,
 * dessen Verbreitung ueber einen Chat laeuft, verschenkt damit seinen
 * wichtigsten Auftritt: der Link zeigt nicht, was er zeigt.
 *
 * WARUM OHNE EIN EINZIGES WORT. Das Laufzeitbild ist `eclipse-temurin:17-jre-
 * alpine` - ohne Fontconfig und ohne eine einzige Schrift. Text mit
 * `java.awt.Font` waere dort im besten Fall ein Kaestchengitter. Er fehlt aber
 * auch inhaltlich nicht: Discord und Twitter setzen Titel und Beschreibung als
 * TEXT neben das Bild, aus `og:title` und `og:description`. Was im Bild stuende,
 * stuende zweimal da.
 *
 * Bleibt die Marke, und die ist eine Form, kein Wort: das Lambda mit der
 * Keyframe-Raute, hier als Pfad gezeichnet.
 *
 * DIE RECHNUNG IST DIE AUS `viewer.js`, Zeile fuer Zeile - dieselbe
 * Vorwaertskinematik, dieselbe Projektion, dieselben Seitenfarben. Sie steht
 * hier ein zweites Mal, weil das eine JavaScript im Browser ist und das andere
 * Java auf dem Server; wer eine aendert, muss an die andere denken. Der
 * Gegenwert ist, dass ein Clip in jedem Chat der Welt seine eigene Bewegung
 * zeigt, ohne dass irgendwo ein Browser laufen muss.
 */
@Component
class ClipCardRenderer {

    private val json = JsonMapper.builder().build()

    /**
     * Fertige Bilder. Discord holt dieselbe Adresse fuer jeden Post erneut, und
     * eine Vorwaertskinematik ueber alle Frames ist zu viel Arbeit fuer ein
     * Bild, das sich nur mit einer neuen Version aendert.
     *
     * DER SCHLUESSEL TRAEGT DIE VERSION, damit es keine Entwertung braucht.
     * Der Renderer wuesste sonst nur ueber den [CatalogService] von einer neuen
     * Version - und der muesste dann seinerseits den Renderer kennen, um ihm
     * Bescheid zu sagen. Ein Schluessel aus Slug und Versionsnummer loest das,
     * ohne dass die beiden voneinander wissen muessen: eine neue Version ist
     * schlicht ein anderer Schluessel, und der alte faellt irgendwann hinten
     * aus der Liste.
     */
    private val cache: MutableMap<String, ByteArray> = Collections.synchronizedMap(
        object : LinkedHashMap<String, ByteArray>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>) = size > MAX_CACHED
        }
    )

    fun card(slug: String, version: Int, preview: () -> ByteArray): ByteArray =
        cache.getOrPut("$slug:v$version") { render(preview()) }

    // ════════════════════════════════════════════════════════════════════
    // ZEICHNEN
    // ════════════════════════════════════════════════════════════════════

    private fun render(previewBytes: ByteArray): ByteArray {
        val preview = json.readTree(previewBytes)

        val bones = preview["bones"].map { it.asString() }
        val parents = preview["parents"].map { it.asInt() }
        val rest = preview["rest"].map { v -> floatArrayOf(v[0].asDouble().toFloat(), v[1].asDouble().toFloat(), v[2].asDouble().toFloat()) }
        val hips = preview["hips"].map { v -> floatArrayOf(v[0].asDouble().toFloat(), v[1].asDouble().toFloat(), v[2].asDouble().toFloat()) }
        val rotations = preview["rotations"]

        if (bones.isEmpty() || hips.isEmpty()) throw PortalException.notFound("This clip has no preview.")

        val frames = hips.indices.map { solveFrame(it, parents, rest, hips, rotations) }
        val points = frames[expressiveFrame(frames)]

        val image = BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

        //  Derselbe Verlauf wie die Buehne im Browser: oben Mitte heller.
        g.paint = GradientPaint(WIDTH / 2f, 0f, Color(0x1e, 0x1c, 0x28), WIDTH / 2f, HEIGHT.toFloat(), Color(0x0c, 0x0b, 0x10))
        g.fillRect(0, 0, WIDTH, HEIGHT)

        //  Ein weicher Schein hinter der Figur statt eines Bodenrasters. Das
        //  Raster des Viewers braucht Flaeche, um als Boden gelesen zu werden;
        //  in einer Vorschau von Daumennagelgroesse wird daraus ein Gitter aus
        //  Striemen. Der Schein verankert die Figur, ohne etwas zu behaupten.
        g.paint = RadialGradientPaint(
            java.awt.geom.Point2D.Float(WIDTH / 2f, HEIGHT / 2f), HEIGHT * 0.62f,
            floatArrayOf(0f, 1f),
            arrayOf(Color(0x8e, 0x77, 0xff, 34), Color(0x8e, 0x77, 0xff, 0)),
        )
        g.fillRect(0, 0, WIDTH, HEIGHT)

        drawFigure(g, points, bones, parents)
        drawMark(g)

        g.dispose()

        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }

    /**
     * Der Frame, der am meisten zu erzaehlen hat: der mit der groessten Summe
     * der Abstaende zur Huefte.
     *
     * Frame 0 waere die bequeme Wahl und fast immer die schlechteste - viele
     * Clips fangen in der Ruhelage an, und ein Laufzyklus saehe dann aus wie
     * jemand, der steht. Gemessen wird die AUSGESTRECKTHEIT, weil genau sie
     * eine Bewegung von einer Pose unterscheidet.
     */
    private fun expressiveFrame(frames: List<Array<FloatArray>>): Int {
        var best = 0
        var bestScore = -1.0
        for ((index, frame) in frames.withIndex()) {
            val root = frame[0]
            var score = 0.0
            for (p in frame) score += hypot((p[0] - root[0]).toDouble(), (p[2] - root[2]).toDouble()) + abs(p[1] - root[1])
            if (score > bestScore) {
                bestScore = score
                best = index
            }
        }
        return best
    }

    private fun drawFigure(g: java.awt.Graphics2D, points: Array<FloatArray>, bones: List<String>, parents: List<Int>) {
        //  Blickwinkel wie ueberall sonst: dieselben zwei Zahlen wie die
        //  Buehne im Browser (stage.js) und das Strichmaennchen (viewer.js) -
        //  leicht von der Seite und von oben, damit ein Schritt als Schritt
        //  lesbar ist und nicht als Strich. Wer einen Clip in Discord sieht
        //  und dann oeffnet, sieht zweimal dasselbe Bild.
        val yaw = PI - 0.55
        val pitch = 0.2

        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (p in points) {
            minY = min(minY, p[1])
            maxY = max(maxY, p[1])
        }
        val height = max(0.5f, maxY - minY)
        val root = points[0]
        val target = floatArrayOf(root[0], minY + height * 0.5f, root[2])

        val cy = cos(yaw); val sy = sin(yaw)
        val cp = cos(pitch); val sp = sin(pitch)

        /** Ein Punkt im Raum der Kamera, noch ohne Massstab und ohne Mitte. */
        fun flatten(p: FloatArray): DoubleArray {
            val x = (p[0] - target[0]).toDouble()
            val y = (p[1] - target[1]).toDouble()
            val z = (p[2] - target[2]).toDouble()
            val x1 = cy * x + sy * z
            val z1 = -sy * x + cy * z
            //  Ein groesserer Pitch HEBT die Kamera - dieselbe Bedeutung wie
            //  in `viewer.js`, wo die Herleitung steht.
            val y2 = cp * y + sp * z1
            val z2 = cp * z1 - sp * y
            val perspective = 3.5 / (3.5 + z2 / height)
            return doubleArrayOf(x1 * perspective, -y2 * perspective, z2)
        }

        //  ERST MESSEN, DANN EINPASSEN. Die Kamera auf die Huefte zu richten
        //  und mit der Koerperhoehe zu skalieren - so macht es der Viewer im
        //  Browser - ist dort richtig, weil die Figur laeuft und im Bild bleiben
        //  soll. Hier steht ein einzelner Augenblick, und zwar der am weitesten
        //  ausgestreckte: ein ausgestreckter Arm schob die Figur dann sichtbar
        //  aus der Mitte, und die Hoehe allein liess sie zu klein.
        //
        //  Also: alle Punkte flach rechnen, ihre Ausdehnung messen, und daraus
        //  Massstab und Mitte bestimmen. Was gezeichnet wird, sitzt damit immer
        //  mittig und immer gleich gross im Bild - unabhaengig von der Pose.
        val flat = Array(points.size) { flatten(points[it]) }

        var minX = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE
        var minFlatY = Double.MAX_VALUE; var maxFlatY = -Double.MAX_VALUE
        for (p in flat) {
            minX = min(minX, p[0]); maxX = max(maxX, p[0])
            minFlatY = min(minFlatY, p[1]); maxFlatY = max(maxFlatY, p[1])
        }
        val spanX = max(0.2, maxX - minX)
        val spanY = max(0.2, maxFlatY - minFlatY)

        //  Der Rahmen, den die Figur fuellen darf. Nicht das ganze Bild: eine
        //  Vorschau wird klein angezeigt, und eine Figur am Rand wirkt darin
        //  gedraengt.
        val scale = min(HEIGHT * 0.78 / spanY, WIDTH * 0.52 / spanX)
        val centerX = (minX + maxX) / 2 * scale
        val centerY = (minFlatY + maxFlatY) / 2 * scale

        val projected = Array(flat.size) { i ->
            doubleArrayOf(
                WIDTH / 2.0 + flat[i][0] * scale - centerX,
                HEIGHT / 2.0 + flat[i][1] * scale - centerY,
                flat[i][2],
            )
        }

        //  Hinten zuerst, damit vorne oben liegt.
        val order = projected.indices.sortedByDescending { projected[it][2] }

        val lineWidth = WIDTH / 150f

        //  Ein weicher Fleck unter dem tiefsten Punkt. Kein Schattenwurf -
        //  dafuer muesste man wissen, wo das Licht steht und wo der Boden ist,
        //  und bei einem Sprung waere beides gelogen. Es ist ein Halt: ohne
        //  ihn haengt die Figur in einem leeren Rechteck.
        //  Unter den TIEFSTEN PUNKT, nicht unter die Bildmitte: bei einem
        //  ausgestreckten Arm liegt die Mitte des Bildes neben der Figur, und
        //  der Fleck stuende dann als Schmutz daneben.
        val lowest = projected.indices.maxBy { projected[it][1] }
        val ground = projected[lowest][1]
        val shadowWidth = max(150.0, (maxX - minX) * scale * 0.55)
        g.paint = RadialGradientPaint(
            java.awt.geom.Point2D.Float(projected[lowest][0].toFloat(), ground.toFloat()),
            (shadowWidth / 2).toFloat(),
            floatArrayOf(0f, 1f),
            arrayOf(Color(0, 0, 0, 130), Color(0, 0, 0, 0)),
        )
        g.fill(Ellipse2D.Double(projected[lowest][0] - shadowWidth / 2, ground - 17, shadowWidth, 34.0))

        //  TIEFE ALS FARBE. Die Reihenfolge allein sagt nur, was vor was
        //  liegt; sie sagt nicht, wie weit. Ein Arm hinter dem Ruecken hatte
        //  dieselbe Leuchtkraft wie einer davor, und das Bild wurde flach.
        //  Was hinten steht, rueckt deshalb zur Hintergrundfarbe hin - die
        //  Luftperspektive eines Malers, in zwei Zeilen.
        val zNear = projected.minOf { it[2] }
        val zSpan = max(1e-6, projected.maxOf { it[2] } - zNear)

        fun away(z: Double) = (z - zNear) / zSpan

        fun fade(c: Color, t: Double): Color {
            val k = t * 0.34
            fun mix(channel: Int, target: Int) = (channel + (target - channel) * k).toInt()
            return Color(mix(c.red, 0x1a), mix(c.green, 0x18), mix(c.blue, 0x24))
        }

        //  EIN DURCHGANG, VON HINTEN NACH VORN, und jedes Glied bringt seinen
        //  eigenen Saum mit: erst ein breiterer dunkler Strich, dann die
        //  Farbe. Wo zwei Glieder sich kreuzen, schneidet das vordere damit
        //  sichtbar ins hintere - vorher liefen sie ineinander und der
        //  Koerper wurde zum Knaeuel. Die Gelenke gehoeren in denselben
        //  Durchgang; als eigener Nachlauf sassen sie auch auf Gliedern, die
        //  eigentlich davor liegen.
        //  DER RUMPF IST EINE FLAECHE, KEIN STRICH.
        //
        //  Als Kette aus Spine, Chest und UpperChest war er ein Stab von der
        //  Dicke eines Oberschenkels, an dem oben ein Querbalken fuer die
        //  Schultern sass - ein Drahtgestell. Ein Koerper hat zwischen
        //  Schultern und Huefte eine Breite, und die steht schon da: die vier
        //  Gelenke, an denen Arme und Beine ansetzen, sind seine Ecken.
        //
        //  Die Ecken werden um ihren Schwerpunkt nach Winkel sortiert. Das
        //  kostet fuenf Zeilen und nimmt dem Viereck die Moeglichkeit, sich
        //  bei einer starken Drehung selbst zu durchschlagen - aus der
        //  Schleife wird dann ein schmales Dreieck, was in einer Drehung auch
        //  richtig ist.
        //
        //  Ein fremdes Rig hat diese Gelenke nicht. Dann gibt es keinen Rumpf
        //  und die Kette wird gezeichnet wie zuvor.
        val corners = TRUNK_CORNERS.map { bones.indexOf(it) }.takeIf { it.none { j -> j < 0 } }
        val trunkZ = corners?.let { c -> c.sumOf { projected[it][2] } / c.size }

        fun drawTrunk(c: List<Int>) {
            val cx = c.sumOf { projected[it][0] } / c.size
            val cy = c.sumOf { projected[it][1] } / c.size
            val ring = c.sortedBy { atan2(projected[it][1] - cy, projected[it][0] - cx) }

            val path = GeneralPath()
            ring.forEachIndexed { k, j ->
                if (k == 0) path.moveTo(projected[j][0], projected[j][1])
                else path.lineTo(projected[j][0], projected[j][1])
            }
            path.closePath()

            //  DER HALS WAECHST AUS DER SCHULTERKANTE, nicht aus dem
            //  Halsgelenk. Das liegt je nach Rig ein Stueck ueber den
            //  Schultern, und die Strecke von dort zum Kopf ist so kurz, dass
            //  sie ganz unter dem Kopfkreis verschwand: der Kopf schwebte
            //  ueber einem Koerper, mit dem er sichtbar nichts zu tun hatte.
            //  Gezeichnet wird er VOR dem Rumpf, damit der ihn unten sauber
            //  abschneidet - herausstehen soll nur, was ueber den Schultern
            //  liegt.
            val head = bones.indexOf("Head")
            if (head >= 0) {
                val shoulders = c.take(2)
                val neck = Line2D.Double(
                    (projected[shoulders[0]][0] + projected[shoulders[1]][0]) / 2,
                    (projected[shoulders[0]][1] + projected[shoulders[1]][1]) / 2,
                    projected[head][0], projected[head][1],
                )
                val width = lineWidth * thickness("Neck")
                g.color = Color(0x0a, 0x09, 0x0e, 205)
                g.stroke = BasicStroke(width + lineWidth * 0.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.draw(neck)
                g.color = fade(Color(0xd9, 0xd5, 0xe4), away(projected[head][2]))
                g.stroke = BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.draw(neck)
            }

            //  Erst der Saum, dann die Flaeche darueber: was vom Strich aussen
            //  stehen bleibt, ist der Rand.
            g.color = Color(0x0a, 0x09, 0x0e, 205)
            g.stroke = BasicStroke(lineWidth * 1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.draw(path)
            g.color = fade(Color(0xd9, 0xd5, 0xe4), away(trunkZ!!))
            g.fill(path)
        }

        var trunkDone = corners == null

        for (i in order) {
            //  Der Rumpf liegt in derselben Reihenfolge wie alles andere: er
            //  kommt, sobald das erste Glied naeher steht als er.
            if (!trunkDone && projected[i][2] <= trunkZ!!) {
                drawTrunk(corners!!)
                trunkDone = true
            }

            val name = bones[i]
            if (hidden(name)) continue
            val parent = parents[i]
            val t = away(projected[i][2])

            if (parent >= 0 && !(corners != null && name in TRUNK)) {
                //  Seitenfarben aus der Marke: Akzentviolett links, Warmton rechts.
                val base = when {
                    name.startsWith("Left") -> Color(0x8e, 0x77, 0xff)
                    name.startsWith("Right") -> Color(0xfb, 0x92, 0x3c)
                    else -> Color(0xd9, 0xd5, 0xe4)
                }
                val width = lineWidth * thickness(name)
                val line = Line2D.Double(
                    projected[parent][0], projected[parent][1], projected[i][0], projected[i][1])

                //  DER SAUM REICHT NICHT BIS ANS GELENK. Der erste Versuch zog
                //  ihn ueber die ganze Strecke, und weil jeder Knochen nach
                //  seinem Vorgaenger gezeichnet wird, frass er dessen rundes
                //  Ende an: der Rumpf zerfiel in dunkel abgesetzte Glieder wie
                //  eine Raupe. Er hoert jetzt dort auf, wo der Knochen dick
                //  wird - trennen soll er dort, wo sich zwei Glieder KREUZEN,
                //  und das ist nie am eigenen Gelenk.
                val extra = lineWidth * 0.9f
                val seam = inset(line, ((width + extra) / 2).toDouble())
                if (seam != null) {
                    g.color = Color(0x0a, 0x09, 0x0e, 205)
                    g.stroke = BasicStroke(width + extra, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    g.draw(seam)
                }

                g.color = fade(base, t)
                g.stroke = BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.draw(line)
            }

            if (name != "Head" && name !in JOINTS) continue

            //  Der Kopf ist eine Kugel, jedes andere Gelenk ein Knopf, der
            //  gerade so ueber sein Glied hinausragt. Der alte Radius war
            //  fast doppelt so breit wie der Strich - eine Kette aus Perlen
            //  entlang der Wirbelsaeule.
            val radius = if (name == "Head") lineWidth * 3.2 else lineWidth * thickness(name) * 0.72
            val dot = Ellipse2D.Double(
                projected[i][0] - radius, projected[i][1] - radius, radius * 2, radius * 2)

            g.color = Color(0x0a, 0x09, 0x0e, 205)
            g.stroke = BasicStroke(lineWidth * 0.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.draw(dot)
            g.color = fade(Color(0xee, 0xec, 0xf3), t)
            g.fill(dot)
        }

        if (!trunkDone) drawTrunk(corners!!)
    }

    /**
     * Wie dick ein Knochen gezeichnet wird, in Vielfachen der Grundstaerke.
     *
     * Ein Koerper ist nicht ueberall gleich dick, und ein Strichmaennchen mit
     * ueberall demselben Strich sieht aus wie aus Draht gebogen. Rumpf am
     * dicksten, dann Becken und Oberschenkel, am duennsten Unterarm und Fuss.
     *
     * DIE NAMEN SIND UM EINS VERSETZT: gezeichnet wird die Strecke vom
     * ELTERNGELENK zu diesem Gelenk. Was hier "LeftHand" heisst, ist der
     * Unterarm; "LeftLowerArm" ist der Oberarm; "LeftUpperArm" ist das
     * Schluesselbein; "Head" ist der Hals. Wer diese Zahlen anfasst, muss das
     * im Kopf haben, sonst verdickt er die falsche Stelle.
     *
     * Ein fremdes Rig (`generic`) faellt auf 1.0 und sieht aus wie vorher.
     */
    /**
     * Dieselbe Strecke, an beiden Enden um [by] gekuerzt - oder `null`, wenn
     * dann nichts mehr uebrig bleibt. Ein Fuss ist kuerzer als er dick ist;
     * dort gibt es keinen Innenteil, und dann faellt der Saum eben aus.
     */
    private fun inset(line: Line2D.Double, by: Double): Line2D.Double? {
        val dx = line.x2 - line.x1
        val dy = line.y2 - line.y1
        val length = hypot(dx, dy)
        if (length <= by * 2.2) return null
        val ux = dx / length * by
        val uy = dy / length * by
        return Line2D.Double(line.x1 + ux, line.y1 + uy, line.x2 - ux, line.y2 - uy)
    }

    private fun thickness(bone: String): Float = when (bone) {
        "Spine", "Chest", "UpperChest" -> 2.4f
        "LeftUpperLeg", "RightUpperLeg" -> 1.8f
        "LeftLowerLeg", "RightLowerLeg" -> 1.45f
        "LeftFoot", "RightFoot" -> 1.15f
        "LeftToes", "RightToes" -> 0.9f
        "LeftShoulder", "RightShoulder" -> 1.6f
        "LeftUpperArm", "RightUpperArm" -> 1.25f
        "LeftLowerArm", "RightLowerArm" -> 1.1f
        "LeftHand", "RightHand" -> 0.9f
        "Neck" -> 1.3f
        "Head" -> 1.2f
        else -> 1.0f
    }

    /**
     * Was auf dieser Karte NICHT gezeichnet wird.
     *
     * Beides steckt in etwas anderem drin und macht es kaputt. Kiefer und
     * Augen liegen INNERHALB des Kopfkreises: gezeichnet werden sie zu einer
     * Beule an der Schaedeldecke, die wie ein Fehler aussieht. Und die Finger
     * sind bei 1200 Pixeln noch drei Striche, in der Vorschaugroesse eines
     * Chats aber ein Kratzer neben der Hand.
     *
     * Der Viewer im Browser zeigt beides weiter, und das ist kein
     * Widerspruch: dort ist die Figur gross, drehbar und der Gegenstand der
     * Seite. Hier ist sie einen Daumennagel gross.
     */
    private fun hidden(bone: String) = DETAIL.containsMatchIn(bone) || INSIDE_HEAD.matches(bone)

    /**
     * Die Marke, unten links.
     *
     * NICHT NACHGEBAUT, SONDERN ABGESCHRIEBEN: die Zahlen unten sind Zeile
     * fuer Zeile die aus `assets/brand/aw-mark-dark.svg`, nur durch 64
     * geteilt. Der erste Versuch hier zeichnete ein einzelnes Lambda mit der
     * Raute in der Mitte - das ergab ein plumpes "A" mit einem Punkt drin,
     * und es war keinem Blick anzusehen, dass es die Marke sein sollte. Die
     * Marke sind ZWEI Lambdas, das hintere versetzt und halb durchsichtig,
     * und die Raute sitzt nicht in der Mitte, sondern auf dem rechten
     * Schenkel des vorderen.
     *
     * Wer die SVG aendert, aendert sie hier mit. Das ist derselbe Handel wie
     * bei der Kinematik weiter oben, und er steht aus demselben Grund: auf
     * dem Server laeuft kein Browser, der eine SVG zeichnen koennte.
     */
    private fun drawMark(g: java.awt.Graphics2D) {
        val size = 72.0
        val unit = size / 64.0
        val left = 56.0
        val top = HEIGHT - 56.0 - size
        fun px(x: Double) = left + x * unit
        fun py(y: Double) = top + y * unit

        g.stroke = BasicStroke((8 * unit).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

        fun lambda(footLeft: Double, apex: Double, footRight: Double) = GeneralPath().apply {
            moveTo(px(footLeft), py(54.0))
            lineTo(px(apex), py(10.0))
            lineTo(px(footRight), py(54.0))
        }

        //  0.42 Deckung wie in der Datei.
        g.color = Color(0xff, 0xff, 0xff, 107)
        g.draw(lambda(11.0, 28.0, 45.0))
        g.color = Color(0xff, 0xff, 0xff)
        g.draw(lambda(19.0, 36.0, 53.0))

        val diamond = GeneralPath().apply {
            moveTo(px(36.0), py(33.0))
            lineTo(px(44.4), py(40.0))
            lineTo(px(36.0), py(47.0))
            lineTo(px(27.6), py(40.0))
            closePath()
        }
        g.color = Color(0x8e, 0x77, 0xff)
        g.fill(diamond)
    }

    // ── Vorwaertskinematik, wie in viewer.js ─────────────────────────────

    private fun solveFrame(
        frame: Int, parents: List<Int>, rest: List<FloatArray>,
        hips: List<FloatArray>, rotations: JsonNode,
    ): Array<FloatArray> {
        val row = rotations[frame]
        val count = parents.size
        val worldRot = Array(count) { FloatArray(4) }
        val worldPos = Array(count) { FloatArray(3) }

        for (i in 0 until count) {
            val local = floatArrayOf(
                row[i * 4].asDouble().toFloat(), row[i * 4 + 1].asDouble().toFloat(),
                row[i * 4 + 2].asDouble().toFloat(), row[i * 4 + 3].asDouble().toFloat(),
            )
            val parent = parents[i]
            if (parent < 0) {
                worldRot[i] = local
                worldPos[i] = hips[frame].copyOf()
            } else {
                worldRot[i] = qmul(worldRot[parent], local)
                val offset = qrot(worldRot[parent], rest[i])
                worldPos[i] = floatArrayOf(
                    worldPos[parent][0] + offset[0],
                    worldPos[parent][1] + offset[1],
                    worldPos[parent][2] + offset[2],
                )
            }
        }
        return worldPos
    }

    private fun qmul(a: FloatArray, b: FloatArray) = floatArrayOf(
        a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
        a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
        a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
        a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2],
    )

    private fun qrot(q: FloatArray, v: FloatArray): FloatArray {
        val x = q[0]; val y = q[1]; val z = q[2]; val w = q[3]
        val tx = 2 * (y * v[2] - z * v[1])
        val ty = 2 * (z * v[0] - x * v[2])
        val tz = 2 * (x * v[1] - y * v[0])
        return floatArrayOf(
            v[0] + w * tx + (y * tz - z * ty),
            v[1] + w * ty + (z * tx - x * tz),
            v[2] + w * tz + (x * ty - y * tx),
        )
    }

    companion object {
        /** Das Mass, das Discord, Twitter und Slack gleichermassen erwarten. */
        const val WIDTH = 1200
        const val HEIGHT = 630

        /** Dieselben Prefixe wie `SkeletonViewer.DETAIL` und die Workbench. */
        private val DETAIL = Regex("^(Left|Right)(Thumb|Index|Middle|Ring|Little)")

        /** Kiefer und Augen - sie sitzen im Kopf, siehe [hidden]. */
        private val INSIDE_HEAD = Regex("^(Jaw|(Left|Right)Eye)$")

        /**
         * Wo ein Knopf sitzt: an den Stellen, die sich wirklich beugen.
         *
         * Wirbelsaeule, Hals und Schluesselbein stehen NICHT drin. Sie
         * beugen sich zwar auch, aber sie liegen so dicht beieinander, dass
         * aus vier Knoepfen eine Perlenkette im Rumpf wurde.
         */
        /**
         * Die vier Ecken des Rumpfes: die Gelenke, an denen die Glieder
         * ansetzen. Die Reihenfolge spielt keine Rolle, sie wird ohnehin nach
         * Winkel sortiert.
         */
        private val TRUNK_CORNERS = listOf(
            "LeftUpperArm", "RightUpperArm", "RightUpperLeg", "LeftUpperLeg")

        /**
         * Was im Rumpf verschwindet, sobald es ihn gibt: die Wirbelsaeule, die
         * Schluesselbeine und das Becken liegen alle INNERHALB der Flaeche
         * oder auf ihrem Rand. Gezeichnet ergaeben sie Striche auf einem
         * Koerper.
         *
         * "Neck" und "Head" stehen mit drin, obwohl der Hals sichtbar ist:
         * die eine Strecke laeuft vom UpperChest zum Hals und damit quer
         * durch die Brust, die andere ist zu kurz, um unter dem Kopf
         * hervorzukommen. Den Hals zeichnet [drawTrunk] selbst, von der
         * Schulterkante aus.
         */
        private val TRUNK = setOf(
            "Spine", "Chest", "UpperChest", "Neck", "Head",
            "LeftShoulder", "RightShoulder",
            "LeftUpperArm", "RightUpperArm",
            "LeftUpperLeg", "RightUpperLeg",
        )

        private val JOINTS = setOf(
            "LeftUpperArm", "RightUpperArm",
            "LeftLowerArm", "RightLowerArm",
            "LeftHand", "RightHand",
            "LeftUpperLeg", "RightUpperLeg",
            "LeftLowerLeg", "RightLowerLeg",
            "LeftFoot", "RightFoot",
        )

        private const val MAX_CACHED = 200
    }
}

/**
 * Das Kartenbild als Datei.
 *
 * Der Name traegt `.png`, weil manche Dienste an der Endung entscheiden, ob
 * sie ein Bild ueberhaupt holen.
 */
@RestController
class ClipCardController(
    private val cards: ClipCardRenderer,
    private val catalog: CatalogService,
) {

    @GetMapping("/clip-card/{slug}.png", produces = [MediaType.IMAGE_PNG_VALUE])
    fun card(@PathVariable slug: String): ResponseEntity<ByteArray> {
        //  Ein privater Clip bekommt keine Vorschau. `detail` ohne Konto gibt
        //  ihn seit Schema 10 gar nicht mehr her; die Zeile darunter haelt
        //  trotzdem Wache, falls jemand hier einmal mit Anmeldung fragt.
        val clip = catalog.detail(slug, null)
        if (clip.license != AwclipSchema.LICENSE_PUBLIC) throw PortalException.notFound("No preview card for this clip.")

        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(7, TimeUnit.DAYS).cachePublic())
            .body(cards.card(slug, clip.version) { catalog.previewJson(slug, null) })
    }
}
