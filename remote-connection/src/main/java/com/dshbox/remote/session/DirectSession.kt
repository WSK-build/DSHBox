package com.dshbox.remote.session

import com.dshbox.remote.model.SessionMode

/**
 * 直连会话（端点 [SessionMode.DIRECT]）：不建回环代理，渲染器直接加载真实
 * 上游 origin。
 *
 * 接口语义复用：[loopbackOrigin] 承载「渲染器的可信加载基准 origin」——直连
 * 下即真实上游 origin（控制器把 [start] 后落账的 origin 流与显示槽的可渲染
 * 判定照常驱动）；[initialLoadUrl] 为真实入口地址（档案目标地址）；门禁令牌
 * 恒 null（直连无门禁，渲染器宿主按既有 bypass 路径直接放行）。[start]/
 * [stop] 为空操作：无代理监听可启停，释放路径照常调用不报错。
 *
 * 已知限制：直连会话不经代理，下载落盘与认证注入等代理侧能力不适用；页面
 * 登录态由渲染引擎自持（直连场景为账号型入口，无一次性凭据可注入）；上游
 * origin 为 https 由会话工厂先行拒绝明文端点（渲染器 AnyHttps 口径只放宽
 * https 信任基准）。
 */
class DirectSession(
    /** 会话入口地址（档案目标地址，含路径）。 */
    private val entryUrl: String,
    /** 真实上游 origin（scheme+host[:port]），同时充当渲染器可信基准。 */
    override val upstreamOrigin: String?,
) : ProxySession {

    init {
        // origin 缺失/空白不得进入会话账目：[loopbackOrigin] 以本身即「渲染器
        // 的可信加载基准 origin」，空串会让会话以空白可渲染值落账（工厂构造
        // 期已先行 https 校验，本 require 是模型层的第二道防线）。
        require(!upstreamOrigin.isNullOrBlank()) {
            "Direct session requires a non-blank upstream origin"
        }
    }

    override val loopbackOrigin: String
        get() = upstreamOrigin.orEmpty()

    override val initialLoadUrl: String?
        get() = entryUrl

    override fun start() = Unit

    override fun stop() = Unit
}
