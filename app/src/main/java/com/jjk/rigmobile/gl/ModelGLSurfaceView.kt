package com.jjk.rigmobile.gl

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet

class ModelGLSurfaceView(context: Context, attrs: AttributeSet? = null) : GLSurfaceView(context, attrs) {

    val modelRenderer = ModelRenderer()

    init {
        setEGLContextClientVersion(3)
        setRenderer(modelRenderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }
}
