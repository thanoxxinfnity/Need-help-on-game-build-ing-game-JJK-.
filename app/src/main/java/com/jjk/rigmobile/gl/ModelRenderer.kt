package com.jjk.rigmobile.gl

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import com.jjk.rigmobile.math.Mat4
import com.jjk.rigmobile.math.Vec3
import com.jjk.rigmobile.model.Mesh
import com.jjk.rigmobile.rig.AutoRigger
import com.jjk.rigmobile.rig.Skeleton
import com.jjk.rigmobile.rig.SkinWeights
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

private const val MESH_VERTEX_SHADER = """
    #version 300 es
    layout(location = 0) in vec3 aPosition;
    layout(location = 1) in vec3 aNormal;
    uniform mat4 uMvp;
    uniform mat4 uModel;
    out vec3 vNormal;
    void main() {
        vNormal = mat3(uModel) * aNormal;
        gl_Position = uMvp * vec4(aPosition, 1.0);
    }
"""

private const val MESH_FRAGMENT_SHADER = """
    #version 300 es
    precision mediump float;
    in vec3 vNormal;
    out vec4 fragColor;
    void main() {
        vec3 n = normalize(vNormal);
        vec3 lightDir = normalize(vec3(0.4, 0.8, 0.6));
        float diffuse = max(dot(n, lightDir), 0.0);
        float ambient = 0.35;
        vec3 base = vec3(0.75, 0.76, 0.8);
        vec3 color = base * (ambient + diffuse * 0.65);
        fragColor = vec4(color, 1.0);
    }
"""

private const val LINE_VERTEX_SHADER = """
    #version 300 es
    layout(location = 0) in vec3 aPosition;
    uniform mat4 uMvp;
    void main() {
        gl_Position = uMvp * vec4(aPosition, 1.0);
        gl_PointSize = 18.0;
    }
"""

private const val LINE_FRAGMENT_SHADER = """
    #version 300 es
    precision mediump float;
    uniform vec4 uColor;
    out vec4 fragColor;
    void main() {
        fragColor = uColor;
    }
"""

private val SKINNED_VERTEX_SHADER = """
    #version 300 es
    #define MAX_BONES ${AutoRigger.MAX_BONE_COUNT}
    layout(location = 0) in vec3 aPosition;
    layout(location = 1) in vec3 aNormal;
    layout(location = 2) in vec4 aJoints;
    layout(location = 3) in vec4 aWeights;
    uniform mat4 uMvp;
    uniform mat4 uJointMatrices[MAX_BONES];
    out vec3 vNormal;
    void main() {
        ivec4 j = ivec4(aJoints);
        mat4 skinMat =
            aWeights.x * uJointMatrices[j.x] +
            aWeights.y * uJointMatrices[j.y] +
            aWeights.z * uJointMatrices[j.z] +
            aWeights.w * uJointMatrices[j.w];
        vec4 skinnedPos = skinMat * vec4(aPosition, 1.0);
        vNormal = mat3(skinMat) * aNormal;
        gl_Position = uMvp * skinnedPos;
    }
"""

private const val SKINNED_FRAGMENT_SHADER = MESH_FRAGMENT_SHADER

class ModelRenderer : GLSurfaceView.Renderer {

    val camera = Camera()

    @Volatile var mesh: Mesh? = null
    @Volatile var skeleton: Skeleton? = null
    @Volatile var showSkeleton: Boolean = false

    @Volatile private var meshDirty = false

    @Volatile var viewportWidth: Int = 1
    @Volatile var viewportHeight: Int = 1
    @Volatile var viewProj: Mat4 = Mat4.identity()
        private set

    private var meshProgram = 0
    private var lineProgram = 0
    private var skinnedProgram = 0

    private var vboPositions = 0
    private var vboNormals = 0
    private var iboIndices = 0
    private var indexCount = 0
    private var indexIsShort = true

    private var vboJoints = 0
    private var vboWeights = 0
    @Volatile private var skinDirty = false
    @Volatile private var pendingSkin: SkinWeights? = null

    /** When set, the mesh is drawn skinned using these per-bone matrices (already poseWorld * inverseBind). */
    @Volatile var jointMatrices: Array<Mat4>? = null
    @Volatile var skinningEnabled: Boolean = false

    fun loadMesh(newMesh: Mesh) {
        mesh = newMesh
        meshDirty = true
        skinningEnabled = false
        jointMatrices = null
    }

    fun setSkinning(skin: SkinWeights) {
        pendingSkin = skin
        skinDirty = true
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.09f, 0.09f, 0.1f, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        meshProgram = ShaderUtil.buildProgram(MESH_VERTEX_SHADER, MESH_FRAGMENT_SHADER)
        lineProgram = ShaderUtil.buildProgram(LINE_VERTEX_SHADER, LINE_FRAGMENT_SHADER)
        skinnedProgram = ShaderUtil.buildProgram(SKINNED_VERTEX_SHADER, SKINNED_FRAGMENT_SHADER)

        val buffers = IntArray(5)
        GLES30.glGenBuffers(5, buffers, 0)
        vboPositions = buffers[0]
        vboNormals = buffers[1]
        iboIndices = buffers[2]
        vboJoints = buffers[3]
        vboWeights = buffers[4]
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        val currentMesh = mesh ?: run {
            viewProj = projMatrix() * camera.viewMatrix()
            return
        }
        if (meshDirty) {
            uploadMesh(currentMesh)
            meshDirty = false
        }
        if (skinDirty) {
            pendingSkin?.let { uploadSkin(it) }
            skinDirty = false
        }

        val proj = projMatrix()
        val view = camera.viewMatrix()
        val vp = proj * view
        viewProj = vp

        if (skinningEnabled && jointMatrices != null) {
            drawSkinnedMesh(vp)
        } else {
            drawStaticMesh(vp)
        }

        if (showSkeleton) {
            skeleton?.let { drawSkeleton(it, vp) }
        }
    }

    private fun drawStaticMesh(vp: Mat4) {
        val model = Mat4.identity()
        val mvp = vp * model

        GLES30.glUseProgram(meshProgram)
        val mvpLoc = GLES30.glGetUniformLocation(meshProgram, "uMvp")
        val modelLoc = GLES30.glGetUniformLocation(meshProgram, "uModel")
        GLES30.glUniformMatrix4fv(mvpLoc, 1, false, mvp.m, 0)
        GLES30.glUniformMatrix4fv(modelLoc, 1, false, model.m, 0)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboPositions)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 0, 0)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboNormals)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, 0, 0)

        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, iboIndices)
        val type = if (indexIsShort) GLES30.GL_UNSIGNED_SHORT else GLES30.GL_UNSIGNED_INT
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, type, 0)

        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
    }

    private fun drawSkinnedMesh(vp: Mat4) {
        val mats = jointMatrices ?: return
        GLES30.glUseProgram(skinnedProgram)
        val mvpLoc = GLES30.glGetUniformLocation(skinnedProgram, "uMvp")
        GLES30.glUniformMatrix4fv(mvpLoc, 1, false, vp.m, 0)

        val jointsLoc = GLES30.glGetUniformLocation(skinnedProgram, "uJointMatrices")
        val flat = FloatArray(mats.size * 16)
        for (i in mats.indices) System.arraycopy(mats[i].m, 0, flat, i * 16, 16)
        GLES30.glUniformMatrix4fv(jointsLoc, mats.size, false, flat, 0)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboPositions)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 0, 0)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboNormals)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, 0, 0)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboJoints)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 4, GLES30.GL_UNSIGNED_BYTE, false, 0, 0)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboWeights)
        GLES30.glEnableVertexAttribArray(3)
        GLES30.glVertexAttribPointer(3, 4, GLES30.GL_FLOAT, false, 0, 0)

        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, iboIndices)
        val type = if (indexIsShort) GLES30.GL_UNSIGNED_SHORT else GLES30.GL_UNSIGNED_INT
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, type, 0)

        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
        GLES30.glDisableVertexAttribArray(2)
        GLES30.glDisableVertexAttribArray(3)
    }

    private fun uploadSkin(skin: SkinWeights) {
        val jointBytes = ByteArray(skin.joints.size)
        for (i in skin.joints.indices) jointBytes[i] = skin.joints[i].toByte()
        val jointBuf = ByteBuffer.allocateDirect(jointBytes.size).order(ByteOrder.nativeOrder()).put(jointBytes).apply { position(0) }
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboJoints)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, jointBytes.size, jointBuf, GLES30.GL_STATIC_DRAW)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboWeights)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, skin.weights.size * 4, floatBufferOf(skin.weights), GLES30.GL_STATIC_DRAW)
    }

    private fun drawSkeleton(skeleton: Skeleton, vp: Mat4) {
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glUseProgram(lineProgram)
        val mvpLoc = GLES30.glGetUniformLocation(lineProgram, "uMvp")
        val colorLoc = GLES30.glGetUniformLocation(lineProgram, "uColor")
        GLES30.glUniformMatrix4fv(mvpLoc, 1, false, vp.m, 0)

        // Bone lines.
        val lineVerts = ArrayList<Float>()
        for (bone in skeleton.bones) {
            if (bone.parentIndex >= 0) {
                val p = skeleton.bones[bone.parentIndex].bindWorldPosition
                lineVerts.add(p.x); lineVerts.add(p.y); lineVerts.add(p.z)
                lineVerts.add(bone.bindWorldPosition.x); lineVerts.add(bone.bindWorldPosition.y); lineVerts.add(bone.bindWorldPosition.z)
            }
        }
        if (lineVerts.isNotEmpty()) {
            val buf = floatBufferOf(lineVerts.toFloatArray())
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 0, buf)
            GLES30.glUniform4f(colorLoc, 1f, 0.6f, 0f, 1f)
            GLES30.glLineWidth(4f)
            GLES30.glDrawArrays(GLES30.GL_LINES, 0, lineVerts.size / 3)
        }

        // Joint points.
        val pointVerts = FloatArray(skeleton.bones.size * 3)
        for ((i, bone) in skeleton.bones.withIndex()) {
            pointVerts[i * 3] = bone.bindWorldPosition.x
            pointVerts[i * 3 + 1] = bone.bindWorldPosition.y
            pointVerts[i * 3 + 2] = bone.bindWorldPosition.z
        }
        val pbuf = floatBufferOf(pointVerts)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 0, pbuf)
        GLES30.glUniform4f(colorLoc, 0.1f, 0.9f, 1f, 1f)
        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, skeleton.bones.size)
        GLES30.glDisableVertexAttribArray(0)

        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }

    private fun uploadMesh(m: Mesh) {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboPositions)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, m.positions.size * 4, floatBufferOf(m.positions), GLES30.GL_STATIC_DRAW)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboNormals)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, m.normals.size * 4, floatBufferOf(m.normals), GLES30.GL_STATIC_DRAW)

        indexIsShort = m.vertexCount <= 65535
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, iboIndices)
        if (indexIsShort) {
            val sb = ByteBuffer.allocateDirect(m.indices.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
            for (idx in m.indices) sb.put(idx.toShort())
            sb.position(0)
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, m.indices.size * 2, sb, GLES30.GL_STATIC_DRAW)
        } else {
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, m.indices.size * 4, intBufferOf(m.indices), GLES30.GL_STATIC_DRAW)
        }
        indexCount = m.indices.size

        // Frame the camera on the newly loaded model.
        val min = m.boundsMin(); val max = m.boundsMax()
        val center = (min + max) * 0.5f
        val size = max(max.x - min.x, max.y - min.y, max.z - min.z)
        camera.target = center
        camera.distance = size * 1.6f + 0.5f
    }

    private fun max(a: Float, b: Float, c: Float) = kotlin.math.max(a, kotlin.math.max(b, c))

    private fun projMatrix(): Mat4 {
        val aspect = viewportWidth.toFloat() / viewportHeight.toFloat().coerceAtLeast(1f)
        return Mat4.perspective(50f, aspect, 0.02f, 200f)
    }

    /** Projects a world-space point to Android screen pixel coordinates (origin top-left). */
    fun project(world: Vec3): FloatArray {
        val clip = viewProj.transformVec4(world.x, world.y, world.z, 1f)
        val w = if (kotlin.math.abs(clip[3]) < 1e-6f) 1e-6f else clip[3]
        val ndcX = clip[0] / w
        val ndcY = clip[1] / w
        val screenX = (ndcX * 0.5f + 0.5f) * viewportWidth
        val screenY = (1f - (ndcY * 0.5f + 0.5f)) * viewportHeight
        return floatArrayOf(screenX, screenY, clip[2] / w)
    }

    /**
     * Casts a ray from the camera through the given screen pixel, and returns where it
     * crosses the plane facing the camera through [planePoint]. Used to drag a joint
     * handle around in 3D while keeping the interaction 2D/screen-space.
     */
    fun screenToPlanePoint(screenX: Float, screenY: Float, planePoint: Vec3): Vec3 {
        val inv = viewProj.inverted()
        val ndcX = (screenX / viewportWidth) * 2f - 1f
        val ndcY = 1f - (screenY / viewportHeight) * 2f

        val near = inv.transformVec4(ndcX, ndcY, -1f, 1f)
        val far = inv.transformVec4(ndcX, ndcY, 1f, 1f)
        val nearW = if (kotlin.math.abs(near[3]) < 1e-6f) 1e-6f else near[3]
        val farW = if (kotlin.math.abs(far[3]) < 1e-6f) 1e-6f else far[3]
        val nearP = Vec3(near[0] / nearW, near[1] / nearW, near[2] / nearW)
        val farP = Vec3(far[0] / farW, far[1] / farW, far[2] / farW)

        val rayDir = (farP - nearP).normalized()
        val planeNormal = camera.forward()
        val denom = planeNormal.x * rayDir.x + planeNormal.y * rayDir.y + planeNormal.z * rayDir.z
        if (kotlin.math.abs(denom) < 1e-6f) return planePoint
        val diff = planePoint - nearP
        val t = (diff.x * planeNormal.x + diff.y * planeNormal.y + diff.z * planeNormal.z) / denom
        return nearP + rayDir * t
    }

    private fun floatBufferOf(arr: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(arr.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(arr).apply { position(0) }

    private fun intBufferOf(arr: IntArray): IntBuffer =
        ByteBuffer.allocateDirect(arr.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer().put(arr).apply { position(0) }
}
