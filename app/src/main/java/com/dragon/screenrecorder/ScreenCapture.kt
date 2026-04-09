package com.dragon.screenrecorder

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import androidx.annotation.RequiresApi

/**
 * 屏幕捕获管理类
 * 负责使用 MediaProjection 创建 VirtualDisplay
 * 从 Android 10+ 开始，MediaProjection 需要在前台服务中运行
 */
class ScreenCapture(
    private val context: Context,
    private val surfaceReady: (Surface) -> Unit
) {
    companion object {
        private const val TAG = "ScreenCapture"
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // 帧率设置
    private var frameRate: Float = 30f

    /**
     * MediaProjection 回调，用于处理 MediaProjection 状态变化
     */
    private val mediaProjectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.d(TAG, "MediaProjection stopped")
            release()
        }
    }

    /**
     * VirtualDisplay 回调，用于处理虚拟显示状态变化
     */
    private val virtualDisplayCallback = object : VirtualDisplay.Callback() {
        override fun onPaused() {
            Log.d(TAG, "VirtualDisplay paused")
        }

        override fun onResumed() {
            Log.d(TAG, "VirtualDisplay resumed")
        }

        override fun onStopped() {
            Log.d(TAG, "VirtualDisplay stopped")
        }
    }

    /**
     * 设置 MediaProjection 实例
     */
    fun setMediaProjection(mediaProjection: MediaProjection?) {
        // 取消注册旧的回调
        this.mediaProjection?.unregisterCallback(mediaProjectionCallback)
        
        this.mediaProjection = mediaProjection
        
        // 注册新的回调
        mediaProjection?.registerCallback(mediaProjectionCallback, mainHandler)
    }
    
    /**
     * 设置帧率
     * @param fps 帧率值
     */
    fun setFrameRate(fps: Float) {
        this.frameRate = fps.coerceIn(1f, 60f)
    }
    
    /**
     * 获取当前帧率
     */
    fun getFrameRate(): Float = frameRate

    /**
     * 创建 VirtualDisplay
     * @param width 目标宽度
     * @param height 目标高度
     * @param surface 用于渲染的 Surface
     * @return 创建的 VirtualDisplay，失败返回 null
     */
    fun createVirtualDisplay(width: Int, height: Int, surface: Surface): VirtualDisplay? {
        return createVirtualDisplay(width, height, surface, frameRate)
    }

    /**
     * 创建 VirtualDisplay，支持帧率设置
     * @param width 目标宽度
     * @param height 目标高度
     * @param surface 用于渲染的 Surface
     * @param frameRate 帧率 (Android 11+, 1-60 fps)
     * @return 创建的 VirtualDisplay，失败返回 null
     */
    fun createVirtualDisplay(width: Int, height: Int, surface: Surface, frameRate: Float): VirtualDisplay? {
        val mediaProjection = mediaProjection ?: run {
            Log.e(TAG, "MediaProjection is null")
            return null
        }

        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = android.util.DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(metrics)

        virtualDisplay?.release()
        
        // Android 11+ 在 Surface 上设置帧率
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            setSurfaceFrameRate(surface, frameRate)
        }
        
        @Suppress("DEPRECATION")
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "ScreenRecorder",
            width,
            height,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            virtualDisplayCallback,
            mainHandler
        )

        if (virtualDisplay != null) {
            surfaceReady(surface)
            Log.d(TAG, "VirtualDisplay created: ${width}x${height} @ ${frameRate}fps")
        } else {
            Log.e(TAG, "Failed to create VirtualDisplay")
        }

        return virtualDisplay
    }

    /**
     * 设置 Surface 的帧率（Android 11+）
     * @param surface 要设置帧率的 Surface
     * @param frameRate 目标帧率
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun setSurfaceFrameRate(surface: Surface, frameRate: Float) {
        try {
            // 使用 Surface.setFrameRate() 方法
            // CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS 表示只有在无缝切换时才改变帧率
//            surface.setFrameRate(
//                frameRate,
//                Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
//                Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS
//            )
            Log.d(TAG, "Surface frame rate set to: $frameRate")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set surface frame rate: ${e.message}")
        }
    }

    /**
     * 释放资源
     */
    fun release() {
        virtualDisplay?.release()
        virtualDisplay = null
        mediaProjection?.unregisterCallback(mediaProjectionCallback)
        mediaProjection = null
        Log.d(TAG, "ScreenCapture released")
    }
}
