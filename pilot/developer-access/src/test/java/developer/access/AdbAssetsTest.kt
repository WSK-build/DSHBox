package developer.access

import developer.access.adb.AdbAssets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 解包命令的形态与结论标记解析。
 *
 * 守的是两条硬约束：**必须保留符号链接的解包方式**（用 dpkg-deb + tar，不是 dpkg -x），
 * 以及**最后一定要移除 deb 自带的 `/usr/bin/adb` 软链**——否则"关闭即不可见"不成立。
 */
class AdbAssetsTest {

    @Test
    fun `解包命令逐个 deb 且不做 chown`() {
        val command = AdbAssets.unpackCommand(listOf("/tmp/a.deb", "/tmp/b.deb"))
        assertTrue(command.contains("dpkg-deb --fsys-tarfile '/tmp/a.deb' | tar -x --no-same-owner -p -C /"))
        assertTrue(command.contains("dpkg-deb --fsys-tarfile '/tmp/b.deb' | tar -x --no-same-owner -p -C /"))
        assertFalse(command.contains("dpkg -x"))
    }

    @Test
    fun `解包命令末尾移除 usr-bin-adb 软链并输出结论标记`() {
        val command = AdbAssets.unpackCommand(listOf("/tmp/a.deb"))
        assertTrue(command.endsWith("; rm -f /usr/bin/adb; echo DEVMODE_UNPACK=\$ok"))
    }

    @Test
    fun `含单引号的路径被转义`() {
        val command = AdbAssets.unpackCommand(listOf("/tmp/it's.deb"))
        assertTrue(command.contains("'/tmp/it'\\''s.deb'"))
    }

    @Test
    fun `结论标记为 1 才算成功`() {
        assertTrue(AdbAssets.parseUnpackResult("noise\nDEVMODE_UNPACK=1"))
        assertFalse(AdbAssets.parseUnpackResult("noise\nDEVMODE_UNPACK=0"))
        assertFalse(AdbAssets.parseUnpackResult(""))
    }

    @Test
    fun `多行标记取最后一行`() {
        assertTrue(AdbAssets.parseUnpackResult("DEVMODE_UNPACK=0\nDEVMODE_UNPACK=1"))
        assertFalse(AdbAssets.parseUnpackResult("DEVMODE_UNPACK=1\nDEVMODE_UNPACK=0"))
    }

    @Test
    fun `资产清单顺序为先依赖后主程序`() {
        val files = AdbAssets.DEB_FILES
        assertEquals("adb_35.0.2-1~exp7_arm64.deb", files.last())
        assertEquals(9, files.size)
    }
}
