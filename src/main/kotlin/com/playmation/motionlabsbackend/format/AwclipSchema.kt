package com.playmation.motionlabsbackend.format

/**
 * Feste Mengen und Grenzen von `.awclip` Version 1 - Portierung von
 * `AWClipSchema.cs`. Jede Zahl hier ist Teil des Formats; ändert sie sich,
 * ändert sie sich auf beiden Seiten, und die Testdateien zeigen es.
 */
object AwclipSchema {
    const val FORMAT_NAME = "awclip"
    const val FORMAT_VERSION = 1

    const val MAX_COMPRESSED_BYTES = 8L * 1024 * 1024
    const val MAX_UNCOMPRESSED_BYTES = 64L * 1024 * 1024

    const val MAX_TITLE_LENGTH = 80
    const val MAX_DESCRIPTION_LENGTH = 2000
    const val MAX_TAGS = 10

    /**
     * Wie viele Schlagworte ein Clip beim TEILEN tragen darf (2026-09-26).
     *
     * Weniger als das Format erlaubt ([MAX_TAGS]), und mit Absicht nicht
     * dasselbe: die Datei bleibt bei zehn, damit jeder Clip, der schon mit
     * mehr geteilt wurde, weiter gelesen, geladen und umgeschrieben werden
     * kann. Diese Grenze gilt fuer das, was jemand NEU vergibt - fuenf
     * Schlagworte beschreiben einen Clip; ab dem sechsten wird es Rauschen,
     * das die Leiste des Katalogs fuellt.
     */
    const val MAX_SHARED_TAGS = 5
    const val MAX_TAG_LENGTH = 32
    const val MAX_TOOL_LENGTH = 80
    const val MIN_FRAME_RATE = 1.0
    const val MAX_FRAME_RATE = 240.0
    const val MAX_DURATION = 600.0

    /** Humanoid: mehr als 300 kann ein Clip nicht haben, die Namensmenge ist kleiner. */
    const val MAX_CURVES = 300

    /**
     * Generisch liegt die Grenze höher, und zwar gemessen: 83 Clips mit
     * Transformkurven im Dev-Projekt tragen im Median 47 Kurven - aber ein
     * Drache mit 125 Knochen trägt 1 136, und 30 % aller gemessenen Clips
     * liegen über den 300 des Humanoiden. Mit 300 wäre ausgerechnet der Fall
     * ausgeschlossen, für den es generische Clips gibt: eine Kreatur, die kein
     * Mensch ist.
     *
     * Die Speichergrenze ist das nicht - dafür stehen [MAX_KEYS_TOTAL] und
     * [MAX_UNCOMPRESSED_BYTES].
     */
    const val MAX_GENERIC_CURVES = 2000
    const val MAX_KEYS_PER_CURVE = 100_000
    const val MAX_KEYS_TOTAL = 2_000_000
    const val MAX_ABS_VALUE = 1e6
    const val KEY_TIME_TOLERANCE = 1e-3

    const val MAX_PREVIEW_FRAMES = 3600
    const val MAX_PREVIEW_FRAME_RATE = 60.0

    /**
     * Die öffentliche Lizenz. Eine einzige, damit das Teilen keine Lizenzkunde
     * verlangt: benutzen, ändern, kommerziell nutzen - ohne Bedingung.
     *
     * CC0 statt CC BY seit dem 2026-09-24 (Schema 10). CC BY verlangte von
     * jedem, der einen Clip in ein Spiel baut, den Namen des Urhebers in den
     * Credits - bei zwanzig Clips von zwoelf Leuten eine Buchfuehrung, die
     * viele meiden. Genannt wird der Urheber trotzdem: auf der Karte, im
     * Profil. Nur nicht mehr als Pflicht.
     */
    const val LICENSE_PUBLIC = "CC0-1.0"

    /**
     * Privat: der Clip liegt im Portal, und NUR SEIN BESITZER sieht ihn (dazu
     * die Admins, fuer Moderation und Loeschanfragen). Kein Nutzungsrecht.
     *
     * Bis zum 2026-09-24 hiess das "nicht gelistet, nur ueber den Link" - wer
     * den Link hatte, sah den Clip. Jetzt oeffnet der Link ihn fuer niemanden
     * sonst (CatalogService.visible).
     */
    const val LICENSE_PRIVATE = "ARR"

    val LICENSES = listOf(LICENSE_PUBLIC, LICENSE_PRIVATE)

    /**
     * Die Rigs.
     *
     * `humanoid` ist Unitys Muskelraum: eine feste, kleine Menge von Namen,
     * die auf JEDER humanoiden Figur dasselbe bedeuten. Darum steht sie unten
     * als Liste, und die Liste ist die Prüfung.
     *
     * `generic` ist alles andere - eine Tür, ein Schwanz, ein Kranarm. So ein
     * Clip hängt an SEINEM Skelett, mit Namen, die nur kennt, wer das Skelett
     * gebaut hat. Eine Liste dafür gibt es nicht und kann es nicht geben.
     *
     * WARUM DAS TROTZDEM OHNE FIGURENBIBLIOTHEK GEHT, und das ist der Grund,
     * warum hier überhaupt etwas aufgeht: ein generischer Clip lässt sich
     * nicht auf eine fremde Figur umrechnen - es gibt keinen gemeinsamen
     * Muskelraum, auf den man beide abbilden könnte. Er BRAUCHT aber auch
     * keine. Seine Vorschau bringt ihr Skelett selbst mit (`bones`, `parents`,
     * `rest`), und das Strichmännchen zeichnet genau das. Die Figur fehlt
     * nicht - sie wäre an einer Tür schlicht falsch.
     */
    const val RIG_HUMANOID = "humanoid"
    const val RIG_GENERIC = "generic"

    val RIGS = listOf(RIG_HUMANOID, RIG_GENERIC)

    /**
     * Was das PORTAL annimmt - nicht, was das Format kann.
     *
     * [RIGS] bleibt vollständig: eine generische Datei, die jemand schon
     * geschrieben hat, muss weiter LESBAR sein, sonst würde aus einer Absage
     * ein Formatfehler, und die Testdateien prüfen genau diesen Unterschied.
     * Der Leser sagt, ob eine Datei heil ist; diese Liste sagt, ob wir sie
     * haben wollen. Zwei Fragen, zwei Orte.
     *
     * ANGENOMMEN wird vorerst nur `humanoid`. Die Community fängt mit dem
     * einen Fall an, der auf jeder fremden Figur läuft - ein generischer Clip
     * hängt an SEINEM Skelett, und alles, was das Portal darum herum baut
     * (Mannequin, Proportionen, eigene Figuren, Match Score), hat für ihn
     * keine Antwort.
     *
     * Diese Liste ist der Schalter, und sie ist der einzige. Kommt
     * [RIG_GENERIC] zurück, geht alles wieder auf, was hier gerade zu ist -
     * die Annahme beim Upload und die Auslage im Katalog. Die Workbench hat
     * dieselbe Liste als `AWClipSchema.AcceptedRigs`, damit die Absage schon
     * dort kommt; das letzte Wort hat diese hier.
     */
    val ACCEPTED_RIGS = listOf(RIG_HUMANOID)

    fun isAcceptedRig(rig: String) = rig in ACCEPTED_RIGS

    val ORIGINS = listOf("own", "unknown")

    /**
     * Die Grenzen für die freien Namen eines generischen Rigs.
     *
     * An die Stelle der Liste tritt eine REGEL, und eine Regel prüft nur, was
     * sie prüfen kann: Länge, keine Steuerzeichen, kein Leerraum am Rand. Was
     * ein Name BEDEUTET, weiß allein das Rig, aus dem er stammt - der Server
     * reicht ihn durch, er versteht ihn nicht.
     *
     * [MAX_PREVIEW_BONES] liegt über den 55 des Humanoiden, aber nicht offen:
     * die Vorschau trägt je Frame vier Zahlen pro Knochen, und das Produkt aus
     * Knochen und Frames bleibt so unter [MAX_UNCOMPRESSED_BYTES].
     */
    const val MAX_ATTRIBUTE_LENGTH = 255
    const val MAX_BONE_NAME_LENGTH = 64
    const val MAX_PREVIEW_BONES = 128

    fun isTag(tag: String): Boolean {
        if (tag.isEmpty() || tag.length > MAX_TAG_LENGTH) return false
        return tag.withIndex().all { (i, c) -> c in 'a'..'z' || c in '0'..'9' || (c == '-' && i > 0) }
    }

    /**
     * Ein freier Name eines generischen Rigs.
     *
     * DER RAND AUS LEERRAUM IST KEINE SCHÖNHEITSFRAGE. Zwei Namen, die sich
     * nur um ein Leerzeichen unterscheiden, sind für einen Menschen derselbe
     * und für die Duplikatprüfung zwei - damit ließe sich dieselbe Kurve
     * zweimal in einen Clip legen, und niemand sähe es.
     */
    fun isFreeName(name: String, maxLength: Int): Boolean =
        name.isNotEmpty() && name.length <= maxLength && name == name.trim() && name.none { it.isISOControl() }

    /** Gehört die Kurve zum Rig: humanoid an der Liste, generisch an der Regel. */
    fun isAttribute(attribute: String, rig: String): Boolean =
        if (rig == RIG_GENERIC) isFreeName(attribute, MAX_ATTRIBUTE_LENGTH) else attribute in HUMANOID_ATTRIBUTES

    /** Dasselbe für einen Knochen der Vorschau. */
    fun isPreviewBone(bone: String, rig: String): Boolean =
        if (rig == RIG_GENERIC) isFreeName(bone, MAX_BONE_NAME_LENGTH) else bone in PREVIEW_BONES

    /** Wie viele Knochen die Vorschau tragen darf. */
    fun maxPreviewBones(rig: String): Int =
        if (rig == RIG_GENERIC) MAX_PREVIEW_BONES else PREVIEW_BONES.size

    /** Wie viele Kurven ein Clip tragen darf - siehe [MAX_GENERIC_CURVES]. */
    fun maxCurves(rig: String): Int =
        if (rig == RIG_GENERIC) MAX_GENERIC_CURVES else MAX_CURVES

    /** Gemessen an 28 humanoiden Clips (134 Namen) plus die TDOF-Familie - siehe AWClipSchema.cs. */
    val HUMANOID_ATTRIBUTES: Set<String> = buildSet {
        addAll(
            listOf(
                "Spine Front-Back", "Spine Left-Right", "Spine Twist Left-Right",
                "Chest Front-Back", "Chest Left-Right", "Chest Twist Left-Right",
                "UpperChest Front-Back", "UpperChest Left-Right", "UpperChest Twist Left-Right",
                "Neck Nod Down-Up", "Neck Tilt Left-Right", "Neck Turn Left-Right",
                "Head Nod Down-Up", "Head Tilt Left-Right", "Head Turn Left-Right",
                "Left Eye Down-Up", "Left Eye In-Out", "Right Eye Down-Up", "Right Eye In-Out",
                "Jaw Close", "Jaw Left-Right",
            )
        )

        for (side in listOf("Left", "Right")) {
            for (muscle in listOf(
                "Upper Leg Front-Back", "Upper Leg In-Out", "Upper Leg Twist In-Out", "Lower Leg Stretch",
                "Lower Leg Twist In-Out", "Foot Up-Down", "Foot Twist In-Out", "Toes Up-Down",
                "Shoulder Down-Up", "Shoulder Front-Back", "Arm Down-Up", "Arm Front-Back", "Arm Twist In-Out",
                "Forearm Stretch", "Forearm Twist In-Out", "Hand Down-Up", "Hand In-Out",
            )) add("$side $muscle")

            for (finger in listOf("Thumb", "Index", "Middle", "Ring", "Little")) {
                val prefix = "${side}Hand.$finger."
                add("${prefix}1 Stretched")
                add("${prefix}2 Stretched")
                add("${prefix}3 Stretched")
                add("${prefix}Spread")
            }

            for (goal in listOf("Foot", "Hand")) {
                for (axis in listOf("x", "y", "z")) add("$side${goal}T.$axis")
                for (axis in listOf("x", "y", "z", "w")) add("$side${goal}Q.$axis")
            }
        }

        for (axis in listOf("x", "y", "z")) add("RootT.$axis")
        for (axis in listOf("x", "y", "z", "w")) add("RootQ.$axis")

        for (bone in listOf(
            "Spine", "Chest", "UpperChest", "Neck", "Head",
            "LeftShoulder", "RightShoulder", "LeftUpperArm", "RightUpperArm",
            "LeftLowerArm", "RightLowerArm", "LeftHand", "RightHand",
            "LeftUpperLeg", "RightUpperLeg", "LeftLowerLeg", "RightLowerLeg",
            "LeftFoot", "RightFoot", "LeftToes", "RightToes",
        )) for (axis in listOf("x", "y", "z")) add("${bone}TDOF.$axis")
    }

    val PREVIEW_BONES: Set<String> = buildSet {
        addAll(
            listOf(
                "Hips", "LeftUpperLeg", "RightUpperLeg", "LeftLowerLeg", "RightLowerLeg", "LeftFoot",
                "RightFoot", "Spine", "Chest", "Neck", "Head", "LeftShoulder", "RightShoulder",
                "LeftUpperArm", "RightUpperArm", "LeftLowerArm", "RightLowerArm", "LeftHand", "RightHand",
                "LeftToes", "RightToes", "LeftEye", "RightEye", "Jaw", "UpperChest",
            )
        )
        for (side in listOf("Left", "Right"))
            for (finger in listOf("Thumb", "Index", "Middle", "Ring", "Little"))
                for (segment in listOf("Proximal", "Intermediate", "Distal"))
                    add("$side$finger$segment")
    }
}
