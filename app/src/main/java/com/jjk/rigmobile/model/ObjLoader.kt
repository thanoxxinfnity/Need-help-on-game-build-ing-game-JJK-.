package com.jjk.rigmobile.model

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Minimal Wavefront OBJ loader: positions, normals, texcoords, triangulated faces.
 * Materials/textures (.mtl) are ignored — geometry only, which is all rigging needs.
 */
object ObjLoader {

    fun load(input: InputStream): Mesh {
        val positions = ArrayList<Float>()
        val normals = ArrayList<Float>()
        val uvs = ArrayList<Float>()

        data class Key(val p: Int, val n: Int, val t: Int)

        val outPositions = ArrayList<Float>()
        val outNormals = ArrayList<Float>()
        val outUvs = ArrayList<Float>()
        val outIndices = ArrayList<Int>()
        val vertexCache = HashMap<Key, Int>()

        fun resolveIndex(raw: Int, count: Int): Int =
            if (raw > 0) raw - 1 else count + raw // negative = relative to end

        fun emitVertex(pIdx: Int, tIdx: Int, nIdx: Int): Int {
            val key = Key(pIdx, nIdx, tIdx)
            vertexCache[key]?.let { return it }

            val newIndex = outPositions.size / 3
            val pBase = pIdx * 3
            outPositions.add(positions.getOrElse(pBase) { 0f })
            outPositions.add(positions.getOrElse(pBase + 1) { 0f })
            outPositions.add(positions.getOrElse(pBase + 2) { 0f })

            if (nIdx >= 0) {
                val nBase = nIdx * 3
                outNormals.add(normals.getOrElse(nBase) { 0f })
                outNormals.add(normals.getOrElse(nBase + 1) { 0f })
                outNormals.add(normals.getOrElse(nBase + 2) { 1f })
            } else {
                outNormals.add(0f); outNormals.add(0f); outNormals.add(1f)
            }

            if (tIdx >= 0) {
                val tBase = tIdx * 2
                outUvs.add(uvs.getOrElse(tBase) { 0f })
                outUvs.add(uvs.getOrElse(tBase + 1) { 0f })
            } else {
                outUvs.add(0f); outUvs.add(0f)
            }

            vertexCache[key] = newIndex
            return newIndex
        }

        BufferedReader(InputStreamReader(input)).useLines { lines ->
            for (rawLine in lines) {
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val parts = line.split(Regex("\\s+"))
                when (parts[0]) {
                    "v" -> {
                        positions.add(parts[1].toFloat())
                        positions.add(parts[2].toFloat())
                        positions.add(parts[3].toFloat())
                    }
                    "vn" -> {
                        normals.add(parts[1].toFloat())
                        normals.add(parts[2].toFloat())
                        normals.add(parts[3].toFloat())
                    }
                    "vt" -> {
                        uvs.add(parts[1].toFloat())
                        uvs.add(if (parts.size > 2) parts[2].toFloat() else 0f)
                    }
                    "f" -> {
                        val faceVerts = IntArray(parts.size - 1)
                        for (i in 1 until parts.size) {
                            val comps = parts[i].split("/")
                            val pRaw = comps[0].toInt()
                            val pIdx = resolveIndex(pRaw, positions.size / 3)
                            val tIdx = if (comps.size > 1 && comps[1].isNotEmpty())
                                resolveIndex(comps[1].toInt(), uvs.size / 2) else -1
                            val nIdx = if (comps.size > 2 && comps[2].isNotEmpty())
                                resolveIndex(comps[2].toInt(), normals.size / 3) else -1
                            faceVerts[i - 1] = emitVertex(pIdx, tIdx, nIdx)
                        }
                        // Fan-triangulate polygons with >3 vertices.
                        for (i in 1 until faceVerts.size - 1) {
                            outIndices.add(faceVerts[0])
                            outIndices.add(faceVerts[i])
                            outIndices.add(faceVerts[i + 1])
                        }
                    }
                    else -> { /* ignore mtllib, usemtl, g, o, s, etc. */ }
                }
            }
        }

        val mesh = Mesh(
            outPositions.toFloatArray(),
            outNormals.toFloatArray(),
            outUvs.toFloatArray(),
            outIndices.toIntArray()
        )
        return if (normals.isEmpty()) computeSmoothNormals(mesh) else mesh
    }

    /** Generates area-weighted smooth normals when the source OBJ omitted them. */
    private fun computeSmoothNormals(mesh: Mesh): Mesh {
        val normals = FloatArray(mesh.positions.size)
        var i = 0
        while (i < mesh.indices.size) {
            val ia = mesh.indices[i] * 3
            val ib = mesh.indices[i + 1] * 3
            val ic = mesh.indices[i + 2] * 3
            val ax = mesh.positions[ia]; val ay = mesh.positions[ia + 1]; val az = mesh.positions[ia + 2]
            val bx = mesh.positions[ib]; val by = mesh.positions[ib + 1]; val bz = mesh.positions[ib + 2]
            val cx = mesh.positions[ic]; val cy = mesh.positions[ic + 1]; val cz = mesh.positions[ic + 2]
            val ux = bx - ax; val uy = by - ay; val uz = bz - az
            val vx = cx - ax; val vy = cy - ay; val vz = cz - az
            val nx = uy * vz - uz * vy
            val ny = uz * vx - ux * vz
            val nz = ux * vy - uy * vx
            for (idx in intArrayOf(ia, ib, ic)) {
                normals[idx] += nx; normals[idx + 1] += ny; normals[idx + 2] += nz
            }
            i += 3
        }
        var j = 0
        while (j < normals.size) {
            val nx = normals[j]; val ny = normals[j + 1]; val nz = normals[j + 2]
            val len = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz)
            if (len > 1e-8f) {
                normals[j] = nx / len; normals[j + 1] = ny / len; normals[j + 2] = nz / len
            } else {
                normals[j] = 0f; normals[j + 1] = 0f; normals[j + 2] = 1f
            }
            j += 3
        }
        return Mesh(mesh.positions, normals, mesh.uvs, mesh.indices)
    }
}
