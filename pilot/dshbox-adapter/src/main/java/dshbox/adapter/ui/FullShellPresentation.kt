package dshbox.adapter.ui

/**
 * 「完全 Shizuku 能力」栏目的纯呈现判据。设备的执行判定全在内核（档位在闸门、
 * 就绪三环在装配根），这里只把装配根已经给定的快照映射成界面该显示哪一档——
 * 抽成纯函数是为了能在 JVM 单测里把 uid 与缺环的各个场景穷举掉。
 */

/** 特权通路的执行身份档。uid 由 Shizuku 快照给出，读不到（未连接/未就绪）为 -1。 */
enum class FullShellIdentity {
    /** Shizuku 经 ADB / 无线调试启动的常规身份。 */
    SHELL,

    /** Shizuku 本身以 root 启动时的身份；这不是本应用能自动拿到的内容。 */
    ROOT,

    /** 其余可读到的 uid：按数报，不猜角色。 */
    OTHER_UID,

    /** 此刻读不到执行身份：如实说未知，不冒充任何一个具体身份。 */
    UNKNOWN,
}

fun fullShellIdentity(uid: Int): FullShellIdentity = when {
    uid == 2000 -> FullShellIdentity.SHELL
    uid == 0 -> FullShellIdentity.ROOT
    uid > 0 -> FullShellIdentity.OTHER_UID
    else -> FullShellIdentity.UNKNOWN
}

/** 特权开关不可拨时缺的那一环；NONE 表示三环齐备，开关可拨。 */
enum class FullShellGap { NONE, NOT_RUNNING, NOT_GRANTED, NOT_BOUND }

/**
 * 缺环按「缺哪一环」点名，次序与装配根的就绪判据一致：先运行、再授权、最后用户服务绑定。
 * 一环没到就停在那一环——把人送去授权一个还没启动的服务，等于让他去找一个不存在的开关。
 */
fun fullShellGap(running: Boolean, granted: Boolean, bound: Boolean): FullShellGap = when {
    running && granted && bound -> FullShellGap.NONE
    !running -> FullShellGap.NOT_RUNNING
    !granted -> FullShellGap.NOT_GRANTED
    else -> FullShellGap.NOT_BOUND
}
