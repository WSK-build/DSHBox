package interlock.relay.core.interlock

import interlock.relay.core.exec.a11y.NodeSelector
import org.json.JSONObject

/**
 * 系统授权界面的归属判定。
 *
 * 这些页面是"系统替某个应用向用户要权限"的地方。AI 能点它们，就等于能替用户答应
 * **任意应用**的授权请求 —— 一次提示词注入就够拿到通讯录、位置、悬浮窗这类权限，
 * 而本模块的两层闸门在这条路上一个都拦不住：它看的是我们自己的能力档位，
 * 不是"这颗按钮是谁家的"。
 *
 * 所以命中判据的目标一律走最严的一条：不认会话授权、强制当场问人、也不给
 * 「本会话内允许」。用户答过一次"读通讯录"不等于答过"以后所有读通讯录都算数"，
 * 更不等于他答应过"替别的 App 批准权限"。
 *
 * 判据只用**包名**与 **view 资源 id**：授权框的按钮文字有六种语言、各家 ROM 还各自改词，
 * 按文字判会把任意应用里的「允许」按钮（cookie 同意、通知授权）都当成系统授权页 ——
 * 一条太吵的判据最后只会被关掉。
 */
object ConsentSurfaces {

    /** `AID_SYSTEM`：`android` 与 system_server 持有的窗口都落在这一格。 */
    private const val UID_SYSTEM = 1000

    /**
     * 包名 → uid 的解析器，由装配根接上 `PackageManager`。
     *
     * 做成注入点有两个理由：这条 object 不带 Context，JVM 侧也要能在不碰框架的情况下
     * 判这条结构规则。没接上时结构判据一律不成立，判定退回下面那两张名单。
     */
    @Volatile
    var uidResolver: (String) -> Int? = { null }

    /**
     * 结构判据：**这块窗口的属主是不是系统 uid**。
     *
     * 它替代的是"按机型补名单"这条路 —— 未知 ROM 上由 `android`/system_server 直接承载的
     * 授权窗（AOSP 的权限对话框就是 `com.android.permissioncontroller` 之外的另一族）
     * 不必先进名单也算命中。
     */
    fun isSystemOwned(packageName: String?): Boolean {
        val pkg = packageName?.trim() ?: return false
        if (pkg.isEmpty()) return false
        return runCatching { uidResolver(pkg) }.getOrNull() == UID_SYSTEM
    }

    /** 直接承载系统授权对话框的组件族：命中即认定是授权界面。 */
    private val CONSENT_PACKAGES = setOf(
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.providers.media.module",
        "com.android.providers.telephony",
        "com.android.emulator.telephony.provider",
        "com.android.externalstorage",
        "com.android.intentresolver",
        "com.android.systemui",
    )

    /**
     * 厂商权限管理器的包名前缀。这些应用同时是"自启动 / 后台运行 / 关联启动"这类
     * 系统弹窗的宿主，也是权限授予对话框在部分 ROM 上的实现方。
     */
    private val CONSENT_PACKAGE_PREFIXES = listOf(
        "com.samsung.android.permissioncontroller",
        "com.huawei.systemmanager",
        "com.hihonor.systemmanager",
        "com.miui.securitycore",
        "com.miui.permcenter",
        "com.coloros.safecenter",
        "com.oplus.safecenter",
        "com.vivo.permissionmanager",
        "com.iqoo.secure",
    )

    fun isConsentPackage(packageName: String?): Boolean {
        val pkg = packageName?.lowercase()?.trim() ?: return false
        if (pkg.isEmpty()) return false
        if (isSystemOwned(pkg) || pkg in CONSENT_PACKAGES) return true
        return CONSENT_PACKAGE_PREFIXES.any { pkg.startsWith(it) }
    }

    /**
     * 执行时用的窄判据：只认两条硬特征 —— 授权框架的 view 资源 id，或直接承载授权框的组件族。
     *
     * 不含 [isSystemOwned] 那一条。系统 uid 的应用远不止授权框：`com.android.settings`
     * 在不少版本上就跑在 `android.uid.system` 里，拿它当"这是授权界面"会把用户自己
     * 要调的音量、亮度滑杆一并拒掉（实测：这台模拟器上设置页属 uid 1000）。
     * 闸门那一侧用宽判据只是多问一次人，代价付得起；执行时这一问是**直接拒**，
     * 判据必须只挑不会认错的那两种。
     */
    fun looksLikeConsentControl(packageName: String?, viewId: String?): Boolean {
        val pkg = packageName?.lowercase()?.trim().orEmpty()
        if (pkg.isNotEmpty() && (pkg in CONSENT_PACKAGES || CONSENT_PACKAGE_PREFIXES.any { pkg.startsWith(it) })) {
            return true
        }
        val id = viewId?.lowercase() ?: return false
        return id.contains("permission_allow") || id.contains("permission_deny") ||
            id.contains("permission_button") || id.contains("button_allow")
    }

    /**
     * 只看调用方递来的选择器条件能否认出"这是要动授权框"。
     *
     * 这一层不碰控件树，所以无障碍没连上时也给得出判断。它漏判是可能的（助手常常只递
     * 一个 `nodeId`），所以还有 [looksLikeConsentNode] 那道按解析结果判的兜底。
     */
    fun looksLikeConsentFromArgs(args: JSONObject): Boolean {
        // 带 `relative` 时参数里那颗节点只是**参照物**，真正要按的那一颗要到执行时才定出来。
        // 归属判据看不见它，就只能按本文件最严的那一条走：每次问人、不给会话授权。
        // 少了这一问，「标题作锚点 + 下方第 N 颗」就能把系统授权框上的「允许」绕成
        // 一次普通点击 —— 那条兜底 viewId 判据防的正是这种 ROM 代管的弹窗。
        if (args.has("relative")) return true
        val selector = args.optJSONObject(NodeSelector.KEY_SELECTOR) ?: args
        val pkg = selector.optString(NodeSelector.KEY_PACKAGE).takeIf { it.isNotEmpty() } ?: return false
        return isConsentPackage(pkg)
    }

    /**
     * 按解析出来的节点判：归属包名是授权组件族，或它的 view 资源 id 属于授权框架。
     *
     * 授权页的按钮 id 在各版本里都是 `com.android.permissioncontroller:id/permission_allow_button`
     * 这一族；带 id 这一问是为了兜住"授权弹窗由别的应用代管、包名认不出"的 ROM。
     */
    fun looksLikeConsentNode(packageName: String?, viewId: String?): Boolean {
        if (isConsentPackage(packageName)) return true
        val id = viewId?.lowercase() ?: return false
        return id.contains("permission_allow") || id.contains("permission_deny") ||
            id.contains("permission_button") || id.contains("button_allow")
    }
}
