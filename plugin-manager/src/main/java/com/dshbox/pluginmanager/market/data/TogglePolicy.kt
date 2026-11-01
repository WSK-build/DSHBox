package com.dshbox.pluginmanager.market.data

import com.dshbox.pluginmanager.layer.HostInfrastructure
import com.dshbox.pluginmanager.market.model.ActivationState

/**
 * 市场能不能在这里启停一个包。
 *
 * 判据全部来自数据层事实，UI 只看激活态是推不出来的：
 * 1. 条目 id 必须可解析——写不出行就写不进停用；
 * 2. 不能是宿主基础设施（`cordis:`、官方包、运行层自带），关掉会破坏 DSH 本身；
 * 3. 不能是市场自己；
 * 4. 宿主启动期已经拒绝加载（DENIED）的条目没有开关可用，写了也不会生效；
 * 5. 声明的库（LIBRARY）拨了看不出变化，也不给开关。
 */
internal fun isToggleablePackage(
    name: String,
    rowIds: List<String>,
    hostPackages: Set<String>,
    activation: ActivationState,
    isMarketItself: Boolean,
): Boolean = !isMarketItself &&
    activation != ActivationState.DENIED &&
    // 声明的库让位于 LIBRARY、且通常没有可写的行；给了开关也看不出变化。
    activation != ActivationState.LIBRARY &&
    rowIds.isNotEmpty() &&
    rowIds.none { HostInfrastructure.isProtected(it, name, hostPackages) }

/**
 * 停用是压倒性的用户事实：这些状态下不再叠加宿主的拒绝/跳过观测。
 *
 * 库没有可拒绝的加载行为；停用（含"刚写入、待重启"）本身即为未运行的原因。
 */
internal fun isStopFact(activation: ActivationState): Boolean =
    activation == ActivationState.DISABLED ||
        activation == ActivationState.DISABLED_PENDING ||
        activation == ActivationState.LIBRARY
