package developer.access

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 面板入口的持有者。
 *
 * 面板由宿主界面（手机助手的「概览」页）导航进入，而控制器的装配需要宿主侧的执行端口
 * （`DeveloperAccessHost` 的实现依赖 app 模块）。为免让界面模块反向依赖宿主，
 * 由宿主在启动时通过 [install] 注册控制器，界面侧只读取。
 *
 * 用 [StateFlow] 而非普通字段：装配发生在服务启动之后，界面可能先被打开。
 * 只有可观察的持有者才能让"尚未初始化"在装配完成的那一刻自动变成真实面板，
 * 否则用户得退出再进一次。
 *
 * 未注册时面板显示"尚未初始化"而不是崩溃——本能力是附加项，缺失不应影响助手主流程。
 */
object DeveloperAccessEntry {

    private val current = MutableStateFlow<DeveloperAccessController?>(null)

    /** 当前控制器；宿主尚未装配时为 null。 */
    val controller: StateFlow<DeveloperAccessController?> = current.asStateFlow()

    /** 由宿主在装配阶段调用；重复调用以最后一次为准。 */
    fun install(controller: DeveloperAccessController) {
        current.value = controller
    }

    /** 当前控制器；宿主尚未装配时为 null。 */
    fun controllerOrNull(): DeveloperAccessController? = current.value
}
