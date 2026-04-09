package com.dragon.renderlib.texture

import android.graphics.SurfaceTexture
import android.util.Log
import android.view.Surface
import com.dragon.renderlib.extension.MirrorType
import com.dragon.renderlib.utils.OpenGlUtils

class CombineSurfaceTexture(
    width: Int,
    height: Int,
    rotate: Float,
    mirrorType: MirrorType,
    frameRate: Int = 30,
    private val surfaceCallback: (Surface) -> Unit = {},
    private val notify: () -> Unit = { }
) :
    BasicTexture(width, height, rotate, mirrorType) {
    private lateinit var surfaceTexture: SurfaceTexture
    private lateinit var surface: Surface

    private var lastUpdateTime: Long = 0L
    private var updateInterval: Long = (1000_000_000L * 3) / (frameRate * 4)

    init {
        recreate()
    }

    override fun recreate() {
        textureId = OpenGlUtils.createTexture()
        surfaceTexture = SurfaceTexture(textureId)
        surfaceTexture.setDefaultBufferSize(width, height)
        surfaceTexture.setOnFrameAvailableListener {
            val current = System.nanoTime()
            if (current - lastUpdateTime >= updateInterval) {
                lastUpdateTime = current
                notify.invoke()
            } else {
                update()
            }
        }
        surface = Surface(surfaceTexture)
        surfaceCallback.invoke(surface)
    }

    fun update() {
        if (surface.isValid) {
            surfaceTexture.updateTexImage()
        }
    }

    override fun release() {
        super.release()
        surface.release()
        surfaceTexture.release()
    }
}