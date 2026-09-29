package com.dshbox.pluginmanager.market.data

import com.dshbox.app.common.AppResult
import com.dshbox.pluginmanager.core.GuestCommandResult
import com.dshbox.pluginmanager.core.GuestCommandRunner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 安装的「假成功守卫」。
 *
 * 这一组用例钉住两条很容易被当成"过度设计"、但后果很重的规则：
 *
 * 1. **命令行退出码为 0 不等于装出来的包能用**：命令成功之后必须再核对磁盘事实，
 *    不满足就回收。少了这一步，用户看到"安装成功"，重启后发现插件不存在。
 * 2. **校验对象是依赖差集，不是从安装目标猜出来的名字**：仓库名 ≠ 包名时，
 *    从 URL 反推会把一次成功的安装判成失败，回收时还会误删别人的真实依赖。
 */
class InstallGuardTest {

    /** 记录命令的假 runner：按命令里的动词决定成败；`onAdd` 模拟"安装真的改了 profile"。 */
    private class FakeRunner(
        private val addOk: Boolean = true,
        private val removeOk: Boolean = true,
        private val onAdd: () -> Unit = {},
    ) : GuestCommandRunner {
        val commands = mutableListOf<String>()

        override suspend fun run(command: String, onLine: (String) -> Unit): GuestCommandResult {
            commands.add(command)
            onLine("fake: $command")
            return when {
                command.contains(" add ") -> {
                    onAdd()
                    GuestCommandResult(addOk, listOf(if (addOk) "added" else "ERR_PNPM_FETCH_404"))
                }

                command.contains(" remove ") ->
                    GuestCommandResult(removeOk, listOf(if (removeOk) "removed" else "remove failed"))

                else -> GuestCommandResult(true, emptyList())
            }
        }

        fun verbs(): List<String> =
            commands.map { it.substringAfter("plugin --profile 'web' ").substringBefore(" '") }
    }

    /** 伪造的包事实来源（等价于一个伪造的 ProfileReader）。 */
    private class FakeFacts(private val facts: Map<String, PackageFacts>) : PackageFactsSource {
        val queried = mutableListOf<String>()
        override fun facts(name: String, spec: String?): PackageFacts {
            queried.add(name)
            return facts[name] ?: PackageFacts(
                name = name, spec = spec, version = null,
                installed = false, manifestReadable = false,
                hasDshField = false, hasBundle = false, hasClient = false,
            )
        }
    }

    /** 可编程的依赖键集：装前 / 装后各读一次，差集就是本次新增的包。null = 读不出来。 */
    private class FakeDeps(initial: Set<String> = emptySet()) : DependencyNamesSource {
        var names: Set<String>? = initial
        override fun dependencyNames(): Set<String>? = names
    }

    private fun loadable(name: String, hasBundle: Boolean = true, hasClient: Boolean = false) =
        PackageFacts(
            name = name, spec = null, version = "1.0.0",
            installed = true, manifestReadable = true,
            hasDshField = true, hasBundle = hasBundle, hasClient = hasClient,
            // 声明与产物都齐：这是"装好了"的形态。
            bundleEntryExists = hasBundle, clientEntryExists = hasClient,
        )

    private fun guard(runner: FakeRunner, facts: FakeFacts, deps: FakeDeps = FakeDeps()): InstallGuard =
        InstallGuard(GuestPluginOps(runner, "web"), facts, deps)

    @Test
    fun loadablePackagePassesWithoutRollback() = runBlocking {
        val runner = FakeRunner()
        val facts = FakeFacts(mapOf("foo" to loadable("foo")))
        val result = guard(runner, facts).install("foo", "foo", onLine = {})
        assertTrue(result is AppResult.Success)
        assertEquals(listOf("add"), runner.verbs())
    }

    @Test
    fun commandSuccessButPackageMissingIsAFailureAndRollsBack() = runBlocking {
        val deps = FakeDeps()
        // 让 `add` 真的改变依赖键集：只有差集可用时，守卫才被允许回收。
        val runner = FakeRunner(onAdd = { deps.names = setOf("foo") })
        val facts = FakeFacts(emptyMap())
        val result = guard(runner, facts, deps).install("foo", "foo", onLine = {})
        assertTrue("命令成功但包不在，必须判失败", result is AppResult.Failure)
        assertEquals("INSTALL_VERIFY_FAILED", (result as AppResult.Failure).error.code)
        assertTrue(result.error.message.contains("已执行回收"))
        // 回收动作必须真的发出去：半个包装在 profile 里会让下次启动报错。
        assertEquals(listOf("add", "remove"), runner.verbs())
    }

    @Test
    fun unreadableManifestIsAFailure() = runBlocking {
        val deps = FakeDeps()
        val runner = FakeRunner(onAdd = { deps.names = setOf("foo") })
        val facts = FakeFacts(
            mapOf(
                "foo" to PackageFacts(
                    name = "foo", spec = null, version = null,
                    installed = true, manifestReadable = false,
                    hasDshField = false, hasBundle = false, hasClient = false,
                ),
            ),
        )
        val result = guard(runner, facts, deps).install("foo", "foo", onLine = {})
        assertTrue(result is AppResult.Failure)
        assertTrue((result as AppResult.Failure).error.message.contains("package.json 读不出来"))
        assertEquals(listOf("add", "remove"), runner.verbs())
    }

    @Test
    fun packageWithoutDshFieldIsAFailure() = runBlocking {
        val runner = FakeRunner()
        val facts = FakeFacts(
            mapOf(
                "foo" to PackageFacts(
                    name = "foo", spec = null, version = "1.0.0",
                    installed = true, manifestReadable = true,
                    hasDshField = false, hasBundle = false, hasClient = false,
                ),
            ),
        )
        val result = guard(runner, facts).install("foo", "foo", onLine = {})
        assertTrue(result is AppResult.Failure)
        assertTrue((result as AppResult.Failure).error.message.contains("没有 dsh 字段"))
    }

    @Test
    fun dshFieldWithoutEntryArtifactIsAFailure() = runBlocking {
        val runner = FakeRunner()
        val facts = FakeFacts(mapOf("foo" to loadable("foo", hasBundle = false, hasClient = false)))
        val result = guard(runner, facts).install("foo", "foo", onLine = {})
        assertTrue(result is AppResult.Failure)
        assertTrue((result as AppResult.Failure).error.message.contains("没有入口产物"))
    }

    @Test
    fun declaredEntryThatDoesNotExistOnDiskIsAFailure() = runBlocking {
        // 声明了 dsh.bundle，但产物文件不在（构建脚本被 pnpm 拦下的典型形态）：
        // 只看"声明存在"会报成功，重启后 loader 会失败——守卫必须抓住它。
        val deps = FakeDeps()
        val runner = FakeRunner(onAdd = { deps.names = setOf("foo") })
        val facts = FakeFacts(
            mapOf(
                "foo" to loadable("foo").copy(hasBundle = true, bundleEntryExists = false),
            ),
        )
        val result = guard(runner, facts, deps).install("foo", "foo", onLine = {})
        assertTrue("声明了入口但产物不在，必须判失败", result is AppResult.Failure)
        assertTrue((result as AppResult.Failure).error.message.contains("入口产物"))
        assertEquals(listOf("add", "remove"), runner.verbs())
    }

    @Test
    fun clientOnlyPackageIsLoadable() = runBlocking {
        // 纯客户端插件没有宿主侧入口产物，但它是可加载的——守卫不能把它判死。
        val runner = FakeRunner()
        val facts = FakeFacts(mapOf("foo" to loadable("foo", hasBundle = false, hasClient = true)))
        val result = guard(runner, facts).install("foo", "foo", onLine = {})
        assertTrue(result is AppResult.Success)
    }

    @Test
    fun commandFailureIsPassedThroughWithoutRollback() = runBlocking {
        val runner = FakeRunner(addOk = false)
        val facts = FakeFacts(emptyMap())
        val result = guard(runner, facts).install("foo", "foo", onLine = {})
        assertTrue(result is AppResult.Failure)
        // 命令本身失败时不该再去 remove：什么都没装上，多一步只会再报一个错。
        assertEquals(listOf("add"), runner.verbs())
    }

    // ── 差集才是真值：不从安装目标反推包名 ──────────────────────────────

    @Test
    fun repoNameDifferentFromPackageNameStillSucceeds() = runBlocking {
        // 仓库叫 awesome-dsh-plugin，里面的包叫 dsh-market。目录条目的 npm 名与
        // 仓库名不同，从 URL 反推会把这次**成功**的安装判成失败。
        val deps = FakeDeps()
        val runner = FakeRunner(onAdd = { deps.names = setOf("dsh-market") })
        val facts = FakeFacts(mapOf("dsh-market" to loadable("dsh-market")))
        val result = guard(runner, facts, deps).install(
            "github:owner/awesome-dsh-plugin",
            "awesome-dsh-plugin",
            onLine = {},
        )
        assertTrue("差集里的包可加载就该成功，实际=$result", result is AppResult.Success)
        assertEquals(listOf("dsh-market"), facts.queried.distinct())
        assertEquals(listOf("add"), runner.verbs())
    }

    @Test
    fun githubTargetNeverDerivesAPackageNameFromTheRepo() = runBlocking {
        // `github:owner/repo` 推不出包名：目录条目也没有 npm 名 → 保留安装并
        // 如实说"未校验"，绝不去查/删一个猜出来的 `repo`（它可能是别人的依赖）。
        val runner = FakeRunner()
        val facts = FakeFacts(mapOf("repo" to loadable("repo")))
        val lines = mutableListOf<String>()
        val result = guard(runner, facts).install("github:owner/repo#main", "", onLine = { lines.add(it) })
        assertTrue(result is AppResult.Success)
        assertTrue("不该按仓库名去查任何包", facts.queried.isEmpty())
        assertTrue(lines.any { it.contains("未校验") })
        assertEquals(listOf("add"), runner.verbs())
    }

    @Test
    fun rollbackOnlyTouchesTheNewlyAddedPackage() = runBlocking {
        // 装前已经有 real-dep（别人的真实依赖），本次新增的是 new-broken。
        // 回收**只能**动 new-broken：对 real-dep 执行 remove 会把别人的包删了。
        val deps = FakeDeps(setOf("real-dep"))
        val runner = FakeRunner(onAdd = { deps.names = setOf("real-dep", "new-broken") })
        val facts = FakeFacts(mapOf("real-dep" to loadable("real-dep")))
        val result = guard(runner, facts, deps).install("new-broken", "real-dep", onLine = {})
        assertTrue(result is AppResult.Failure)
        assertTrue("只应查过新增的 new-broken，实际=${facts.queried}", facts.queried.all { it == "new-broken" })
        assertEquals(listOf("add", "remove"), runner.verbs())
        assertTrue(runner.commands.any { it.contains("remove 'new-broken'") })
        assertFalse("绝不能对 real-dep 执行 remove", runner.commands.any { it.contains("remove 'real-dep'") })
    }

    @Test
    fun unrelatedLoadablePackageCannotStandInForTheAddedOne() = runBlocking {
        // 旧实现"候选任一通过即通过"：一个无关的同名可加载包能顶替真正新增的
        // 坏包，把守卫绕过去。差集判定下，new-broken 坏就是坏。
        val deps = FakeDeps()
        val runner = FakeRunner(onAdd = { deps.names = setOf("new-broken") })
        val facts = FakeFacts(mapOf("unrelated" to loadable("unrelated")))
        val result = guard(runner, facts, deps).install("new-broken", "unrelated", onLine = {})
        assertTrue("新增的包坏了就必须失败", result is AppResult.Failure)
        assertTrue(runner.commands.any { it.contains("remove 'new-broken'") })
        assertFalse(runner.commands.any { it.contains("remove 'unrelated'") })
    }

    @Test
    fun aliasInstallIsVerifiedUnderTheDependencyKeyNotTheTarget() = runBlocking {
        val deps = FakeDeps()
        val runner = FakeRunner(onAdd = { deps.names = setOf("alias") })
        val facts = FakeFacts(emptyMap())
        val result = guard(runner, facts, deps).install("npm:real-pkg@1.0.0", "alias", onLine = {})
        assertTrue(result is AppResult.Failure)
        // 校验与回收都用**依赖键**（alias），而不是从安装目标里拆出来的 real-pkg。
        assertEquals(listOf("alias"), facts.queried.distinct())
        assertTrue(runner.commands.any { it.contains("remove 'alias'") })
    }

    @Test
    fun unreadableDependencyListFallsBackToTheDeclaredName() = runBlocking {
        val deps = FakeDeps().apply { names = null }
        val runner = FakeRunner()
        val facts = FakeFacts(mapOf("foo" to loadable("foo")))
        val result = guard(runner, facts, deps).install("foo", "foo", onLine = {})
        assertTrue(result is AppResult.Success)
        assertTrue(facts.queried.contains("foo"))
    }

    @Test
    fun unreadableDependencyListAndNoDeclaredNameStaysUnverified() = runBlocking {
        val deps = FakeDeps().apply { names = null }
        val runner = FakeRunner()
        val facts = FakeFacts(emptyMap())
        val lines = mutableListOf<String>()
        val result = guard(runner, facts, deps).install("weird-target", "", onLine = { lines.add(it) })
        // 推不出包名 ≠ 安装失败：保留安装、如实说"未校验"，也不 remove 任何名字。
        assertTrue(result is AppResult.Success)
        assertTrue(lines.any { it.contains("未校验") })
        assertEquals(listOf("add"), runner.verbs())
    }

    @Test
    fun declaredNameIsUsedAsFallbackWhenNothingNewWasAdded() = runBlocking {
        // 差集为空（重装同一版本）：退回目录条目的 npm 名去校验。
        val deps = FakeDeps(setOf("catalog-name"))
        val runner = FakeRunner()
        val facts = FakeFacts(mapOf("catalog-name" to loadable("catalog-name")))
        val result = guard(runner, facts, deps).install(
            "https://example.com/pkg-1.0.0.tgz",
            "catalog-name",
            onLine = {},
        )
        assertTrue(result is AppResult.Success)
        assertTrue(facts.queried.contains("catalog-name"))
    }

    @Test
    fun unverifiableTargetStaysInstalledAndSaysSo() = runBlocking {
        val runner = FakeRunner()
        val facts = FakeFacts(emptyMap())
        val lines = mutableListOf<String>()
        val result = guard(runner, facts).install("#", "", onLine = { lines.add(it) })
        // 推不出包名 ≠ 安装失败：tarball 直链这类目标本来就还原不出包名，
        // 报失败会让一次**成功**的安装看起来没装上，用户会反复重试。
        assertTrue(result is AppResult.Success)
        // 必须如实说明"没校验"，也不能瞎猜一个名字去 remove（那可能删掉别的包）。
        assertTrue(lines.any { it.contains("未校验") })
        assertEquals(listOf("add"), runner.verbs())
    }

    @Test
    fun rollbackFailureIsReportedInTheMessage() = runBlocking {
        val runner = FakeRunner(removeOk = false)
        val facts = FakeFacts(emptyMap())
        val result = guard(runner, facts).install("foo", "foo", onLine = {})
        assertTrue(result is AppResult.Failure)
        assertTrue((result as AppResult.Failure).error.message.contains("回收"))
    }

    @Test
    fun failureMessageKeepsExactlyOneBilingualSeparator() {
        // 双语串只能有一个 ` / `：嵌套双语会被 localizeBilingual 切错，
        // 用户可能拿到"中文 / 中文"。
        val runner = FakeRunner()
        val facts = FakeFacts(emptyMap())
        val message = runBlocking { guard(runner, facts).install("foo", "foo", onLine = {}) }
            .let { (it as AppResult.Failure).error.message }
        assertEquals(1, Regex(" / ").findAll(message).count())
    }

    @Test
    fun installGapCoversEveryLoadabilityRequirement() {
        assertNull(installGap(loadable("foo")))
        assertNotNull(installGap(loadable("foo").copy(installed = false)))
        assertNotNull(installGap(loadable("foo").copy(manifestReadable = false)))
        assertNotNull(installGap(loadable("foo").copy(hasDshField = false)))
        assertNotNull(installGap(loadable("foo").copy(hasBundle = false)))
        // 声明存在 ≠ 产物存在。
        assertNotNull(installGap(loadable("foo").copy(bundleEntryExists = false)))
        assertNull(installGap(loadable("foo", hasBundle = false, hasClient = true)))
        assertNotNull(
            installGap(loadable("foo", hasBundle = false, hasClient = true).copy(clientEntryExists = false)),
        )
    }

    @Test
    fun declaredPackageNameAcceptsOnlyPackageShapes() {
        assertEquals("foo", declaredPackageName("foo"))
        assertEquals("@scope/foo", declaredPackageName(" @scope/foo "))
        assertNull(declaredPackageName(""))
        assertNull(declaredPackageName("   "))
        assertNull(declaredPackageName("not a package name"))
        assertNull(declaredPackageName("github:owner/repo"))
    }
}
