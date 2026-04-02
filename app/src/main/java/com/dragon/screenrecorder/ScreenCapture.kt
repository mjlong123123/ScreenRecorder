package com.dragon.screenrecorder

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import androidx.annotation.RequiresApi

/**
 * 屏幕捕获管理类
 * 负责使用 MediaProjection 创建 VirtualDisplay
 * 从 Android 10+ 开始，MediaProjection 需要在前台服务中运行
 */
@RequiresApi(android.os.Build.VERSION_CODES.LOLLIPOP)
class ScreenCapture(
    private val context: Context,
    private val surfaceReady: (Surface) -> Unit
) {
    companion object {
        private const val TAG = "ScreenCapture"
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null

    /**
     * 设置 MediaProjection 实例
     */
    fun setMediaProjection(mediaProjection: MediaProjection?) {
        this.mediaProjection = mediaProjection
    }

    /**
     * 创建 VirtualDisplay
     * @param width 目标宽度
     * @param height 目标高度
     * @param surface 用于渲染的 Surface
     * @return 创建的 VirtualDisplay，失败返回 null
     */
    fun createVirtualDisplay(width: Int, height: Int, surface: Surface): VirtualDisplay? {
        val mediaProjection = mediaProjection ?: run {
            Log.e(TAG, "MediaProjection is null")
            return null
        }

        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = android.util.DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(metrics)

        virtualDisplay?.release()
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "ScreenRecorder",
            width,
            height,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null,
            null
        )

        if (virtualDisplay != null) {
            surfaceReady(surface)
            Log.d(TAG, "VirtualDisplay created: ${width}x${height}")
        } else {
            Log.e(TAG, "Failed to create VirtualDisplay")
        }

        return virtualDisplay
    }

    /**
     * 释放资源
     */
    fun release() {
        virtualDisplay?.release()
        virtualDisplay = null
        mediaProjection = null
        Log.d(TAG, "ScreenCapture released")
    }
}
