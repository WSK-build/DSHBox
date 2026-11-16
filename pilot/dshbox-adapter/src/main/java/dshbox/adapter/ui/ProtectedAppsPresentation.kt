package dshbox.adapter.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dshbox.adapter.R

/**
 * 「受保护应用」的呈现判据与模型。名单的读写正本在内核
 * （[interlock.relay.core.interlock.ProtectedAppsStore]，经 RelayContainer 转发），
 * 这里只放界面要消费的纯函数：入口状态词、勾选集合的翻转、搜索过滤——
 * 三者都抽出来供 JVM 单测钉住，界面组合期不再各算一遍。
 */

/** 子页应用列表里的一行：显示名（取不到时回退包名）、包名与已解码的图标（无图标时界面画占位框）。 */
data class ProtectedAppUiModel(
    val label: String,
    val packageName: String,
    val icon: ImageBitmap?,
    /** 该包不在桌面入口清单里（手动按包名添加 / 已卸载 / 无启动入口）：界面要标注小字提示。 */
    val unlisted: Boolean = false,
)

/**
 * 入口行右侧的状态词：0 → 「未设置」，>0 → 「已保护 N 个」（N 由界面用
 * [R.string.pilot_protected_status_count] 带占位填充）。抽成纯函数供单测钉住
 * 「0 与非 0 各有各的词」，不与子页内文案混用。
 */
@StringRes
fun protectedEntryStatusRes(count: Int): Int =
    if (count == 0) R.string.pilot_protected_status_unset else R.string.pilot_protected_status_count

/**
 * 勾选/取消一个包之后的下一份名单。正本在内核，写入前界面用它算出要提交的集合；
 * 空集是合法输入（清空名单）。抽成纯函数是让「+1 / -1 都是对称翻转」可被穷举。
 */
fun toggledProtectedPackages(current: Set<String>, packageName: String, protect: Boolean): Set<String> =
    if (protect) current + packageName else current - packageName

/**
 * 搜索过滤：按显示名或包名包含匹配，忽略大小写；空查询原样返回全部
 * （返回同一份列表而不是副本，避免每次按键都白拷一遍几百行）。
 */
fun filterProtectedApps(apps: List<ProtectedAppUiModel>, query: String): List<ProtectedAppUiModel> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return apps
    return apps.filter {
        it.label.contains(trimmed, ignoreCase = true) ||
            it.packageName.contains(trimmed, ignoreCase = true)
    }
}

/**
 * 「按包名添加」的形状校验：镜像内核的 [interlock.relay.core.interlock.ProtectedAppsStore]
 * `PACKAGE_SHAPE`（字母开头，只含字母数字下划线与点，至多 128 字符），先剥首尾空白。
 * 这里不放行内核会整单拒绝的输入，界面就不会把用户引向一次注定失败的保存。
 */
fun isValidPackageName(raw: String): Boolean =
    raw.trim().matches(Regex("^[A-Za-z][A-Za-z0-9_.]{0,127}$"))

/**
 * 把「已保护但不在桌面入口清单里」的包（手动按包名添加、已卸载、无启动入口——
 * 枚举用的 MAIN/LAUNCHER 天然看不到它们）补进列表：排在最前面保证取消得掉，
 * 应用名回退包名，[ProtectedAppUiModel.unlisted] 置真供界面标注小字。
 * 已在已安装列表里的包不重复给一行；补出的行按包名排序，已安装行的原顺序不动。
 */
fun mergeProtectedApps(
    installed: List<ProtectedAppUiModel>,
    protectedPackages: Set<String>,
): List<ProtectedAppUiModel> {
    val listed = installed.map { it.packageName }.toSet()
    val unlistedRows = protectedPackages.filter { it !in listed }
        .map { ProtectedAppUiModel(label = it, packageName = it, icon = null, unlisted = true) }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.packageName })
    return if (unlistedRows.isEmpty()) installed else unlistedRows + installed
}

/** 图标按固定边长解码为位图：自适应图标的内在尺寸可达数百像素，按 48px 统一缩。 */
fun Drawable.toIconBitmap(size: Int = 48): ImageBitmap {
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    setBounds(0, 0, size, size)
    draw(canvas)
    return bitmap.asImageBitmap()
}
