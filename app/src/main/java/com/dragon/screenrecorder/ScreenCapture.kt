package com.dragon.screenrecorder

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import androidx.activity.result.ActivityResultLauncher
import androidx.annotation.RequiresApi

/**
 * 屏幕捕获管理类
 * 用于获取屏幕录制权限并创建 VirtualDisplay
 * 从 Android 10+ 开始，MediaProjection 需要在前台服务中运行
 */
@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
class ScreenCapture(
    private val context: Context,
    launcher: ActivityResultLauncher<Intent>,
    private val surfaceReady: (Surface) -> Unit
) {
    companion object {
        private const val TAG = "ScreenCapture"
    }

    // 使用可变的 launcher 引用，支持 Activity 重建时更新
    private var _launcher: ActivityResultLauncher<Intent>? = launcher
    private var isLauncherValid: Boolean = true

    private var mediaProjectionManager: MediaProjectionManager =
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    private var captureService: ScreenCaptureService? = null
    private var serviceBound = false
    private var pendingSurface: Surface? = null
    private var pendingWidth: Int = 0
    private var pendingHeight: Int = 0
    var isStarted = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "Service connected")
            val binder = service as ScreenCaptureService.LocalBinder
            captureService = binder.getService()
            serviceBound = true

            // 如果有待处理的 Surface，立即创建 VirtualDisplay
            pendingSurface?.let { surface ->
                createVirtualDisplayInternal(surface, pendingWidth, pendingHeight)
                pendingSurface = null
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "Service disconnected")
            captureService = null
            serviceBound = false
        }
    }

    /**
     * 请求屏幕录制权限
     */
    fun requestPermission() {
        val intent = mediaProjectionManager.createScreenCaptureIntent()
        if (_launcher != null && isLauncherValid) {
            _launcher!!.launch(intent)
        } else {
            Log.e(TAG, "Launcher is null or invalid, cannot request permission")
        }
    }

    /**
     * 启动屏幕捕获
     * @param resultCode 权限请求结果码
     * @param data 权限请求数据
     * @param width 目标宽度
     * @param height 目标高度
     * @return 是否启动成功
     */
    fun startScreenCapture(resultCode: Int, data: Intent?, width: Int, height: Int): Boolean {
        if (isStarted) {
            Log.w(TAG, "Screen capture already started")
            return false
        }

        if (resultCode != Activity.RESULT_OK || data == null) {
            Log.e(TAG, "Permission denied or invalid data")
            return false
        }

        return try {
            // 启动前台服务（Android 10+ 要求）
            val serviceIntent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenCaptureService.EXTRA_DATA, data)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }

            // 绑定服务
            val bindIntent = Intent(context, ScreenCaptureService::class.java)
            context.bindService(bindIntent, serviceConnection, Context.BIND_AUTO_CREATE)

            isStarted = true
            pendingWidth = width
            pendingHeight = height
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start screen capture service", e)
            false
        }
    }

    /**
     * 设置 Surface 并创建 VirtualDisplay
     */
    fun createVirtualDisplay(width: Int, height: Int, surface: Surface) {
        if (serviceBound && captureService != null) {
            createVirtualDisplayInternal(surface, width, height)
        } else {
            // 服务尚未绑定，保存参数稍后处理
            pendingSurface = surface
            pendingWidth = width
            pendingHeight = height
        }
    }

    private fun createVirtualDisplayInternal(surface: Surface, width: Int, height: Int): VirtualDisplay? {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = android.util.DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(metrics)

        val virtualDisplay = captureService?.createVirtualDisplay(surface, width, height, metrics.densityDpi)

        if (virtualDisplay != null) {
            surfaceReady(surface)
            Log.d(TAG, "VirtualDisplay created: ${width}x${height}")
        } else {
            Log.e(TAG, "Failed to create VirtualDisplay")
        }

        return virtualDisplay
    }

    /**
     * 停止屏幕捕获
     */
    fun stopScreenCapture() {
        if (!isStarted) {
            return
        }

        // 解绑服务（捕获异常防止服务未注册时崩溃）
        if (serviceBound) {
            try {
                context.unbindService(serviceConnection)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Service was not registered or already unbound")
            }
            serviceBound = false
        }

        // 停止服务
        val stopIntent = Intent(context, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP
        }
        context.startService(stopIntent)

        captureService = null
        isStarted = false
        Log.d(TAG, "Screen capture stopped")
    }

    /**
     * 更新启动器（用于 Activity 重建时重新连接）
     */
    fun updateLauncher(launcher: ActivityResultLauncher<Intent>) {
        _launcher = launcher
        isLauncherValid = true
        Log.d(TAG, "Launcher updated for Activity reconnection")
    }

    /**
     * 释放资源
     */
    fun release() {
        stopScreenCapture()
    }
}
