package com.dshbox.app

import android.app.Application
import com.dshbox.app.common.Constants
import com.dshbox.app.di.AppContainer
import com.dshbox.app.di.ServiceLocator
import com.dshbox.app.lanaccess.LanAccessController
import com.dshbox.app.sandbox.OfficialPluginOverlay
import com.dshbox.app.sandbox.DshState
import com.dshbox.app.service.DshboxLayoutMigration
import com.dshbox.app.service.SandboxService
import com.dshbox.app.ui.theme.AppDshAddressState
import com.dshbox.app.ui.theme.AppDshBehaviorState
import com.dshbox.app.ui.home.AppUpdateHighlightsState
import com.dshbox.app.ui.theme.AppLocaleState
import com.dshbox.app.ui.theme.AppThemeState
import com.dshbox.app.util.FileOps
import com.dshbox.app.util.MoveEngine
import java.io.File
import com.dshbox.browser.bridge.BrowserCliBroker
import com.dshbox.browser.api.BrowserContract
import android.content.Intent
import com.dshbox.app.sandbox.SandboxState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DshApp : Application() {
    private val browserScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var browserBroker: BrowserCliBroker? = null
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        if (com.dshbox.app.dsh.DshRendererProcessPolicy.isRendererProcess(this, Application.getProcessName())) {
            return
        }
        if (Application.getProcessName() == "$packageName:browser") {
            android.webkit.WebView.setDataDirectorySuffix("browser")
            return
        }
        container = ServiceLocator.createAppContainer(this)
        AppThemeState.load(this)
        // 运行期 DSH 地址状态：把 sandbox-manager 从启动输出解析
        // 出的地址流转播为应用级状态——界面（首页/设置/诊断/WebView/token 拼接）
        // 端口变化即时随动。常量 DSH_BASE_URL 保留为默认兜底（未确证时读取方
        // 拿到它）。
        browserScope.launch {
            container.sandboxManager.dshBaseUrl.collect { url -> AppDshAddressState.update(url) }
        }
        // 局域网访问网关：装配密钥存取、DSH 地址通道与网络观测。
        // 开关默认关闭且不跨进程保持；仅当用户开启且 DSH READY 时才会真正
        // 监听。DshState 的 READY 变化（重启/停机）由下面第二条 collect 触发
        // 重评（refresh 在 READY 回场时自动重绑）。
        LanAccessController.install(
            context = this,
            dshProvider = object : LanAccessController.DshProvider {
                override fun baseUrl(): String? = AppDshAddressState.confirmedBaseUrl
                override fun launchToken(): String? =
                    container.sandboxManager.dshLaunchToken.value
            },
            dshReady = { container.sandboxManager.dshState.value == DshState.READY },
        )
        browserScope.launch {
            container.sandboxManager.dshState.collect {
                LanAccessController.refresh()
            }
        }
        // DSH 页签两项通用偏好（本机状态栏 / 回车键档位）：先于任何读取方
        // 装配（渲染容器、远程门面在组合期读现值）。
        AppDshBehaviorState.load(this)
        // 首页「版本更新要点」卡的展示状态（开关 + 上次展示版本号）：先于
        // 任何读取方装配（首页组合期读现值判定是否展示）。
        AppUpdateHighlightsState.load(this)
        // 资产目录布局的一次性迁移（旧名 → 新名）。**必须早于任何往新路径写文件的代码**：
        // 紧随其后的那次 refresh 就会写新路径，那会让迁移误判"新文件已存在"而跳过，
        // 设备上就永久留下一份旧的 features/ 目录。
        val layout = DshboxLayoutMigration.run(
            File(container.sandboxConfig.userDataDir, Constants.DSHBOX_ASSETS_RELATIVE_DIR),
        )
        if (layout.changed) android.util.Log.i("DshApp", "dshbox layout migrated $layout")

        // 功能开关预置层：用 dsh 官方的 `--patch` 入口打开上游"按 profile 默认关闭"
        // 的官方条目（当前为侧边栏「浏览器」）。各层互不覆盖。与面板一致恒锚主 home：
        // 应急 home 的覆盖层由其自身 provisioning 负责，不在启动期把主偏好写入应急 home。
        OfficialPluginOverlay.refresh(this)
        // 解析应用语言（delegate 优先、prefs 镜像兜底），供设置页与
        // 前台服务通知取用；早于 SandboxService.start 构建首条通知。
        AppLocaleState.refresh(this)
        // 启动时清理上一次移动崩溃/断电遗留的 .dsh-moving-* 中转残留。
        // 后台线程 + 延迟 30s 错峰（审查修正：不全量遍历 filesDir、避免与沙盒启动争 IO，
        // 扫描范围限定移动落点四层，见 MoveEngine.cleanupMovingResidualsInAppDirs）。
        Thread {
            runCatching {
                Thread.sleep(30_000)
                MoveEngine.cleanupMovingResidualsInAppDirs(filesDir)
            }
        }.start()
        // Start the foreground service as early as possible. On first run it
        // will bootstrap both the sandbox and DSH; afterwards the user controls
        // each independently.
        //
        // 这是**尽力而为**：进程也可能是被系统在后台拉起来的（绑定无障碍服务），此时不允许启动
        // 前台服务。`SandboxService.start` 已经把那种情况吞掉并返回 false；这里再兜一层，
        // 是因为 **`onCreate` 里任何异常都会让整个进程启动即崩**，而"沙盒晚几秒起"绝不能是崩溃的理由。
        initializeBrowserTools()
    }

    private fun initializeBrowserTools() {
        val broker = runCatching {
            BrowserCliBroker(this, container.sandboxConfig.browserEntryDir).also { it.entryDir.mkdirs() }
        }.getOrElse {
            android.util.Log.w("BrowserTools", "CLI initialization unavailable")
            runCatching { SandboxService.start(this) }
            return
        }
        browserBroker = broker
        browserScope.launch {
            runCatching { broker.prepareAssets() }
                .onFailure { android.util.Log.w("BrowserTools", "CLI assets unavailable") }
            launch {
                container.sandboxManager.sandboxState.collect { state ->
                    runCatching {
                        when (state) {
                            SandboxState.STARTING, SandboxState.RUNNING, SandboxState.RECOVERING -> broker.start()
                            else -> {
                                broker.stop()
                                sendBroadcast(
                                    Intent(BrowserContract.ACTION_REVOKE_CONTROL).setPackage(packageName),
                                    BrowserContract.internalPermission(packageName),
                                )
                            }
                        }
                    }.onFailure { android.util.Log.w("BrowserTools", "CLI channel unavailable") }
                }
            }
            withContext(Dispatchers.Main) { runCatching { SandboxService.start(this@DshApp) } }
        }
    }
}
