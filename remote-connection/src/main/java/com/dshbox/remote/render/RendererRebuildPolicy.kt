package com.dshbox.remote.render

import androidx.annotation.MainThread

/**
 * 渲染引擎崩溃的重建记账：崩溃时就地重建（代理与登录态不动），页面成功
 * 渲染后清零（「连续崩溃」语义），连续超限转失败态由用户显式重试。
 *
 * 独立成类以便 JVM 单测锁定计数语义；实例由渲染器宿主持有，所有调用都在
 * 主线程（渲染器事件与页面回调的主线程契约），计数不做同步。
 */
internal class RendererRebuildPolicy(private val limit: Int = DEFAULT_LIMIT) {

    private var count = 0

    /**
     * 崩溃观测：返回 true 表示允许就地重建，false 表示连续超限（调用方转
     * 失败态，不再自动重建）。
     */
    @MainThread
    fun onCrash(): Boolean {
        count += 1
        return count <= limit
    }

    /** 页面成功渲染（含主框架加载失败触发错误页的宽松场景）：计数清零。 */
    @MainThread
    fun onPageLoaded() {
        count = 0
    }

    private companion object {
        const val DEFAULT_LIMIT = 3
    }
}
