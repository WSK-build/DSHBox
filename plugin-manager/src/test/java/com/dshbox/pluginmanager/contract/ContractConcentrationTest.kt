package com.dshbox.pluginmanager.contract

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **契约收敛的元测试**：防止宿主契约知识重新散落回业务文件。
 *
 * ## 用途
 *
 * 本轮改造把散在十几个文件里的宿主事实收敛到了 `contract/` 包。但重构会被
 * 后续补丁侵蚀——在业务文件里直接写一个
 * `Regex("""dsh: disabling profile plugin…""")` 是最容易的修法。
 *
 * 这个测试就是那道闸：**业务代码里不得再出现宿主契约的字面量/正则**，必须
 * 从 [HostContract] / [HostSignals] / [PatchSyntax] 引用。
 *
 * ## 采用源码扫描的原因
 *
 * Kotlin 没有"禁止字面量"的语言机制。源码扫描是这里唯一可行的强制手段，
 * 而且它的失败信息（指出文件与行）正好就是"该去哪儿改"的答案。
 *
 * ## 豁免范围
 *
 * 只扫描 `contract/` 之外的 `main` 源码；`contract/` 自身持有这些字面量。
 * 注释行（`*` / `//`）不算——注释里引用宿主形态是**有意的文档**，不是耦合。
 */
class ContractConcentrationTest {

    /**
     * 被扫描的 `main` 源码根。
     *
     * **必须是绝对路径，且必须断言它真的存在**。原先写成相对路径
     * `File("src/main/java/...")`：Gradle 单测的工作目录默认是模块目录，所以
     * 平时能跑对；但只要工作目录一变（IDE 直跑、从仓库根跑、`--rerun` 的某些
     * 组合），`walkTopDown()` 会**扫到空集合**，于是"不得硬编码"这条断言在
     * 什么都没检查的情况下通过——**静默失效**，比直接报错危险得多。
     *
     * 锚定到 `user.dir` 再定位，并在 `init` 里断言目录存在：工作目录不对时
     * 立刻红，而不是假绿。
     */
    private val mainSrc: File = File(
        System.getProperty("user.dir"),
        "src/main/java/com/dshbox/pluginmanager",
    )

    init {
        assertTrue(
            "扫描根不存在，本次审计无效：${mainSrc.absolutePath}（工作目录=${System.getProperty("user.dir")}）",
            mainSrc.isDirectory,
        )
    }

    /** 业务文件里**不允许**直接出现的宿主契约字面量（应改引契约层）。 */
    private val forbiddenLiterals = listOf(
        // 宿主日志/输出文本
        "dsh: disabling profile plugin",
        "dsh: skipping profile bundle",
        "patch: entry",
        "failed to ",   // loader entry 失败串的开头（配合下一条更精确）
        "entries did not activate",
        // profile 结构字段（应走 HostContract.FIELD_PROFILE_BUNDLES）
        "\"bundles\"",
        // 补丁层与豁免文件名（应走 HostContract.USER_PATCH / COMPATIBILITY / PatchSyntax.CONVENTIONAL_PATCH）
        "\"cordis.patch.yml\"",
        "\"compatibility.json\"",
        // 兼容门禁的两个字段名（应走 HostContract.FIELD_PEER_DEPENDENCIES / APP_BOOT_ENGINES_GATE）
        "\"peerDependencies\"",
        "\"engines.dsh\"",
        // 命名空间（应走 HostContract）
        "\"@deepseek-ai/\"",
        "\"cordis:\"",
        // bundles 字段路径与运行层布局（应走 HostContract.readProfileBundles / 常量）
        ".optJSONObject(\"dsh\")?.optJSONObject(\"profile\")",
        "\"dsh/node_modules\"",
        // 我方随包资产前缀（应走 HostContract.LOCAL_NAMESPACE）
        "\"@local/\"",
        // 插件清单文件名（应走 HostContract.PROFILE_MANIFEST）
        "\"package.json\"",
        // registry 兜底基址（应走 HostContract.DEFAULT_REGISTRY_BASE）
        "https://registry.npmjs.org",
    )

    /**
     * 已知绕过面（**记录在案，不假装防得住**）：字符串拼接
     * （`"dsh: " + "disabling…"`）、raw string 换行断开、模板 `$prefix` 拼接
     * 都能绕过行级扫描。本测试的定位是"拦住顺手硬编码"，不是"防住有意规避"；
     * 有意规避只能靠评审拦截。
     */
    @Test
    fun `业务源码不得直接硬编码宿主契约字面量`() {
        val violations = mutableListOf<String>()
        mainSrc.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.parentFile.name == "contract" }
            .forEach { file ->
                file.readLines().forEachIndexed { index, raw ->
                    val line = raw.trim()
                    // 跳过注释与 KDoc：注释里引用宿主形态是有意的文档。
                    if (line.startsWith("*") || line.startsWith("//") || line.startsWith("/*")) return@forEachIndexed
                    forbiddenLiterals.forEach { literal ->
                        if (line.contains(literal)) {
                            violations += "${file.relativeTo(mainSrc)}:${index + 1} 含 `$literal`"
                        }
                    }
                }
            }
        assertTrue(
            "宿主契约字面量必须收敛到 contract/ 包，以下位置仍直接硬编码：\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun `契约层本身确实持有这些字面量`() {
        // 反向断言：确保上面的豁免不是"因为没人写"而通过——契约层必须真的有它们。
        val contractSrc = File(mainSrc, "contract")
        val all = contractSrc.walkTopDown().filter { it.isFile }.joinToString("\n") { it.readText() }
        assertTrue("HostSignals 应持有宿主拒绝行", all.contains("disabling profile plugin"))
        assertTrue("HostSignals 应持有补丁告警", all.contains("patch: entry"))
        assertTrue("HostContract 应持有官方命名空间", all.contains("@deepseek-ai/"))
        assertTrue("HostContract 应持有 loader 命名空间", all.contains("cordis:"))
        assertTrue("HostContract 应持有 bundles 字段路径", all.contains("bundles"))
        assertTrue("HostContract 应持有补丁层文件名", all.contains("cordis.patch.yml"))
        assertTrue("HostContract 应持有 peer 字段名", all.contains("peerDependencies"))
        assertTrue("HostContract 应持有 engines 门禁标记", all.contains("engines.dsh"))
        assertTrue("HostContract 应持有 registry 兜底基址", all.contains("https://registry.npmjs.org"))
    }

    @Test
    fun `补丁方言正则只在契约层定义`() {
        // 三处（PluginLayer / PackageRowIds / PatchDialect）曾各写一份补丁行正则；
        // 收敛后它们只能引用 PatchSyntax，不得再各自 `Regex("^- id: …")`。
        val violations = mutableListOf<String>()
        // 补丁行语法：这些形状的正则必须只在 PatchSyntax 定义。
        val patterns = listOf("^- id:", "^- insert:", "disabled:", "name:").map { prefix ->
            Regex("""Regex\(""" + "\"\"\"" + Regex.escape(prefix))
        }
        mainSrc.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.parentFile.name == "contract" }
            .forEach { file ->
                file.readLines().forEachIndexed { index, raw ->
                    val line = raw.trim()
                    if (line.startsWith("*") || line.startsWith("//")) return@forEachIndexed
                    if (patterns.any { it.containsMatchIn(line) }) {
                        violations += "${file.relativeTo(mainSrc)}:${index + 1}"
                    }
                }
            }
        assertTrue("补丁行正则必须只定义在 PatchSyntax：\n" + violations.joinToString("\n"), violations.isEmpty())
    }
}
