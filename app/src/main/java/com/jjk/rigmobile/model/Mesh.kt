package com.jjk.rigmobile.model

import com.jjk.rigmobile.math.Vec3

/**
 * A single imported mesh: flat vertex attribute arrays + triangle indices.
 * Each vertex occupies 3 floats in [positions]/[normals] and 2 in [uvs].
 */
class Mesh(
    val positions: FloatArray,
    val normals: FloatArray,
    val uvs: FloatArray,
    val indices: IntArray
) {
    val vertexCount: Int get() = positions.size / 3

    fun boundsMin(): Vec3 {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var i = 0
        while (i < positions.size) {
            if (positions[i] < minX) minX = positions[i]
            if (positions[i + 1] < minY) minY = positions[i + 1]
            if (positions[i + 2] < minZ) minZ = positions[i + 2]
            i += 3
        }
        return Vec3(minX, minY, minZ)
    }

    fun boundsMax(): Vec3 {
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        var i = 0
        while (i < positions.size) {
            if (positions[i] > maxX) maxX = positions[i]
            if (positions[i + 1] > maxY) maxY = positions[i + 1]
            if (positions[i + 2] > maxZ) maxZ = positions[i + 2]
            i += 3
        }
        return Vec3(maxX, maxY, maxZ)
    }

    fun vertexAt(index: Int): Vec3 =
        Vec3(positions[index * 3], positions[index * 3 + 1], positions[index * 3 + 2])
}
