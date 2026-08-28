package com.jjk.rigmobile.gl

import com.jjk.rigmobile.math.Mat4
import com.jjk.rigmobile.math.Vec3
import kotlin.math.cos
import kotlin.math.sin

/** Simple orbit/pan/zoom camera, touch-driven like a turntable viewer. */
class Camera {
    var yawDeg = 20f
    var pitchDeg = 10f
    var distance = 3f
    var target = Vec3(0f, 1f, 0f)

    fun eye(): Vec3 {
        val yaw = Math.toRadians(yawDeg.toDouble())
        val pitch = Math.toRadians(pitchDeg.toDouble())
        val x = (distance * sin(yaw) * cos(pitch)).toFloat()
        val y = (distance * sin(pitch)).toFloat()
        val z = (distance * cos(yaw) * cos(pitch)).toFloat()
        return target + Vec3(x, y, z)
    }

    fun viewMatrix(): Mat4 = Mat4.lookAt(eye(), target, Vec3(0f, 1f, 0f))

    fun forward(): Vec3 = (target - eye()).normalized()

    fun right(): Vec3 {
        val f = forward()
        val up = Vec3(0f, 1f, 0f)
        return Vec3(f.z * up.y - f.y * up.z, f.x * up.z - f.z * up.x, f.y * up.x - f.x * up.y).normalized()
    }

    fun up(): Vec3 {
        val f = forward(); val r = right()
        return Vec3(r.y * f.z - r.z * f.y, r.z * f.x - r.x * f.z, r.x * f.y - r.y * f.x).normalized()
    }

    fun orbit(dYawDeg: Float, dPitchDeg: Float) {
        yawDeg += dYawDeg
        pitchDeg = (pitchDeg + dPitchDeg).coerceIn(-85f, 85f)
    }

    fun zoom(factor: Float) {
        distance = (distance * factor).coerceIn(0.15f, 60f)
    }

    fun pan(dx: Float, dy: Float) {
        target = target + right() * dx + up() * dy
    }
}
