package com.dshbox.pluginmanager.market.data

import com.dshbox.pluginmanager.market.model.HostCompatibility
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 宿主版本探测与宿主要求解析。
 *
 * 这一组用例覆盖的是"兼容性恒为 UNKNOWN"这个症状的两个成因：
 * 1. **候选路径不对**：DSH 的产品版本在 `runtime-current/dsh/node_modules/
 *    @deepseek-ai/dsh/package.json`（`dsh` 层根部的 package.json 只是构建桩），
 *    少探这一层就永远读不到宿主版本；
 * 2. **BOM**：随包的桩清单带 UTF-8 BOM，`org.json` 会直接抛异常。
 *
 * 另外钉住"取不到声明"与"声明为空"的区别：前者是 UNAVAILABLE（我们没读到），
 * 后者才是 UNDECLARED（插件没说）。
 */
class HostCompatCheckerTest {

    private val roots = mutableListOf<File>()

    private fun tempDir(): File =
        Files.createTempDirectory("host-compat-test").toFile().also { roots.add(it) }

    @After
    fun cleanUp() {
        roots.forEach { runCatching { it.deleteRecursively() } }
    }

    private fun writeFile(path: String, text: String, root: File): File {
        val file = File(root, path)
        file.parentFile?.mkdirs()
        file.writeText(text, Charsets.UTF_8)
        return file
    }

    // ── 候选路径 ────────────────────────────────────────────────────

    @Test
    fun candidateOrderPrefersTheRealDshLayerPackage() {
        val files = File("/files")
        val nodeModules = File("/files/user-data/.dsh/profiles/web/node_modules")
        val candidates = hostVersionCandidates(files, nodeModules).map { it.path.replace('\\', '/') }
        assertEquals(
            listOf(
                "/files/runtime/runtime-current/dsh/node_modules/@deepseek-ai/dsh/package.json",
                "/files/runtime/runtime-current/dsh/.dshbox/version",
                "/files/runtime/runtime-current/node_modules/@deepseek-ai/dsh/package.json",
                "/files/user-data/.dsh/profiles/web/node_modules/@deepseek-ai/dsh/package.json",
            ),
            candidates,
        )
    }

    @Test
    fun hostVersionIsFoundInTheAppPlainTextRecord() {
        val root = tempDir()
        writeFile("runtime/runtime-current/dsh/.dshbox/version", "0.1.6\n", root)
        val checker = HostCompatChecker(root, File(root, "nm"), { null })
        assertEquals("0.1.6", checker.hostVersion())
    }

    @Test
    fun plainTextVersionRecordRejectsNonVersionContent() {
        val root = tempDir()
        writeFile("runtime/runtime-current/dsh/.dshbox/version", "not-a-version", root)
        assertNull(HostCompatChecker(root, File(root, "nm"), { null }).hostVersion())
    }

    @Test
    fun thePlainTextRecordOutranksTheOtherLayouts() {
        val root = tempDir()
        writeFile("runtime/runtime-current/dsh/.dshbox/version", "0.1.6", root)
        writeFile(
            "runtime/runtime-current/node_modules/@deepseek-ai/dsh/package.json",
            """{"name":"@deepseek-ai/dsh","version":"0.2.0"}""",
            root,
        )
        assertEquals("0.1.6", HostCompatChecker(root, File(root, "nm"), { null }).hostVersion())
    }

    @Test
    fun theBuildStubManifestIsNotAVersionSource() {
        // dsh 层根部的 package.json 只是构建桩（name = "dsh-layer"），
        // 必被名字校验拒绝；它不再是候选，留着就是死代码。
        val root = tempDir()
        writeFile(
            "runtime/runtime-current/dsh/package.json",
            """{"name":"dsh-layer","version":"9.9.9"}""",
            root,
        )
        assertNull(HostCompatChecker(root, File(root, "nm"), { null }).hostVersion())
    }

    @Test
    fun hostVersionIsFoundInTheDshLayerLayout() {
        val root = tempDir()
        writeFile(
            "runtime/runtime-current/dsh/node_modules/@deepseek-ai/dsh/package.json",
            """{"name":"@deepseek-ai/dsh","version":"0.1.5"}""",
            root,
        )
        val checker = HostCompatChecker(root, File(root, "nm"), { null })
        assertEquals("0.1.5", checker.hostVersion())
    }

    @Test
    fun hostVersionIsFoundInTheRuntimeNodeModulesLayout() {
        val root = tempDir()
        writeFile(
            "runtime/runtime-current/node_modules/@deepseek-ai/dsh/package.json",
            """{"name":"@deepseek-ai/dsh","version":"0.2.0"}""",
            root,
        )
        val checker = HostCompatChecker(root, File(root, "nm"), { null })
        assertEquals("0.2.0", checker.hostVersion())
    }

    @Test
    fun hostVersionIsFoundInTheProfileNodeModules() {
        val root = tempDir()
        val nm = File(root, "nm")
        writeFile("@deepseek-ai/dsh/package.json", """{"name":"@deepseek-ai/dsh","version":"0.3.0"}""", nm)
        val checker = HostCompatChecker(root, nm, { null })
        assertEquals("0.3.0", checker.hostVersion())
    }

    @Test
    fun hostVersionReadsThroughAUtf8Bom() {
        val root = tempDir()
        writeFile(
            "runtime/runtime-current/dsh/node_modules/@deepseek-ai/dsh/package.json",
            "\uFEFF" + """{"name":"@deepseek-ai/dsh","version":"0.1.5-rc.3"}""",
            root,
        )
        val checker = HostCompatChecker(root, File(root, "nm"), { null })
        assertEquals("0.1.5-rc.3", checker.hostVersion())
    }

    @Test
    fun hostVersionIsNullWhenNothingIsInstalled() {
        val root = tempDir()
        assertNull(HostCompatChecker(root, File(root, "nm"), { null }).hostVersion())
    }

    @Test
    fun aDifferentPackageDoesNotProvideTheHostVersion() {
        val root = tempDir()
        writeFile(
            "runtime/runtime-current/dsh/node_modules/@deepseek-ai/dsh/package.json",
            """{"name":"some-other-package","version":"9.9.9"}""",
            root,
        )
        assertNull(HostCompatChecker(root, File(root, "nm"), { null }).hostVersion())
    }

    // ── 声明解析与判定 ──────────────────────────────────────────────

    @Test
    fun missingManifestIsUnavailableNotUndeclared() {
        val root = tempDir()
        val checker = HostCompatChecker(root, File(root, "nm"), { null })
        assertNull("清单不存在必须返回 null", checker.requirementsFor("pkg"))
        val verdict = checker.check(listOf("pkg")).getValue("pkg")
        assertEquals(HostCompatibility.Status.UNKNOWN, verdict.status)
        assertEquals(
            "取不到信息是 UNAVAILABLE，不是「插件未声明」",
            HostCompatibility.Basis.UNAVAILABLE,
            verdict.basis,
        )
    }

    @Test
    fun readableManifestWithoutDeclarationsIsUndeclared() {
        val root = tempDir()
        val manifest = writeFile("pkg/package.json", """{"name":"pkg","version":"1.0.0"}""", root)
        val checker = HostCompatChecker(root, File(root, "nm"), { manifest })
        assertEquals(emptyList<HostRequirement>(), checker.requirementsFor("pkg"))
        val verdict = checker.check(listOf("pkg")).getValue("pkg")
        assertEquals(HostCompatibility.Status.UNKNOWN, verdict.status)
        assertEquals(HostCompatibility.Basis.UNDECLARED, verdict.basis)
    }

    @Test
    fun unreadableManifestIsUnavailable() {
        val root = tempDir()
        val manifest = writeFile("pkg/package.json", "{ not json", root)
        val checker = HostCompatChecker(root, File(root, "nm"), { manifest })
        assertNull(checker.requirementsFor("pkg"))
        assertEquals(
            HostCompatibility.Basis.UNAVAILABLE,
            checker.check(listOf("pkg")).getValue("pkg").basis,
        )
    }

    @Test
    fun compatibleAndIncompatibleVerdictsUseTheDiscoveredHostVersion() {
        val root = tempDir()
        writeFile(
            "runtime/runtime-current/dsh/node_modules/@deepseek-ai/dsh/package.json",
            """{"name":"@deepseek-ai/dsh","version":"0.1.5"}""",
            root,
        )
        val ok = writeFile("ok/package.json", """{"name":"ok","engines":{"dsh":">=0.1.0"}}""", root)
        val bad = writeFile("bad/package.json", """{"name":"bad","engines":{"dsh":">=0.9.0"}}""", root)
        val checker = HostCompatChecker(root, File(root, "nm")) { name ->
            when (name) {
                "ok" -> ok
                "bad" -> bad
                else -> null
            }
        }
        val verdicts = checker.check(listOf("ok", "bad", "absent"))
        assertEquals(HostCompatibility.Status.COMPATIBLE, verdicts.getValue("ok").status)
        assertEquals(HostCompatibility.Status.INCOMPATIBLE, verdicts.getValue("bad").status)
        assertEquals(HostCompatibility.Basis.UNAVAILABLE, verdicts.getValue("absent").basis)
    }

    @Test
    fun parseHostRequirementsReadsBothEnginePlacements() {
        val top = parseHostRequirements("""{"engines":{"dsh":"^0.1.0"}}""")
        assertEquals(1, top.size)
        assertEquals(HostRequirement.Kind.ENGINE, top.single().kind)
        assertEquals("^0.1.0", top.single().range)

        val nested = parseHostRequirements("""{"dsh":{"engines":{"dsh":">=0.1.5"}}}""")
        assertEquals(1, nested.size)
        assertEquals(">=0.1.5", nested.single().range)
    }

    @Test
    fun parseHostRequirementsKeepsOnlyHostPeerDeclarations() {
        val requirements = parseHostRequirements(
            """
            {"peerDependencies":{
              "@deepseek-ai/dsh":"^0.1.0",
              "@deepseek-ai/dsh-web":"^0.1.0",
              "react":">=18"
            }}
            """.trimIndent(),
        )
        assertEquals(2, requirements.size)
        assertTrue(requirements.all { it.kind == HostRequirement.Kind.PEER })
        assertTrue(requirements.none { it.packageName == "react" })
    }

    @Test
    fun judgeDistinguishesUnavailableFromUndeclared() {
        assertEquals(
            HostCompatibility.Basis.UNAVAILABLE,
            HostCompatJudge.judge(null, "0.1.5").basis,
        )
        assertEquals(
            HostCompatibility.Basis.UNDECLARED,
            HostCompatJudge.judge(emptyList(), "0.1.5").basis,
        )
        assertEquals(
            HostCompatibility.Status.UNKNOWN,
            HostCompatJudge.judge(null, "0.1.5").status,
        )
    }
}
