package com.jjk.rigmobile.rig

import com.jjk.rigmobile.math.Vec3

/**
 * A single skeleton joint. [bindWorldPosition] is the bind-pose position in the
 * model's own coordinate space (mutable so the user can drag-adjust it before export).
 */
data class Bone(
    val name: String,
    val parentIndex: Int,
    var bindWorldPosition: Vec3
)
