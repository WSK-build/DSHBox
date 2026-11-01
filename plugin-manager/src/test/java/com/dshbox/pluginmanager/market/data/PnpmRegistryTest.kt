package com.dshbox.pluginmanager.market.data

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 镜像源覆盖文件取数点的文件系统接线。
 *
 * 解析规则本身由 [com.dshbox.pluginmanager.contract.ResolveRegistryBaseTest] 钉死；
 * 这里只验证读文件这条路径：缺失、读不出、目录、坏内容都必须落到 null，
 * 由装配点回退官方——不得把"读不出"说成已配置。
 */
class PnpmRegistryTest {

    private fun tempFile(content: String?): java.io.File {
        val file = Files.createTempFile("pnpm-registry", ".tmp").toFile()
        content?.let { file.writeText(it, Charsets.UTF_8) } ?: file.delete()
        return file
    }

    @Test
    fun `文件缺失与读不出一律 null`() {
        assertNull(PnpmRegistry.readBase(tempFile(null)))
        assertNull(PnpmRegistry.readBase(Files.createTempDirectory("reg").toFile()))
    }

    @Test
    fun `空文件与坏内容 null，合法内容出基址`() {
        assertNull(PnpmRegistry.readBase(tempFile("")))
        assertNull(PnpmRegistry.readBase(tempFile("\n")))
        assertNull(PnpmRegistry.readBase(tempFile("not a url")))
        assertEquals(
            "https://mirrors.cloud.tencent.com/npm",
            PnpmRegistry.readBase(tempFile("https://mirrors.cloud.tencent.com/npm\n")),
        )
    }
}
