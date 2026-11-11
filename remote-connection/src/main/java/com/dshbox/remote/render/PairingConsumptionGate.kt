package com.dshbox.remote.render

/**
 * 一次性配对凭据的消费门控：消费提交挂在渲染器宿主的「首个干净首帧」——
 * 配对页完成兑换并成功渲染才算凭据已用。
 *
 * 不采用代理侧 Set-Cookie 信号：兑换前到达的基础设施 cookie（如 CDN 的
 * HttpOnly 防机器人 cookie）会造成误消费，且摘除未消费集合后失败引导不可
 * 达。失败页（主框架失败后的引擎错误页）不发成功完结信号——system 引擎
 * 对失败也发成功完结，故以页内失败状态联合判定；自愈重试后的干净首帧仍
 * 可提交（此时票已兑换成功）。
 *
 * 实例随渲染器宿主持有，调用都在主线程；触发恰好一次。
 */
internal class PairingConsumptionGate(private val usesConsumableCredential: Boolean) {

    private var fired = false

    /**
     * 页面完结观测：返回 true 表示应提交消费（首个干净首帧，仅一次）。
     *
     * @param loadSucceeded 本次完结是否成功（失败终态为 false）。
     * @param pageNavigationFailed 本页导航是否出现过主框架失败。
     */
    fun onPageFinished(loadSucceeded: Boolean, pageNavigationFailed: Boolean): Boolean {
        if (!usesConsumableCredential || fired || !loadSucceeded || pageNavigationFailed) return false
        fired = true
        return true
    }
}
