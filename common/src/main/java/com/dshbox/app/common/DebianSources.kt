package com.dshbox.app.common

/**
 * 在线获取 base 层（Debian）可用的 archive 源。
 *
 * 每个源都必须能提供 `dists/<suite>/Release`（含各索引文件 SHA256，作完整性锚点）与
 * `dists/<suite>/main/binary-<arch>/Packages.gz`，且对应 pool .deb 可下载。
 * URL 末尾不带斜杠（探测/拼接时统一处理）；[note] 是展示给用户的补充说明。
 * 运行时不标注地区：并行探测按「是否含所需内容 + 延迟」自动排序，用户自选。
 */
data class DebianArchiveSource(
    /** 展示名（可本地化 [UiText]）。 */
    val name: UiText,
    val url: String,
    /** 展示给用户的补充说明（可本地化）。 */
    val note: UiText,
) {
    /** Release 文件（内含 Packages.gz 的 SHA256 声明，作下载完整性锚点）。 */
    fun releaseUrl(suite: String): String = "$url/dists/$suite/Release"

    fun packagesIndexUrl(suite: String, debArch: String): String =
        "$url/dists/$suite/main/binary-$debArch/Packages.gz"
}

object DebianSources {
    /** 我们的 base 层构建所用的 Debian 套件（runtime-bundle/build_base.sh 默认值）。 */
    const val SUITE = "trixie"

    /** APK ABI → Debian 架构名（模拟器 x86_64 对应 amd64）。 */
    fun debArch(supportedAbi: String?): String = when (supportedAbi) {
        "x86_64" -> "amd64"
        else -> "arm64"
    }

    /** APK ABI → 架构三元组（层内 `usr/lib/<triple>/` 路径与 dpkg 库中的架构字样用）。 */
    fun debTriple(supportedAbi: String?): String = when (supportedAbi) {
        "x86_64" -> "x86_64-linux-gnu"
        else -> "aarch64-linux-gnu"
    }

    /**
     * 探测与安装的源清单（24 源）。
     * TUNA/BFSU 各节点均不可用，已移除。
     */
    val ALL: List<DebianArchiveSource> = listOf(
        DebianArchiveSource(
            name = UiText.Res(R.string.deb_source_official_name),
            url = "https://deb.debian.org/debian",
            note = UiText.Res(R.string.deb_source_official_note),
        ),
        DebianArchiveSource(
            name = UiText.Res(R.string.deb_source_jaist_name),
            url = "https://ftp.jaist.ac.jp/pub/Linux/debian",
            note = UiText.Res(R.string.deb_source_jaist_note),
        ),
        DebianArchiveSource(
            name = UiText.Res(R.string.deb_source_kernel_name),
            url = "https://mirrors.kernel.org/debian",
            note = UiText.Res(R.string.deb_source_kernel_note),
        ),
        DebianArchiveSource(
            name = UiText.Res(R.string.deb_source_waterloo_name),
            url = "https://mirror.csclub.uwaterloo.ca/debian",
            note = UiText.Res(R.string.deb_source_waterloo_note),
        ),
        DebianArchiveSource(
            name = UiText.Res(R.string.deb_source_ustc_name),
            url = "https://mirrors.ustc.edu.cn/debian",
            note = UiText.Res(R.string.deb_source_ustc_note),
        ),
        DebianArchiveSource(
            name = UiText.Res(R.string.deb_source_aliyun_name),
            url = "https://mirrors.aliyun.com/debian",
            note = UiText.Res(R.string.deb_source_aliyun_note),
        ),
        DebianArchiveSource(
            name = UiText.Res(R.string.deb_source_tencent_name),
            url = "https://mirrors.cloud.tencent.com/debian",
            note = UiText.Res(R.string.deb_source_tencent_note),
        ),
        DebianArchiveSource(
            name = UiText.Res(R.string.deb_source_huawei_name),
            url = "https://repo.huaweicloud.com/debian",
            note = UiText.Res(R.string.deb_source_huawei_note),
        ),
        DebianArchiveSource(
            name = UiText.Res(R.string.deb_source_archive_name),
            url = "https://ftp.debian.org/debian",
            note = UiText.Res(R.string.deb_source_archive_note),
        ),
        DebianArchiveSource(
            name = UiText.Res(R.string.deb_source_riken_name),
            url = "https://ftp.riken.jp/Linux/debian/debian",
            note = UiText.Res(R.string.deb_source_riken_note),
        ),
        DebianArchiveSource(
            name = UiText.Res(R.string.deb_source_fau_name),
            url = "https://ftp.fau.de/debian",
            note = UiText.Res(R.string.deb_source_fau_note),
        ),
        DebianArchiveSource(
            name = UiText.Res(R.string.deb_source_esslingen_name),
            url = "https://ftp-stud.hs-esslingen.de/debian",
            note = UiText.Res(R.string.deb_source_esslingen_note),
        ),
        DebianArchiveSource(
            name = UiText.Res(R.string.deb_source_rwth_name),
            url = "https://ftp.halifax.rwth-aachen.de/debian",
            note = UiText.Res(R.string.deb_source_rwth_note),
        ),
        DebianArchiveSource(
            name = UiText.Res(R.string.deb_source_leaseweb_name),
            url = "https://mirror.leaseweb.com/debian",
            note = UiText.Res(R.string.deb_source_leaseweb_note),
        ),
        DebianArchiveSource(
            name = UiText.Res(R.string.deb_source_mirrorservice_name),
            url = "https://www.mirrorservice.org/sites/ftp.debian.org/debian",
            note = UiText.Res(R.string.deb_source_mirrorservice_note),
        ),
        DebianArchiveSource(
            name = UiText.Res(R.string.deb_source_nchc_name),
            url = "https://opensource.nchc.org.tw/debian",
            note = UiText.Res(R.string.deb_source_nchc_note),
        ),
        DebianArchiveSource(
            name = UiText.Res(R.string.deb_source_ocf_name),
            url = "https://mirrors.ocf.berkeley.edu/debian",
            note = UiText.Res(R.string.deb_source_ocf_note),
        ),
        DebianArchiveSource(
            name = UiText.Res(R.string.deb_source_osuosl_name),
            url = "https://debian.osuosl.org/debian",
            note = UiText.Res(R.string.deb_source_osuosl_note),
        ),
        DebianArchiveSource(
            name = UiText.Res(R.string.deb_source_onecom_name),
            url = "https://mirror.one.com/debian",
            note = UiText.Res(R.string.deb_source_onecom_note),
        ),
        DebianArchiveSource(
            name = UiText.Res(R.string.deb_source_yandex_name),
            url = "https://mirror.yandex.ru/debian",
            note = UiText.Res(R.string.deb_source_yandex_note),
        ),
        DebianArchiveSource(
            name = UiText.Res(R.string.deb_source_aarnet_name),
            url = "https://mirror.aarnet.edu.au/pub/debian",
            note = UiText.Res(R.string.deb_source_aarnet_note),
        ),
        DebianArchiveSource(
            name = UiText.Res(R.string.deb_source_task_name),
            url = "https://ftp.task.gda.pl/debian",
            note = UiText.Res(R.string.deb_source_task_note),
        ),
        DebianArchiveSource(
            name = UiText.Res(R.string.deb_source_163_name),
            url = "https://mirrors.163.com/debian",
            note = UiText.Res(R.string.deb_source_163_note),
        ),
        DebianArchiveSource(
            name = UiText.Res(R.string.deb_source_sjtug_name),
            url = "https://mirrors.sjtug.sjtu.edu.cn/debian",
            note = UiText.Res(R.string.deb_source_sjtug_note),
        ),
    )
}
