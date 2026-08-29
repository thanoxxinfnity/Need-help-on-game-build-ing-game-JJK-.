package com.jjk.rigmobile.model

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.jjk.rigmobile.math.Mat4
import com.jjk.rigmobile.math.Vec3
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal static-mesh glTF 2.0 / GLB importer.
 *
 * Supports: binary .glb (JSON + BIN chunk), and .gltf with buffers embedded as
 * base64 data URIs (self-contained exports). External .bin references are not
 * resolved, since the app only has a single picked file via SAF. Skinning data
 * on the *source* file is ignored — rigging is this app's job, not the input's.
 * Base-color textures on the source materials ARE read, so imported models
 * keep their look in the viewer and in the exported rigged file.
 */
object GltfLoader {

    private const val COMPONENT_BYTE = 5120
    private const val COMPONENT_UBYTE = 5121
    private const val COMPONENT_SHORT = 5122
    private const val COMPONENT_USHORT = 5123
    private const val COMPONENT_UINT = 5125
    private const val COMPONENT_FLOAT = 5126

    fun loadGlb(bytes: ByteArray): Mesh {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = bb.int
        require(magic == 0x46546C67) { "Not a valid GLB file" }
        bb.int // version
        bb.int // total length

        var jsonText: String? = null
        var binChunk: ByteArray? = null
        while (bb.remaining() >= 8) {
            val chunkLength = bb.int
            val chunkType = bb.int
            val chunkBytes = ByteArray(chunkLength)
            bb.get(chunkBytes)
            when (chunkType) {
                0x4E4F534A -> jsonText = String(chunkBytes, Charsets.UTF_8) // "JSON"
                0x004E4942 -> binChunk = chunkBytes // "BIN\0"
            }
        }
        requireNotNull(jsonText) { "GLB missing JSON chunk" }
        return parse(JSONObject(jsonText), binChunk)
    }

    fun loadGltfJson(jsonText: String): Mesh {
        val root = JSONObject(jsonText)
        return parse(root, null)
    }

    private fun parse(root: JSONObject, glbBin: ByteArray?): Mesh {
        val buffers = ArrayList<ByteArray>()
        val buffersJson = root.optJSONArray("buffers") ?: JSONArray()
        for (i in 0 until buffersJson.length()) {
            val bufObj = buffersJson.getJSONObject(i)
            val uri = bufObj.optString("uri", "")
            if (uri.startsWith("data:")) {
                val base64Part = uri.substringAfter("base64,")
                buffers.add(Base64.decode(base64Part, Base64.DEFAULT))
            } else if (uri.isEmpty() && glbBin != null) {
                buffers.add(glbBin)
            } else {
                throw IllegalArgumentException("External buffer '$uri' not supported — export as .glb instead")
            }
        }

        val bufferViews = root.optJSONArray("bufferViews") ?: JSONArray()
        val accessors = root.optJSONArray("accessors") ?: JSONArray()
        val meshesJson = root.optJSONArray("meshes") ?: JSONArray()
        val nodesJson = root.optJSONArray("nodes") ?: JSONArray()
        val scenesJson = root.optJSONArray("scenes") ?: JSONArray()
        val imagesJson = root.optJSONArray("images") ?: JSONArray()
        val texturesJson = root.optJSONArray("textures") ?: JSONArray()
        val materialsJson = root.optJSONArray("materials") ?: JSONArray()
        val defaultScene = root.optInt("scene", 0)

        fun bufferViewBytes(bufferViewIndex: Int): ByteArray {
            val bv = bufferViews.getJSONObject(bufferViewIndex)
            val buffer = buffers[bv.getInt("buffer")]
            val offset = bv.optInt("byteOffset", 0)
            val length = bv.getInt("byteLength")
            return buffer.copyOfRange(offset, offset + length)
        }

        // Decode each referenced image once, keyed by glTF image index; drop any that fail
        // to decode rather than aborting the whole import over one bad/unsupported image.
        val decodedImages = HashMap<Int, Bitmap>()
        fun decodeImage(imageIndex: Int): Bitmap? {
            decodedImages[imageIndex]?.let { return it }
            val img = imagesJson.getJSONObject(imageIndex)
            val bytes = if (img.has("bufferView")) {
                bufferViewBytes(img.getInt("bufferView"))
            } else {
                val uri = img.optString("uri", "")
                if (uri.startsWith("data:")) Base64.decode(uri.substringAfter("base64,"), Base64.DEFAULT) else null
            } ?: return null
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            decodedImages[imageIndex] = bmp
            return bmp
        }

        // Materials may share textures/images; keep one Bitmap per distinct texture index in
        // the OUTPUT list, so re-used textures aren't decoded or uploaded to the GPU twice.
        val outTextures = ArrayList<Bitmap>()
        val textureIndexToOutputIndex = HashMap<Int, Int>()
        fun resolveMaterialTexture(materialIndex: Int): Int {
            if (materialIndex < 0 || materialIndex >= materialsJson.length()) return -1
            val mat = materialsJson.getJSONObject(materialIndex)
            val pbr = mat.optJSONObject("pbrMetallicRoughness") ?: return -1
            val texRef = pbr.optJSONObject("baseColorTexture") ?: return -1
            val texIndex = texRef.optInt("index", -1)
            if (texIndex < 0) return -1
            textureIndexToOutputIndex[texIndex]?.let { return it }
            val tex = texturesJson.getJSONObject(texIndex)
            val imageIndex = tex.optInt("source", -1)
            if (imageIndex < 0) return -1
            val bmp = decodeImage(imageIndex) ?: return -1
            outTextures.add(bmp)
            val outIndex = outTextures.size - 1
            textureIndexToOutputIndex[texIndex] = outIndex
            return outIndex
        }

        fun readFloatAccessor(accessorIndex: Int, componentsPerElement: Int): FloatArray {
            val acc = accessors.getJSONObject(accessorIndex)
            val count = acc.getInt("count")
            val bufferViewIndex = acc.getInt("bufferView")
            val accByteOffset = acc.optInt("byteOffset", 0)
            val bv = bufferViews.getJSONObject(bufferViewIndex)
            val buffer = buffers[bv.getInt("buffer")]
            val bvOffset = bv.optInt("byteOffset", 0)
            val stride = bv.optInt("byteStride", componentsPerElement * 4)
            val bb = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
            val out = FloatArray(count * componentsPerElement)
            for (i in 0 until count) {
                val base = bvOffset + accByteOffset + i * stride
                for (c in 0 until componentsPerElement) {
                    out[i * componentsPerElement + c] = bb.getFloat(base + c * 4)
                }
            }
            return out
        }

        fun readIndexAccessor(accessorIndex: Int): IntArray {
            val acc = accessors.getJSONObject(accessorIndex)
            val count = acc.getInt("count")
            val componentType = acc.getInt("componentType")
            val bufferViewIndex = acc.getInt("bufferView")
            val accByteOffset = acc.optInt("byteOffset", 0)
            val bv = bufferViews.getJSONObject(bufferViewIndex)
            val buffer = buffers[bv.getInt("buffer")]
            val bvOffset = bv.optInt("byteOffset", 0)
            val bb = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
            val out = IntArray(count)
            val compSize = when (componentType) {
                COMPONENT_UBYTE, COMPONENT_BYTE -> 1
                COMPONENT_USHORT, COMPONENT_SHORT -> 2
                else -> 4
            }
            val stride = bv.optInt("byteStride", compSize)
            for (i in 0 until count) {
                val base = bvOffset + accByteOffset + i * stride
                out[i] = when (componentType) {
                    COMPONENT_UBYTE -> bb.get(base).toInt() and 0xFF
                    COMPONENT_USHORT -> bb.getShort(base).toInt() and 0xFFFF
                    COMPONENT_UINT -> bb.getInt(base)
                    else -> bb.getInt(base)
                }
            }
            return out
        }

        fun nodeLocalMatrix(node: JSONObject): Mat4 {
            if (node.has("matrix")) {
                val arr = node.getJSONArray("matrix")
                val m = FloatArray(16) { arr.getDouble(it).toFloat() }
                return Mat4(m)
            }
            var m = Mat4.identity()
            if (node.has("scale")) {
                val s = node.getJSONArray("scale")
                val sc = Mat4.identity()
                sc.m[0] = s.getDouble(0).toFloat(); sc.m[5] = s.getDouble(1).toFloat(); sc.m[10] = s.getDouble(2).toFloat()
                m = sc * m
            }
            if (node.has("rotation")) {
                val q = node.getJSONArray("rotation")
                val qx = q.getDouble(0).toFloat(); val qy = q.getDouble(1).toFloat()
                val qz = q.getDouble(2).toFloat(); val qw = q.getDouble(3).toFloat()
                val rot = Mat4.identity()
                rot.m[0] = 1 - 2 * (qy * qy + qz * qz); rot.m[1] = 2 * (qx * qy + qz * qw); rot.m[2] = 2 * (qx * qz - qy * qw)
                rot.m[4] = 2 * (qx * qy - qz * qw); rot.m[5] = 1 - 2 * (qx * qx + qz * qz); rot.m[6] = 2 * (qy * qz + qx * qw)
                rot.m[8] = 2 * (qx * qz + qy * qw); rot.m[9] = 2 * (qy * qz - qx * qw); rot.m[10] = 1 - 2 * (qx * qx + qy * qy)
                m = rot * m
            }
            if (node.has("translation")) {
                val t = node.getJSONArray("translation")
                val tr = Mat4.translation(
                    Vec3(t.getDouble(0).toFloat(), t.getDouble(1).toFloat(), t.getDouble(2).toFloat())
                )
                m = tr * m
            }
            return m
        }

        val outPositions = ArrayList<Float>()
        val outNormals = ArrayList<Float>()
        val outUvs = ArrayList<Float>()
        val outIndices = ArrayList<Int>()
        val outParts = ArrayList<MeshPart>()

        fun appendMesh(meshIndex: Int, worldMatrix: Mat4) {
            val meshObj = meshesJson.getJSONObject(meshIndex)
            val primitives = meshObj.getJSONArray("primitives")
            for (p in 0 until primitives.length()) {
                val prim = primitives.getJSONObject(p)
                val attrs = prim.getJSONObject("attributes")
                if (!attrs.has("POSITION")) continue
                val positions = readFloatAccessor(attrs.getInt("POSITION"), 3)
                val normals = if (attrs.has("NORMAL")) readFloatAccessor(attrs.getInt("NORMAL"), 3) else null
                val uvs = if (attrs.has("TEXCOORD_0")) readFloatAccessor(attrs.getInt("TEXCOORD_0"), 2) else null
                val indices = if (prim.has("indices")) readIndexAccessor(prim.getInt("indices"))
                else IntArray(positions.size / 3) { it }

                val vertexBase = outPositions.size / 3
                var i = 0
                while (i < positions.size) {
                    val p3 = worldMatrix.transformPoint(Vec3(positions[i], positions[i + 1], positions[i + 2]))
                    outPositions.add(p3.x); outPositions.add(p3.y); outPositions.add(p3.z)
                    if (normals != null) {
                        // Approximate normal transform (ignores non-uniform scale; fine for game-asset imports).
                        val n3 = worldMatrix.transformPoint(Vec3(normals[i], normals[i + 1], normals[i + 2])) -
                            worldMatrix.transformPoint(Vec3.ZERO)
                        val nn = n3.normalized()
                        outNormals.add(nn.x); outNormals.add(nn.y); outNormals.add(nn.z)
                    } else {
                        outNormals.add(0f); outNormals.add(0f); outNormals.add(1f)
                    }
                    i += 3
                }
                if (uvs != null) {
                    var j = 0
                    while (j < uvs.size) {
                        outUvs.add(uvs[j]); outUvs.add(uvs[j + 1])
                        j += 2
                    }
                } else {
                    repeat(positions.size / 3) { outUvs.add(0f); outUvs.add(0f) }
                }

                val indexStart = outIndices.size
                for (idx in indices) outIndices.add(vertexBase + idx)
                val textureIndex = resolveMaterialTexture(prim.optInt("material", -1))
                outParts.add(MeshPart(indexStart, indices.size, textureIndex))
            }
        }

        fun visitNode(nodeIndex: Int, parentWorld: Mat4) {
            val node = nodesJson.getJSONObject(nodeIndex)
            val world = parentWorld * nodeLocalMatrix(node)
            if (node.has("mesh")) appendMesh(node.getInt("mesh"), world)
            val children = node.optJSONArray("children")
            if (children != null) {
                for (i in 0 until children.length()) visitNode(children.getInt(i), world)
            }
        }

        if (scenesJson.length() > 0) {
            val scene = scenesJson.getJSONObject(defaultScene.coerceIn(0, scenesJson.length() - 1))
            val roots = scene.getJSONArray("nodes")
            for (i in 0 until roots.length()) visitNode(roots.getInt(i), Mat4.identity())
        } else {
            // No scene graph — just dump every mesh at identity.
            for (i in 0 until meshesJson.length()) appendMesh(i, Mat4.identity())
        }

        return Mesh(
            outPositions.toFloatArray(),
            outNormals.toFloatArray(),
            outUvs.toFloatArray(),
            outIndices.toIntArray(),
            outParts,
            outTextures
        )
    }
}
