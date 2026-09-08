package com.dshbox.app

import android.app.Application
import com.dshbox.app.di.AppContainer
import com.dshbox.app.di.ServiceLocator
import com.dshbox.app.service.SandboxService
import com.dshbox.app.ui.theme.AppThemeState
import com.dshbox.app.util.FileOps
import com.dshbox.app.util.MoveEngine

class DshApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = ServiceLocator.createAppContainer(this)
        AppThemeState.load(this)
        // 1.2.0 §5.3.6：启动时清理上一次移动崩溃/断电遗留的 .dsh-moving-* 中转残留。
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
        SandboxService.start(this)
    }
}
