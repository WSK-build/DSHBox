package interlock.relay.core.exec.a11y

import android.content.Context
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * 一次节点级调用要读的那棵树：根、它所在那块屏的编号与像素面。
 *
 * 快照与按编号回读**必须共用同一棵树**：`nodeId` 是前序下标，换一块屏就从 0 重数，
 * 两处各取各的会把一次点击打到另一颗节点上 —— 那是"看起来成功"的错误，比报错难发现。
 *
 * 落点屏由执行面决定，不由参数决定：助手递不进 `displayId`（`ArgErrors` 那条拒绝话术说的
 * 就是这件事），用户选的「优先后台」才决定这一趟落在哪块屏上。
 */
internal class DisplayTree(
    val displayId: Int,
    val screen: Rect,
    /** 那块屏的密度；本进程看不见它时为 0，表示报不出来，不拿别的屏的值顶。 */
    val densityDpi: Int,
    private val rootProvider: () -> AccessibilityNodeInfo?,
) {

    /** 每次都重新取根：页面一切换树就换人，缓存下来的旧根是一具空壳。 */
    fun root(): AccessibilityNodeInfo? = rootProvider()

    companion object {

        /**
         * 把"读到空"再问一次的读法包成一个提供者。只给默认屏用：那块屏上
         * `rootInActiveWindow` 会在窗口状态刚换手的那一瞬回空，第二次读通常就有了；
         * 代价是一次 binder 往返，不睡眠、不占通道预算。
         *
         * 虚拟屏不走这里：那边是按屏枚举窗口，第二次读的代价与收益都不成立。
         */
        fun retryOnce(first: () -> AccessibilityNodeInfo?): () -> AccessibilityNodeInfo? = {
            first() ?: first()
        }


        /**
         * 可信虚拟屏上的那棵树。
         *
         * `rootInActiveWindow` 在这里没用：活动窗口只在默认屏上判定得出来，所以这批能力
         * 只能靠按屏取树。跨屏取窗口只有 `getWindowsOnAllDisplays()` 这一条路，而它
         * **按屏号分组**的签名是 Android 13 才有的（30~32 返回不分屏的 List，按 13 的签名
         * 调用会 NoSuchMethodError）；可信虚拟屏本身也要 13 才建得起来。
         *
         * 回三档而不是「有或没有」：**这一问本身没成**与**问成了但那块屏没窗口**是两件
         * 不同的事。混成一谈，capabilities.json 里的 `nodeTree` 就会把一次调用失败说成
         * 平台边界，一条只是暂时不通的路会被就此放弃。
         */
        fun ofDisplay(
            context: Context,
            service: android.accessibilityservice.AccessibilityService,
            displayId: Int,
        ): TreeLook {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                return TreeLook.QueryFailed("below-api-33")
            }
            // 就地 catch 而不是 runCatching 一把捞掉：这一问抛出来的东西就是这条通路的病历，
            // 吞掉它会把"我们没问到"写成"平台没给"。
            val windows: List<AccessibilityWindowInfo>? = try {
                service.getWindowsOnAllDisplays().get(displayId)
            } catch (t: Throwable) {
                return TreeLook.QueryFailed(describe(t))
            }
            val root = windows?.let { pickRoot(it) } ?: return TreeLook.NoWindowOnDisplay
            // 取不到度量时用窗口自己的边界当像素面：节点 bounds 与它同属一套坐标，
            // 拿默认屏的尺寸去裁会把整棵树的节点判成屏外。
            val metrics = displayMetrics(context, displayId)
            return TreeLook.Has(
                DisplayTree(
                    displayId = displayId,
                    screen = metrics?.first ?: Rect().also { root.getBoundsInScreen(it) },
                    densityDpi = metrics?.second ?: 0,
                    // 每次都重新取根：页面一切换树就换人，缓存下来的旧根是一具空壳。
                    rootProvider = { rootNow(service, displayId) },
                ),
            )
        }

        /**
         * 再取一次根。这里把异常读成"此刻没有根"，与 [ofDisplay] 不同：那一次要回答的是
         * "这块屏到底取不取得到"，得把失败如实分开；这一次是在一棵已经认定取得到的树上
         * 找一个编号，找不到就是找不到，调用方回的是 `E_NODE_NOT_FOUND` 而不是平台结论。
         */
        private fun rootNow(
            service: android.accessibilityservice.AccessibilityService,
            displayId: Int,
        ): AccessibilityNodeInfo? {
            // 版本地板就地再问一次：这里是第二次碰到那个 API 的地方（每次回读都走它），
            // 只靠 `ofDisplay` 那头挡住，会让这棵树在低版本上被别处调到时炸。
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
            return runCatching {
                service.getWindowsOnAllDisplays().get(displayId)?.let { pickRoot(it) }
            }.getOrNull()
        }

        /** 异常的种类与那一句话。留类名不留栈：栈按行读的日志里放不下，也不该出现在回包里。 */
        private fun describe(t: Throwable): String =
            "${t.javaClass.simpleName}:${t.message?.take(120)?.replace('\n', ' ') ?: "no-message"}"

        /**
         * 那块屏上此刻该用哪颗窗口：焦点窗口优先，其次应用窗口，最后任意一颗有树的窗口。
         *
         * 优先级不是审美：虚拟屏上常常同时挂着输入法与一个应用窗口，选错那颗就等于
         * 在键盘的树上找"闹钟"两个字，然后如实回一句"没有这颗节点"。
         */
        private fun pickRoot(windows: List<AccessibilityWindowInfo>): AccessibilityNodeInfo? =
            windows.firstOrNull { window -> window.isFocused }?.root
                ?: windows.firstOrNull { window -> window.type == AccessibilityWindowInfo.TYPE_APPLICATION }?.root
                ?: windows.firstNotNullOfOrNull { window -> window.root }

        /** 那块屏的像素面与密度。本进程看不见它（跨 UID 创建且未公开）时回 null。 */
        private fun displayMetrics(context: Context, displayId: Int): Pair<Rect, Int>? {
            val manager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return null
            val display = manager.getDisplay(displayId) ?: return null
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION") // 按屏取真实尺寸没有更新的公开替代
            display.getRealMetrics(metrics)
            if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) return null
            return Rect(0, 0, metrics.widthPixels, metrics.heightPixels) to metrics.densityDpi
        }
    }
}

/** 去那块屏取一次树的三种结局。见 [DisplayTree.ofDisplay] 为什么必须分这三档。 */
internal sealed interface TreeLook {

    data class Has(val tree: DisplayTree) : TreeLook

    /** 这一问成了，而那块屏上此刻一颗有树的窗口都没有。 */
    object NoWindowOnDisplay : TreeLook

    /** 这一问本身没成。[cause] 说的是哪一种，不能拿它去说"平台不给树"。 */
    data class QueryFailed(val cause: String) : TreeLook
}
