package com.dshbox.app.common

/**
 * DSH 在线更新可用的 npm registry 源（1.1.0 新增，取代旧的 Constants.DSH_MIRRORS）。
 *
 * 每个源都必须能提供 @deepseek-ai/dsh 的 registry 元数据（GET <url>/@deepseek-ai/dsh，
 * 返回 dist-tags + versions）并能作为 npm --registry 的取包源。
 * URL 末尾不带斜杠（探测时统一拼接）；[note] 是展示给用户的补充说明。
 *
 * 实测（2026-08）：四个源均返回 200 + 一致的 dist-tags/versions 结构，latest=0.1.1-rc.2。
 */
data class DshNpmSource(
    val name: String,
    val url: String,
    val note: String,
    /** 是否国内源（仅用于展示排序/标记，不影响探测——探测永远并行发起）。 */
    val chinaMirror: Boolean = false,
) {
    /** registry 元数据地址（scoped 包，实测四个源均接受未编码的 @scope/name 形式）。 */
    fun metadataUrl(): String = "$url/@deepseek-ai/dsh"
}

object DshSources {
    /** 探测与安装的源清单，官方上游在前（权威），国内镜像随后。 */
    val ALL: List<DshNpmSource> = listOf(
        DshNpmSource(
            name = "npm 官方源",
            url = "https://registry.npmjs.org",
            note = "权威上游，发布最及时",
        ),
        DshNpmSource(
            name = "阿里 npmmirror",
            url = "https://registry.npmmirror.com",
            note = "国内阿里云镜像，通常最快",
            chinaMirror = true,
        ),
        DshNpmSource(
            name = "腾讯云镜像",
            url = "https://mirrors.cloud.tencent.com/npm",
            note = "国内腾讯云镜像",
            chinaMirror = true,
        ),
        DshNpmSource(
            name = "华为云镜像",
            url = "https://repo.huaweicloud.com/repository/npm",
            note = "国内华为云镜像",
            chinaMirror = true,
        ),
    )
}
