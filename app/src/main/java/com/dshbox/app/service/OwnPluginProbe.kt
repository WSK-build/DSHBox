package com.dshbox.app.service

/**
 * DSHBox 自有插件包（`mobile-adapt` / `mobile-pilot`）「装配态探测」的脚本构造与结果解析。
 *
 * 两个包共用这一份：包名、装配目录、暂存目录都作为参数传入，判定语义完全一致 ——
 * 它们在 profile 里的存在形式相同（`node_modules/@local/<包名>` + `dsh.profile.bundles` 里的一行），
 * 因此没有理由各写一套。
 *
 * ## 为什么单独抽出来
 *
 * 探测要 spawn 一个 guest 进程，代价可观，所以四项判断**合并为一条命令**，靠脚本回传的
 * 标记区分状态。合并带来一个易错点：脚本是一段**手工拼接的字符串**，
 * 条件之间少一个 `&&` 就会静默改变语义（例如把 `test -f X` 与 `[ ... ]` 粘成一条命令，
 * 让存在性检查形同虚设）。这类错误编译器看不见、运行时也不报错，只会让判定结果悄悄反转。
 *
 * 因此把拼接与解析提为纯函数，用单测锁住脚本结构与标记解析。
 * 纯字符串进 / 字符串出，无 Android 依赖，可直接在 JVM 单测里跑。
 */
internal object OwnPluginProbe {

    /**
     * profile 目录不存在（DSH 从没跑过）→ **不装配**：不替 DSH 创建它的家目录。
     */
    const val MARKER_PROFILE_MISSING = "__DSHBOX_PLUGIN_PROFILE_MISSING__"

    /** staged 的 `install.sh` 不在（随包资产复制失败）→ 装不成，跳过，下次启动再试。 */
    const val MARKER_STAGE_MISSING = "__DSHBOX_PLUGIN_STAGE_MISSING__"

    /** 内容一致且 bundle 已注册 → 已是最新，跳过（省 I/O，也不覆盖用户手改）。 */
    const val MARKER_UP_TO_DATE = "__DSHBOX_PLUGIN_UP_TO_DATE__"

    /**
     * 其余情况 → 执行 `install.sh`。
     *
     * **包含"插件目录整个不存在"**：开关为开即视为用户要这个包，启动期就该把它装配到位，
     * 不能只让开关亮着而 profile 里空空如也。
     */
    const val MARKER_NEEDS_INSTALL = "__DSHBOX_PLUGIN_NEEDS_INSTALL__"

    /** 这次启动要怎么处置这个包。 */
    enum class Action {
        /** 跑 `install.sh`，把包装配进 profile。 */
        ENSURE_INSTALL,

        /** 不动用户的 profile。 */
        SKIP,
    }

    /**
     * 探测结果 → 动作。
     *
     * **只有 [MARKER_NEEDS_INSTALL] 会走安装**：其余一切（未就绪、已最新、以及"没拿到标记"）
     * 都必须放过用户 profile —— 其中"没拿到标记"覆盖 proot spawn 失败、guest 异常、
     * 脚本被中断等情况，信息不足时宁可下次启动再试。
     */
    fun actionFor(marker: String?): Action = when (marker) {
        MARKER_NEEDS_INSTALL -> Action.ENSURE_INSTALL
        else -> Action.SKIP
    }

    /**
     * 从一行 guest 输出里解析状态标记；无关行返回 null。
     *
     * 用 `contains` 而非全等：guest 输出可能带前后空白或 `\r`（PRoot 管道下会出现），
     * 全等匹配会漏判 —— 而漏判的后果是"没有标记"→ 保守跳过，插件永远不更新。
     */
    fun markerFrom(line: String): String? = when {
        line.contains(MARKER_PROFILE_MISSING) -> MARKER_PROFILE_MISSING
        line.contains(MARKER_STAGE_MISSING) -> MARKER_STAGE_MISSING
        line.contains(MARKER_UP_TO_DATE) -> MARKER_UP_TO_DATE
        line.contains(MARKER_NEEDS_INSTALL) -> MARKER_NEEDS_INSTALL
        else -> null
    }

    /**
     * 拼接单次探测脚本（一条命令同时完成「能不能装」与「要不要装」两类判断）。
     *
     * @param profileDir profile 目录（`.../profiles/web`）
     * @param pluginDir profile 内的插件目录（`.../node_modules/@local/<包名>`）
     * @param stageInstall staged 的 `install.sh` 绝对路径
     * @param stagePlugin staged 的插件目录（`.../<暂存名>/plugin`）
     * @param profilePackageJson profile 的 `package.json` 绝对路径
     * @param bundleId `install.sh` 写进 `dsh.profile.bundles` 的包名
     * @param fingerprintFiles 参与一致性比对的文件（相对插件根）。
     *   **由调用方按暂存包的实际内容推导**，不要写死：两个包的组成并不相同
     *   （适配包有 `lib/client.js`，mobile-pilot 空壳只有 `lib/index.js`）。
     *   写死过一份就会踩到"清单里有、包里没有"——那个文件永远 `test -f` 不过，
     *   于是每次都判为需安装，**每次启动都重装一遍**，而且看日志只像"更新了"。
     *
     * ## 为什么按"先否决、再判最新"分四支
     *
     * 前三支都是**装不成或不必装**，且各有各的原因，混成一个标记就没法定位：
     *  - profile 不存在：DSH 还没跑过，此时装进去等于替他造一个家目录；
     *  - staged 缺失：随包资产没复制成功，装也装不成；
     *  - 已最新：省一次写盘，也不覆盖用户对 profile 内插件的手动修改。
     *
     * `runGuestCommand` 只把退出码折叠成 Success/Failure，拿不到具体码值，
     * 所以用**输出标记**表达这四态，由 Kotlin 侧分派。
     *
     * ## 脚本自身不留会失败的收尾语句
     *
     * 四个分支都以 `echo` 成功结束，因此**任何未预期的失败都表现为"没有标记"**，
     * 由调用方保守处理（跳过，下次启动再试）。
     */
    fun buildScript(
        profileDir: String,
        pluginDir: String,
        stageInstall: String,
        stagePlugin: String,
        profilePackageJson: String,
        bundleId: String,
        fingerprintFiles: List<String>,
    ): String {
        // 存在性检查：两侧每个指纹文件都必须**显式存在**（test -f）。
        //
        // ⚠️ 这一条不可省：
        // `cat 缺失文件` 会输出空内容，空内容的 sha256 恒为 e3b0c442…；
        // 若两侧同时缺失，两个空哈希相等 → 哈希比对误判为 true，
        // 若 bundle 恰好仍注册，整体会误判「已是最新」→ **跳过安装，但插件一个文件都没有**。
        // 这不是自愈的：用户永远跑不上插件。
        val existsChecks = (fingerprintFiles.map { "test -f $pluginDir/$it" } +
            fingerprintFiles.map { "test -f $stagePlugin/$it" })
            .joinToString(" && ")

        val digestOf = { root: String -> fingerprintFiles.joinToString(" ") { "$root/$it" } }

        // 已是最新需三条同时成立：
        //   ① 两侧关键文件都显式存在   ② 内容连接后的 sha256 相等   ③ bundle 仍注册
        // 条件之间必须用 " && " 连接 —— 这里漏连接符不会报错，只会静默改变语义，
        // 故由 OwnPluginProbeTest 断言该连接符存在。
        val upToDateCondition =
            "$existsChecks && " +
                "[ \"\$(cat ${digestOf(pluginDir)} | sha256sum)\" = " +
                "\"\$(cat ${digestOf(stagePlugin)} | sha256sum)\" ] && " +
                "grep -q '$bundleId' $profilePackageJson"

        return listOf(
            "if ! test -d $profileDir; then",
            "  echo $MARKER_PROFILE_MISSING",
            "elif ! test -f $stageInstall; then",
            "  echo $MARKER_STAGE_MISSING",
            "elif $upToDateCondition; then",
            "  echo $MARKER_UP_TO_DATE",
            "else",
            "  echo $MARKER_NEEDS_INSTALL",
            "fi",
        ).joinToString("\n")
    }
}
