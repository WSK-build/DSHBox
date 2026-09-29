package com.dshbox.app.common

/**
 * DSH 在线更新可用的 npm registry 源（1.1.0 新增，取代旧的 Constants.DSH_MIRRORS）。
 *
 * 每个源都必须能提供 @deepseek-ai/dsh 的 registry 元数据（GET <url>/@deepseek-ai/dsh，
 * 返回 dist-tags + versions）并能作为 npm --registry 的取包源。
 * URL 末尾不带斜杠（探测时统一拼接）；[note] 是展示给用户的补充说明。
 *
 * 四个源均返回 200 + 一致的 dist-tags/versions 结构（latest=0.1.1-rc.2）。
 */
data class DshNpmSource(
    /** 展示名（起为可本地化 [UiText]）。 */
    val name: UiText,
    val url: String,
    /** 展示给用户的补充说明（可本地化）。 */
    val note: UiText,
) {
    /** registry 元数据地址（scoped 包；四个源均接受未编码的 @scope/name 形式）。 */
    fun metadataUrl(): String = "$url/@deepseek-ai/dsh"
}

object DshSources {
    /**
     * 探测与安装的源清单——**在原有 4 源基础上只增不删**（地区适配：原 4 源保持
     * 国内源在前，国外补充源追加在后）。
     */
    val ALL: List<DshNpmSource> = listOf(
        // ===== 原有 4 源（不动）=====
        DshNpmSource(
            name = UiText.Res(R.string.dsh_source_official_name),
            url = "https://registry.npmjs.org",
            note = UiText.Res(R.string.dsh_source_official_note),
        ),
        DshNpmSource(
            name = UiText.Res(R.string.dsh_source_npmmirror_name),
            url = "https://registry.npmmirror.com",
            note = UiText.Res(R.string.dsh_source_npmmirror_note),
        ),
        DshNpmSource(
            name = UiText.Res(R.string.dsh_source_tencent_name),
            url = "https://mirrors.cloud.tencent.com/npm",
            note = UiText.Res(R.string.dsh_source_tencent_note),
        ),
        DshNpmSource(
            name = UiText.Res(R.string.dsh_source_huawei_name),
            url = "https://repo.huaweicloud.com/repository/npm",
            note = UiText.Res(R.string.dsh_source_huawei_note),
        ),
        // ===== 国外补充源 =====
        DshNpmSource(
            name = UiText.Res(R.string.dsh_source_yarn_name),
            url = "https://registry.yarnpkg.com",
            note = UiText.Res(R.string.dsh_source_yarn_note),
        ),
        DshNpmSource(
            name = UiText.Res(R.string.dsh_source_npmjscom_name),
            url = "https://registry.npmjs.com",
            note = UiText.Res(R.string.dsh_source_npmjscom_note),
        ),
        DshNpmSource(
            name = UiText.Res(R.string.dsh_source_cnpm_name),
            url = "https://r.cnpmjs.org",
            note = UiText.Res(R.string.dsh_source_cnpm_note),
        ),
        DshNpmSource(
            name = UiText.Res(R.string.dsh_source_tencentalt_name),
            url = "https://mirrors.tencent.com/npm",
            note = UiText.Res(R.string.dsh_source_tencentalt_note),
        ),
    )
}
