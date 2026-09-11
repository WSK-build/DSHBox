package com.dshbox.app.ui.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * `prepareSettingsDocument` 单测（1.3.1 M14 的核心逻辑）。
 *
 * 背景：上游 `openSettingsDocument()` 的第一步是 `settings.prepareDocument()`
 * （`mkdir` + 以 `"wx"` 独占建一个空文件），而本项目的插件把整个调用拦掉了
 * （改走 `dshbox://` 通道），于是那一步在上游**永远不会执行** —— 原生侧必须自己补齐，
 * 否则全新安装（或从未写过设置的设备）点「打开配置文件」只会得到「未找到」。
 *
 * 这里锁住三件事：**缺失时创建**、**已存在时绝不覆盖**、**创建失败时返回 null**。
 */
class PrepareSettingsDocumentTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("windows")

    /** 全新设备：文件不存在 → 应创建（含父目录）并返回绝对路径。 */
    @Test
    fun createsMissingDocumentWithParentDirs() {
        val filesDir = tmp.newFolder("files")
        val path = prepareSettingsDocument(filesDir)

        assertNotNull("应返回可用路径", path)
        assertEquals(
            File(filesDir, SETTINGS_DOCUMENT_RELATIVE_PATH).absolutePath,
            path,
        )
        val file = File(path!!)
        assertTrue("文件应被创建", file.isFile)
        assertEquals("内容应为空（与上游 prepareDocument 一致）", "", file.readText())
        assertTrue("父目录应存在", file.parentFile!!.isDirectory)
    }

    /**
     * **已存在时不得覆盖**：这是最重要的一条 —— 用户可能已经写好配置，
     * 点一次「打开配置文件」绝不能把内容清空。
     */
    @Test
    fun keepsExistingContentUntouched() {
        val filesDir = tmp.newFolder("files")
        val target = File(filesDir, SETTINGS_DOCUMENT_RELATIVE_PATH).apply {
            parentFile!!.mkdirs()
            writeText("llm-pi-ai:\n  providers:\n    xiaomi:\n      models: []\n")
        }
        val before = target.readText()
        val beforeModified = target.lastModified()

        val path = prepareSettingsDocument(filesDir)

        assertEquals("路径应指向同一文件", target.absolutePath, path)
        assertEquals("内容必须原样保留", before, target.readText())
        assertEquals("不应触碰修改时间", beforeModified, target.lastModified())
    }

    /** 目录不可写（用同名文件占住父路径）→ 创建失败，应返回 null 而非抛异常。 */
    @Test
    fun returnsNullWhenCreationFails() {
        val filesDir = tmp.newFolder("files")
        // 让 user-data 成为一个**普通文件**，mkdirs() 必然失败
        File(filesDir, "user-data").writeText("not a directory")

        val path = prepareSettingsDocument(filesDir)

        assertEquals("创建失败应返回 null（调用方据此提示「未找到」）", null, path)
    }

    /** 重复调用应幂等：第二次不改变内容，且仍返回同一路径。 */
    @Test
    fun repeatedCallsAreIdempotent() {
        val filesDir = tmp.newFolder("files")
        val first = prepareSettingsDocument(filesDir)
        val second = prepareSettingsDocument(filesDir)
        assertEquals(first, second)
    }

    /**
     * 创建出的文件权限应收紧到「仅属主可读写」（与上游 `0o600` 对齐）。
     * POSIX 才有意义，Windows 跳过。
     */
    @Test
    fun createdFileIsOwnerOnly() {
        if (isWindows) return
        val filesDir = tmp.newFolder("files")
        val path = prepareSettingsDocument(filesDir)!!
        val file = File(path)

        val perms = runCatching {
            java.nio.file.Files.getPosixFilePermissions(file.toPath())
        }.getOrNull() ?: return

        val group = perms.filter { it.name.startsWith("GROUP_") }
        val others = perms.filter { it.name.startsWith("OTHERS_") }
        assertTrue("同组不应有权限: $perms", group.isEmpty())
        assertTrue("其他用户不应有权限: $perms", others.isEmpty())
        assertTrue("属主应可读: $perms", perms.any { it.name == "OWNER_READ" })
        assertTrue("属主应可写: $perms", perms.any { it.name == "OWNER_WRITE" })
        assertFalse("属主不应可执行: $perms", perms.any { it.name == "OWNER_EXECUTE" })
    }

    /** 路径常量必须落在工作区内（FileProvider 授权根 + 内置查看器可达）。 */
    @Test
    fun relativePathStaysInsideWorkspace() {
        assertEquals("user-data/.dsh/settings.yaml", SETTINGS_DOCUMENT_RELATIVE_PATH)
        assertFalse("不得以 / 开头（必须是相对 filesDir 的路径）",
            SETTINGS_DOCUMENT_RELATIVE_PATH.startsWith("/"))
        assertFalse("不得含 .. 穿越", SETTINGS_DOCUMENT_RELATIVE_PATH.contains(".."))
    }
}
