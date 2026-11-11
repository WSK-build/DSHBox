package com.dshbox.remote.model

/**
 * 远程会话的连接状态（全图八态）。
 *
 * 状态机全图：Idle → Connecting（代理起监听、门禁下发）→ Loading（渲染器
 * 加载，进度 0-100）→ Ready；失败转 Failed（可重试）；Ready/Loading 断连转
 * Disconnected（可重连）；按保留策略释放转 Released（代理关闭，条目与登录态
 * 存档保留，可重建）；删除设备转 Terminated（jar/凭据/槽位全清，不可逆）。
 */
enum class SessionState {
    /** 未建立。 */
    Idle,

    /** 代理监听建立中（槽位/端口分配、回环绑定、门禁准备）。 */
    Connecting,

    /** 代理就绪，渲染器加载中（顶部进度条呈现 0-100）。 */
    Loading,

    /** 会话就绪：代理在跑且页面已渲染（保留档失活后代理仍存活时维持本态）。 */
    Ready,

    /** 建立失败（不可达/端口占用/池耗尽等），原因见会话失败记录。 */
    Failed,

    /** 断连：曾就绪后连接中断，可重连（重走 Connecting）。 */
    Disconnected,

    /** 已按保留策略释放：代理关闭，条目与登录态存档保留，可重建。 */
    Released,

    /** 已随设备删除终止：登录态存档与槽位已销毁，不可恢复。 */
    Terminated,
}
