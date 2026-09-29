package com.dshbox.app.common

/**
 * 在线获取 node 层可用的 dist 源。
 *
 * 每个源都必须提供 `v<版本>/node-v<版本>-linux-<架构>.tar.xz` **与同目录
 * `SHASUMS256.txt`**（上游锚定校验必须随源可得）——各家的目录布局一致：
 * `<url>/v<版本>/<文件名>`。运行时不标注地区，探测按「含目标文件 + 延迟」排序。
 */
data class NodeDistSource(
    val name: UiText,
    val url: String,
    val note: UiText,
) {
    fun shasumsUrl(version: String): String = "$url/v$version/SHASUMS256.txt"

    fun tarballUrl(version: String, nodeArch: String): String =
        "$url/v$version/node-v$version-linux-$nodeArch.tar.xz"
}

object NodeSources {
    /** node 层版本基线（与 runtime-bundle/build_node.sh / RUNTIME_LAYERS.md 一致，随版本更新）。 */
    const val NODE_VERSION = "24.19.0"

    /** APK ABI → node dist 架构名（模拟器 x86_64 对应 x64）。 */
    fun nodeArch(supportedAbi: String?): String = when (supportedAbi) {
        "x86_64" -> "x64"
        else -> "arm64"
    }

    /**
     * 探测与安装的源清单（6 源）。
     * TUNA/BFSU 各节点均不可用，已移除。
     */
    val ALL: List<NodeDistSource> = listOf(
        NodeDistSource(
            name = UiText.Res(R.string.node_source_official_name),
            url = "https://nodejs.org/dist",
            note = UiText.Res(R.string.node_source_official_note),
        ),
        NodeDistSource(
            name = UiText.Res(R.string.node_source_npmmirror_name),
            url = "https://npmmirror.com/mirrors/node",
            note = UiText.Res(R.string.node_source_npmmirror_note),
        ),
        NodeDistSource(
            name = UiText.Res(R.string.node_source_huawei_name),
            url = "https://mirrors.huaweicloud.com/nodejs",
            note = UiText.Res(R.string.node_source_huawei_note),
        ),
        NodeDistSource(
            name = UiText.Res(R.string.node_source_tencent_name),
            url = "https://mirrors.cloud.tencent.com/nodejs-release",
            note = UiText.Res(R.string.node_source_tencent_note),
        ),
        NodeDistSource(
            name = UiText.Res(R.string.node_source_aliyun_name),
            url = "https://mirrors.aliyun.com/nodejs-release",
            note = UiText.Res(R.string.node_source_aliyun_note),
        ),
        NodeDistSource(
            name = UiText.Res(R.string.node_source_sjtug_name),
            url = "https://mirrors.sjtug.sjtu.edu.cn/nodejs-release",
            note = UiText.Res(R.string.node_source_sjtug_note),
        ),
    )
}
