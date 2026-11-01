package com.dshbox.pluginmanager.market.data

import com.dshbox.pluginmanager.market.model.ActivationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 激活状态判定表。
 *
 * 判定顺序本身就是规格的一部分：开关状态压过一切，其次是文件在不在，
 * 再次是有没有 dsh 元数据，最后才是 bundle 层。顺序错了，用户看到的
 * 原因就会是次要的那一个。
 */
class ActivationClassifierTest {

    private fun facts(
        name: String = "pkg",
        installed: Boolean = true,
        manifestReadable: Boolean = true,
        hasDshField: Boolean = true,
        hasBundle: Boolean = false,
        hasClient: Boolean = false,
        // 默认"声明了就有产物"：这是正常装好的形态。要测"声明了但产物不在"
        // 显式覆盖成 false。
        bundleEntryExists: Boolean = hasBundle,
        clientEntryExists: Boolean = hasClient,
        declaredLibrary: Boolean = false,
    ) = PackageFacts(
        name = name,
        spec = "^1.0.0",
        version = "1.0.0",
        installed = installed,
        manifestReadable = manifestReadable,
        hasDshField = hasDshField,
        hasBundle = hasBundle,
        hasClient = hasClient,
        bundleEntryExists = bundleEntryExists,
        clientEntryExists = clientEntryExists,
        declaredLibrary = declaredLibrary,
    )

    @Test
    fun marketDisableIsReportedAsPending() {
        val verdict = ActivationClassifier.classify(
            facts(), inBundles = true, disabled = true, restartPending = true,
        )
        assertEquals(ActivationState.DISABLED_PENDING, verdict.state)
        assertTrue("停用也要如实说明何时生效", verdict.reasons.single().contains("重启"))
    }

    @Test
    fun alreadyDisabledStaysDisabled() {
        val verdict = ActivationClassifier.classify(facts(), inBundles = true, disabled = true)
        assertEquals(ActivationState.DISABLED, verdict.state)
    }

    @Test
    fun disabledWinsOverEverything() {
        val verdict = ActivationClassifier.classify(facts(installed = false), inBundles = true, disabled = true)
        assertEquals(ActivationState.DISABLED, verdict.state)
    }

    @Test
    fun declaredLibraryIsInertForOthersUse() {
        val verdict = ActivationClassifier.classify(facts(declaredLibrary = true), inBundles = true, disabled = false)
        assertEquals(ActivationState.LIBRARY, verdict.state)
        assertTrue(verdict.reasons.single().contains("供其他插件使用"))
    }

    @Test
    fun disabledDefersToDeclaredLibrary() {
        // 上游同语义：库没有插件面，"已停用"对它没有信息量。
        val verdict = ActivationClassifier.classify(facts(declaredLibrary = true), inBundles = false, disabled = true)
        assertEquals(ActivationState.LIBRARY, verdict.state)
    }

    @Test
    fun disabledStillWinsForNormalPlugins() {
        val verdict = ActivationClassifier.classify(facts(hasBundle = true), inBundles = true, disabled = true)
        assertEquals(ActivationState.DISABLED, verdict.state)
    }

    @Test
    fun dependencyWithoutDirectoryIsMissing() {
        val verdict = ActivationClassifier.classify(facts(installed = false), inBundles = false, disabled = false)
        assertEquals(ActivationState.MISSING, verdict.state)
    }

    @Test
    fun unreadableManifestDoesNotClaimPluginOrDependency() {
        val verdict = ActivationClassifier.classify(facts(manifestReadable = false), inBundles = false, disabled = false)
        assertEquals(ActivationState.INERT, verdict.state)
        assertTrue(verdict.reasons.single().contains("读不出来"))
    }

    @Test
    fun plainDependencyIsInert() {
        val verdict = ActivationClassifier.classify(facts(hasDshField = false), inBundles = false, disabled = false)
        assertEquals(ActivationState.INERT, verdict.state)
    }

    @Test
    fun declaredPluginInBundleLayerIsActive() {
        // 安装与 DSH 自身的开关都即时生效，只有市场自己刚拨过的那次才要重启。
        val verdict = ActivationClassifier.classify(facts(), inBundles = true, disabled = false)
        assertEquals(ActivationState.LIVE, verdict.state)
    }

    @Test
    fun marketToggleIsReportedAsPendingRestart() {
        val verdict = ActivationClassifier.classify(
            facts(), inBundles = true, disabled = false, restartPending = true,
        )
        assertEquals(ActivationState.RESTART, verdict.state)
        assertTrue(verdict.reasons.single().contains("重启"))
    }

    @Test
    fun declaredPluginOutsideBundleLayerIsBroken() {
        val verdict = ActivationClassifier.classify(facts(), inBundles = false, disabled = false)
        assertEquals(ActivationState.BROKEN, verdict.state)
        assertTrue(verdict.reasons.single().contains("dsh.profile.bundles"))
    }

    @Test
    fun clientOnlyPluginIsInertNotBroken() {
        // `dsh.profile.bundles` 是**宿主侧** loader 的组合清单；只声明了
        // `dsh.client` 的插件由客户端侧加载，不应出现在里面。
        // 把它判成 BROKEN 等于把正常插件说成坏的。
        val verdict = ActivationClassifier.classify(
            facts(hasBundle = false, hasClient = true),
            inBundles = false,
            disabled = false,
        )
        assertEquals(ActivationState.INERT, verdict.state)
        assertTrue(verdict.reasons.single().contains("纯客户端插件"))
    }

    @Test
    fun clientOnlyPluginListedInBundlesIsBrokenWithTheBundleReason() {
        // 纯客户端插件被列进 `dsh.profile.bundles`：宿主侧 loader 会去加载一个
        // 不存在的宿主侧入口，重启时直接报错。显示成"已安装，未使用"会掩盖这个
        // 会拖垮启动的状态——必须是 BROKEN，且理由里点名 bundles 与"建议停用"。
        val verdict = ActivationClassifier.classify(
            facts(hasBundle = false, hasClient = true),
            inBundles = true,
            disabled = false,
        )
        assertEquals(ActivationState.BROKEN, verdict.state)
        val reason = verdict.reasons.single()
        assertTrue("理由必须点名 bundles，实际=$reason", reason.contains("dsh.profile.bundles"))
        assertTrue("理由必须给出停用建议，实际=$reason", reason.contains("停用"))
    }

    @Test
    fun clientEntryArtifactMissingIsBrokenNotInert() {
        // 声明了 dsh.client 但产物文件不在：不是"正常的纯客户端插件"，
        // 而是一次不完整的安装（构建脚本被拦是常见成因），加载会失败。
        val verdict = ActivationClassifier.classify(
            facts(hasBundle = false, hasClient = true, clientEntryExists = false),
            inBundles = false,
            disabled = false,
        )
        assertEquals(ActivationState.BROKEN, verdict.state)
        assertTrue(verdict.reasons.single().contains("入口产物"))
    }

    @Test
    fun bundleEntryArtifactMissingIsBrokenEvenInsideBundles() {
        val verdict = ActivationClassifier.classify(
            facts(hasBundle = true, bundleEntryExists = false),
            inBundles = true,
            disabled = false,
        )
        assertEquals(ActivationState.BROKEN, verdict.state)
        assertTrue(verdict.reasons.single().contains("入口产物"))
    }

    @Test
    fun bundleOnlyPluginStillFollowsBundleMembership() {
        val inside = ActivationClassifier.classify(
            facts(hasBundle = true, hasClient = false),
            inBundles = true,
            disabled = false,
        )
        assertEquals(ActivationState.LIVE, inside.state)
        val outside = ActivationClassifier.classify(
            facts(hasBundle = true, hasClient = false),
            inBundles = false,
            disabled = false,
        )
        // 声明与产物都在、只是不在 bundles 里 = 未装配（宿主不会加载它），
        // 上游同形态判 inert——把它说成"坏"会诱导用户去修一个没坏的东西。
        assertEquals(ActivationState.INERT, outside.state)
        assertTrue(outside.reasons.single().contains("dsh.profile.bundles"))
    }

    @Test
    fun dshFieldWithoutAnyEntryArtifactIsBroken() {
        // 有 dsh 字段、bundle/client 都没有、也不在 bundles 里：确实是缺入口。
        val verdict = ActivationClassifier.classify(
            facts(hasBundle = false, hasClient = false),
            inBundles = false,
            disabled = false,
        )
        assertEquals(ActivationState.BROKEN, verdict.state)
    }

    @Test
    fun bundleAndClientTogetherFollowBundleMembership() {
        val verdict = ActivationClassifier.classify(
            facts(hasBundle = true, hasClient = true),
            inBundles = false,
            disabled = false,
        )
        // 同上：宿主侧入口齐备、只是不在 bundles = 未装配，不是坏件。
        assertEquals(ActivationState.INERT, verdict.state)
    }

    @Test
    fun liveOnlyComesFromTheBundleLayer() {
        // LIVE 的判据只有一条：在 profile bundle 层里且没有待重启的开关；
        // 其它任何组合都不得声称"已生效"。
        for (installed in listOf(true, false)) {
            for (readable in listOf(true, false)) {
                for (hasDsh in listOf(true, false)) {
                    for (inBundles in listOf(true, false)) {
                        for (disabled in listOf(true, false)) {
                            for (pending in listOf(true, false)) {
                                val verdict = ActivationClassifier.classify(
                                    facts(installed = installed, manifestReadable = readable, hasDshField = hasDsh),
                                    inBundles = inBundles,
                                    disabled = disabled,
                                    restartPending = pending,
                                )
                                val legitimate = inBundles && !disabled && !pending &&
                                    installed && readable && hasDsh
                                assertEquals(
                                    "只有'在 bundle 层且无待生效开关'才可以是 LIVE",
                                    legitimate,
                                    verdict.state == ActivationState.LIVE,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun everyVerdictCarriesAReason() {
        val states = listOf(
            ActivationClassifier.classify(facts(), true, true),
            ActivationClassifier.classify(facts(installed = false), false, false),
            ActivationClassifier.classify(facts(hasDshField = false), false, false),
            ActivationClassifier.classify(facts(hasClient = true), false, false),
            ActivationClassifier.classify(facts(), true, false),
            ActivationClassifier.classify(facts(), false, false),
        )
        for (verdict in states) {
            assertTrue(verdict.reasons.isNotEmpty())
            assertTrue(verdict.reasons.all { it.isNotBlank() })
        }
    }

    // ───────────────── 兼容门禁（INCOMPATIBLE） ─────────────────
    //
    // 这一态解决的是"假承诺"：宿主每次启动都会拒绝的插件，若显示成
    // RESTART（重启后生效），用户会一直重启而永远不生效。

    @Test
    fun incompatibleWhenGateRejectsABundledPlugin() {
        val verdict = ActivationClassifier.classify(
            facts(hasBundle = true),
            inBundles = true,
            disabled = false,
            gateRequirement = "engines.dsh: ^0.1.0",
        )
        assertEquals(ActivationState.INCOMPATIBLE, verdict.state)
        assertTrue("原因里要带出是哪条声明", verdict.reasons.single().contains("engines.dsh: ^0.1.0"))
    }

    @Test
    fun incompatibleOnlyAppliesToBundledPlugins() {
        // 不在 bundles 里的包宿主不会加载，判"不兼容"没有意义。
        val verdict = ActivationClassifier.classify(
            facts(hasBundle = true),
            inBundles = false,
            disabled = false,
            gateRequirement = "engines.dsh: ^0.1.0",
        )
        assertEquals(ActivationState.INERT, verdict.state)
    }

    @Test
    fun disabledStillWinsOverIncompatible() {
        val verdict = ActivationClassifier.classify(
            facts(hasBundle = true),
            inBundles = true,
            disabled = true,
            gateRequirement = "engines.dsh: ^0.1.0",
        )
        assertEquals(ActivationState.DISABLED, verdict.state)
    }

    @Test
    fun brokenEntryArtifactStillWinsOverIncompatible() {
        // 缺产物比"不兼容"更根本：它连加载的机会都没有。
        val verdict = ActivationClassifier.classify(
            facts(hasBundle = true, bundleEntryExists = false),
            inBundles = true,
            disabled = false,
            gateRequirement = "engines.dsh: ^0.1.0",
        )
        assertEquals(ActivationState.BROKEN, verdict.state)
    }

    @Test
    fun noGateVerdictKeepsActive() {
        val verdict = ActivationClassifier.classify(
            facts(hasBundle = true),
            inBundles = true,
            disabled = false,
            gateRequirement = null,
        )
        assertEquals(ActivationState.LIVE, verdict.state)
    }
}

/**
 * 包清单 → 磁盘事实的解析。
 *
 * 抽出来单测是因为 `ProfileReader.facts` 需要 `android.content.Context`
 * （JVM 单测里造不出来），而"`dsh` 字段怎么读"恰恰是判定链的输入。
 */
class PackageFactsParsingTest {

    @Test
    fun readsDshBundleAndClientFlags() {
        val facts = parsePackageFacts(
            name = "pkg",
            spec = "^1.0.0",
            manifestText = """
                {"name":"pkg","version":"1.2.3","dsh":{"bundle":"./dist/bundle.js","client":"./dist/client.js"}}
            """.trimIndent(),
        )
        assertEquals("1.2.3", facts.version)
        assertTrue(facts.hasDshField)
        assertTrue(facts.hasBundle)
        assertTrue(facts.hasClient)
        assertTrue(facts.manifestReadable)
        assertTrue(facts.installed)
    }

    @Test
    fun nullDshFieldsAreNotTreatedAsDeclared() {
        val facts = parsePackageFacts(
            name = "pkg",
            spec = null,
            manifestText = """{"name":"pkg","version":"1.0.0","dsh":{"bundle":null,"client":null}}""",
        )
        assertTrue(facts.hasDshField)
        assertFalse(facts.hasBundle)
        assertFalse(facts.hasClient)
    }

    @Test
    fun missingDshFieldMeansPlainDependency() {
        val facts = parsePackageFacts("pkg", null, """{"name":"pkg","version":"1.0.0"}""")
        assertFalse(facts.hasDshField)
        assertFalse(facts.hasBundle)
        assertFalse(facts.hasClient)
        assertEquals("1.0.0", facts.version)
    }

    @Test
    fun bomPrefixedManifestStillParses() {
        // 随包清单可能带 UTF-8 BOM，org.json 遇到 BOM 会直接抛异常。
        val facts = parsePackageFacts(
            name = "pkg",
            spec = null,
            manifestText = "\uFEFF" + """{"name":"pkg","version":"0.9.0","dsh":{"client":"./c.js"}}""",
        )
        assertEquals("0.9.0", facts.version)
        assertTrue(facts.hasClient)
    }

    @Test
    fun entryExistenceIsResolvedAgainstThePackageDirectory() {
        // 入口是**包自身的 JS 入口**（main / exports），不是 dsh.bundle 里的 patch 文件：
        // dsh.bundle 的值可能是对象（dshmarket 就是 `{patch: "./cordis.patch.yml"}`），
        // 把它当路径字符串去查文件会让每个对象形态的插件都被误判成"没有产物"。
        val facts = parsePackageFacts(
            name = "pkg",
            spec = null,
            manifestText = """{"name":"pkg","main":"lib/index.js","dsh":{"bundle":{"patch":"./cordis.patch.yml"}}}""",
        ) { path -> path == "lib/index.js" }
        assertTrue("对象形态的 dsh.bundle 也算声明", facts.hasBundle)
        assertTrue("main 指向的入口在磁盘上就算有产物", facts.bundleEntryExists)
        assertFalse(facts.hasClient)
        assertTrue(facts.hasUsableEntry())
    }

    @Test
    fun clientEntryComesFromExportsWhenClientIsAnObject() {
        val facts = parsePackageFacts(
            name = "pkg",
            spec = null,
            manifestText = """{"name":"pkg","exports":{"./client":"./client/client.js"},"dsh":{"client":{"inject":["x"],"platform":"web"}}}""",
        ) { path -> path == "client/client.js" }
        assertTrue(facts.hasClient)
        assertTrue("对象形态的 dsh.client + exports['./client'] 能定位到产物", facts.clientEntryExists)
    }

    @Test
    fun declaredEntriesWithoutFilesAreNotUsable() {
        val facts = parsePackageFacts(
            name = "pkg",
            spec = null,
            manifestText = """{"name":"pkg","main":"lib/index.js","dsh":{"bundle":"./dist/bundle.js"}}""",
        )
        assertTrue(facts.hasBundle)
        assertFalse("main 与兜底路径都不在，就是没有产物", facts.bundleEntryExists)
        assertFalse(facts.hasUsableEntry())
    }

    @Test
    fun declaredLibraryRequiresKindAndNoSurface() {
        val library = parsePackageFacts(
            name = "pkg",
            spec = null,
            manifestText = """{"name":"pkg","dsh":{"kind":"library"}}""",
        )
        assertTrue(library.declaredLibrary)
        assertTrue(library.hasDshField)

        // 有插件面就不再是"声明的库"：分类器按插件面继续判定。
        val withBundle = parsePackageFacts(
            name = "pkg",
            spec = null,
            manifestText = """{"name":"pkg","dsh":{"kind":"library","bundle":"./dist/bundle.js"}}""",
        )
        assertFalse(withBundle.declaredLibrary)

        // 上游按"键不存在"判定：显式 null 的 bundle 同样不是声明库。
        val withNullBundle = parsePackageFacts(
            name = "pkg",
            spec = null,
            manifestText = """{"name":"pkg","dsh":{"kind":"library","bundle":null}}""",
        )
        assertFalse(withNullBundle.declaredLibrary)

        // 其他 kind 取值与缺 kind 都不是库。
        assertFalse(
            parsePackageFacts(
                name = "pkg",
                spec = null,
                manifestText = """{"name":"pkg","dsh":{"kind":"plugin"}}""",
            ).declaredLibrary,
        )
        assertFalse(
            parsePackageFacts(
                name = "pkg",
                spec = null,
                manifestText = """{"name":"pkg","dsh":{}}""",
            ).declaredLibrary,
        )
    }
}
