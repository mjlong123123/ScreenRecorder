package com.dragon.screenrecorder

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import com.dragon.screenrecorder.viewmodel.MainViewModel

/**
 * 录制管理器单例
 * 负责管理全局录制状态，确保 Activity 退出后录制继续运行
 */
object RecordingManager {
    private const val TAG = "RecordingManager"

    // 录制配置
    const val TARGET_WIDTH = 720
    const val TARGET_HEIGHT = 1280

    // 核心组件
    private var screenCapture: ScreenCapture? = null
    private var recorder: VideoRecorder? = null
    private var viewModel: MainViewModel? = null
    private var applicationContext: Context? = null

    // 录制参数（供 Activity 重建时恢复使用）
    private var currentIps: List<String> = emptyList()
    private var currentPort: Int = 0

    val isStarted: Boolean
        get() = recorder?.isStarted ?: false

    /**
     * 初始化录制管理器
     * 如果已在录制中，仅更新 launcher 和 viewModel 引用，不中断录制
     */
    fun initialize(
        context: Context,
        launcher: ActivityResultLauncher<Intent>,
        viewModel: MainViewModel
    ) {
        applicationContext = context.applicationContext
        this.viewModel = viewModel

        // 如果已有组件但未在录制中，先清理旧组件
        if (!isStarted && (recorder != null || screenCapture != null)) {
            Log.d(TAG, "Cleaning up old components before re-initialization")
            stopRecording()
            release()
        }

        if (isStarted) {
            // 正在录制中，仅更新 ScreenCapture 的 launcher 引用
            screenCapture?.updateLauncher(launcher)
            // 同步录制状态到新的 ViewModel
            viewModel.setRecording(true)
            Log.d(TAG, "RecordingManager reconnected (recording in progress)")
            return
        }

        // 首次初始化或重新初始化，创建所有组件
        initRecorder()
        screenCapture = ScreenCapture(context, launcher){}
        Log.d(TAG, "RecordingManager initialized")
    }

    /**
     * 初始化 VideoRecorder
     */
    private fun initRecorder() {
        recorder = VideoRecorder(TARGET_WIDTH, TARGET_HEIGHT,
            createSurface = { surface ->
                screenCapture?.createVirtualDisplay(TARGET_WIDTH, TARGET_HEIGHT, surface)
                screenCapture?.requestPermission()
            },
            destroySurface = { surface ->
                screenCapture?.stopScreenCapture()
                surface.release()
            }
        )
    }

    /**
     * 开始录制
     */
    fun startRecording(ips: List<String>, port: Int): Boolean {
        if (isStarted) {
            Log.w(TAG, "Recording already started")
            return false
        }
        if (ips.isEmpty()) {
            Log.e(TAG, "No destination IPs provided")
            return false
        }

        return try {
            currentIps = ips
            currentPort = port
            viewModel?.setRecording(true)
            recorder?.startVideoEncoder(ips, port)
            Log.d(TAG, "Recording started, targets: ${ips.size}, port: $port")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            viewModel?.setRecording(false)
            false
        }
    }

    /**
     * 停止录制
     */
    fun stopRecording() {
        if (!isStarted) {
            Log.w(TAG, "Recording not started")
            return
        }

        try {
            recorder?.stopVideoEncoder()
            viewModel?.setRecording(false)
            currentIps = emptyList()
            currentPort = 0
            Log.d(TAG, "Recording stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop recording", e)
        }
    }

    /**
     * 处理屏幕录制权限结果
     */
    fun handlePermissionResult(resultCode: Int, data: Intent?): Boolean {
        return screenCapture?.startScreenCapture(resultCode, data, TARGET_WIDTH, TARGET_HEIGHT) ?: false
    }

    /**
     * 获取当前录制目标 IPs
     */
    fun getCurrentIps(): List<String> = currentIps

    /**
     * 获取当前录制端口
     */
    fun getCurrentPort(): Int = currentPort

    /**
     * 释放所有资源（仅应用完全退出时调用）
     */
    fun release() {
        Log.d(TAG, "Releasing all resources")
        recorder?.stopVideoEncoder()
        recorder = null
        screenCapture?.release()
        screenCapture = null
        applicationContext = null
        viewModel = null
        currentIps = emptyList()
        currentPort = 0
    }
}
