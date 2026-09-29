package com.dshbox.app

import android.app.Application
import com.dshbox.app.common.Constants
import com.dshbox.app.di.AppContainer
import com.dshbox.app.di.ServiceLocator
import com.dshbox.app.sandbox.OfficialPluginOverlay
import com.dshbox.app.service.DshboxLayoutMigration
import com.dshbox.app.service.SandboxService
import com.dshbox.app.ui.theme.AppLocaleState
import com.dshbox.app.ui.theme.AppThemeState
import com.dshbox.app.util.FileOps
import com.dshbox.app.util.MoveEngine
import java.io.File

class DshApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = ServiceLocator.createAppContainer(this)
        AppThemeState.load(this)
        // 资产目录布局的一次性迁移（旧名 → 新名）。**必须早于任何往新路径写文件的代码**：
        // 紧随其后的那次 refresh 就会写新路径，那会让迁移误判"新文件已存在"而跳过，
        // 设备上就永久留下一份旧的 features/ 目录。
        val layout = DshboxLayoutMigration.run(
            File(container.sandboxConfig.userDataDir, Constants.DSHBOX_ASSETS_RELATIVE_DIR),
        )
        if (layout.changed) android.util.Log.i("DshApp", "dshbox layout migrated $layout")

        // 功能开关预置层：用 dsh 官方的 `--patch` 入口打开上游"按 profile 默认关闭"
        // 的官方条目（当前为侧边栏「浏览器」）。各层互不覆盖。
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
        runCatching { SandboxService.start(this) }
    }
}
