package com.dshbox.app.service

/**
 * 移动端适配插件「启动刷新探测」的脚本构造与结果解析（1.3.1 复查 M30）。
 *
 * ## 为什么单独抽出来
 *
 * 原先这段逻辑内联在 [SandboxService.refreshAssembledMobileAdaptPlugin] 的私有方法里，
 * 每次启动都要付一次 guest 进程 spawn；**两道判断合并为一条命令后**，
 * 靠脚本回传的标记区分三种状态。合并带来一个易错点：
 * 脚本是一段**手工拼接的字符串**，条件之间少一个 `&&` 就会静默改变语义
 * （例如把 `test -f X` 与 `[ ... ]` 粘成一条命令，让存在性检查形同虚设）。
 * 这类错误编译器看不见、运行时也不报错，只会让判定结果悄悄反转。
 *
 * 因此把拼接与解析提为纯函数，用单测锁住：
 *  - 脚本结构（三个标记齐全、条件之间的连接符正确）
 *  - 标记解析（含无关行不误判）
 *
 * 纯字符串进 / 字符串出，无 Android 依赖，可直接在 JVM 单测里跑。
 */
internal object MobileAdaptProbe {

    /**
     * 参与「内容是否一致」比对的关键文件（相对插件根、profile 与 staged 两侧同名）。
     *
     * 取三个而非只取 `lib/client.js`：将来若只改 `package.json`（版本号/描述）
     * 也能识别为有变化。新增项时两侧必须同步，故集中定义于此。
     */
    val FINGERPRINT_FILES = listOf("lib/client.js", "package.json", "cordis.patch.yml")

    /** 未装配 / staged 缺失 → 跳过且**不安装**（不擅自往用户 profile 塞插件）。 */
    const val MARKER_NOT_READY = "__DSHBOX_ADAPT_NOT_READY__"

    /** 内容一致且 bundle 已注册 → 跳过安装（省 I/O，且不覆盖用户手改）。 */
    const val MARKER_UP_TO_DATE = "__DSHBOX_ADAPT_UP_TO_DATE__"

    /** 其余情况 → 继续执行 install.sh。 */
    const val MARKER_NEEDS_INSTALL = "__DSHBOX_ADAPT_NEEDS_INSTALL__"

    /** bundle 注册名（install.sh 会把它写进 profile 的 package.json）。 */
    private const val BUNDLE_ID = "@local/dsh-mobile-adapt"

    /**
     * 从一行 guest 输出里解析状态标记；无关行返回 null。
     *
     * 用 `contains` 而非全等：guest 输出可能带前后空白或 \r（PRoot 管道下实测见过），
     * 全等匹配会漏判 —— 而漏判的后果是"没有标记"→ 保守跳过刷新，插件永远不更新。
     */
    fun markerFrom(line: String): String? = when {
        line.contains(MARKER_NOT_READY) -> MARKER_NOT_READY
        line.contains(MARKER_UP_TO_DATE) -> MARKER_UP_TO_DATE
        line.contains(MARKER_NEEDS_INSTALL) -> MARKER_NEEDS_INSTALL
        else -> null
    }

    /**
     * 拼接单次探测脚本（一条命令同时完成「是否已装配」与「是否已是最新」两项判断）。
     *
     * @param pluginDir profile 内的插件目录（`.../node_modules/@local/dsh-mobile-adapt`）
     * @param stageInstall staged 的 `install.sh` 绝对路径
     * @param stagePlugin staged 的插件目录（`.../mobile-adapt/plugin`）
     * @param profilePackageJson profile 的 `package.json` 绝对路径
     *
     * ## 为什么不能用 `A && B` 直接串联两道判断
     *
     * 两条判断的**失败去向相反**：
     *  - 判断 1 不成立（未装配）→ 必须跳过且**不安装**；
     *  - 判断 2 不成立（非最新）→ 必须**继续安装**。
     *
     * 而 `runGuestCommand` 只把退出码折叠成 Success/Failure，拿不到具体码值，
     * 无法区分"两条都成功""第一条失败""第二条失败"。故改用**输出标记**表达三态。
     *
     * ## 脚本自身不留会失败的收尾语句
     *
     * 三个分支都以 `echo` 成功结束，因此**任何未预期的失败都表现为"没有标记"**，
     * 由调用方保守处理（跳过刷新，下次启动再试）。
     */
    fun buildScript(
        pluginDir: String,
        stageInstall: String,
        stagePlugin: String,
        profilePackageJson: String,
    ): String {
        // 存在性检查：两侧每个指纹文件都必须**显式存在**（test -f）。
        //
        // ⚠️ 这一条不可省（1.3.1 M27 实测踩到）：
        // `cat 缺失文件` 会输出空内容，空内容的 sha256 恒为 e3b0c442…；
        // 若两侧同时缺失，两个空哈希相等 → 哈希比对误判为 true，
        // 若 bundle 恰好仍注册，整体会误判「已是最新」→ **跳过安装，但插件一个文件都没有**。
        // 这不是自愈的：用户永远跑不上插件。
        val existsChecks = (FINGERPRINT_FILES.map { "test -f $pluginDir/$it" } +
            FINGERPRINT_FILES.map { "test -f $stagePlugin/$it" })
            .joinToString(" && ")

        val digestOf = { root: String -> FINGERPRINT_FILES.joinToString(" ") { "$root/$it" } }

        // 三个条件同时成立才算「已是最新」：
        //   ① 两侧关键文件都显式存在   ② 内容连接后的 sha256 相等   ③ bundle 仍注册
        // 条件之间必须用 " && " 连接 —— 这里漏连接符不会报错，只会静默改变语义，
        // 故由 MobileAdaptProbeTest 断言该连接符存在。
        val upToDateCondition =
            "$existsChecks && " +
                "[ \"\$(cat ${digestOf(pluginDir)} | sha256sum)\" = " +
                "\"\$(cat ${digestOf(stagePlugin)} | sha256sum)\" ] && " +
                "grep -q '$BUNDLE_ID' $profilePackageJson"

        return listOf(
            "if test -d $pluginDir && test -f $stageInstall; then",
            "  if $upToDateCondition; then",
            "    echo $MARKER_UP_TO_DATE",
            "  else",
            "    echo $MARKER_NEEDS_INSTALL",
            "  fi",
            "else",
            "  echo $MARKER_NOT_READY",
            "fi",
        ).joinToString("\n")
    }
}
