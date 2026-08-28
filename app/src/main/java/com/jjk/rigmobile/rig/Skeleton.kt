package com.jjk.rigmobile.rig

/**
 * Bone list, always topologically ordered (a bone's parentIndex is always
 * smaller than its own index, root bones have parentIndex == -1).
 */
class Skeleton(val bones: List<Bone>) {

    fun childrenOf(index: Int): List<Int> =
        bones.indices.filter { bones[it].parentIndex == index }

    fun isRoot(index: Int): Boolean = bones[index].parentIndex == -1

    companion object {
        const val HIPS = 0
    }
}
