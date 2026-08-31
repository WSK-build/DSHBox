package com.dshbox.app.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 进程级「后台维护操作」计数（1.1.0，M12.1 评审修正 P1③）。
 *
 * 清理功能（SandboxCleanup.clean）绝不能与下列写文件的操作并发——它们写入的目录
 * 恰好是清理目标（cacheDir、bundled-runtime-staging、dsh-staging、base/tmp 等）：
 * - SandboxService.bootstrap：首启安装运行环境（bundled-runtime-staging）+ 内置 DSH
 *   预置（cacheDir/dsh-bundled-*.tar）——包住整个 bootstrap；
 * - FilesScreen 文件导入/压缩包导入/合并（cacheDir 的 import_* 与 extract_*）；
 * - SettingsScreen 离线导入运行环境包与 DSH 层包（cacheDir 的 runtime-bundle-*.zip、
 *   dsh-import-*）；
 * - RuntimeUpdateManager 在线安装（base/tmp 的 npm stage → GUEST_TMP、dsh-staging → CACHE）。
 *
 * 设置页清理入口在 count>0 时禁用并在执行前二次校验；同进程内单例即可。
 */
object BackgroundOps {
    private val count = MutableStateFlow(0)

    /** 当前未结束的受保护后台操作数；>0 表示清理功能应禁用。 */
    val busyCount: StateFlow<Int> = count.asStateFlow()

    val isBusy: Boolean get() = count.value > 0

    /** 计数 +1（原子）；必须与 [end] 成对（置于 try/finally）。 */
    fun begin() {
        count.update { it + 1 }
    }

    /** 计数 -1（原子，下限 0）；与 [begin] 成对使用。 */
    fun end() {
        count.update { (it - 1).coerceAtLeast(0) }
    }

    /** 计数 +1，执行 [block]，无论成败（含取消） finally -1。 */
    suspend fun <T> runTracked(block: suspend () -> T): T {
        begin()
        try {
            return block()
        } finally {
            end()
        }
    }
}
