package com.jjk.rigmobile.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.jjk.rigmobile.gl.ModelGLSurfaceView
import com.jjk.rigmobile.math.Vec3
import com.jjk.rigmobile.rig.Skeleton
import kotlin.math.hypot

/**
 * Transparent view drawn over the GL surface. Handles all touch input:
 * one-finger drag orbits the camera (or drags a joint handle, in rig-edit
 * mode, when the finger lands on one); two-finger pinch/drag zooms and pans.
 */
class JointOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var glView: ModelGLSurfaceView? = null
    var skeleton: Skeleton? = null
        set(value) { field = value; invalidate() }
    var jointsEditable: Boolean = false
        set(value) { field = value; invalidate() }
    var onJointSelected: ((String?) -> Unit)? = null
    var onJointsChanged: (() -> Unit)? = null

    private val jointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.CYAN; style = Paint.Style.FILL }
    private val jointSelectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.YELLOW; style = Paint.Style.FILL }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 28f }

    private var draggedBoneIndex = -1
    private var dragPlanePoint = Vec3.ZERO
    private var lastX = 0f
    private var lastY = 0f
    private var orbiting = false
    private var panning = false

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val renderer = glView?.modelRenderer ?: return true
            renderer.camera.zoom(1f / detector.scaleFactor)
            glView?.requestRender()
            return true
        }
    })

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val renderer = glView?.modelRenderer ?: return
        val sk = skeleton ?: return
        if (!jointsEditable) return
        for ((i, bone) in sk.bones.withIndex()) {
            val screen = renderer.project(bone.bindWorldPosition)
            if (screen[2] < -1f || screen[2] > 1f) continue // behind camera / clipped
            val paint = if (i == draggedBoneIndex) jointSelectedPaint else jointPaint
            canvas.drawCircle(screen[0], screen[1], 16f, paint)
        }
        if (draggedBoneIndex in sk.bones.indices) {
            canvas.drawText(sk.bones[draggedBoneIndex].name, 24f, height - 48f, labelPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val renderer = glView?.modelRenderer ?: return false
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x; lastY = event.y
                draggedBoneIndex = -1
                if (jointsEditable) {
                    draggedBoneIndex = hitTestJoint(event.x, event.y, renderer)
                }
                orbiting = draggedBoneIndex < 0
                if (draggedBoneIndex >= 0) {
                    dragPlanePoint = skeleton!!.bones[draggedBoneIndex].bindWorldPosition
                    onJointSelected?.invoke(skeleton!!.bones[draggedBoneIndex].name)
                    invalidate()
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    orbiting = false
                    panning = true
                    draggedBoneIndex = -1
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (draggedBoneIndex >= 0 && event.pointerCount == 1) {
                    val world = renderer.screenToPlanePoint(event.x, event.y, dragPlanePoint)
                    skeleton!!.bones[draggedBoneIndex].bindWorldPosition = world
                    dragPlanePoint = world
                    onJointsChanged?.invoke()
                    glView?.requestRender()
                    invalidate()
                } else if (panning && event.pointerCount >= 2) {
                    val cx = (event.getX(0) + event.getX(1)) / 2f
                    val cy = (event.getY(0) + event.getY(1)) / 2f
                    val dx = cx - lastX; val dy = cy - lastY
                    renderer.camera.pan(-dx * 0.01f, dy * 0.01f)
                    lastX = cx; lastY = cy
                    glView?.requestRender()
                } else if (orbiting) {
                    val dx = event.x - lastX; val dy = event.y - lastY
                    renderer.camera.orbit(-dx * 0.4f, -dy * 0.4f)
                    lastX = event.x; lastY = event.y
                    glView?.requestRender()
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount <= 2) {
                    panning = false
                    // Re-seed lastX/Y from the remaining pointer to avoid a jump.
                    val remainingIndex = if (event.actionIndex == 0) 1 else 0
                    if (remainingIndex < event.pointerCount) {
                        lastX = event.getX(remainingIndex)
                        lastY = event.getY(remainingIndex)
                    }
                    orbiting = true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                draggedBoneIndex = -1
                orbiting = false
                panning = false
                onJointSelected?.invoke(null)
                invalidate()
            }
        }
        return true
    }

    private fun hitTestJoint(x: Float, y: Float, renderer: com.jjk.rigmobile.gl.ModelRenderer): Int {
        val sk = skeleton ?: return -1
        var best = -1
        var bestDist = 60f // touch radius in px
        for ((i, bone) in sk.bones.withIndex()) {
            val screen = renderer.project(bone.bindWorldPosition)
            val d = hypot((screen[0] - x).toDouble(), (screen[1] - y).toDouble()).toFloat()
            if (d < bestDist) { bestDist = d; best = i }
        }
        return best
    }
}
