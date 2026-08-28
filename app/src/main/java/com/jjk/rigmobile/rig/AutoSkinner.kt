package com.jjk.rigmobile.rig

import com.jjk.rigmobile.math.Vec3
import com.jjk.rigmobile.model.Mesh

/** Per-vertex skin weights: up to 4 influencing bones each, matching glTF's JOINTS_0/WEIGHTS_0 layout. */
class SkinWeights(val joints: IntArray, val weights: FloatArray) {
    companion object {
        const val INFLUENCES_PER_VERTEX = 4
    }
}

/**
 * Computes bone skin weights per vertex from bone bind positions alone (no
 * volumetric/heat-diffusion solve — this is a distance-to-bone-segment
 * heuristic). Each bone's influence region is the capsule between itself and
 * its child joint(s) (or, for leaf bones, between itself and its parent).
 * The nearest 4 bones win, inverse-square weighted and normalized, then
 * smoothed one pass across triangle-adjacent vertices to avoid hard seams.
 */
object AutoSkinner {

    private data class Segment(val boneIndex: Int, val start: Vec3, val end: Vec3)

    fun computeWeights(mesh: Mesh, skeleton: Skeleton): SkinWeights {
        val segments = buildSegments(skeleton)
        val vertexCount = mesh.vertexCount
        val joints = IntArray(vertexCount * SkinWeights.INFLUENCES_PER_VERTEX)
        val weights = FloatArray(vertexCount * SkinWeights.INFLUENCES_PER_VERTEX)

        for (v in 0 until vertexCount) {
            val p = mesh.vertexAt(v)
            // Best (min) distance per bone across all of its segments.
            val bestDistPerBone = HashMap<Int, Float>()
            for (seg in segments) {
                val d = pointToSegmentDistance(p, seg.start, seg.end)
                val prev = bestDistPerBone[seg.boneIndex]
                if (prev == null || d < prev) bestDistPerBone[seg.boneIndex] = d
            }
            val top = bestDistPerBone.entries.sortedBy { it.value }.take(SkinWeights.INFLUENCES_PER_VERTEX)
            var sum = 0f
            val rawWeights = FloatArray(top.size)
            for ((i, entry) in top.withIndex()) {
                val w = 1f / ((entry.value * entry.value) + 1e-4f)
                rawWeights[i] = w
                sum += w
            }
            for (i in top.indices) {
                joints[v * SkinWeights.INFLUENCES_PER_VERTEX + i] = top[i].key
                weights[v * SkinWeights.INFLUENCES_PER_VERTEX + i] = if (sum > 0f) rawWeights[i] / sum else 0f
            }
            // Unused influence slots stay at joint 0 / weight 0, which is harmless.
        }

        return smooth(mesh, SkinWeights(joints, weights))
    }

    private fun buildSegments(skeleton: Skeleton): List<Segment> {
        val segments = ArrayList<Segment>()
        for (i in skeleton.bones.indices) {
            val children = skeleton.childrenOf(i)
            if (children.isNotEmpty()) {
                for (c in children) {
                    segments.add(Segment(i, skeleton.bones[i].bindWorldPosition, skeleton.bones[c].bindWorldPosition))
                }
            } else {
                val parent = skeleton.bones[i].parentIndex
                val start = if (parent >= 0) skeleton.bones[parent].bindWorldPosition else skeleton.bones[i].bindWorldPosition
                segments.add(Segment(i, start, skeleton.bones[i].bindWorldPosition))
            }
        }
        return segments
    }

    private fun pointToSegmentDistance(p: Vec3, a: Vec3, b: Vec3): Float {
        val ab = b - a
        val abLenSq = ab.x * ab.x + ab.y * ab.y + ab.z * ab.z
        if (abLenSq < 1e-8f) return p.distanceTo(a)
        val ap = p - a
        var t = (ap.x * ab.x + ap.y * ab.y + ap.z * ab.z) / abLenSq
        t = t.coerceIn(0f, 1f)
        val closest = a + ab * t
        return p.distanceTo(closest)
    }

    /** One pass of averaging each vertex's weights with its triangle-adjacent neighbors, to soften hard bone-region seams. */
    private fun smooth(mesh: Mesh, skin: SkinWeights): SkinWeights {
        val vertexCount = mesh.vertexCount
        val neighbors = Array(vertexCount) { HashSet<Int>() }
        var i = 0
        while (i < mesh.indices.size) {
            val a = mesh.indices[i]; val b = mesh.indices[i + 1]; val c = mesh.indices[i + 2]
            neighbors[a].add(b); neighbors[a].add(c)
            neighbors[b].add(a); neighbors[b].add(c)
            neighbors[c].add(a); neighbors[c].add(b)
            i += 3
        }

        val newJoints = IntArray(skin.joints.size)
        val newWeights = FloatArray(skin.weights.size)
        val n = SkinWeights.INFLUENCES_PER_VERTEX

        for (v in 0 until vertexCount) {
            val blended = HashMap<Int, Float>()
            for (k in 0 until n) {
                val j = skin.joints[v * n + k]
                val w = skin.weights[v * n + k]
                if (w > 0f) blended[j] = (blended[j] ?: 0f) + w * 0.6f
            }
            val neighborSet = neighbors[v]
            if (neighborSet.isNotEmpty()) {
                val share = 0.4f / neighborSet.size
                for (nb in neighborSet) {
                    for (k in 0 until n) {
                        val j = skin.joints[nb * n + k]
                        val w = skin.weights[nb * n + k]
                        if (w > 0f) blended[j] = (blended[j] ?: 0f) + w * share
                    }
                }
            }
            val top = blended.entries.sortedByDescending { it.value }.take(n)
            val sum = top.sumOf { it.value.toDouble() }.toFloat()
            for ((idx, entry) in top.withIndex()) {
                newJoints[v * n + idx] = entry.key
                newWeights[v * n + idx] = if (sum > 0f) entry.value / sum else 0f
            }
        }
        return SkinWeights(newJoints, newWeights)
    }
}
