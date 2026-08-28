package com.jjk.rigmobile.math

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Column-major 4x4 matrix, matching OpenGL / glTF conventions.
 * m[col * 4 + row]
 */
class Mat4(val m: FloatArray = FloatArray(16)) {

    companion object {
        fun identity(): Mat4 {
            val r = Mat4()
            r.m[0] = 1f; r.m[5] = 1f; r.m[10] = 1f; r.m[15] = 1f
            return r
        }

        fun translation(t: Vec3): Mat4 {
            val r = identity()
            r.m[12] = t.x; r.m[13] = t.y; r.m[14] = t.z
            return r
        }

        fun scale(s: Float): Mat4 {
            val r = identity()
            r.m[0] = s; r.m[5] = s; r.m[10] = s
            return r
        }

        fun perspective(fovYDeg: Float, aspect: Float, near: Float, far: Float): Mat4 {
            val f = 1f / tan(Math.toRadians(fovYDeg.toDouble() / 2.0)).toFloat()
            val r = Mat4()
            r.m[0] = f / aspect
            r.m[5] = f
            r.m[10] = (far + near) / (near - far)
            r.m[11] = -1f
            r.m[14] = (2f * far * near) / (near - far)
            return r
        }

        fun lookAt(eye: Vec3, center: Vec3, up: Vec3): Mat4 {
            val f = (center - eye).normalized()
            val s = crossProd(f, up).normalized()
            val u = crossProd(s, f)
            val r = identity()
            r.m[0] = s.x; r.m[4] = s.y; r.m[8] = s.z
            r.m[1] = u.x; r.m[5] = u.y; r.m[9] = u.z
            r.m[2] = -f.x; r.m[6] = -f.y; r.m[10] = -f.z
            r.m[12] = -(s.x * eye.x + s.y * eye.y + s.z * eye.z)
            r.m[13] = -(u.x * eye.x + u.y * eye.y + u.z * eye.z)
            r.m[14] = (f.x * eye.x + f.y * eye.y + f.z * eye.z)
            return r
        }

        fun rotationX(deg: Float): Mat4 {
            val r = identity()
            val rad = Math.toRadians(deg.toDouble())
            val c = cos(rad).toFloat(); val s = sin(rad).toFloat()
            r.m[5] = c; r.m[6] = s; r.m[9] = -s; r.m[10] = c
            return r
        }

        fun rotationY(deg: Float): Mat4 {
            val r = identity()
            val rad = Math.toRadians(deg.toDouble())
            val c = cos(rad).toFloat(); val s = sin(rad).toFloat()
            r.m[0] = c; r.m[2] = -s; r.m[8] = s; r.m[10] = c
            return r
        }

        private fun crossProd(a: Vec3, b: Vec3): Vec3 =
            Vec3(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x)

        fun multiply(a: Mat4, b: Mat4): Mat4 {
            val r = Mat4()
            for (col in 0 until 4) {
                for (row in 0 until 4) {
                    var sum = 0f
                    for (k in 0 until 4) {
                        sum += a.m[k * 4 + row] * b.m[col * 4 + k]
                    }
                    r.m[col * 4 + row] = sum
                }
            }
            return r
        }
    }

    operator fun times(o: Mat4): Mat4 = multiply(this, o)

    fun transformPoint(p: Vec3): Vec3 {
        val x = m[0] * p.x + m[4] * p.y + m[8] * p.z + m[12]
        val y = m[1] * p.x + m[5] * p.y + m[9] * p.z + m[13]
        val z = m[2] * p.x + m[6] * p.y + m[10] * p.z + m[14]
        return Vec3(x, y, z)
    }

    /** Full homogeneous transform, returning (x,y,z,w) — needed for perspective projection/unprojection. */
    fun transformVec4(x: Float, y: Float, z: Float, w: Float): FloatArray {
        return floatArrayOf(
            m[0] * x + m[4] * y + m[8] * z + m[12] * w,
            m[1] * x + m[5] * y + m[9] * z + m[13] * w,
            m[2] * x + m[6] * y + m[10] * z + m[14] * w,
            m[3] * x + m[7] * y + m[11] * z + m[15] * w
        )
    }

    fun inverted(): Mat4 {
        // General 4x4 inverse via cofactor expansion.
        val a = m
        val inv = FloatArray(16)

        inv[0] = a[5]*a[10]*a[15] - a[5]*a[11]*a[14] - a[9]*a[6]*a[15] + a[9]*a[7]*a[14] + a[13]*a[6]*a[11] - a[13]*a[7]*a[10]
        inv[4] = -a[4]*a[10]*a[15] + a[4]*a[11]*a[14] + a[8]*a[6]*a[15] - a[8]*a[7]*a[14] - a[12]*a[6]*a[11] + a[12]*a[7]*a[10]
        inv[8] = a[4]*a[9]*a[15] - a[4]*a[11]*a[13] - a[8]*a[5]*a[15] + a[8]*a[7]*a[13] + a[12]*a[5]*a[11] - a[12]*a[7]*a[9]
        inv[12] = -a[4]*a[9]*a[14] + a[4]*a[10]*a[13] + a[8]*a[5]*a[14] - a[8]*a[6]*a[13] - a[12]*a[5]*a[10] + a[12]*a[6]*a[9]

        inv[1] = -a[1]*a[10]*a[15] + a[1]*a[11]*a[14] + a[9]*a[2]*a[15] - a[9]*a[3]*a[14] - a[13]*a[2]*a[11] + a[13]*a[3]*a[10]
        inv[5] = a[0]*a[10]*a[15] - a[0]*a[11]*a[14] - a[8]*a[2]*a[15] + a[8]*a[3]*a[14] + a[12]*a[2]*a[11] - a[12]*a[3]*a[10]
        inv[9] = -a[0]*a[9]*a[15] + a[0]*a[11]*a[13] + a[8]*a[1]*a[15] - a[8]*a[3]*a[13] - a[12]*a[1]*a[11] + a[12]*a[3]*a[9]
        inv[13] = a[0]*a[9]*a[14] - a[0]*a[10]*a[13] - a[8]*a[1]*a[14] + a[8]*a[2]*a[13] + a[12]*a[1]*a[10] - a[12]*a[2]*a[9]

        inv[2] = a[1]*a[6]*a[15] - a[1]*a[7]*a[14] - a[5]*a[2]*a[15] + a[5]*a[3]*a[14] + a[13]*a[2]*a[7] - a[13]*a[3]*a[6]
        inv[6] = -a[0]*a[6]*a[15] + a[0]*a[7]*a[14] + a[4]*a[2]*a[15] - a[4]*a[3]*a[14] - a[12]*a[2]*a[7] + a[12]*a[3]*a[6]
        inv[10] = a[0]*a[5]*a[15] - a[0]*a[7]*a[13] - a[4]*a[1]*a[15] + a[4]*a[3]*a[13] + a[12]*a[1]*a[7] - a[12]*a[3]*a[5]
        inv[14] = -a[0]*a[5]*a[14] + a[0]*a[6]*a[13] + a[4]*a[1]*a[14] - a[4]*a[2]*a[13] - a[12]*a[1]*a[6] + a[12]*a[2]*a[5]

        inv[3] = -a[1]*a[6]*a[11] + a[1]*a[7]*a[10] + a[5]*a[2]*a[11] - a[5]*a[3]*a[10] - a[9]*a[2]*a[7] + a[9]*a[3]*a[6]
        inv[7] = a[0]*a[6]*a[11] - a[0]*a[7]*a[10] - a[4]*a[2]*a[11] + a[4]*a[3]*a[10] + a[8]*a[2]*a[7] - a[8]*a[3]*a[6]
        inv[11] = -a[0]*a[5]*a[11] + a[0]*a[7]*a[9] + a[4]*a[1]*a[11] - a[4]*a[3]*a[9] - a[8]*a[1]*a[7] + a[8]*a[3]*a[5]
        inv[15] = a[0]*a[5]*a[10] - a[0]*a[6]*a[9] - a[4]*a[1]*a[10] + a[4]*a[2]*a[9] + a[8]*a[1]*a[6] - a[8]*a[2]*a[5]

        var det = a[0]*inv[0] + a[1]*inv[4] + a[2]*inv[8] + a[3]*inv[12]
        if (kotlin.math.abs(det) < 1e-12f) det = 1e-12f
        val invDet = 1f / det
        for (i in 0 until 16) inv[i] *= invDet
        return Mat4(inv)
    }
}
