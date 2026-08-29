package com.jjk.rigmobile.rig

import com.jjk.rigmobile.math.Vec3
import com.jjk.rigmobile.model.Mesh

/**
 * Fits a standard Mixamo-compatible humanoid skeleton to a mesh's bounding box
 * using average human body proportions (fractions of total height, measured
 * from the lowest point of the mesh upward). This is a heuristic fit, not a
 * learned/topology-aware rig — assumes a roughly upright, front-facing,
 * left/right-symmetric biped (T-pose or A-pose), which covers the vast
 * majority of game character models. The joint-adjustment step exists
 * precisely to correct whatever this heuristic gets wrong for a given model.
 *
 * Coordinate convention assumed: Y-up, model roughly centered on X/Z.
 */
object AutoRigger {

    private data class Def(val name: String, val parent: String?, val yFrac: Float, val xFrac: Float, val zFrac: Float = 0f)

    // yFrac/xFrac/zFrac are fractions of (height H / half-width W / depth D) offset from
    // the bounding box's bottom-center. xFrac is signed per-side (mirrored for Left/Right).
    private val bodyDefs = listOf(
        Def("Hips", null, 0.50f, 0f),
        Def("Spine", "Hips", 0.60f, 0f),
        Def("Spine1", "Spine", 0.68f, 0f),
        Def("Spine2", "Spine1", 0.74f, 0f),
        Def("Neck", "Spine2", 0.84f, 0f),
        Def("Head", "Neck", 0.90f, 0f),

        Def("LeftShoulder", "Spine2", 0.79f, 0.10f),
        Def("LeftArm", "LeftShoulder", 0.78f, 0.20f),
        Def("LeftForeArm", "LeftArm", 0.60f, 0.24f),
        Def("LeftHand", "LeftForeArm", 0.44f, 0.28f),

        Def("RightShoulder", "Spine2", 0.79f, -0.10f),
        Def("RightArm", "RightShoulder", 0.78f, -0.20f),
        Def("RightForeArm", "RightArm", 0.60f, -0.24f),
        Def("RightHand", "RightForeArm", 0.44f, -0.28f),

        Def("LeftUpLeg", "Hips", 0.49f, 0.10f),
        Def("LeftLeg", "LeftUpLeg", 0.27f, 0.10f),
        Def("LeftFoot", "LeftLeg", 0.05f, 0.10f),
        Def("LeftToeBase", "LeftFoot", 0.01f, 0.10f, 0.15f),

        Def("RightUpLeg", "Hips", 0.49f, -0.10f),
        Def("RightLeg", "RightUpLeg", 0.27f, -0.10f),
        Def("RightFoot", "RightLeg", 0.05f, -0.10f),
        Def("RightToeBase", "RightFoot", 0.01f, -0.10f, 0.15f)
    )

    private data class FingerSpec(val name: String, val jointCount: Int, val zFrac: Float, val yDropPerJoint: Float)

    // One "column" per finger, spreading front-to-back (zFrac) across the palm;
    // xFrac grows per-joint to walk out along the finger away from the hand.
    private val fingerSpecs = listOf(
        FingerSpec("Thumb", 2, 0.05f, 0.004f),
        FingerSpec("Index", 3, 0.020f, 0.010f),
        FingerSpec("Middle", 3, 0.000f, 0.012f),
        FingerSpec("Ring", 3, -0.020f, 0.010f),
        FingerSpec("Pinky", 3, -0.038f, 0.008f)
    )

    /** Fixed uniform-array size for the GPU skinning shader — the worst case (22 body + 14-per-hand fingers x2), regardless of what a given rig actually uses. */
    const val MAX_BONE_COUNT = 50

    fun fit(mesh: Mesh, includeFingers: Boolean = false): Skeleton {
        val min = mesh.boundsMin()
        val max = mesh.boundsMax()
        val height = (max.y - min.y).coerceAtLeast(1e-4f)
        val halfWidth = (max.x - min.x) / 2f
        val depth = (max.z - min.z)
        val centerX = (min.x + max.x) / 2f
        val centerZ = (min.z + max.z) / 2f
        val baseY = min.y

        val nameToIndex = HashMap<String, Int>()
        val bones = ArrayList<Bone>()

        fun place(def: Def) {
            val parentIndex = def.parent?.let { nameToIndex.getValue(it) } ?: -1
            val pos = Vec3(
                centerX + def.xFrac * halfWidth,
                baseY + def.yFrac * height,
                centerZ + def.zFrac * depth
            )
            bones.add(Bone(def.name, parentIndex, pos))
            nameToIndex[def.name] = bones.size - 1
        }

        for (def in bodyDefs) place(def)

        if (includeFingers) {
            for (side in listOf("Left" to 1f, "Right" to -1f)) {
                val (sideName, sign) = side
                val handName = "${sideName}Hand"
                val handXFrac = if (sideName == "Left") 0.28f else -0.28f
                val handYFrac = 0.44f
                for (finger in fingerSpecs) {
                    var parentName = handName
                    for (j in 1..finger.jointCount) {
                        val boneName = "$sideName${finger.name}$j"
                        val xFrac = handXFrac + sign * (0.02f + j * 0.025f)
                        val yFrac = handYFrac - j * finger.yDropPerJoint
                        place(Def(boneName, parentName, yFrac, xFrac, finger.zFrac))
                        parentName = boneName
                    }
                }
            }
        }

        return Skeleton(bones)
    }
}
