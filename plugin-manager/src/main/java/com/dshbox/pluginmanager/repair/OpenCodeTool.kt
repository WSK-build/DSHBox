package com.dshbox.pluginmanager.repair

import com.dshbox.app.common.Constants
import com.dshbox.app.common.DshSources

/**
 * 「插件崩溃修复辅助」所用的 opencode 终端 Agent：**安装位置、三条命令与安装态探测的唯一构造处**。
 *
 * 用途：DSH 插件加载失败会让 DSH 起不来，用户在终端里用一个开源终端 Agent 自助排查与修复。
 * 工具本体**不随 APK 打包** —— 随包只带下面这几条 npm 命令，运行期按需下载。
 *
 * 安装位置固定在「工具链缓存」挂载点内（[Constants.OPENCODE_GUEST_DIR]）：既不在工作区里，
 * 也不在运行环境层里，因此换层不会丢、文件页也看不到；配置与用户数据由命令包装脚本一并引到
 * 同一目录，所以"删除"就是删这一个目录。
 *
 * 本文件只做**纯字符串构造**（不触盘、不起进程），命令内容因此可在 JVM 单测里直接断言；
 * 执行与状态由 [OpenCodeToolController] 负责。
 */
internal object OpenCodeTool {

    /** npm 包名。真正的平台二进制由它作为可选依赖按平台拉取。 */
    const val PACKAGE = "opencode-ai"

    /** 已安装标记（探测输出的首行）。 */
    const val MARKER_INSTALLED = "__DSHBOX_OPENCODE_INSTALLED__"

    /** 未安装标记。 */
    const val MARKER_MISSING = "__DSHBOX_OPENCODE_MISSING__"

    /** 安装目录下的启动器；`npm install --prefix <目录>` 会生成它。 */
    val launcher: String
        get() = Constants.OPENCODE_LAUNCHER

    /** 包清单路径，用于读出已装版本号。 */
    private val manifest: String
        get() = "${Constants.OPENCODE_GUEST_DIR}/node_modules/$PACKAGE/package.json"

    /**
     * registry 的尝试顺序：镜像优先、官方兜底。**前台不展示这一步**。
     *
     * 地址取自公共的 npm 源表，因此不在这里重写地址；这里只按特征片段定位条目。
     */
    val registries: List<String> = listOfNotNull(
        DshSources.ALL.firstOrNull { MIRROR_KEY in it.url }?.url,
        DshSources.ALL.firstOrNull { OFFICIAL_KEY in it.url }?.url,
    )

    private const val MIRROR_KEY = "npmmirror"
    private const val OFFICIAL_KEY = "registry.npmjs.org"

    /**
     * 安装与更新**共用**的脚本：先铺暂存目录，装好后在同卷内挪到正式位置。
     *
     * 两个要点：
     *  - `set -e` 让 npm 失败时**立即退出**，因此搬运那一步不会执行 ——
     *    已装好的旧版本不会被一次失败的更新破坏；
     *  - 暂存与正式位置在同一挂载点内，`mv` 是同卷改名，不会出现"装到一半可用"的中间态。
     */
    fun installScript(registry: String): String = buildString {
        append("set -e; ")
        append("rm -rf ${Constants.OPENCODE_STAGING_GUEST_DIR}; ")
        append("mkdir -p ${Constants.OPENCODE_STAGING_GUEST_DIR}; ")
        append("npm install --prefix ${Constants.OPENCODE_STAGING_GUEST_DIR} ")
        append("--registry $registry --no-audit --no-fund --loglevel http $PACKAGE@latest; ")
        append("rm -rf ${Constants.OPENCODE_GUEST_DIR}; ")
        append("mv ${Constants.OPENCODE_STAGING_GUEST_DIR} ${Constants.OPENCODE_GUEST_DIR}")
    }

    /** 删除脚本：装体、配置与用户数据都在同一目录下，删它即全清。 */
    fun removeScript(): String = "rm -rf ${Constants.OPENCODE_GUEST_DIR}"

    /**
     * 安装态探测脚本。
     *
     * 判据是**安装目录里的启动器**，而不是 `command -v opencode` —— 命令包装脚本常驻于
     * CLI 垫片目录，拿它当判据会永远"已安装"。
     */
    fun probeScript(): String = buildString {
        append("if [ -x $launcher ]; then echo $MARKER_INSTALLED; ")
        append("node -p \"require('$manifest').version\" 2>/dev/null || true; ")
        append("else echo $MARKER_MISSING; fi")
    }

    /** 解析探测输出。版本号取标记行之后第一个形如 `x.y.z` 的行；拿不到就只报"已安装"。 */
    fun parseProbe(output: List<String>): InstalledState {
        val start = output.indexOfFirst { MARKER_INSTALLED in it }
        if (start < 0) return InstalledState(installed = false, version = null)
        val version = output.drop(start + 1).firstOrNull { VERSION_RE.matches(it.trim()) }?.trim()
        return InstalledState(installed = true, version = version)
    }

    private val VERSION_RE = Regex("""^\d+\.\d+\.\d+([-.+][0-9A-Za-z.-]+)?$""")

    /** 安装态。 */
    data class InstalledState(val installed: Boolean, val version: String?)
}
