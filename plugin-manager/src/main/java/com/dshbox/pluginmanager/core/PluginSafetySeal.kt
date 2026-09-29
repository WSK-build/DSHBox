package com.dshbox.pluginmanager.core

/**
 * 安全模式的封存位（编译期常量）。
 *
 * 置 `true` 时的行为：不启动生命周期观察者、不创建任何提示、面板不显示"安全模式"开关
 * 与"失败隔离清单"入口。相关实现完整保留在源码中，后续需要时改回 `false` 即可恢复。
 *
 * 封存的机制依据：新版 DSH 会把加载失败的条目记为未激活（`N entries did not activate`）
 * 并继续启动，因此"跳过出错条目后重启"不再有触发场景；而"卡死"与"环境/宿主类"故障
 * （残留写者锁、端口占用）分别由绝对安全模式的独占层与启动前的写者锁清理覆盖。
 */
object PluginSafetySeal {

    /** true = 安全模式封存（不参与运行、不在界面出现）。 */
    const val SEALED = true
}
