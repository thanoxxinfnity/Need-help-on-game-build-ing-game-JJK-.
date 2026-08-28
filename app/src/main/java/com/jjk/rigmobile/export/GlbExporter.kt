package com.jjk.rigmobile.export

import com.jjk.rigmobile.model.Mesh
import com.jjk.rigmobile.rig.SkinWeights
import com.jjk.rigmobile.rig.Skeleton
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes a rigged mesh out as a self-contained binary glTF 2.0 (.glb): geometry,
 * a bone hierarchy as glTF nodes, a skin with inverse bind matrices, and
 * JOINTS_0/WEIGHTS_0 vertex attributes. The result loads directly into
 * Unity, Unreal, Godot, or Blender as an animatable rigged character.
 */
object GlbExporter {

    private const val FLOAT = 5126
    private const val UNSIGNED_BYTE = 5121
    private const val UNSIGNED_SHORT = 5123
    private const val UNSIGNED_INT = 5125
    private const val ARRAY_BUFFER = 34962
    private const val ELEMENT_ARRAY_BUFFER = 34963

    fun export(mesh: Mesh, skeleton: Skeleton, skin: SkinWeights): ByteArray {
        val bin = ByteArrayOutputStream()
        val bufferViews = JSONArray()
        val accessors = JSONArray()

        fun pad4(stream: ByteArrayOutputStream) {
            while (stream.size() % 4 != 0) stream.write(0)
        }

        fun writeFloatsAsBufferView(values: FloatArray, componentsPerElement: Int, target: Int?): Int {
            pad4(bin)
            val byteOffset = bin.size()
            val bb = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (v in values) bb.putFloat(v)
            bin.write(bb.array())
            val bv = JSONObject()
            bv.put("buffer", 0)
            bv.put("byteOffset", byteOffset)
            bv.put("byteLength", values.size * 4)
            if (target != null) bv.put("target", target)
            bufferViews.put(bv)
            return bufferViews.length() - 1
        }

        fun accessorMinMax(values: FloatArray, componentsPerElement: Int): Pair<JSONArray, JSONArray> {
            val min = FloatArray(componentsPerElement) { Float.MAX_VALUE }
            val max = FloatArray(componentsPerElement) { -Float.MAX_VALUE }
            var i = 0
            while (i < values.size) {
                for (c in 0 until componentsPerElement) {
                    val v = values[i + c]
                    if (v < min[c]) min[c] = v
                    if (v > max[c]) max[c] = v
                }
                i += componentsPerElement
            }
            val minArr = JSONArray(); val maxArr = JSONArray()
            for (c in 0 until componentsPerElement) { minArr.put(min[c].toDouble()); maxArr.put(max[c].toDouble()) }
            return minArr to maxArr
        }

        fun addFloatAccessor(values: FloatArray, componentsPerElement: Int, type: String, target: Int? = null, withBounds: Boolean = false): Int {
            val bvIndex = writeFloatsAsBufferView(values, componentsPerElement, target)
            val acc = JSONObject()
            acc.put("bufferView", bvIndex)
            acc.put("componentType", FLOAT)
            acc.put("count", values.size / componentsPerElement)
            acc.put("type", type)
            if (withBounds) {
                val (min, max) = accessorMinMax(values, componentsPerElement)
                acc.put("min", min)
                acc.put("max", max)
            }
            accessors.put(acc)
            return accessors.length() - 1
        }

        // --- Geometry ---
        val positionAccessor = addFloatAccessor(mesh.positions, 3, "VEC3", ARRAY_BUFFER, withBounds = true)
        val normalAccessor = addFloatAccessor(mesh.normals, 3, "VEC3", ARRAY_BUFFER)
        val uvAccessor = addFloatAccessor(mesh.uvs, 2, "VEC2", ARRAY_BUFFER)

        val indexAccessor: Int
        run {
            pad4(bin)
            val byteOffset = bin.size()
            val useShort = mesh.vertexCount <= 65535
            val bb = ByteBuffer.allocate(mesh.indices.size * (if (useShort) 2 else 4)).order(ByteOrder.LITTLE_ENDIAN)
            for (idx in mesh.indices) if (useShort) bb.putShort(idx.toShort()) else bb.putInt(idx)
            bin.write(bb.array())
            val bv = JSONObject().put("buffer", 0).put("byteOffset", byteOffset)
                .put("byteLength", bb.capacity()).put("target", ELEMENT_ARRAY_BUFFER)
            bufferViews.put(bv)
            val acc = JSONObject()
                .put("bufferView", bufferViews.length() - 1)
                .put("componentType", if (useShort) UNSIGNED_SHORT else UNSIGNED_INT)
                .put("count", mesh.indices.size)
                .put("type", "SCALAR")
            accessors.put(acc)
            indexAccessor = accessors.length() - 1
        }

        // --- Skinning attributes ---
        val jointsAccessor: Int
        run {
            pad4(bin)
            val byteOffset = bin.size()
            val bytes = ByteArray(skin.joints.size)
            for (i in skin.joints.indices) bytes[i] = skin.joints[i].toByte()
            bin.write(bytes)
            val bv = JSONObject().put("buffer", 0).put("byteOffset", byteOffset)
                .put("byteLength", bytes.size).put("target", ARRAY_BUFFER)
            bufferViews.put(bv)
            val acc = JSONObject()
                .put("bufferView", bufferViews.length() - 1)
                .put("componentType", UNSIGNED_BYTE)
                .put("count", skin.joints.size / SkinWeights.INFLUENCES_PER_VERTEX)
                .put("type", "VEC4")
            accessors.put(acc)
            jointsAccessor = accessors.length() - 1
        }
        val weightsAccessor = addFloatAccessor(skin.weights, SkinWeights.INFLUENCES_PER_VERTEX, "VEC4", ARRAY_BUFFER)

        // --- Skeleton nodes ---
        val boneCount = skeleton.bones.size
        val nodesJson = JSONArray()
        for (i in 0 until boneCount) {
            val bone = skeleton.bones[i]
            val parentPos = if (bone.parentIndex >= 0) skeleton.bones[bone.parentIndex].bindWorldPosition else null
            val local = if (parentPos != null) bone.bindWorldPosition - parentPos else bone.bindWorldPosition
            val node = JSONObject()
            node.put("name", bone.name)
            node.put("translation", JSONArray().put(local.x.toDouble()).put(local.y.toDouble()).put(local.z.toDouble()))
            val children = skeleton.childrenOf(i)
            if (children.isNotEmpty()) {
                val arr = JSONArray()
                for (c in children) arr.put(c) // node indices == bone indices (bones written first, in order)
                node.put("children", arr)
            }
            nodesJson.put(node)
        }

        // Inverse bind matrices: our skeleton only stores joint positions (no per-joint
        // rotation), so bind matrices are translation-only: inverse = translate(-boneWorldPos).
        val ibmFloats = FloatArray(boneCount * 16)
        for (i in 0 until boneCount) {
            val p = skeleton.bones[i].bindWorldPosition
            val base = i * 16
            ibmFloats[base + 0] = 1f; ibmFloats[base + 5] = 1f; ibmFloats[base + 10] = 1f; ibmFloats[base + 15] = 1f
            ibmFloats[base + 12] = -p.x
            ibmFloats[base + 13] = -p.y
            ibmFloats[base + 14] = -p.z
        }
        val ibmAccessor = addFloatAccessor(ibmFloats, 16, "MAT4")

        // Mesh node (identity transform — mesh-space already matches joint-space).
        val meshNode = JSONObject().put("name", "RiggedMesh").put("mesh", 0).put("skin", 0)
        nodesJson.put(meshNode)
        val meshNodeIndex = nodesJson.length() - 1

        val rootBoneIndices = skeleton.bones.indices.filter { skeleton.isRoot(it) }

        val sceneRoots = JSONArray()
        sceneRoots.put(meshNodeIndex)
        for (r in rootBoneIndices) sceneRoots.put(r)

        val skinJoints = JSONArray()
        for (i in 0 until boneCount) skinJoints.put(i)

        val root = JSONObject()
        root.put("asset", JSONObject().put("version", "2.0").put("generator", "RigMobile"))
        root.put("scene", 0)
        root.put("scenes", JSONArray().put(JSONObject().put("nodes", sceneRoots)))
        root.put("nodes", nodesJson)
        root.put("buffers", JSONArray().put(JSONObject().put("byteLength", 0))) // patched below
        root.put("bufferViews", bufferViews)
        root.put("accessors", accessors)
        root.put(
            "meshes", JSONArray().put(
                JSONObject().put(
                    "primitives", JSONArray().put(
                        JSONObject()
                            .put(
                                "attributes", JSONObject()
                                    .put("POSITION", positionAccessor)
                                    .put("NORMAL", normalAccessor)
                                    .put("TEXCOORD_0", uvAccessor)
                                    .put("JOINTS_0", jointsAccessor)
                                    .put("WEIGHTS_0", weightsAccessor)
                            )
                            .put("indices", indexAccessor)
                            .put("mode", 4)
                    )
                )
            )
        )
        root.put(
            "skins", JSONArray().put(
                JSONObject()
                    .put("joints", skinJoints)
                    .put("inverseBindMatrices", ibmAccessor)
                    .put("skeleton", rootBoneIndices.firstOrNull() ?: 0)
            )
        )

        val binBytes = bin.toByteArray()
        (root.getJSONArray("buffers").getJSONObject(0)).put("byteLength", binBytes.size)

        val jsonBytes = root.toString().toByteArray(Charsets.UTF_8)
        return packGlb(jsonBytes, binBytes)
    }

    private fun packGlb(jsonBytes: ByteArray, binBytes: ByteArray): ByteArray {
        val jsonPadded = padTo4(jsonBytes, ' '.code.toByte())
        val binPadded = padTo4(binBytes, 0)

        val totalLength = 12 + 8 + jsonPadded.size + 8 + binPadded.size
        val out = ByteBuffer.allocate(totalLength).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(0x46546C67) // "glTF"
        out.putInt(2)
        out.putInt(totalLength)

        out.putInt(jsonPadded.size)
        out.putInt(0x4E4F534A) // "JSON"
        out.put(jsonPadded)

        out.putInt(binPadded.size)
        out.putInt(0x004E4942) // "BIN\0"
        out.put(binPadded)

        return out.array()
    }

    private fun padTo4(bytes: ByteArray, padByte: Byte): ByteArray {
        val rem = bytes.size % 4
        if (rem == 0) return bytes
        val padded = ByteArray(bytes.size + (4 - rem))
        System.arraycopy(bytes, 0, padded, 0, bytes.size)
        for (i in bytes.size until padded.size) padded[i] = padByte
        return padded
    }
}
