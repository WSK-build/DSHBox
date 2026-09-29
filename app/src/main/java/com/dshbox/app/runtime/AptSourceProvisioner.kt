package com.dshbox.app.runtime

import com.dshbox.app.common.DebianSources
import java.io.File

/**
 * 保证 base 层内的 apt 源可用。
 *
 * 随包层出厂时写入的是一个已失效的镜像（该镜像的各节点均不可达），这会让 guest 内的
 * `apt-get update` 直接失败、连「包索引都拿不到」。在线组装路径会在组装时写入「本次实际跑通的
 * 镜像」，而离线导入路径没有对应的写入点——本类补上这一步：启动期做一次幂等校验，
 * 源缺失/为空/指向已知失效镜像时改写为官方源；用户自配的其它源保持不动。
 *
 * 只在宿主侧改一个文本文件，不联网、不改动界面、不增加随包体积。
 */
object AptSourceProvisioner {

    /** 已知不可达的镜像主机（源清单里已移除的节点）。 */
    private val DEAD_HOSTS = listOf(
        "mirrors.tuna.tsinghua.edu.cn",
        "mirrors.bfsu.edu.cn",
    )

    /** 官方源：在线路径的首选源，无地域偏好、长期可达。 */
    private const val CANONICAL_URL = "https://deb.debian.org/debian"

    /**
     * 校验并（必要时）改写 [baseRoot]（即 `runtime-current/base`）下的 `etc/apt/sources.list`。
     *
     * @return true 表示本次确实做了改写；base 层不存在时不做任何事并返回 false。
     */
    fun provision(baseRoot: File): Boolean {
        if (!baseRoot.isDirectory) return false
        val file = File(baseRoot, "etc/apt/sources.list")
        val current = runCatching { file.readText() }.getOrNull()
        if (current != null && !needsRewrite(current)) return false
        return runCatching {
            file.parentFile?.mkdirs()
            file.writeText(
                "# 由 DSHBox 写入：随包层出厂源已失效，改用官方源\n" +
                    "deb $CANONICAL_URL ${DebianSources.SUITE} main\n",
            )
            true
        }.getOrDefault(false)
    }

    /**
     * 源内容是否需要改写：没有任何生效的 `deb` 行，或其中任一行指向已知失效镜像。
     *
     * 只要存在可用行就保持原样，避免覆盖用户自己配置的镜像。
     */
    internal fun needsRewrite(content: String): Boolean {
        val debLines = content.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.startsWith("deb ") }
            .toList()
        if (debLines.isEmpty()) return true
        return debLines.any { line -> DEAD_HOSTS.any { host -> line.contains(host) } }
    }
}
