package com.dragon.screenrecorder

import android.app.Activity.RESULT_OK
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat

/**
 * 屏幕录制前台服务
 * 用于在后台保持 MediaProjection 运行（Android 10+ 要求）
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val CHANNEL_ID = "ScreenCaptureServiceChannel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_DATA = "EXTRA_DATA"

        var mediaProjection: MediaProjection? = null
            private set
    }

    private val binder = LocalBinder()
    private var virtualDisplay: VirtualDisplay? = null
    private var surface: Surface? = null

    inner class LocalBinder : Binder() {
        fun getService(): ScreenCaptureService = this@ScreenCaptureService
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        createNotificationChannel()
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 立即启动前台服务，避免 ANR
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
                if (resultCode == RESULT_OK && data != null) {
                    startService(resultCode, data)
                } else {
                    Log.e(TAG, "Invalid resultCode or data")
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                stopCapture()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startService(resultCode: Int, data: Intent) {
        Log.d(TAG, "Initializing MediaProjection")

        // 创建 MediaProjection
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        if (mediaProjection == null) {
            Log.e(TAG, "Failed to create MediaProjection")
            stopSelf()
            return
        }

        Log.d(TAG, "MediaProjection initialized successfully")
    }

    /**
     * 创建 VirtualDisplay
     */
    fun createVirtualDisplay(surface: Surface, width: Int, height: Int, dpi: Int): VirtualDisplay? {
        this.surface = surface

        if (mediaProjection == null) {
            Log.e(TAG, "MediaProjection is null")
            return null
        }

        virtualDisplay?.release()
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenRecorder",
            width,
            height,
            dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null,
            null
        )

        Log.d(TAG, "VirtualDisplay created: ${width}x${height}")
        return virtualDisplay
    }

    /**
     * 停止捕获
     */
    fun stopCapture() {
        Log.d(TAG, "Stopping capture")
        virtualDisplay?.release()
        virtualDisplay = null
        surface = null
        mediaProjection?.stop()
        mediaProjection = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "屏幕录制服务"
            val descriptionText = "屏幕录制前台服务通知"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("正在录制屏幕")
            .setContentText("屏幕录制服务正在运行")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCapture()
        Log.d(TAG, "Service destroyed")
    }
}
