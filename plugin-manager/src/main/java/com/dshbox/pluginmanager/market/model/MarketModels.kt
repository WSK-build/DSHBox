package com.dshbox.pluginmanager.market.model

/**
 * 市场的数据模型。字段刻意与上游 `dshmarket` 的目录 JSON、
 * `state.json`、激活状态等契约保持一致，便于与桌面端互迁与后续对齐。
 *
 * 这些类型是 UI 与数据层之间的稳定契约：数据层只能填充它们，
 * UI 只能读取它们；新增字段必须给默认值，不得改动既有字段语义。
 */

/** 目录条目（来自策展目录 JSON）。 */
data class RegistryPlugin(
    val name: String,
    val owner: String,
    val url: String,
    /** 归一后的分类；空集合表示该条目不可用（上游会直接拒绝这种条目）。 */
    val categories: List<String>,
    /** 语言 → 描述。 */
    val description: Map<String, String> = emptyMap(),
    val install: String = "",
    val added: String = "",
    val npm: String? = null,
    val tarball: String? = null,
    val stars: Int? = null,
    /** 缺失表示"没有 npm 包"，不是 0——排序时不得当 0 处理。 */
    val downloads: Int? = null,
    val version: String? = null,
    val deprecated: Boolean = false,
    val replacement: String? = null,
    /**
     * 作者在目录里策展的截图（只收 https 的 GitHub 图床地址）。
     *
     * 目录里只有约两成条目带这个字段；没有时卡片不显示图，不做额外的 README 抽取。
     */
    val screenshots: List<String> = emptyList(),
) {
    /** 显示名：优先 npm 名，否则 owner/name。 */
    val displayName: String get() = npm ?: "$owner/$name"

    fun descriptionFor(language: String): String =
        description[language] ?: description["en"] ?: description.values.firstOrNull() ?: ""
}

/** 策展目录。 */
data class Registry(
    val updated: String,
    val count: Int,
    val categories: Map<String, Map<String, String>>,
    val plugins: List<RegistryPlugin>,
)

/** 宿主兼容性判定结果。 */
data class HostCompatibility(
    val status: Status,
    val basis: Basis,
    val requirement: String? = null,
) {
    enum class Status { COMPATIBLE, INCOMPATIBLE, UNKNOWN }

    /** `MANIFEST` = 有声明可判；`UNDECLARED` = 插件未声明；`UNAVAILABLE` = 取不到信息。 */
    enum class Basis { MANIFEST, UNDECLARED, UNAVAILABLE }

    companion object {
        /** 取不到信息时的默认值：**绝不**判成不兼容。 */
        val unknown = HostCompatibility(Status.UNKNOWN, Basis.UNAVAILABLE)
    }
}

/** 激活状态（六态，语义与上游一致）。 */
enum class ActivationState {
    /** 已加载、正在提供能力。 */
    LIVE,

    /** 已装但需要重启才生效。 */
    RESTART,

    /** 装上但当前不参与组合（普通依赖 / 仅在客户端侧存在）。 */
    INERT,

    /** 装了但不可加载（缺入口产物等）。 */
    BROKEN,

    /** 清单里有，但文件不在。 */
    MISSING,

    /** 被开关关掉。 */
    DISABLED,
}

/** 已安装插件。 */
data class InstalledPlugin(
    val name: String,
    val version: String?,
    val spec: String?,
    val activation: ActivationState,
    /** 判定理由（双语串由上游格式沿用，UI 按 ` / ` 择半展示）。 */
    val reasons: List<String> = emptyList(),
    val deprecated: Boolean = false,
    val replacement: String? = null,
    /** 我在市场里写的备注（200 字以内）。 */
    val note: String? = null,
    /** 是否属于第三方（包名不以官方前缀开头）。 */
    val thirdParty: Boolean = true,
)

/** 更新检测结果。 */
data class UpdateStatus(
    /** 只向前进：semver 严格更高，或 git 的 commit 变化。 */
    val updateAvailable: Boolean = false,
    val current: String? = null,
    val latest: String? = null,
    /** `npm` / `github` / `linked` / `generation` / `file`。 */
    val kind: String? = null,
    /** 有更新但方向不是"升级"（如切通道）时置位，UI 不得当作更新。 */
    val channelSwitch: String? = null,
    /**
     * **是否真的完成了比较**（拿到了对端版本，且本地版本可比）。
     *
     * `false` 的含义是"我们没查到"，不是"已经是最新"：非 npm 来源
     * （`link:` / `file:` / `github:` / `generation`）与查询失败都落在这里。
     * UI 必须把 `!checked` 渲染成"未检测（附原因）"，只有
     * `checked && !updateAvailable` 才是「已是最新」。
     *
     * 新增字段带默认值 `false`：默认值必须是"保守的一侧"，忘记填的地方
     * 只会显示"未检测"，不会谎报"已是最新"。
     */
    val checked: Boolean = false,
)

/** 目录加载失败的说明（用于 UI 给出具体原因，而不是"加载失败"四个字）。 */
data class RegistryFailure(
    val message: String,
    val attempts: Int,
    val elapsedMs: Long,
)

/** 市场页签。 */
enum class MarketTab { DISCOVER, INSTALLED }
