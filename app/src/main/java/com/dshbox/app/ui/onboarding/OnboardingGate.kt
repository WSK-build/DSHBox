package com.dshbox.app.ui.onboarding

/**
 * 新手引导的展示判定，可在 JVM 上验证就绪状态边界。
 *
 * 判定依据是**运行环境是否真正就绪**，而非一次性标志：
 * - [runtimeReady]（**装配级**判定：sandboxManager.isRuntimeInstalled()，含 base/node/
 *   android-side 逐层与 runtime-profile.json 校验——任一层损坏即 false）与
 *   [nodeReady]（node 层文件系统口径）**同时**就绪时不展示——环境可用，无需安装引导；
 *   任一未就绪（含 profile 缺失的半成品）即展示，用户重启后可接着补装。
 *   注意分工：门控的 [runtimeReady] 回答「环境整体是否可用」，而引导内 Debian 层
 *   的呈现用层级判定（只看 base）——两者口径不同是有意的，勿混用。
 * - [bundledRuntimeAvailable] 为真时不展示：APK 内置随包层由启动流程异步解包安装，
 *   首帧探测必然显示未就绪，若据此展示会让新装发布包用户误入引导并触发在线全量重装；
 *   该路径的安装进度由首页既有状态卡承载。
 */
object OnboardingGate {

    fun shouldShow(
        runtimeReady: Boolean,
        nodeReady: Boolean,
        bundledRuntimeAvailable: Boolean,
    ): Boolean = !bundledRuntimeAvailable && !(runtimeReady && nodeReady)
}
