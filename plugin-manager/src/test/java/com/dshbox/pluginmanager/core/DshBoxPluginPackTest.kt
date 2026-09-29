package com.dshbox.pluginmanager.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DSHBox 自有插件包（`dshbox-plugins/assets/plugins/`）的**跨文件一致性**。
 *
 * 同一个插件 id 在一个包里出现在五处：`install.sh` / `uninstall.sh` 的 `PKG`、
 * `plugin/package.json` 的 `name`、`plugin/cordis.patch.yml` 的 `insert` id 与 name、
 * `plugin/lib/index.js` 的 `exports.name`。任何一处漏改都不会编译报错，只会**静默**表现为
 * 「装配成功但插件没加载」或「卸载后 bundles 里还留着登记」。
 *
 * 这里把五处钉在一起，并顺手挡掉最常见的复制粘贴事故：新包里残留另一个包的 id。
 */
class DshBoxPluginPackTest {

    private val packsDir: File = findPacksDir()

    private fun pack(name: String) = File(packsDir, name)

    private fun read(path: String): String = File(packsDir, path).readText(Charsets.UTF_8)

    @Test
    fun bothOwnPacksArePresent() {
        assertTrue("移动端适配包不见了", pack("dsh-mobile-adapt").isDirectory)
        assertTrue("DSH连接手机包不见了", pack("mobile-pilot").isDirectory)
    }

    @Test
    fun mobilePilotPackDeclaresTheSameIdEverywhere() {
        val id = "@local/mobile-pilot"

        assertTrue(
            "install.sh 的 PKG 对不上",
            read("mobile-pilot/install.sh").contains("PKG=\"$id\""),
        )
        assertTrue(
            "uninstall.sh 的 PKG 对不上",
            read("mobile-pilot/uninstall.sh").contains("PKG=\"$id\""),
        )
        assertTrue(
            "package.json 的 name 对不上",
            read("mobile-pilot/plugin/package.json").contains("\"name\": \"$id\""),
        )
        assertTrue(
            "cordis.patch.yml 的 insert id 对不上",
            read("mobile-pilot/plugin/cordis.patch.yml").contains("id: '$id'"),
        )
        assertTrue(
            "cordis.patch.yml 的 insert name 对不上",
            read("mobile-pilot/plugin/cordis.patch.yml").contains("name: '$id'"),
        )
        assertTrue(
            "lib/index.js 的 exports.name 对不上",
            read("mobile-pilot/plugin/lib/index.js").contains("exports.name = '$id'"),
        )
    }

    @Test
    fun mobilePilotPackCarriesNoLeftoverIdFromTheOtherPack() {
        // 复制 mobile-adapt 的包来改是最容易的写法，也最容易漏掉一处 id。
        for (rel in PILOT_PACK_FILES) {
            assertFalse(
                "$rel 里残留了 dsh-mobile-adapt 的 id",
                read(rel).contains("@local/dsh-mobile-adapt"),
            )
        }
    }

    @Test
    fun mobilePilotScriptsRegisterAndUnregisterTheBundle() {
        // 关键在两件事：包装进 profile 之后要登记进 dsh.profile.bundles，卸载要反向摘掉；
        // 少任何一步，dsh 都不会加载这个插件。
        //
        // 注意 mobile-pilot 的安装脚本**不是**「直接 cp 进 node_modules」：它先复制到阶段目录、
        // 过 JS 语法预检与文件清单核对，再把旧包挪进备份位后落位（首装失败可回滚）。因此这里
        // 钉的是这套机制的关键语句，而不是某一种复制写法。
        val install = read("mobile-pilot/install.sh")
        val uninstall = read("mobile-pilot/uninstall.sh")

        assertTrue("install.sh 没有登记 bundle", install.contains("bundles.append(pkg)"))
        assertTrue(
            "install.sh 没有先复制到阶段目录",
            install.contains("cp -r \"${'$'}SRC\" \"${'$'}STAGE\""),
        )
        assertTrue("uninstall.sh 没有摘掉 bundle", uninstall.contains("b != pkg"))
        assertTrue(
            "uninstall.sh 没有删掉已装配的包目录",
            uninstall.contains("rm -rf \"${'$'}PKGDIR\""),
        )
    }

    @Test
    fun mobilePilotPackDeclaresAllPhoneTools() {
        // 这个包的交付价值就是那组 DSH 原生工具：装完 DSH 的工具列表里应出现 14 个 phone_*。
        // 名单少一个不会报错，只会「装配成功但少了个工具」，所以在这里按名字清单钉住。
        val tools = read("mobile-pilot/plugin/lib/tools.js")
        val declared = Regex("name: '(phone_[A-Za-z]+)'")
            .findAll(tools)
            .map { it.groupValues[1] }
            .toSortedSet().toList()

        assertEquals(
            "phone_* 工具名单与交付约定不一致",
            listOf(
                "phone_ask", "phone_call", "phone_capture", "phone_click",
                "phone_intent", "phone_key", "phone_launch", "phone_node",
                "phone_observe", "phone_scroll", "phone_setValue", "phone_status",
                "phone_surface", "phone_waitFor",
            ),
            declared,
        )
        assertEquals("工具名不应重复", declared.size, declared.toSet().size)

        // 插件半依赖宿主工具注册表（`tools`）；声明成空数组会让它在没有工具面时也照样加载。
        assertTrue(
            "lib/index.js 未声明依赖 tools",
            read("mobile-pilot/plugin/lib/index.js").contains("exports.inject = ['tools']"),
        )
    }

    /**
     * Gradle 单测的工作目录通常是模块目录，但也可能是 `build/` 下的临时目录，
     * 因此从两个锚点各自向上找，找到含 `dshbox-plugins/assets/plugins` 的那一级为止。
     */
    private fun findPacksDir(): File {
        val anchors = buildList {
            add(File("").absoluteFile)
            runCatching {
                javaClass.protectionDomain?.codeSource?.location?.let { File(it.toURI()) }
            }.getOrNull()?.let { add(it) }
        }
        anchors.forEach { anchor ->
            var dir: File? = anchor
            while (dir != null) {
                val candidate = File(dir, "dshbox-plugins/assets/plugins")
                if (candidate.isDirectory) return candidate
                dir = dir.parentFile
            }
        }
        error("找不到 dshbox-plugins/assets/plugins（锚点：$anchors）")
    }

    private companion object {
        val PILOT_PACK_FILES = listOf(
            "mobile-pilot/install.sh",
            "mobile-pilot/uninstall.sh",
            "mobile-pilot/plugin/package.json",
            "mobile-pilot/plugin/cordis.patch.yml",
            "mobile-pilot/plugin/lib/index.js",
            "mobile-pilot/plugin/lib/mount.js",
            "mobile-pilot/plugin/lib/pilot.js",
            "mobile-pilot/plugin/lib/tools.js",
            "mobile-pilot/plugin/bin/mobile-pilot",
        )
    }
}
