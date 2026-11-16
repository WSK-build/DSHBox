package interlock.relay.core.interlock

import interlock.relay.core.exec.a11y.NodeSelector
import interlock.relay.core.protocol.CapabilityId
import interlock.relay.core.spi.RelayPrefs
import org.json.JSONObject

/**
 * 「受保护应用」名单的存储：用户自己勾选的、助手**完全不能碰**的应用包名集合。
 *
 * 名单没有任何默认预设：不预置应用、不自动排除桌面、系统界面、DSHBox 自身或 Shizuku
 * ——哪些应用该保护由用户自己判断，宿主不替用户做决定。名单的优先级高于能力档位：
 * 「完全访问」放宽的是审批，不放宽名单；判定也不读档位，因此完全访问照样被拒。
 *
 * 与档位（[TierStore]）和自定义配置（[CustomPresetStore]）**分开存**：它不进档位预设，
 * 不随「默认配置/全部禁止/全部询问审批/全部完全访问/自定义配置」切换而改变，升级、
 * 重铺、切换预设都不得清空已有名单。同一份宿主私有偏好文件，绝不落共享目录
 * （理由见 TierStore 的类注）。
 *
 * 名单为空时，所有读数都是空集：调用侧的每一条判定都以「空集跳过」开头，
 * 行为与没有这条名单时完全一致。
 */
class ProtectedAppsStore(private val prefs: RelayPrefs) {

    private val file = RelayPrefs.FILE_MAIN

    /** 名单变更监听：勾选即生效的界面与需要即时刷新的读数从这里接通知。可为 null。 */
    @Volatile
    private var listener: (() -> Unit)? = null

    /** 此刻的名单。空集 = 名单未启用。 */
    fun packages(): Set<String> = decode(prefs.getString(file, KEY, null))

    /**
     * 目标包名是否在名单里。**精确相等**：包名是大小写敏感的整体，前缀或后缀相同
     * 都不是同一个包（双开/工作资料同包名会一起被挡，当前不引入 UID/userId 区分）。
     * 空与 null 不算命中——它们不是包名，而是"这次调用没有点名目标"。
     */
    fun blocks(packageName: String?): Boolean =
        !packageName.isNullOrEmpty() && packageName in packages()

    /**
     * 整体写入一份名单，成功即持久化并触发监听。
     *
     * 任一条包名形状非法时**整体拒绝**（返回 false、什么都不写）：半份名单被部分接受，
     * 用户看到的勾选状态与实际生效的名单就会各说各话。空集是合法输入——它就是「清空名单」。
     *
     * 内容与当前名单完全一致时照常返回 true，但**不落盘、不触发监听**：界面上同一批
     * 勾选被重复提交（重渲染、状态同步）不该让执行侧的读数缓存白白刷一轮。
     */
    fun set(packages: Set<String>): Boolean {
        if (packages.any { !PACKAGE_SHAPE.matches(it) }) return false
        val raw = prefs.getString(file, KEY, null)
        if (packages == decode(raw)) {
            // 同集不落盘、不触发监听（生效名单没变）。但原始键里若混着 decode 已丢弃的
            // 脏段（别的写入写入异常损坏的），这次提交顺手清成规范形式：返回值语义不变（true），
            // 名单读数也不变，只是把存储键上那份"看着脏"的原文修掉。
            if (raw != null && raw.split(SEPARATOR).any { it.isEmpty() || !PACKAGE_SHAPE.matches(it) }) {
                prefs.putString(file, KEY, packages.joinToString(SEPARATOR))
            }
            return true
        }
        prefs.putString(file, KEY, packages.joinToString(SEPARATOR))
        listener?.invoke()
        return true
    }

    /** 名单条数，界面上的「已保护 N 个」读这一处。 */
    fun count(): Int = packages().size

    fun setListener(listener: (() -> Unit)?) {
        this.listener = listener
    }

    /**
     * 读侧与写侧同一口径：逐条按 [PACKAGE_SHAPE] 校验，非法项**丢弃**（不整体作废——
     * 偏好文件是同一份宿主存储，别的内容异常写脏这一个键时不该把整份名单清空）。
     * 一条都解不出（null/全非法）就当空集：空集 = 名单未启用，调用侧零改动。
     */
    private fun decode(raw: String?): Set<String> =
        raw?.split(SEPARATOR)?.filter { PACKAGE_SHAPE.matches(it) }?.toSet() ?: emptySet()

    companion object {
        /** internal 只为 JVM 单测能直接钉住存储键与格式（分隔符、逐条形状校验）。 */
        internal const val KEY = "protected_apps_v1"
        private const val SEPARATOR = ";"

        /**
         * 包名形状：字母开头，后接字母/数字/下划线/点，整体至多 128 字符。与执行侧
         * `appops`/`force-stop` 的参数形状判据同一宽松口径——形状校验在这里只挡
         * 明显不是包名的输入，不做存在性判断（名单里可以有尚未安装的包名）。
         */
        internal val PACKAGE_SHAPE = Regex("^[A-Za-z][A-Za-z0-9_.]{0,127}$")
    }
}

/**
 * 入口判据（纯函数，JVM 单测穷举）：这次调用的参数里**明确点名**的目标包名，
 * 没有点名就回 null。
 *
 * 只认明确的包名参数，不把任意字符串都当包名：带 `package` 键的四条（`app.launch`、
 * `app.stop`、`appops.set`、`notify.read`）按顶层包名；节点级十条按 `selector.package`。
 * `notify.read` 的 `package` 是"只看这个包的通知"——点名的就是读取对象本身，受保护包
 * 的通知一条都不能交出去，所以入口先拒；它**不带** package 的"读全部"不算点名（整条
 * 拒绝会把用户允许读的其它应用一并挡掉），改由 Direct 后端按名单过滤（见
 * DirectBackend.readNotifications 与 [filterProtectedEntries]）。`pkg.query` 的键是
 * `keyword`，`notify.post` 的 `package` 是过滤条件而不是操作对象，都不在此判。
 * `pkg.install` 在这里回 null——真实包名要等后端解析 APK 才知道，它的判定在执行场景
 * （解析之后、`pm install` 之前）。`sys.shell` / `sys.shell.full` 完全不解析：完全
 * Shizuku（任意命令）、ADB 与 root 通道不受名单限制，这是产品语义明写的一条边界，
 * 不是遗漏。
 *
 * `ui.snapshot` 不在节点级清单里：它的键表（两侧 ARG_SPECS 均为空集）不接受任何参数，
 * `selector.package` 在它身上永远不可达——留着只会让矩阵读起来像"快照按选择器点名"。
 * 快照的名单判定在执行场景按树根包名做（见 A11yBackend）。
 */
internal fun protectedTarget(capability: CapabilityId, args: JSONObject): String? = when {
    capability == CapabilityId.APP_LAUNCH ||
        capability == CapabilityId.APP_STOP ||
        capability == CapabilityId.APPOPS_SET ||
        capability == CapabilityId.NOTIFY_READ ->
        args.optString("package").takeIf { it.isNotBlank() }

    capability in NODE_TARGET_CAPABILITIES ->
        args.optJSONObject(NodeSelector.KEY_SELECTOR)
            ?.optString(NodeSelector.KEY_PACKAGE)
            ?.takeIf { it.isNotBlank() }

    else -> null
}

/** 参数里带 `selector.package` 的节点级能力：读树与写树都按选择器点名目标。 */
private val NODE_TARGET_CAPABILITIES = setOf(
    CapabilityId.UI_NODE,
    CapabilityId.UI_WAIT_FOR,
    CapabilityId.UI_CLICK,
    CapabilityId.UI_LONG_CLICK,
    CapabilityId.UI_SELECT,
    CapabilityId.UI_DISMISS,
    CapabilityId.UI_SCROLL,
    CapabilityId.UI_SET_VALUE,
    CapabilityId.UI_SET_PROGRESS,
    CapabilityId.UI_IME_ACTION,
)

/**
 * 执行场景的名单判定三档（纯函数，JVM 单测穷举）。所有「按当前前台/顶层包名」判定的
 * 调用点（无障碍活动窗口、Shizuku 各显示顶层、Direct 前台采集、实时流）共用这一份判据，
 * 各调用点只负责把自己的场景取成包名再交给它。
 *
 * - **Pass**：名单为空（一切照旧，零额外系统查询），或读到的包名不在名单里；
 * - **Hit**：读到的包名在名单里——拒绝；
 * - **Unconfirmable**：名单非空而场景读不到包名。按 **fail-closed** 处置：
 * 「读不到」不是「不在名单里」，不能拿一次读数失败当成放行的证据。
 */
internal sealed interface ProtectedVerdict {
    object Pass : ProtectedVerdict
    data class Hit(val packageName: String) : ProtectedVerdict
    object Unconfirmable : ProtectedVerdict
}

internal fun protectedVerdict(packages: Set<String>, observedPackage: String?): ProtectedVerdict = when {
    packages.isEmpty() -> ProtectedVerdict.Pass
    observedPackage.isNullOrEmpty() -> ProtectedVerdict.Unconfirmable
    observedPackage in packages -> ProtectedVerdict.Hit(observedPackage)
    else -> ProtectedVerdict.Pass
}
