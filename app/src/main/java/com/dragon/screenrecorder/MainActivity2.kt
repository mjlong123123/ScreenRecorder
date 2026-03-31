package com.dragon.screenrecorder

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.dragon.renderlib.background.RenderScope
import com.dragon.renderlib.egl.EGLCore
import com.dragon.renderlib.node.NodesRender
import com.dragon.screenrecorder.ui.main.*
import com.dragon.screenrecorder.ui.theme.VideoRecorderTheme
import com.dragon.screenrecorder.utils.ToastUtils
import com.dragon.screenrecorder.viewmodel.MainViewModel

/**
 * 新的主Activity - 使用Compose UI和MVVM架构
 */
class MainActivity2 : ComponentActivity() {
    private val targetWidth = 720
    private val targetHeight = 1280
    private lateinit var screenCapture: ScreenCapture
    private val viewModel: MainViewModel by viewModels()

    private val recorder = VideoRecorder(targetWidth, targetHeight, { surface ->
        screenCapture.createVirtualDisplay(targetWidth, targetHeight, surface)
        screenCapture.requestPermission()
    }, { surface ->
        screenCapture.stopScreenCapture()
        surface.release()
    })

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            screenCapture.startScreenCapture(result.resultCode,result.data,targetWidth,targetWidth)
        } else {
            recorder.stopVideoEncoder()
            ToastUtils.showError(this, "屏幕录制权限被拒绝")
        }
    }

    // SDP 文件保存启动器
    private val saveSdpLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/sdp")
    ) { uri ->
        uri?.let {
            try {
                val port = viewModel.rtpPort.value
                val sdpContent = """m=video $port RTP/AVP 96
a=rtpmap:96 H264
a=framerate:30"""

                contentResolver.openOutputStream(it)?.use { outputStream ->
                    outputStream.write(sdpContent.toByteArray())
                }
                ToastUtils.showSuccess(this, "SDP 文件已保存：mobile_${port}.sdp")
            } catch (e: Exception) {
                ToastUtils.showError(this, "保存 SDP 文件失败：${e.message}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 启用全面屏，设置为透明状态栏
        enableEdgeToEdge()

        // 保持屏幕常亮
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 不适配系统窗口边距，让内容延伸到全屏
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // 隐藏状态栏和导航栏，并允许用户滑动拉出
        val windowInsetsController = WindowInsetsControllerCompat(
            window,
            window.decorView
        )
        windowInsetsController.hide(
            WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars()
        )
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        // 加载已保存的设备 IP 和当前 IP
        viewModel.loadIpAddress(this)
        viewModel.loadDevices(this)
        viewModel.loadRtpPort(this)

        // 初始化屏幕捕获
        screenCapture = ScreenCapture(this, screenCaptureLauncher) { surface ->
        }

        setContent {
            VideoRecorderTheme {
                val context = LocalContext.current
                val isRecording by viewModel.isRecording.collectAsState()
                val deviceIps by viewModel.deviceIps.collectAsState()
                val showDeviceMenu by viewModel.showDeviceMenu.collectAsState()
                val showIpDialog by viewModel.showIpDialog.collectAsState()
                val showPortDialog by viewModel.showPortDialog.collectAsState()
                val showSdpDialog by viewModel.showSdpDialog.collectAsState()
                val showAboutDialog by viewModel.showAboutDialog.collectAsState()
                val rtpPort by viewModel.rtpPort.collectAsState()

                MainScreen2(
                    isRecording = isRecording,
                    deviceIps = deviceIps,
                    rtpPort = rtpPort,
                    onSettingsClick = { viewModel.toggleDeviceMenu(true) },
                    onRecordClick = { handleRecordClick() },
                    showDeviceMenu = showDeviceMenu,
                    showIpDialog = showIpDialog,
                    showPortDialog = showPortDialog,
                    showSdpDialog = showSdpDialog,
                    showAboutDialog = showAboutDialog,
                    onDismissDeviceMenu = { viewModel.toggleDeviceMenu(false) },
                    onDismissIpDialog = { viewModel.showIpDialog(false) },
                    onConfirmIpDialog = { ip ->
                        viewModel.addDevice(ip, this)
                        viewModel.showIpDialog(false)
                    },
                    onDismissPortDialog = { viewModel.showPortDialog(false) },
                    onConfirmPortDialog = { port ->
                        if (viewModel.updateRtpPort(port, this)) {
                            viewModel.showPortDialog(false)
                        }
                    },
                    onDismissSdpDialog = { viewModel.showSdpDialog(false) },
                    onConfirmSdpDialog = {
                        viewModel.showSdpDialog(false)
                        saveSdpLauncher.launch("mobile_${rtpPort}.sdp")
                    },
                    onDismissAboutDialog = { viewModel.showAboutDialog(false) },
                    onScanDevice = { /* TODO: implement scan */ },
                    onAddDevice = { viewModel.showIpDialog(true) },
                    onSetPort = { viewModel.showPortDialog(true) },
                    onGenerateSdp = { viewModel.showSdpDialog(true) },
                    onDeviceClick = { ip ->
                        viewModel.selectDevice(ip, this)
                        viewModel.toggleDeviceMenu(false)
                    },
                    onDeleteDevice = { ip ->
                        viewModel.removeDevice(ip, this)
                    },
                    onAboutClick = {
                        viewModel.toggleDeviceMenu(false)
                        viewModel.showAboutDialog(true)
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        screenCapture.release()
        recorder.stopVideoEncoder()
    }

    /**
     * 处理录制按钮点击
     */
    private fun handleRecordClick() {
        if (recorder.isStarted) {
            recorder.stopVideoEncoder()
            viewModel.setRecording(false)
            ToastUtils.showSuccess(this, "已停止录制")
        } else {
            val deviceIps = viewModel.getSelectedIps()
            if (deviceIps.isEmpty()) {
                ToastUtils.showError(this, "请先添加目的设备 IP 地址")
                return
            }
            val rtpPort = viewModel.rtpPort.value
            recorder.startVideoEncoder(deviceIps, rtpPort)
        }
    }

    companion object {
        private const val TAG = "MainActivity2"
    }
}

/**
 * MainScreen2 Composable - 主界面UI
 */
@Composable
fun MainScreen2(
    isRecording: Boolean,
    deviceIps: List<String>,
    rtpPort: Int,
    onSettingsClick: () -> Unit,
    onRecordClick: () -> Unit,
    showDeviceMenu: Boolean,
    showIpDialog: Boolean,
    showPortDialog: Boolean,
    showSdpDialog: Boolean,
    showAboutDialog: Boolean,
    onDismissDeviceMenu: () -> Unit,
    onDismissIpDialog: () -> Unit,
    onConfirmIpDialog: (String) -> Unit,
    onDismissPortDialog: () -> Unit,
    onConfirmPortDialog: (Int) -> Unit,
    onDismissSdpDialog: () -> Unit,
    onConfirmSdpDialog: () -> Unit,
    onDismissAboutDialog: () -> Unit,
    onScanDevice: () -> Unit,
    onAddDevice: () -> Unit,
    onSetPort: () -> Unit,
    onGenerateSdp: () -> Unit,
    onDeviceClick: (String) -> Unit,
    onDeleteDevice: (String) -> Unit,
    onAboutClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 顶部区域 - 设置按钮
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 48.dp, end = 24.dp),
                contentAlignment = Alignment.TopEnd
            ) {
                IconButton(
                    onClick = onSettingsClick,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.3f))
                        .size(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "设置",
                        tint = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            // 中间区域 - 状态显示
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.weight(1f)
            ) {
                if (deviceIps.isNotEmpty()) {
                    StatusIndicator2(
                        deviceIps = deviceIps,
                        isRecording = isRecording,
                        currentPort = rtpPort
                    )
                } else {
                    Text(
                        text = "点击设置添加目标设备",
                        color = Color.White.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }

            // 底部区域 - 录制按钮
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 64.dp),
                contentAlignment = Alignment.Center
            ) {
                RecordButton(
                    isRecording = isRecording,
                    onClick = onRecordClick
                )
            }
        }

        // 显示设备菜单
        if (showDeviceMenu) {
            DeviceMenu(
                deviceIps = deviceIps,
                currentPort = rtpPort,
                onScanDevice = onScanDevice,
                onAddDevice = onAddDevice,
                onSetPort = onSetPort,
                onGenerateSdp = onGenerateSdp,
                onDeviceClick = onDeviceClick,
                onDeleteDevice = onDeleteDevice,
                onAboutClick = onAboutClick,
                onDismiss = onDismissDeviceMenu,
                enabled = !isRecording
            )
        }

        // 显示 IP 输入对话框
        if (showIpDialog) {
            IpAddressDialog(
                onDismiss = onDismissIpDialog,
                onConfirm = onConfirmIpDialog
            )
        }

        // 显示端口设置对话框
        if (showPortDialog) {
            PortSettingDialog(
                onDismiss = onDismissPortDialog,
                onConfirm = onConfirmPortDialog,
                initialValue = rtpPort
            )
        }

        // 显示 SDP 生成确认对话框
        if (showSdpDialog) {
            SdpGenerateDialog(
                currentPort = rtpPort,
                onDismiss = onDismissSdpDialog,
                onConfirm = onConfirmSdpDialog
            )
        }

        // 显示关于对话框
        if (showAboutDialog) {
            AboutDialog(
                onDismiss = onDismissAboutDialog
            )
        }
    }
}

/**
 * 录制按钮组件
 */
@Composable
private fun RecordButton(
    isRecording: Boolean,
    onClick: () -> Unit
) {
    // 录制按钮的脉冲动画
    val infiniteTransition = rememberInfiniteTransition(label = "recording pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse alpha"
    )

    val buttonColor = if (isRecording) {
        Color(0xFFFF3B30) // 红色 - 录制中
    } else {
        Color(0xFF34C759) // 绿色 - 待机
    }

    Box(
        modifier = Modifier
            .size(80.dp)
            .clip(CircleShape)
            .background(buttonColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                role = Role.Button
            ),
        contentAlignment = Alignment.Center
    ) {
        // 录制状态指示器（内部圆形或方形）
        Box(
            modifier = Modifier
                .size(if (isRecording) 32.dp else 56.dp)
                .clip(
                    if (isRecording) {
                        RoundedCornerShape(4.dp)
                    } else {
                        CircleShape
                    }
                )
                .background(
                    Color.White.copy(
                        alpha = if (isRecording) pulseAlpha else 0.3f
                    )
                )
        )
    }
}

/**
 * 录制状态指示器 - 显示已连接的设备 IP
 */
@Composable
private fun StatusIndicator2(
    deviceIps: List<String>,
    isRecording: Boolean,
    modifier: Modifier = Modifier,
    currentPort: Int = 40018
) {
    Box(
        modifier = modifier
            .background(
                color = Color.Black.copy(alpha = 0.5f),
                shape = RoundedCornerShape(16.dp)
            )
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 录制状态图标和文字
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 信号图标（使用圆点代替）
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            if (isRecording) Color(0xFF34C759) else Color(0xFF0A84FF)
                        )
                )

                Text(
                    text = if (isRecording) "录制中" else "已连接",
                    color = Color.White.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            // 显示 IP:Port 列表（最多显示 3 个）
            Spacer(modifier = Modifier.height(8.dp))
            deviceIps.take(3).forEachIndexed { index, ip ->
                Text(
                    text = "• $ip:$currentPort",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }

            // 如果超过 3 个，显示省略号
            if (deviceIps.size > 3) {
                Text(
                    text = "+ ${deviceIps.size - 3} 更多",
                    color = Color.White.copy(alpha = 0.5f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
