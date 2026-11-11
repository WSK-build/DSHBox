package com.dshbox.remote.session

import com.dshbox.remote.model.Device
import com.dshbox.remote.model.DeviceId
import com.dshbox.remote.model.Endpoint
import com.dshbox.remote.model.EndpointId

/**
 * 一个已建立的端点代理会话：回环代理的运行句柄。
 *
 * [start] 阻塞直至代理就绪；失败抛 [SessionStartException]（端口占用重试后
 * 仍失败、槽位池满等），会话层据此把状态转 Failed，禁止静默降级。
 */
interface ProxySession {

    /** 代理就绪后的回环 origin（渲染器加载地址）。 */
    val loopbackOrigin: String

    /**
     * 本会话的门禁令牌：显示槽经渲染器 CookieManager 按回环 host 下发，且
     * 下发完成回调之后才放行渲染器加载。null 表示该会话无门禁（渲染引擎
     * 的 cookie 注入路径不可用，代理侧已启用 bypass），显示槽据此跳过下发
     * 直接加载。
     */
    val gateToken: String? get() = null

    /**
     * 会话首次加载的完整地址；null 表示直接加载回环 origin 根。配对 secret
     * 只存在于页面地址 fragment 的插件（回环网关类）由此携带：fragment 只
     * 存在于渲染器侧地址（页面 JS 读取后消费），不进上游请求、不落明文。
     */
    val initialLoadUrl: String? get() = null

    /**
     * 代理实际转发的上游真实 origin（scheme+host[:port]，与出站改写基准一
     * 致）；null 表示该实现不暴露上游信息。页内链接的就地打开判定据此识别
     * 「同一上游站点」的绝对地址，与回环代理转发目标共用同一来源。
     */
    val upstreamOrigin: String? get() = null

    /** 启动代理监听：分配槽位/端口、绑定回环地址。 */
    fun start()

    /** 停止代理并断开在途连接；条目与登录态存档保留。 */
    fun stop()
}

/** 会话建立失败：承载池满、端口占用等显式原因。 */
class SessionStartException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 会话工厂：控制器经它为已建档端点创建代理会话。
 *
 * 生产实现 [DefaultSessionFactory] 由宿主上下文构建；JVM 单测注入假实现
 * 驱动会话状态机。
 */
interface SessionFactory {

    /** 当前已建档设备清单。 */
    fun devices(): List<Device>

    /** 为端点创建会话（可能抛 [SessionStartException]）。 */
    fun createSession(deviceId: DeviceId, endpoint: Endpoint): ProxySession

    /** 删除设备：销毁其全部会话、登录态存档与槽位绑定。 */
    fun removeDevice(deviceId: DeviceId)

    /**
     * 一次性凭据消费回调：会话代理观测到兑换完成时触发（显示槽据此刷新
     * 未消费凭据集合）。默认空实现——宿主工厂覆写，测试假实现无需关心。
     */
    var credentialConsumedListener: ((EndpointId) -> Unit)?
        get() = null
        set(_) {}

    /**
     * 提交一次性凭据消费（渲染器宿主的首个干净首帧触发）：凭据存档置位 +
     * 未消费集合摘除（经 [credentialConsumedListener]）。[generation] 为
     * 提交方的端点代次快照——档案代次已漂移（期间重新配对）时静默忽略，
     * 旧会话的迟到提交不得误标新凭据。默认空实现——宿主工厂覆写，测试
     * 假实现按需覆写。
     */
    fun markCredentialConsumed(endpointId: EndpointId, generation: Long) {}

    /**
     * 工厂生命周期收尾（幂等）：释放工厂持有的系统资源。默认空实现
     * ——生产实现 [DefaultSessionFactory] 当前无需收尾（无工厂级系统资源），
     * 实现持有资源时覆写。close 之后工厂不应再被用于建立会话。
     */
    fun close() {}
}
