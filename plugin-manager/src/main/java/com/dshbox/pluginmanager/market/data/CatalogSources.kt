package com.dshbox.pluginmanager.market.data

/**
 * 插件目录的数据源。
 *
 * 每个源是一份**独立**的策展目录：切换就是整份换掉，不做降级、不混源
 * （上游 `regions.ts` 对自定义 catalog 也是这个语义 —— 命名了就用它，
 * 不把别的东西掺进来）。
 *
 * [addresses] 是**同一个目录的多个地址**，按顺序回退；这与"多个不同的源"
 * 是两件事，不要混。移动端源同时挂在 npm CDN 与 GitHub Pages 上，两处内容一致。
 */
enum class CatalogSource(
    /** 持久化用的 id（也是展示名，六语不翻译）。 */
    val id: String,
    /** 仓库归属，仅展示。 */
    val owner: String,
    /** 仓库主页 —— 选择页里那一行可点的跳转。 */
    val repoUrl: String,
    /** 目录地址；多个 = 同一份目录的多地址回退。 */
    val addresses: List<String>,
) {
    MOBILE(
        id = "awesome-dsh-mobile-plugins",
        owner = "WSK-build",
        repoUrl = "https://github.com/WSK-build/awesome-dsh-mobile-plugins",
        addresses = listOf(
            // npm CDN 放前面：GitHub Pages 在部分网络下不可达，而国内用户占多数。
            // 两个地址都是**纯 URL 的 GET**（npm 包形态也走文件地址，不需要解包），
            // 所以现有的取数点直接可用。
            "https://cdn.jsdelivr.net/npm/dsh-mobile-plugin-catalog/plugins.json",
            "https://wsk-build.github.io/awesome-dsh-mobile-plugins/plugins.json",
        ),
    ),
    OFFICIAL(
        id = "awesome-dsh-plugin",
        owner = "awesome-dsh-plugin",
        repoUrl = "https://github.com/awesome-dsh-plugin/awesome-dsh-plugin",
        addresses = listOf("https://awesome-dsh-plugin.com/plugins.json"),
    ),
    ;

    companion object {
        /** 默认源：移动端精选目录。 */
        val DEFAULT: CatalogSource = MOBILE

        /** 认不出的 id（文件缺失、被改坏、旧版本残留）一律回落默认源。 */
        fun byId(id: String?): CatalogSource =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
