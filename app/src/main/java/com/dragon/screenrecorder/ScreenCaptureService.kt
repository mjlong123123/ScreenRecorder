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
import com.dragon.renderlib.background.RenderScope
import com.dragon.renderlib.camera.CameraHolder
import com.dragon.renderlib.egl.EGLCore
import com.dragon.renderlib.extension.MirrorType
import com.dragon.renderlib.node.NodesRender
import com.dragon.renderlib.node.OesTextureNode
import com.dragon.renderlib.texture.CombineSurfaceTexture

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
        const val ACTION_REQUEST_PERMISSION = "ACTION_REQUEST_PERMISSION"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_DATA = "EXTRA_DATA"

        var mediaProjection: MediaProjection? = null
            private set
        var isRunning = false
            private set
    }

    private val binder = LocalBinder()
    private var virtualDisplay: VirtualDisplay? = null
    private var surface: Surface? = null
    private var screenCapture: ScreenCapture? = null
    private var videoRecorder: VideoRecorder? = null
    private var currentIps: List<String> = emptyList()
    private var currentPort: Int = 0
    private var isRecording: Boolean = false
    private var nodesRender: NodesRender? = null
    private var eglRender: EGLRender? = null
    private var renderScope: RenderScope? = null
    private var eglSurfaceHolder: EGLCore.EGLSurfaceHolder? = null

    inner class LocalBinder : Binder() {
        fun getService(): ScreenCaptureService = this@ScreenCaptureService
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        createNotificationChannel()
        // 初始化 ScreenCapture
        screenCapture = ScreenCapture(applicationContext) { surface ->
            // Surface 就绪回调，可以在此处理
            Log.d(TAG, "Surface ready: $surface")
        }
    }


    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 立即启动前台服务，避免 ANR
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
                if (resultCode == RESULT_OK && data != null) {
                    startService(resultCode, data)
                    isRunning = true
                } else {
                    Log.e(TAG, "Invalid resultCode or data")
                    stopSelf()
                }
            }

            ACTION_STOP -> {
                stopCapture()
                isRunning = false
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startService(resultCode: Int, data: Intent) {
        Log.d(TAG, "Initializing MediaProjection")

        // 创建 MediaProjection
        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        if (mediaProjection == null) {
            Log.e(TAG, "Failed to create MediaProjection")
            stopSelf()
            return
        }

        // 设置给 ScreenCapture
        screenCapture?.setMediaProjection(mediaProjection)

        Log.d(TAG, "MediaProjection initialized successfully")
    }

    /**
     * 创建 VirtualDisplay
     */
    fun createVirtualDisplay(surface: Surface, width: Int, height: Int, dpi: Int): VirtualDisplay? {
        this.surface = surface
        return screenCapture?.createVirtualDisplay(width, height, surface)
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
        isRunning = false
    }

    /**
     * 开始录制
     * @param ips 目标 IP 地址列表
     * @param port RTP 端口
     * @return 是否成功开始录制
     */
    fun startRecording(ips: List<String>, port: Int): Boolean {
        if (isRecording) {
            Log.w(TAG, "Recording already started")
            return false
        }
        if (ips.isEmpty()) {
            Log.e(TAG, "No destination IPs provided")
            return false
        }

        // 检查 MediaProjection 是否有效
        if (mediaProjection == null) {
            Log.w(TAG, "MediaProjection is null, need to request permission")
            // 需要请求权限，返回 false 让调用者处理权限请求
            return false
        }

        // 如果 screenCapture 已被释放，重新创建
        if (screenCapture == null) {
            screenCapture = ScreenCapture(applicationContext) { surface ->
                Log.d(TAG, "Surface ready: $surface")
            }
            screenCapture?.setMediaProjection(mediaProjection)
        }

        currentIps = ips
        currentPort = port
        isRecording = true

        // 目标分辨率
        val targetWidth = 720
        val targetHeight = 1280

        // 获取屏幕实际分辨率
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        val displayMetrics = android.util.DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(displayMetrics)
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        // 计算屏幕和目标的宽高比
        val screenAspectRatio = screenWidth.toFloat() / screenHeight.toFloat()
        val targetAspectRatio = targetWidth.toFloat() / targetHeight.toFloat()

        Log.d(
            TAG,
            "Screen resolution: ${screenWidth}x${screenHeight}, aspect ratio: $screenAspectRatio"
        )
        Log.d(
            TAG,
            "Target resolution: ${targetWidth}x${targetHeight}, aspect ratio: $targetAspectRatio"
        )

        nodesRender = NodesRender(targetWidth, targetHeight)
        eglRender = EGLRender(nodesRender!!)
        renderScope = RenderScope(eglRender!!)
        // 创建 VideoRecorder（使用目标分辨率）
        videoRecorder = VideoRecorder(
            targetWidth, targetHeight,
            createSurface = { surface ->
                renderScope?.addSurfaceHolder(
                    EGLCore.EGLSurfaceHolder(
                        surface,
                        targetWidth.toFloat(),
                        targetHeight.toFloat()
                    )
                )
                renderScope?.requestRender()
            },
            destroySurface = { surface ->
                renderScope?.removeSurfaceHolder(surface)
                // Surface 销毁时的处理
                surface.release()
            }
        )
        videoRecorder?.startVideoEncoder(ips, port)
        nodesRender?.runInRender {
            val texture = CombineSurfaceTexture(
                targetWidth, targetHeight,
                0.toFloat(),
                MirrorType.VERTICAL,
                frameRate = 30,
                { surface ->
                    screenCapture?.createVirtualDisplay(targetWidth, targetHeight, surface)
                }) {
                renderScope?.requestRender()
            }
            val previewNode = OesTextureNode(
                0f,
                0f,
                nodesRender?.width?.toFloat() ?: 720.toFloat(),
                nodesRender?.height?.toFloat() ?: 1280.toFloat(),
                texture
            )
            addNode(0, previewNode)
        }
        // 更新通知栏，显示正在录制状态
        updateNotification(isRecording = true)

        Log.d(TAG, "Recording started, targets: ${ips.size}, port: $port")
        return true
    }

    /**
     * 停止录制
     */
    fun stopRecording() {
        if (!isRecording) {
            Log.w(TAG, "Recording not started")
            return
        }

        videoRecorder?.stopVideoEncoder()
        videoRecorder = null
        currentIps = emptyList()
        currentPort = 0
        isRecording = false
        nodesRender?.release()
        nodesRender = null
        renderScope?.release()
        renderScope = null
        
        // 释放 screenCapture
        screenCapture?.release()
        screenCapture = null
        
        // 清理 EGL 相关资源
        eglRender = null
        eglSurfaceHolder = null
        surface = null
        virtualDisplay = null
        
        // 停止 MediaProjection（每个 MediaProjection 只能创建一次 VirtualDisplay）
        mediaProjection?.stop()
        mediaProjection = null
        
        // 更新通知栏，提示录制已停止
        updateNotification(isRecording = false)

        Log.d(TAG, "Recording stopped")
    }

    /**
     * 检查是否正在录制
     */
    fun isRecording(): Boolean {
        return isRecording
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "屏幕录制服务"
            val descriptionText = "屏幕录制前台服务通知"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(isRecording: Boolean = false): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity2::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (isRecording) "正在录制屏幕" else "录制已停止"
        val text = if (isRecording) "屏幕录制服务正在运行" else "点击打开应用"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    /**
     * 更新通知栏
     */
    private fun updateNotification(isRecording: Boolean) {
        val notification = createNotification(isRecording)
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCapture()
        // 停止前台服务并移除通知栏
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        isRunning = false
        Log.d(TAG, "Service destroyed")
    }
}
