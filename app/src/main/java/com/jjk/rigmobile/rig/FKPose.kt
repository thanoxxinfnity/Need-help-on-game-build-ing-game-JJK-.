package com.jjk.rigmobile.rig

import com.jjk.rigmobile.math.Mat4
import com.jjk.rigmobile.math.Vec3

/**
 * Builds a small "does the rig deform sensibly" test pose (bent elbows and
 * knees) via forward kinematics, so the user can visually confirm the
 * auto-skin weights before exporting. Returns one skinning matrix per bone —
 * already composed as poseWorld * inverseBind — ready to upload to the shader.
 */
object FKPose {

    fun testPoseSkinningMatrices(skeleton: Skeleton, bindPositions: List<Vec3>): Array<Mat4> {
        val n = skeleton.bones.size
        val poseWorld = arrayOfNulls<Mat4>(n)

        val fingerJoint = Regex("(Thumb|Index|Middle|Ring|Pinky)\\d")

        // Kept modest on purpose: a long shin/forearm segment rotated a large angle sweeps a
        // huge arc (a 60° knee bend on a shin that's ~20% of body height moves the foot over
        // a meter). This is meant as a gentle "does the skin follow the bones" sanity check,
        // not a stress test — big angles made correctly-skinned meshes look broken.
        fun localRotationFor(name: String): Mat4 = when {
            name.contains("ForeArm") -> Mat4.rotationX(25f)
            name.contains("Leg") && !name.contains("UpLeg") -> Mat4.rotationX(-25f)
            name == "Head" -> Mat4.rotationY(10f)
            fingerJoint.containsMatchIn(name) -> Mat4.rotationX(20f)
            else -> Mat4.identity()
        }

        for (i in 0 until n) {
            val bone = skeleton.bones[i]
            val parent = bone.parentIndex
            val localOffset = if (parent >= 0) bindPositions[i] - bindPositions[parent] else bindPositions[i]
            val parentWorld = if (parent >= 0) poseWorld[parent]!! else Mat4.identity()
            poseWorld[i] = parentWorld * Mat4.translation(localOffset) * localRotationFor(bone.name)
        }

        return Array(n) { i ->
            val inverseBind = Mat4.translation(bindPositions[i] * -1f)
            poseWorld[i]!! * inverseBind
        }
    }
}
