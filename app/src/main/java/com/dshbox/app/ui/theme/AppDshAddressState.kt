package com.dshbox.app.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dshbox.app.common.Constants

/**
 * 本机 DSH 的运行期地址状态。
 *
 * `Constants.DSH_BASE_URL` 是编译期常量（默认 3080），端口自适应后实际端口
 * 来自启动输出、每次重启都可能变化——所有显示/加载/拼接处读这里的**运行期**
 * 值，常量只作兜底默认。状态以 Compose 状态承载：端口变化（DSH 重启落到
 * 另一端口）即时反映到首页地址展示、设置页、诊断页与 WebView 加载地址。
 *
 * 装配：`DshApp.onCreate` 里由 `DshAddressBinder` 把 `SandboxManager.dshBaseUrl`
 * 的 StateFlow 值回写进来（StateFlow 已是 observable 单源，此处再导一层
 * static 状态只是让 Compose 读取点不必各持 collector）。null = 尚未从输出
 * 确证（未启动 / 旧版输出不可解析），读 [baseUrl] 即回退常量。
 */
object AppDshAddressState {

    /** 从启动输出确证的本机 DSH 地址；null = 无确证值（读方走常量兜底）。
     *  以 Compose 状态承载：确证值变化（端口自适应换端口、重启落新端口）即触发
     *  重组，界面与 WebView 容器按现值跟随，无需各读取方自带 collector。 */
    var confirmedBaseUrl: String? by mutableStateOf(null)
        private set

    /** 当前生效的本机 DSH 地址（确认值优先，否则默认常量）。 */
    val baseUrl: String get() = confirmedBaseUrl ?: Constants.DSH_BASE_URL

    /** 由启动输出确证的地址（sandbox-manager 解析 `dsh web:` 行后推送）。 */
    fun update(baseUrl: String?) {
        confirmedBaseUrl = baseUrl
    }

    /**
     * 地址派生（纯函数，供 JVM 单测锁定）：null/异常形态一律回退默认常量，
     * 合法值原样透传。
     */
    internal fun derive(confirmed: String?): String =
        confirmed?.takeIf { it.startsWith("http://") && it.isNotBlank() && it.length > "http://".length }
            ?: Constants.DSH_BASE_URL

    /** 读侧实际取值 = [derive] 的口径；update 后读 [baseUrl] 即运行期地址。 */
    fun reset() {
        confirmedBaseUrl = null
    }
}
