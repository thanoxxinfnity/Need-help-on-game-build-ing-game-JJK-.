package com.jjk.rigmobile.math

import kotlin.math.sqrt

data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)

    fun length(): Float = sqrt(x * x + y * y + z * z)

    fun normalized(): Vec3 {
        val l = length()
        return if (l < 1e-8f) this else Vec3(x / l, y / l, z / l)
    }

    fun distanceTo(o: Vec3): Float = (this - o).length()

    companion object {
        val ZERO = Vec3(0f, 0f, 0f)
    }
}
