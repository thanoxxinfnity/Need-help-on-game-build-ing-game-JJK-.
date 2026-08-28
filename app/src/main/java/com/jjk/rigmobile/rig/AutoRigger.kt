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
    private val defs = listOf(
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

    val BONE_COUNT = defs.size

    fun fit(mesh: Mesh): Skeleton {
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
        for (def in defs) {
            val parentIndex = def.parent?.let { nameToIndex.getValue(it) } ?: -1
            val pos = Vec3(
                centerX + def.xFrac * halfWidth,
                baseY + def.yFrac * height,
                centerZ + def.zFrac * depth
            )
            bones.add(Bone(def.name, parentIndex, pos))
            nameToIndex[def.name] = bones.size - 1
        }
        return Skeleton(bones)
    }
}
