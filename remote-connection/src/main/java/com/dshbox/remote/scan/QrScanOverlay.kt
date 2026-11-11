package com.dshbox.remote.scan

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dshbox.app.common.R as CommonR
import com.dshbox.remote.R
import com.dshbox.remote.parse.ManualPayloadSupport
import kotlinx.coroutines.launch

/**
 * 扫码器全屏子页：CameraX 预览 + 帧分析（YUV → zxing）+ 取景框 + 手电筒。
 *
 * 权限流：进入即自动请求；授予进入取景，拒绝一次呈现「再次请求/手动输入」
 * 双出口，永久拒绝呈现「去系统设置/手动输入」双出口。识码成功即停分析
 * （防重复回调）并回调 [onPayload]——建档结果经其回流：成功本页关闭子页，
 * 失败就地显示脱敏文案并恢复扫码（用户可重新取景）。相机与帧分析随组合
 * 解绑（顶栏返回与系统返回共用 [onDismiss]）。无后摄机型或相机绑定失败
 * 呈现「相机不可用」说明页（含手动输入出口），不留黑场死路。
 *
 * 根容器消费指针事件：权限拒答态与取景空白区的点击不得穿透到下层设备卡。
 *
 * @param onPayload 识码文本回调（挂起：建档/重配对走控制器既有链路）。
 * @param onDismiss 关闭子页。
 * @param analysisPaused 暂停帧分析（建档前确认卡展示期间置真）：避免同码
 *   反复命中反复清空卡内错误文案。
 */
@Composable
internal fun QrScanOverlay(
    onPayload: suspend (String) -> Result<Unit>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    analysisPaused: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var permissionPhase by remember { mutableStateOf<QrPermissionPhase?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val shouldShowRationale = (context as? android.app.Activity)
            ?.let {
                androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(
                    it, Manifest.permission.CAMERA,
                )
            }
            ?: false
        permissionPhase = QrScanPermissionLogic.phaseAfterRequest(granted, shouldShowRationale)
        hasCameraPermission = granted
    }

    // 进入即自动请求（未授权时）
    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    var payloadError by remember { mutableStateOf<String?>(null) }
    var processing by remember { mutableStateOf(false) }
    // 失败就地显示后恢复扫码的重扫触发计数（命中停机 → resume）
    var rescanTick by remember { mutableIntStateOf(0) }
    // 永久拒绝页的「手动输入」出口：直接打开手动配对对话框（与面板同款）
    var manualFallbackRequested by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            // 根容器消费指针事件：权限拒答态/取景空白区点击不得穿透到下层设备卡
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        when {
            hasCameraPermission -> CameraContent(
                analysisPaused = analysisPaused,
                onFrame = { raw ->
                    if (processing) return@CameraContent
                    processing = true
                    scope.launch {
                        val result = onPayload(raw.trim())
                        if (result.isSuccess) {
                            onDismiss()
                        } else {
                            payloadError = result.exceptionOrNull()?.message
                            rescanTick += 1 // 恢复扫码：同一帧不再命中，换目标重新取景
                        }
                        processing = false
                    }
                },
                onCameraUnavailable = { payloadError = null },
                onManualInput = { manualFallbackRequested = true },
                rescanTick = rescanTick,
                modifier = Modifier.fillMaxSize(),
            )
            permissionPhase == QrPermissionPhase.RATIONALE -> PermissionNotice(
                text = stringResource(R.string.remote_scan_permission_denied),
                showRetry = true,
                showSettings = false,
                onRequestAgain = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                onManual = { manualFallbackRequested = true },
            )
            permissionPhase == QrPermissionPhase.PERMANENT -> PermissionNotice(
                text = stringResource(R.string.remote_scan_permission_permanent),
                showRetry = false,
                showSettings = true,
                onRequestAgain = {},
                onManual = { manualFallbackRequested = true },
            )
            else -> Box(modifier = Modifier.fillMaxSize()) // 自动请求已发出：黑场等待系统框落定
        }

        // 顶栏：返回 + 标题（自让状态栏：面板页签宿主对首页页签的 insets 为 0，
        // 状态栏图标不隐藏，顶栏必须自己避让否则与系统状态栏重叠）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.5f))
                .statusBarsPadding()
                .align(Alignment.TopCenter),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = ImageVector.vectorResource(CommonR.drawable.ic_arrow_left),
                    contentDescription = stringResource(R.string.remote_cancel),
                    tint = Color.White,
                )
            }
            Text(
                text = stringResource(R.string.remote_scan_title),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
        }

        payloadError?.let { message ->
            Text(
                text = stringResource(R.string.remote_add_failed, message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
            )
        }
    }

    // 永久拒绝页「手动输入」：手动配对对话框（与面板同款；成功即关子页）
    if (manualFallbackRequested) {
        var manualAddress by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { manualFallbackRequested = false },
            title = { Text(stringResource(R.string.remote_manual_title)) },
            text = {
                OutlinedTextField(
                    value = manualAddress,
                    onValueChange = { manualAddress = it },
                    label = { Text(stringResource(R.string.remote_manual_address_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    // 门禁与面板手动输入同口径：任意非空 scheme / JSON / 竖线
                    // 分隔形态的载荷（降级手输不做令牌栏拆解，整段提交）
                    enabled = ManualPayloadSupport.isPlausiblePayload(manualAddress),
                    onClick = {
                        scope.launch {
                            val result = onPayload(manualAddress.trim())
                            if (result.isSuccess) {
                                manualFallbackRequested = false
                                onDismiss()
                            } else {
                                payloadError = result.exceptionOrNull()?.message
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.remote_manual_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { manualFallbackRequested = false }) {
                    Text(stringResource(R.string.remote_cancel))
                }
            },
        )
    }

    // 系统返回与顶栏返回同一出口（相机随组合解绑）
    BackHandler(onBack = onDismiss)
}

/**
 * 相机预览与帧分析绑定：随组合生命周期；解绑时分析器停机、手电筒熄灭、
 * 本次绑定用例收口。无后摄机型（provider 预检失败）或绑定异常时呈现
 * 「相机不可用」说明页。命中文本经 [onFrame] 投递主线程（分析器内部已停
 * 机防重复）。
 *
 * @param onCameraUnavailable 相机不可用上报（显示槽呈现说明页）。
 * @param rescanTick 失败重扫触发计数：键变化即恢复分析器（错误就地显示后
 *   允许重新取景）。
 * @param analysisPaused 置真即停分析（确认卡展示期间）；回落后恢复（期内
 *   有过失败重扫的按重扫口径一并复活）。
 */
@Composable
private fun CameraContent(
    onFrame: (String) -> Unit,
    onCameraUnavailable: () -> Unit,
    onManualInput: () -> Unit,
    rescanTick: Int,
    analysisPaused: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var torchEnabled by remember { mutableStateOf(false) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var boundCamera by remember { mutableStateOf<Camera?>(null) }
    var hasFlashUnit by remember { mutableStateOf(false) }
    var cameraUnavailable by remember { mutableStateOf(false) }
    val analyzer = remember {
        QrScanAnalyzer(onPayload = onFrame)
    }

    LaunchedEffect(rescanTick) {
        if (rescanTick > 0) analyzer.resume()
    }
    // 确认卡展示期间停分析：同码不再命中（卡内错误文案与决策状态不被冲掉）
    LaunchedEffect(analysisPaused) {
        when {
            analysisPaused -> analyzer.stop()
            rescanTick > 0 -> analyzer.resume() // 失败重扫口径恢复
        }
    }
    DisposableEffect(lifecycleOwner) {
        onDispose { analyzer.stop() }
    }

    if (cameraUnavailable) {
        // 无后摄机型 / 绑定失败：说明页（含手动输入出口），不留黑场死路
        Column(
            modifier = modifier
                .background(MaterialTheme.colorScheme.background)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.remote_scan_camera_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(24.dp))
            OutlinedButton(onClick = onManualInput) {
                Text(stringResource(R.string.remote_scan_manual_fallback))
            }
        }
    } else {
        Box(modifier = modifier) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
                },
                update = { view -> previewView = view },
            )
            ViewfinderOverlay(modifier = Modifier.fillMaxSize())
            // 手电筒入口仅闪光灯机型可见（能力来自绑定后相机特征）
            if (hasFlashUnit) {
                IconButton(
                    onClick = {
                        torchEnabled = !torchEnabled
                        boundCamera?.cameraControl?.enableTorch(torchEnabled)
                    },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding(),
                ) {
                    Icon(
                        imageVector = ImageVector.vectorResource(CommonR.drawable.ic_bolt),
                        contentDescription = stringResource(R.string.remote_scan_torch),
                        tint = if (torchEnabled) Color.Yellow else Color.White,
                    )
                }
            }
        }

        // 相机绑定：预览 + 帧分析（KEEP_ONLY_LATEST，解码在专用分析执行器）
        DisposableEffect(previewView, lifecycleOwner) {
            val view = previewView ?: return@DisposableEffect onDispose { }
            val analyzerExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
            // 竞态守卫：provider 回调晚于组合解绑时不得再绑定到已关闭执行器
            val disposed = java.util.concurrent.atomic.AtomicBoolean(false)
            // 本次绑定用例的回执：onDispose 按用例精确解绑（不用全局
            // unbindAll——快速关闭再重开时旧实例的延迟回调不得误拆新用例）
            var boundUseCases: Pair<ProcessCameraProvider, Array<androidx.camera.core.UseCase>>? = null
            val providerFuture = ProcessCameraProvider.getInstance(context)
            providerFuture.addListener({
                if (disposed.get()) {
                    analyzerExecutor.shutdown()
                    return@addListener
                }
                try {
                    val provider = providerFuture.get()
                    // 无后摄机型预检（manifest 允许无摄像头设备安装）：预检
                    // 失败呈现「相机不可用」说明页，不留黑场死路
                    if (!provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                        cameraUnavailable = true
                        analyzerExecutor.shutdown()
                        onCameraUnavailable()
                        return@addListener
                    }
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(view.surfaceProvider)
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                        // 默认 640x480 对密集配对 QR 过小（模块过小无法识别）：
                        // 请求 1280x720，四姿态解码的代价可接受
                        .setResolutionSelector(
                            androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                                .setResolutionStrategy(
                                    androidx.camera.core.resolutionselector.ResolutionStrategy(
                                        android.util.Size(1280, 720),
                                        androidx.camera.core.resolutionselector.ResolutionStrategy
                                            .FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                    )
                                )
                                .build()
                        )
                        .setTargetRotation(view.display.rotation)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also {
                            it.setAnalyzer(analyzerExecutor, analyzer)
                            // 重新绑定（权限流程/配置变更后的解绑-绑定循环）会
                            // 经 onDispose 的 stop 污染分析器：绑定成功即复活
                            analyzer.resume()
                        }
                    val bound = provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                    if (disposed.get()) {
                        // 解绑发生在绑定完成之前：立即回收本次用例
                        runCatching { provider.unbind(preview, analysis) }
                        analyzerExecutor.shutdown()
                        return@addListener
                    }
                    boundUseCases = provider to arrayOf(preview, analysis)
                    boundCamera = bound
                    // 闪光灯能力来自绑定后相机特征（无闪光灯机型隐藏切换入口）
                    hasFlashUnit = bound.cameraInfo.hasFlashUnit()
                } catch (t: Throwable) {
                    // 绑定异常（InitializationException/IllegalStateException 等）：
                    // 呈现不可用页而非黑场死路
                    cameraUnavailable = true
                    onCameraUnavailable()
                    analyzerExecutor.shutdown()
                }
            }, ContextCompat.getMainExecutor(context))
            onDispose {
                disposed.set(true)
                runCatching { boundCamera?.cameraControl?.enableTorch(false) }
                analyzer.stop()
                // 精确解绑本次用例：离开扫码子页即释放相机（不占机到 Activity 停止）
                boundUseCases?.let { (provider, useCases) ->
                    runCatching { provider.unbind(*useCases) }
                }
                boundUseCases = null
                analyzerExecutor.shutdown()
                boundCamera = null
            }
        }
    }
}

/** 取景框：半透明遮罩 + 中央方框 + 扫描线动画。 */
@Composable
private fun ViewfinderOverlay(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "scanLine")
    val scanLineProgress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(1600, easing = LinearEasing),
            RepeatMode.Reverse,
        ),
        label = "scanLineProgress",
    )
    Canvas(modifier = modifier) {
        val boxSize = size.minDimension * 0.68f
        val left = (size.width - boxSize) / 2f
        val top = (size.height - boxSize) / 2f
        val dim = Color.Black.copy(alpha = 0.45f)
        drawRect(dim, size = Size(size.width, top))
        drawRect(dim, topLeft = Offset(0f, top + boxSize), size = Size(size.width, size.height - top - boxSize))
        drawRect(dim, topLeft = Offset(0f, top), size = Size(left, boxSize))
        drawRect(dim, topLeft = Offset(left + boxSize, top), size = Size(size.width - left - boxSize, boxSize))
        drawRect(
            color = Color.White,
            topLeft = Offset(left, top),
            size = Size(boxSize, boxSize),
            style = Stroke(width = 4f),
        )
        val lineY = top + boxSize * scanLineProgress
        drawRect(
            color = Color.White.copy(alpha = 0.9f),
            topLeft = Offset(left, lineY),
            size = Size(boxSize, 3f),
        )
    }
}

/** 权限说明页：拒绝后引导（再次请求/去系统设置出口按阶段开关，手动输入恒有）。 */
@Composable
private fun PermissionNotice(
    text: String,
    showRetry: Boolean,
    showSettings: Boolean,
    onRequestAgain: () -> Unit,
    onManual: () -> Unit,
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = ImageVector.vectorResource(CommonR.drawable.ic_qr_scan),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(24.dp))
        if (showRetry) {
            Button(onClick = onRequestAgain, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.remote_scan_request_again))
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
        if (showSettings) {
            // 永久拒绝的补救出口：跳系统应用设置页开权限
            OutlinedButton(
                onClick = {
                    context.startActivity(
                        Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        ),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.remote_scan_open_settings))
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
        OutlinedButton(onClick = onManual, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.remote_scan_manual_fallback))
        }
    }
}
