package com.dshbox.pluginmanager.core

import android.content.Context
import java.io.File
import com.dshbox.app.sandbox.SandboxManager
import com.dshbox.pluginmanager.layer.PluginLayer
import com.dshbox.pluginmanager.market.MarketRepository
import com.dshbox.pluginmanager.market.data.DefaultMarketRepository
import com.dshbox.pluginmanager.safemode.AbsoluteSafeMode
import com.dshbox.pluginmanager.safemode.AbsoluteTargetsCache
import com.dshbox.pluginmanager.safety.GuardStore
import com.dshbox.pluginmanager.safety.GuardToast
import com.dshbox.pluginmanager.repair.OpenCodeToolController
import com.dshbox.pluginmanager.safety.PluginSafetyMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** 模块的依赖组合结果。 */
data class PluginManagerComponents(
    val paths: PluginPaths,
    val marketOverlayLayer: PluginLayer,
    val safety: PluginSafetyMode,
    val market: MarketRepository,
    /** DSHBox 专属适配插件（移动端适配包 / DSH连接手机）的开关与动作。 */
    val adapter: AdapterPluginController,
    /** DSH 官方插件（上游默认关闭的官方能力）的开关与动作。 */
    val official: OfficialPluginController,
    /** 「插件崩溃修复辅助」页的终端工具动作（安装 / 更新 / 删除）。 */
    val repair: OpenCodeToolController,
)

/**
 * 模块的装配点。
 *
 * 依赖方向刻意是单向的：`sandbox-manager` 不知道本模块的存在，本模块通过
 * 它的公开接口拿沙箱能力。市场数据层用参数注入，方便在它尚未接入时
 * 退回 [EmptyMarketRepository]（界面会如实提示不可用，不会显示假数据）。
 */
object PluginManagerFactory {

    /**
     * 生命周期观察者专用 scope：与进程同寿。
     *
     * 观察者原先挂在界面 composition 的 scope 上（调用方传入），界面一销毁/被后台
     * 回收，观察者与探测协程会被一起取消且不留日志，之后 DSH 怎么失败都无人处理、
     * 也无任何提示。安全网的执行体不能依赖界面是否还在，因此改用本模块自己的 scope。
     */
    private val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 界面动作（装配/移除随包插件等）用的 scope：不随面板关闭而取消。 */
    private val actionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun create(
        context: Context,
        sandbox: SandboxManager,
        scope: CoroutineScope,
        profile: String = PluginPaths.DEFAULT_PROFILE,
        market: MarketRepository? = null,
    ): PluginManagerComponents {
        val paths = PluginPaths(context, profile)
        val marketOverlayLayer = PluginLayer(paths.pluginMarketOverlayFile)
        // 绝对安全模式独占用另一份层文件：它的"关闭"是删文件，因此永远不会碰到
        // 守卫/市场写在 layer 里的停用行（共用一份文件时真机出过事故，见 Constants 注释）。
        val absoluteLayer = PluginLayer(paths.absoluteSafeModeOverlayFile)
        val guardStore = GuardStore(paths.guardStateFile)
        val guest = SandboxGuestCommandRunner(sandbox)
        // 官方包集合**运行时推导**（安装层实际有什么），不写死名单：官方加包即自动生效。
        val hostPackages = HostPackages.installedPackages(paths)
        val absolute = AbsoluteSafeMode(
            guest = guest,
            layer = absoluteLayer,
            profile = profile,
            installedPackages = hostPackages,
            targetsCache = AbsoluteTargetsCache(File(paths.assetsDir, "absolute-targets.json")),
            profileFingerprint = {
                AbsoluteTargetsCache.Fingerprint(
                    manifestModified = paths.profileManifest.lastModified(),
                    nodeModulesModified = paths.nodeModulesDir.lastModified(),
                )
            },
        )
        val safety = PluginSafetyMode(
            paths = paths,
            layer = marketOverlayLayer,
            store = guardStore,
            sandbox = sandbox,
            absolute = absolute,
            installedPackages = hostPackages,
            // 提示走 app 统一的 Toast（系统自带应用图标），不需要任何 app 模块改动。
            // 封存期间不创建提示：见 [PluginSafetySeal]。
            toast = if (PluginSafetySeal.SEALED) null else GuardToast(context),
        )
        // 降级轮次只在**本次进程**内累计：一次打开 app 给足尝试次数，进程重启即重置。
        // 绝不能让用户卡在"已达上限"外面——那正是上一版最严重的错误。
        guardStore.mutate { it.copy(roundsUsed = 0) }
        // 日志快照与轮次清零都靠它，创建即开始观察；重复调用是幂等的。
        // 用模块自有的 observerScope，而不是调用方（界面）的 scope，原因见其声明。
        // 安全模式封存期间不启动观察：见 [PluginSafetySeal]。
        if (!PluginSafetySeal.SEALED) safety.startLifecycleObserver(observerScope)
        return PluginManagerComponents(
            paths = paths,
            marketOverlayLayer = marketOverlayLayer,
            safety = safety,
            adapter = AdapterPluginController(
                context = context,
                sandbox = sandbox,
                profile = profile,
                scope = actionScope,
            ),
            official = OfficialPluginController(
                context = context,
                scope = actionScope,
            ),
            repair = OpenCodeToolController(
                sandbox = sandbox,
                scope = actionScope,
            ),
            market = market ?: DefaultMarketRepository(
                paths = paths,
                layer = marketOverlayLayer,
                guest = guest,
                // 市场启停与安全模式的隔离清单必须同步：用户亲手改过状态的条目，
                // 我们不能再把它列成"被跳过"，否则两处显示打架、对齐逻辑还会
                // 把用户刚启用的插件又翻回停用。
                // 封存期间市场不再向安全模式的记录写任何东西：见 [PluginSafetySeal]。
                isolationLedger = if (PluginSafetySeal.SEALED) null else safety,
            ),
        )
    }
}
