package com.dragon.screenrecorder

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 录制管理器单例
 * 负责管理录制状态，提供可监听数据用于 UI 更新
 * 所有状态由 Service 维护，RecordingManager 仅作为代理层
 */
object RecordingManager {
    private const val TAG = "RecordingManager"

    // 录制配置
    const val TARGET_WIDTH = 720
    const val TARGET_HEIGHT = 1280

    // 状态流，用于 UI 观察录制状态
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private var applicationContext: Context? = null
    private var captureService: ScreenCaptureService? = null
    private var serviceBound = false
    private var pendingIps: List<String> = emptyList()
    private var pendingPort: Int = 0

    // Activity Result Launcher 引用（由 Activity 提供）
    private var screenCaptureLauncher: ActivityResultLauncher<Intent>? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "Service connected")
            val binder = service as ScreenCaptureService.LocalBinder
            captureService = binder.getService()
            serviceBound = true
            
            // 从服务同步录制状态
            val isServiceRecording = captureService?.isRecording() ?: false
            _isRecording.value = isServiceRecording
            Log.d(TAG, "Recording state synced from service: $isServiceRecording")

            // 如果有待处理的录制请求，立即开始录制
            if (pendingIps.isNotEmpty()) {
                startRecordingInternal(pendingIps, pendingPort)
                pendingIps = emptyList()
                pendingPort = 0
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "Service disconnected")
            captureService = null
            serviceBound = false
            _isRecording.value = false
        }
    }

    /**
     * 初始化录制管理器
     */
    fun initialize(context: Context, launcher: ActivityResultLauncher<Intent>) {
        applicationContext = context.applicationContext
        this.screenCaptureLauncher = launcher
        
        // 如果服务正在运行，同步状态并重新绑定
        if (ScreenCaptureService.isRunning) {
            Log.d(TAG, "Service is running, syncing state and rebinding...")
            // 立即同步状态（使用静态标志）
            _isRecording.value = ScreenCaptureService.isRunning
            // 重新绑定服务以获取服务实例
            rebindService()
        }
        
        Log.d(TAG, "RecordingManager initialized, isRecording=${_isRecording.value}")
    }

    /**
     * 请求屏幕录制权限
     * @return 是否成功发起权限请求
     */
    fun requestPermission(): Boolean {
        val launcher = screenCaptureLauncher ?: run {
            Log.e(TAG, "Launcher is null, cannot request permission")
            return false
        }
        // 创建屏幕捕获意图并启动权限请求
        val mediaProjectionManager = applicationContext?.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? android.media.projection.MediaProjectionManager
        val intent = mediaProjectionManager?.createScreenCaptureIntent() ?: run {
            Log.e(TAG, "Failed to create screen capture intent")
            return false
        }
        launcher.launch(intent)
        return true
    }

    /**
     * 处理屏幕录制权限结果
     * @param resultCode 权限请求结果码
     * @param data 权限请求数据
     * @return 是否启动成功
     */
    fun handlePermissionResult(resultCode: Int, data: Intent?): Boolean {
        if (resultCode != android.app.Activity.RESULT_OK || data == null) {
            Log.e(TAG, "Permission denied or invalid data")
            return false
        }

        // 启动服务并绑定
        val serviceIntent = Intent(applicationContext, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_START
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_DATA, data)
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            applicationContext?.startForegroundService(serviceIntent)
        } else {
            applicationContext?.startService(serviceIntent)
        }

        // 绑定服务
        val bindIntent = Intent(applicationContext, ScreenCaptureService::class.java)
        applicationContext?.bindService(bindIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        return true
    }

    /**
     * 开始录制
     */
    fun startRecording(ips: List<String>, port: Int): Boolean {
        if (ips.isEmpty()) {
            Log.e(TAG, "No destination IPs provided")
            return false
        }

        // 检查服务是否仍在运行
        if (ScreenCaptureService.isRunning) {
            // 服务正在运行但未绑定，需要重新绑定
            if (!serviceBound || captureService == null) {
                rebindService()
            }
        }

        if (serviceBound && captureService != null) {
            return startRecordingInternal(ips, port)
        } else {
            // 服务尚未绑定，保存参数稍后处理
            pendingIps = ips
            pendingPort = port
            // 需要先请求权限
            requestPermission()
            return true
        }
    }

    private fun startRecordingInternal(ips: List<String>, port: Int): Boolean {
        return try {
            captureService?.startRecording(ips, port)
            _isRecording.value = true
            Log.d(TAG, "Recording started, targets: ${ips.size}, port: $port")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            _isRecording.value = false
            false
        }
    }

    /**
     * 重新绑定到已运行的服务
     */
    private fun rebindService() {
        if (!ScreenCaptureService.isRunning) {
            Log.w(TAG, "Service is not running, cannot rebind")
            return
        }

        try {
            val bindIntent = Intent(applicationContext, ScreenCaptureService::class.java)
            applicationContext?.bindService(bindIntent, serviceConnection, Context.BIND_AUTO_CREATE)
            Log.d(TAG, "Rebinding to service")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rebind service", e)
        }
    }

    /**
     * 停止录制
     */
    fun stopRecording() {
        Log.d(TAG, "stopRecording called, serviceBound=$serviceBound, isRunning=${ScreenCaptureService.isRunning}")
        
        try {
            // 如果服务正在运行，直接发送停止Intent
            if (ScreenCaptureService.isRunning) {
                // 方法1：通过服务绑定停止（如果已绑定）
                if (serviceBound && captureService != null) {
                    captureService?.stopRecording()
                    _isRecording.value = false
                    Log.d(TAG, "Recording stopped via service binding")
                } else {
                    // 方法2：通过Intent停止（如果未绑定）
                    Log.d(TAG, "Service not bound, stopping via Intent")
                    val stopIntent = Intent(applicationContext, ScreenCaptureService::class.java).apply {
                        action = ScreenCaptureService.ACTION_STOP
                    }
                    applicationContext?.startService(stopIntent)
                    _isRecording.value = false
                    serviceBound = false
                    captureService = null
                }
            } else {
                Log.w(TAG, "Service is not running")
                _isRecording.value = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop recording", e)
            _isRecording.value = false
        }
    }

    /**
     * 解绑服务（Activity 退出时调用）
     * 服务继续在后台运行，但不再与 Activity 绑定
     */
    fun unbindService() {
        if (serviceBound) {
            try {
                applicationContext?.unbindService(serviceConnection)
                Log.d(TAG, "Service unbound")
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Service was not registered or already unbound")
            }
            serviceBound = false
            captureService = null
        }
    }

    /**
     * 尝试停止服务
     * 如果正在录制，只解绑服务，让录制在后台继续运行
     * 如果不在录制，停止服务
     */
    fun tryStopService() {
        if (_isRecording.value) {
            // 正在录制，只解绑服务
            Log.d(TAG, "Recording in progress, only unbinding service")
            unbindService()
        } else {
            // 不在录制，停止服务
            Log.d(TAG, "Not recording, stopping service")
            unbindService()
            
            // 发送停止服务的Intent
            val stopIntent = Intent(applicationContext, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_STOP
            }
            applicationContext?.startService(stopIntent)
        }
    }

    /**
     * 释放所有资源（仅应用完全退出时调用）
     */
    fun release() {
        Log.d(TAG, "Releasing all resources")
        
        unbindService()
        
        // 清理所有引用
        applicationContext = null
        screenCaptureLauncher = null
        _isRecording.value = false
        pendingIps = emptyList()
        pendingPort = 0
    }
}
