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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Alignment.Companion.CenterVertically
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.ui.Alignment.Companion.Center
import kotlin.math.cos
import kotlin.math.sin
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.dragon.screenrecorder.ui.main.*
import com.dragon.screenrecorder.ui.theme.*
import com.dragon.screenrecorder.utils.ToastUtils
import com.dragon.screenrecorder.viewmodel.MainViewModel

/**
 * 主Activity - 使用Compose UI和MVVM架构
 */
class MainActivity2 : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    // 屏幕录制权限启动器
    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            if (!RecordingManager.handlePermissionResult(result.resultCode, result.data)) {
                ToastUtils.showError(this, "屏幕录制启动失败")
            }
            // handlePermissionResult 中已经处理了绑定和录制启动
        } else {
            ToastUtils.showError(this, "屏幕录制权限被拒绝")
        }
    }

    // SDP 文件保存启动器
    private val saveSdpLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/sdp")
    ) { uri ->
        uri?.let { saveSdpFile(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setupWindow()
        loadSavedData()
        initializeRecordingManager()
        setContent { MainScreen() }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 尝试停止服务：如果正在录制，只解绑；如果不录制，停止服务
        RecordingManager.tryStopService()
    }

    /**
     * 设置窗口属性
     */
    private fun setupWindow() {
        enableEdgeToEdge()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val windowInsetsController = WindowInsetsControllerCompat(
            window,
            window.decorView
        )
        windowInsetsController.hide(
            WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars()
        )
        windowInsetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    /**
     * 加载已保存的数据
     */
    private fun loadSavedData() {
        viewModel.loadIpAddress(this)
        viewModel.loadDevices(this)
        viewModel.loadRtpPort(this)
    }

    /**
     * 初始化录制管理器
     */
    private fun initializeRecordingManager() {
        RecordingManager.initialize(this, screenCaptureLauncher)
    }

    /**
     * 处理录制按钮点击
     */
    private fun handleRecordClick() {
        if (RecordingManager.isRecording.value) {
            RecordingManager.stopRecording()
            ToastUtils.showSuccess(this, "已停止录制")
        } else {
            startRecording()
        }
    }

    /**
     * 开始录制
     */
    private fun startRecording() {
        val deviceIps = viewModel.getSelectedIps()
        if (deviceIps.isEmpty()) {
            ToastUtils.showError(this, "请先添加目的设备 IP 地址")
            return
        }

        val rtpPort = viewModel.rtpPort.value
        if (RecordingManager.startRecording(deviceIps, rtpPort)) {
            ToastUtils.showSuccess(this, "开始录制")
        } else {
            ToastUtils.showError(this, "启动录制失败")
        }
    }

    /**
     * 保存 SDP 文件
     */
    private fun saveSdpFile(uri: android.net.Uri) {
        try {
            val port = viewModel.rtpPort.value
            val sdpContent = """m=video $port RTP/AVP 96
a=rtpmap:96 H264
a=framerate:30"""

            contentResolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(sdpContent.toByteArray())
            }
            ToastUtils.showSuccess(this, "SDP 文件已保存：mobile_$port.sdp")
        } catch (e: Exception) {
            ToastUtils.showError(this, "保存 SDP 文件失败：${e.message}")
        }
    }

    /**
     * 主界面 Composable
     */
    @Composable
    private fun MainScreen() {
        VideoRecorderTheme {
            val context = LocalContext.current
            val isRecording by RecordingManager.isRecording.collectAsState()
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
                onSettingsClick = { viewModel.toggleDeviceMenu(true, RecordingManager.isRecording.value) },
                onRecordClick = { handleRecordClick() },
                showDeviceMenu = showDeviceMenu,
                showIpDialog = showIpDialog,
                showPortDialog = showPortDialog,
                showSdpDialog = showSdpDialog,
                showAboutDialog = showAboutDialog,
                onDismissDeviceMenu = { viewModel.toggleDeviceMenu(false, RecordingManager.isRecording.value) },
                onDismissIpDialog = { viewModel.showIpDialog(false) },
                onConfirmIpDialog = { ip ->
                    viewModel.addDevice(ip, this@MainActivity2)
                    viewModel.showIpDialog(false)
                },
                onDismissPortDialog = { viewModel.showPortDialog(false) },
                onConfirmPortDialog = { port ->
                    if (viewModel.updateRtpPort(port, this@MainActivity2)) {
                        viewModel.showPortDialog(false)
                    }
                },
                onDismissSdpDialog = { viewModel.showSdpDialog(false) },
                onConfirmSdpDialog = {
                    viewModel.showSdpDialog(false)
                    saveSdpLauncher.launch("mobile_$rtpPort.sdp")
                },
                onDismissAboutDialog = { viewModel.showAboutDialog(false) },
                onScanDevice = { /* TODO: implement scan */ },
                onAddDevice = { viewModel.showIpDialog(true) },
                onSetPort = { viewModel.showPortDialog(true) },
                onGenerateSdp = { viewModel.showSdpDialog(true) },
                onDeviceClick = { ip ->
                    viewModel.selectDevice(ip, this@MainActivity2)
                    viewModel.toggleDeviceMenu(false, RecordingManager.isRecording.value)
                },
                onDeleteDevice = { ip ->
                    viewModel.removeDevice(ip, this@MainActivity2)
                },
                onAboutClick = {
                    viewModel.toggleDeviceMenu(false, RecordingManager.isRecording.value)
                    viewModel.showAboutDialog(true)
                }
            )
        }
    }
}

/**
 * MainScreen2 Composable - 现代主界面UI
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
    val context = LocalContext.current
    val resources = context.resources
    val gradientBrush = remember {
        Brush.verticalGradient(
            colors = listOf(GradientStart, GradientEnd),
            startY = 0f,
            endY = Float.POSITIVE_INFINITY
        )
    }
    
    // 浮动状态指示器动画
    val animatedFloat by animateFloatAsState(
        targetValue = if (isRecording) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "recordingIndicator"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = gradientBrush
            )
    ) {
        // 背景装饰元素
        AnimatedBackground()

        // 主要内容区域
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 顶部应用栏
            TopAppBarSection(
                onSettingsClick = onSettingsClick,
                isRecording = isRecording
            )

            // 中央内容区域 - 调整布局使录制按钮更明显
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {

                    // 状态卡片
                    StatusCard(
                        deviceIps = deviceIps,
                        isRecording = isRecording,
                        rtpPort = rtpPort,
                        animatedFloat = animatedFloat
                    )

                    // 底部状态栏
                    BottomStatusBar(
                        deviceIps = deviceIps,
                        rtpPort = rtpPort,
                        isRecording = isRecording
                    )
                    // 录制按钮区域 - 放在状态卡片前面，使其更靠近屏幕中央
                    RecordButtonSection(
                        isRecording = isRecording,
                        onRecordClick = onRecordClick,
                        enabled = deviceIps.isNotEmpty(),
                        deviceCount = deviceIps.size
                    )
                }
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

// ==================== 现代UI组件 ====================

/**
 * 连接状态动画指示器
 */
@Composable
private fun ConnectionStatusIndicator(
    isConnected: Boolean,
    deviceCount: Int
) {
    val infiniteTransition = rememberInfiniteTransition(label = "connection status")
    
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isConnected && deviceCount > 0) 1.1f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse scale"
    )
    
    val color by animateColorAsState(
        targetValue = when {
            !isConnected -> com.dragon.screenrecorder.ui.theme.StatusDisconnected
            deviceCount == 0 -> com.dragon.screenrecorder.ui.theme.StatusWarning
            else -> com.dragon.screenrecorder.ui.theme.StatusConnected
        },
        animationSpec = tween(300),
        label = "status color"
    )
    
    Row(
        modifier = Modifier
            .graphicsLayer {
                scaleX = pulseScale
                scaleY = pulseScale
            },
        verticalAlignment = CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier.size(12.dp)
        ) {
            Canvas(
                modifier = Modifier.fillMaxSize()
            ) {
                drawCircle(
                    color = color,
                    radius = size.minDimension / 2,
                    center = center
                )
            }
        }
        
        Text(
            text = when {
                !isConnected -> "未连接"
                deviceCount == 0 -> "待添加设备"
                else -> "已连接 ${deviceCount} 设备"
            },
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
    }
}

/**
 * 交互反馈波纹效果
 */
@Composable
private fun RippleFeedbackButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    var isPressed by remember { mutableStateOf(false) }
    
    Box(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = { offset ->
                        if (enabled) {
                            isPressed = true
                            tryAwaitRelease()
                            isPressed = false
                        }
                    },
                    onTap = { if (enabled) onClick() }
                )
            }
            .graphicsLayer {
                scaleX = if (isPressed && enabled) 0.95f else 1f
                scaleY = if (isPressed && enabled) 0.95f else 1f
            }
            .animateContentSize()
            .padding(70.dp) // 为录制按钮的波纹动画预留足够空间（波纹最大约148dp，按钮80dp，需要每侧约70dp）
    ) {
        content()
        
        if (isPressed && enabled) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        color = com.dragon.screenrecorder.ui.theme.Primary.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(24.dp)
                    )
            )
        }
    }
}

/**
 * 动画背景装饰
 */
@Composable
private fun AnimatedBackground() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Primary.copy(alpha = 0.1f),
                        Secondary.copy(alpha = 0.05f),
                        Color.Transparent
                    ),
                    center = Offset.Unspecified,
                    radius = 500f
                )
            )
    )
}

/**
 * 顶部应用栏
 */
@Composable
private fun TopAppBarSection(
    onSettingsClick: () -> Unit,
    isRecording: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = CenterVertically
    ) {
        // 应用标题
        Text(
            text = "ScreenRecorder",
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Bold,
                color = White
            ),
            modifier = Modifier.alpha(0.9f)
        )
        
        // 设置按钮
        IconButton(
            onClick = onSettingsClick,
            modifier = Modifier
                .size(48.dp)
                .shadow(
                    elevation = 8.dp,
                    shape = CircleShape,
                    clip = true
                )
                .background(
                    color = SurfaceDark.copy(alpha = SurfaceAlpha),
                    shape = CircleShape
                )
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = "设置",
                tint = White,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * 状态卡片组件
 */
@Composable
private fun StatusCard(
    deviceIps: List<String>,
    isRecording: Boolean,
    rtpPort: Int,
    animatedFloat: Float
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 80.dp, max = 150.dp)
            .shadow(
                elevation = 16.dp,
                shape = RoundedCornerShape(24.dp),
                spotColor = Primary.copy(alpha = 0.3f)
            ),
        colors = CardDefaults.cardColors(
            containerColor = SurfaceDark.copy(alpha = SurfaceAlpha),
            contentColor = White
        ),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 状态指示器行
            Row(
                verticalAlignment = CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 状态指示器图标
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(
                            brush = if (isRecording) {
                                Brush.verticalGradient(
                                    colors = listOf(RecordingPulse, RecordingActive)
                                )
                            } else {
                                Brush.verticalGradient(
                                    colors = listOf(Primary, Secondary)
                                )
                            }
                        )
                        .border(
                            width = 2.dp,
                            color = if (isRecording) RecordingActive else StatusConnected,
                            shape = CircleShape
                        ),
                    contentAlignment = Center
                ) {
                Icon(
                    imageVector = if (isRecording) Icons.Default.Videocam else Icons.Default.Wifi,
                    contentDescription = "状态",
                    tint = White,
                    modifier = Modifier.size(20.dp)
                )
                }
                
                // 状态文本
                Column {
                    Text(
                        text = if (isRecording) "录制进行中" else "准备就绪",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = White
                    )
                    
                    Text(
                        text = if (isRecording) "正在向 ${deviceIps.size} 个设备传输视频" else "点击下方按钮开始录制",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
            
            // 设备列表
            if (deviceIps.isNotEmpty()) {
                Divider(
                    modifier = Modifier.fillMaxWidth(),
                    color = Outline.copy(alpha = 0.5f),
                    thickness = 1.dp
                )
                
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    deviceIps.take(3).forEachIndexed { index, ip ->
                        Row(
                            verticalAlignment = CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Computer,
                                contentDescription = "设备",
                                tint = Secondary,
                                modifier = Modifier.size(16.dp)
                            )
                            
                            Text(
                                text = "$ip:$rtpPort",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    
                    if (deviceIps.size > 3) {
                        Text(
                            text = "+ ${deviceIps.size - 3} 个设备",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextTertiary,
                            modifier = Modifier.padding(start = 24.dp)
                        )
                    }
                }
            } else {
                // 空状态
                Column(
                    horizontalAlignment = CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    Icon(
                        imageVector = Icons.Default.DevicesOther,
                        contentDescription = "无设备",
                        tint = TextTertiary,
                        modifier = Modifier.size(32.dp)
                    )
                    
                    Text(
                        text = "未添加目标设备",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextTertiary
                    )
                }
            }
        }
    }
}

/**
 * 录制按钮区域
 */
@Composable
private fun RecordButtonSection(
    isRecording: Boolean,
    onRecordClick: () -> Unit,
    enabled: Boolean,
    deviceCount: Int
) {
    Column(
        horizontalAlignment = CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.padding(horizontal = 40.dp, vertical = 24.dp)
    ) {
        // 连接状态指示器
        ConnectionStatusIndicator(
            isConnected = enabled,
            deviceCount = deviceCount
        )
        
        // 录制按钮（带交互反馈）
        RippleFeedbackButton(
            onClick = onRecordClick,
            enabled = enabled
        ) {
            ModernRecordButton(
                isRecording = isRecording,
                enabled = enabled
            )
        }
        
        // 按钮提示文本
        AnimatedContent(
            targetState = Pair(enabled, isRecording),
            transitionSpec = {
                if (targetState.first != initialState.first) {
                    // 状态变化时使用淡入淡出
                    fadeIn(animationSpec = tween(200)) togetherWith fadeOut(animationSpec = tween(200))
                } else {
                    // 同一状态内变化时使用滑动
                    slideIntoContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Up,
                        animationSpec = tween(200)
                    ) togetherWith slideOutOfContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Down,
                        animationSpec = tween(200)
                    )
                }
            },
            label = "button hint animation"
        ) { (currentEnabled, currentRecording) ->
            Text(
                text = if (currentEnabled) {
                    if (currentRecording) "点击停止录制" else "点击开始录制"
                } else {
                    "请先添加目标设备"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (currentEnabled) TextSecondary else com.dragon.screenrecorder.ui.theme.Warning,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * 底部状态栏
 */
@Composable
private fun BottomStatusBar(
    deviceIps: List<String>,
    rtpPort: Int,
    isRecording: Boolean
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp)
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(16.dp)
            ),
        color = SurfaceDark.copy(alpha = SurfaceAlpha),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = CenterVertically
        ) {
            // 设备数量
            Row(
                verticalAlignment = CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.DevicesOther,
                    contentDescription = "设备数量",
                    tint = if (deviceIps.isEmpty()) Warning else Secondary,
                    modifier = Modifier.size(16.dp)
                )
                
                Text(
                    text = "${deviceIps.size} 个设备",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (deviceIps.isEmpty()) Warning else TextSecondary
                )
            }
            
            // 端口信息
            Row(
                verticalAlignment = CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SettingsEthernet,
                    contentDescription = "端口",
                    tint = Primary,
                    modifier = Modifier.size(16.dp)
                )
                
                Text(
                    text = "端口: $rtpPort",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
            
            // 录制状态指示器
            Row(
                verticalAlignment = CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AnimatedVisibility(
                    visible = isRecording,
                    enter = scaleIn() + fadeIn(),
                    exit = scaleOut() + fadeOut()
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(
                                color = RecordingActive
                            )
                    )
                }
                
                Text(
                    text = if (isRecording) "录制中" else "空闲",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isRecording) RecordingActive else TextSecondary
                )
            }
        }
    }
}

/**
 * 浮动操作按钮组
 */
@Composable
private fun FloatingActionGroup(
    onAddDevice: () -> Unit,
    onSetPort: () -> Unit,
    onGenerateSdp: () -> Unit,
    onAboutClick: () -> Unit,
    enabled: Boolean
) {
    var expanded by remember { mutableStateOf(false) }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(end = 24.dp, bottom = 24.dp),
        contentAlignment = Alignment.BottomEnd
    ) {
        // 主要FAB
        FloatingActionButton(
            onClick = { expanded = !expanded },
            containerColor = Primary,
            contentColor = White,
            modifier = Modifier.size(56.dp)
        ) {
            Icon(
                imageVector = if (expanded) Icons.Default.Close else Icons.Default.Add,
                contentDescription = if (expanded) "收起菜单" else "展开菜单",
                modifier = Modifier.size(24.dp)
            )
        }
        
        // 展开的菜单项
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
            exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom)
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.End,
                modifier = Modifier.padding(bottom = 72.dp)
            ) {
                // 添加设备按钮
                FloatingActionButton(
                    onClick = {
                        if (enabled) onAddDevice()
                        expanded = false
                    },
                    containerColor = if (enabled) Secondary else SurfaceVariant,
                    contentColor = White,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "添加设备",
                        modifier = Modifier.size(20.dp)
                    )
                }
                
                // 设置端口按钮
                FloatingActionButton(
                    onClick = {
                        if (enabled) onSetPort()
                        expanded = false
                    },
                    containerColor = if (enabled) SurfaceVariant else SurfaceVariant.copy(alpha = 0.5f),
                    contentColor = White,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "设置端口",
                        modifier = Modifier.size(20.dp)
                    )
                }
                
                // 生成SDP按钮
                FloatingActionButton(
                    onClick = {
                        if (enabled) onGenerateSdp()
                        expanded = false
                    },
                    containerColor = if (enabled) SurfaceVariant else SurfaceVariant.copy(alpha = 0.5f),
                    contentColor = White,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SdStorage,
                        contentDescription = "生成SDP",
                        modifier = Modifier.size(20.dp)
                    )
                }
                
                // 关于按钮
                FloatingActionButton(
                    onClick = {
                        onAboutClick()
                        expanded = false
                    },
                    containerColor = SurfaceVariant,
                    contentColor = White,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "关于",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

/**
 * 现代录制按钮
 */
@Composable
private fun ModernRecordButton(
    isRecording: Boolean,
    enabled: Boolean
) {
    // 录制状态脉冲动画
    val infiniteTransition = rememberInfiniteTransition(label = "recording pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isRecording) 1.2f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse scale"
    )

    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.15f,
        targetValue = if (isRecording) 0.6f else 0.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse alpha"
    )

    // 多层波纹动画
    val pulseScale2 by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isRecording) 1.35f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse scale 2"
    )

    val pulseAlpha2 by infiniteTransition.animateFloat(
        initialValue = 0.08f,
        targetValue = if (isRecording) 0.35f else 0.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse alpha 2"
    )

    Box(
        modifier = Modifier
            .size(80.dp)
            .graphicsLayer {
                scaleX = if (isRecording) pulseScale else 1f
                scaleY = if (isRecording) pulseScale else 1f
            }
            .shadow(
                elevation = 16.dp,
                shape = CircleShape,
                ambientColor = Color(0xFFFF3B30).copy(alpha = 0.3f),
                spotColor = Color(0xFFFF3B30).copy(alpha = 0.5f)
            ),
        contentAlignment = Center
    ) {
        // 第二层波纹 - 录制中时显示
        if (isRecording) {
            Box(
                modifier = Modifier
                    .size(110.dp)
                    .graphicsLayer {
                        alpha = pulseAlpha2
                        scaleX = pulseScale2
                        scaleY = pulseScale2
                    }
                    .background(
                        color = Color(0xFFFF3B30).copy(alpha = pulseAlpha2),
                        shape = CircleShape
                    )
            )
        }

        // 第一层波纹 - 录制中时显示
        if (isRecording) {
            Box(
                modifier = Modifier
                    .size(95.dp)
                    .graphicsLayer {
                        alpha = pulseAlpha
                        scaleX = pulseScale
                        scaleY = pulseScale
                    }
                    .background(
                        color = Color(0xFFFF3B30).copy(alpha = pulseAlpha),
                        shape = CircleShape
                    )
            )
        }

        // 外圈 - 红色
        Box(
            modifier = Modifier
                .size(80.dp)
                .background(
                    color = Color(0xFFFF3B30),
                    shape = CircleShape
                ),
            contentAlignment = Center
        ) {
            // 内圈 - 浅红色/粉色
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(
                        color = Color(0xFFFFCDD2),
                        shape = CircleShape
                    ),
                contentAlignment = Center
            ) {
                // 中心图标
                Icon(
                    imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                    contentDescription = if (isRecording) "停止" else "开始",
                    tint = Color(0xFFFF3B30),
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}
