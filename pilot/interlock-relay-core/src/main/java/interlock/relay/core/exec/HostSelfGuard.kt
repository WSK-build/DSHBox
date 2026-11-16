package interlock.relay.core.exec

/**
 * 「这个包名是不是宿主自己」的判据，供五个执行期落点共用：无障碍窗口归属检查、
 * 写文本的根节点归属、写操作重取树的归属、`force-stop` 的目标、安装替换的目标。
 * 判据散在各落点，就会有一处放宽而其余处不知情。
 *
 * 这不是宿主自保护的全部：参数树里的宿主包名守卫（[interlock.relay.core.runtime.selfTargetArg]）
 * 与"宿主带回前台"形状判定在调用主链路按**参数**另设，前台启动对宿主目标的特殊处理
 * （无障碍读不到聚焦窗口的那一支）也不是拒绝——它们各有各的判据，不在本函数收口范围。
 * 本判据不随用户档位变化：完全访问放宽的是助手审批，不是宿主自保护。
 *
 * 精确相等：包名是大小写敏感的整体，前缀或后缀相同都不是同一个包
 * （`com.example.app` 与 `com.example.app.extra` 是两个应用）。
 * 依赖 Android 框架类型（控件树、窗口列表、包管理器）的调用场景无法在 JVM 单测里
 * 原样驱动，所以判据本体抽在这里，由单测穷举包名形状；各调用点只负责把自己的
 * 场景取成包名再交给它。
 */
internal fun namesHostPackage(packageName: String?, selfPackage: String): Boolean =
    packageName == selfPackage
