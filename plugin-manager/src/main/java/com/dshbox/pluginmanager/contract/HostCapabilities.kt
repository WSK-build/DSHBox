package com.dshbox.pluginmanager.contract

/**
 * 宿主**能力**的探测与判定：问"这个宿主支持什么"，而不是"它是什么版本"。
 *
 * 上游 dsh-market 自己也是能力探测：宿主会**回移**能力（修 bug 时把新语义带回旧线），
 * 版本号推不出能力，而版本比较本身又是另一处契约（谁大谁小、预发布怎么排）。
 * 因此这里**只**看宿主留下的本地痕迹，不读版本号——版本阈值会在用户升级到
 * 下一个发布线时立刻过期。
 *
 * 探测来源全部是本地事实：profile 目录结构、运行层布局。不联网、不启动宿主。
 * 探测失败时一律回落到**最保守**的一侧（当作"不支持"），由调用方给出可读说明。
 *
 * ## 快照语义（调用方须知）
 *
 * 探测是**一次性快照**：`PluginManagerFactory` 在装配时探一次。若 profile 或
 * 运行层在那之后才被初始化/替换，能力不会自动刷新——调用方需在恰当的时机
 * 重新探测。当前唯一消费点（读包清单的 `dsh.bundle.patch`）对快照时效不敏感。
 */
object HostCapabilities {

    /**
     * 一次探测得到的宿主能力集。
     *
     * 每项都是"宿主确实具备"的观察结果，不是版本推断。新增能力时在此加字段，
     * 并在 [detect] 里给出探测依据；没有本地痕迹可依据的能力不得靠版本号推断。
     */
    data class Capabilities(
        /**
         * 宿主对插件声明做版本兼容门禁（peer 不满足则装前拒绝/启动期置停用）。
         *
         * 探测依据：profile 目录里存在 `compatibility.json`——豁免体系启用是门禁
         * 存在的直接痕迹。**消费状态：已探测、待消费。**
         */
        val enforcesVersionCompatibility: Boolean,

        /**
         * 装配失败是**跳过式**（条目不激活但进程继续），而非抛错式。
         *
         * **无法从本地痕迹判定**：它只在运行期日志里体现，探测不到。因此恒为
         * false，且当前无消费方；接线前必须先设计探测依据，不得退回版本推断。
         */
        val skipsFailedEntries: Boolean,

        /**
         * `dsh.bundle.patch` 接受字符串**数组**。
         *
         * 生产侧恒为 true：清单里声明了数组就按数组读。多读一个文件只会让某个包
         * 多认领几行，方向是保守的（少删），不会误删。
         * **消费状态：已接线**（`PackageRowIds.declaredPatchFiles`）。
         */
        val acceptsPatchArray: Boolean,

        /**
         * `dsh plugin` 子命令存在（宿主提供官方插件管理）。
         *
         * 探测依据：安装层的 `@deepseek-ai/dsh-plugin-manager` 存在。不存在时无法
         * 用官方命令安装（只能只读展示）。**消费状态：已探测、待消费。**
         */
        val hasOfficialPluginCommand: Boolean,
    ) {
        companion object {
            /**
             * 最保守的能力集：一切"不确定"都按不支持处理。
             *
             * 探测不到宿主时用它。**不假装支持**——假装支持的后果是写出宿主
             * 不认的命令或结构。
             */
            val CONSERVATIVE = Capabilities(
                enforcesVersionCompatibility = false,
                skipsFailedEntries = false,
                acceptsPatchArray = false,
                hasOfficialPluginCommand = false,
            )

            /**
             * 最宽的能力集：一切按支持处理。
             *
             * 用途只有一个：**未接线能力探测时的默认值**，保证重构不改行为。
             * 生产路径一律用 [com.dshbox.pluginmanager.core.HostCapabilityProbe] 的真实探测结果。
             */
            val WIDEST = Capabilities(
                enforcesVersionCompatibility = true,
                skipsFailedEntries = true,
                acceptsPatchArray = true,
                hasOfficialPluginCommand = true,
            )
        }
    }

    /**
     * 探测宿主能力。
     *
     * @param hasCompatibilityFile profile 目录里是否存在 `compatibility.json`。
     * @param hasPluginManagerPackage 安装层里是否有 `@deepseek-ai/dsh-plugin-manager`。
     */
    fun detect(
        hasCompatibilityFile: Boolean,
        hasPluginManagerPackage: Boolean,
    ): Capabilities = Capabilities(
        enforcesVersionCompatibility = hasCompatibilityFile,
        // 无本地痕迹可判，见字段说明。
        skipsFailedEntries = false,
        acceptsPatchArray = true,
        hasOfficialPluginCommand = hasPluginManagerPackage,
    )
}
