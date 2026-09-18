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
    const val MAX_TAG_LENGTH = 32
    const val MAX_TOOL_LENGTH = 80
    const val MIN_FRAME_RATE = 1.0
    const val MAX_FRAME_RATE = 240.0
    const val MAX_DURATION = 600.0

    const val MAX_CURVES = 300
    const val MAX_KEYS_PER_CURVE = 100_000
    const val MAX_KEYS_TOTAL = 2_000_000
    const val MAX_ABS_VALUE = 1e6
    const val KEY_TIME_TOLERANCE = 1e-3

    const val MAX_PREVIEW_FRAMES = 3600
    const val MAX_PREVIEW_FRAME_RATE = 60.0

    /**
     * Die öffentliche Lizenz. Eine einzige, damit das Teilen keine Lizenzkunde
     * verlangt: nennen, ändern, kommerziell nutzen.
     */
    const val LICENSE_PUBLIC = "CC-BY-4.0"

    /**
     * Kein Nutzungsrecht: der Clip liegt im Portal, ist aber nicht gelistet und
     * nur über seinen Link erreichbar.
     */
    const val LICENSE_PRIVATE = "ARR"

    val LICENSES = listOf(LICENSE_PUBLIC, LICENSE_PRIVATE)

    val RIGS = listOf("humanoid")
    val ORIGINS = listOf("own", "unknown")

    fun isTag(tag: String): Boolean {
        if (tag.isEmpty() || tag.length > MAX_TAG_LENGTH) return false
        return tag.withIndex().all { (i, c) -> c in 'a'..'z' || c in '0'..'9' || (c == '-' && i > 0) }
    }

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
