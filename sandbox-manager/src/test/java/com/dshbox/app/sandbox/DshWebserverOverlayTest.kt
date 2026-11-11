package com.dshbox.app.sandbox

import com.dshbox.app.common.Constants
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * webserver host/port 覆盖层的单测：patch 内容、缺省键完整性、既有行（外部
 * 追加）合并不丢、落点在 user-data 下且 guest 一映射可达。
 */
class DshWebserverOverlayTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun render_writesIdNameHostPort() {
        val body = DshWebserverOverlay.render("127.0.0.1", 3083)
        assertTrue(body.contains("- id: ${Constants.DSH_WEBSERVER_ENTRY_ID}\n"))
        assertTrue(body.contains("name: '${Constants.DSH_WEBSERVER_ENTRY_NAME}'"))
        assertTrue(body.contains("  config:\n"))
        assertTrue(body.contains("    host: '127.0.0.1'\n"))
        assertTrue(body.contains("    port: 3083\n"))
    }

    /** patch 替换整个 config：官方行其余键必须一并复述（否则被抹）。 */
    @Test
    fun render_carriesOfficialConfigKeys() {
        val body = DshWebserverOverlay.render("127.0.0.1", 3080)
        assertTrue(body.contains("tls: null"))
        assertTrue(body.contains("compression: gzip"))
        assertTrue(body.contains("compressionLevel: 1"))
        assertTrue(body.contains("compressionThresholdBytes: 1024"))
    }

    /** 端口范围校验。 */
    @Test
    fun render_rejectsBadPort() {
        try {
            DshWebserverOverlay.render("127.0.0.1", 0)
            throw AssertionError("expected require failure")
        } catch (_: IllegalArgumentException) {
            // expected
        }
        try {
            DshWebserverOverlay.render("127.0.0.1", 70000)
            throw AssertionError("expected require failure")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    /** 既有文件里的外部追加行在重写后原样保留（合并不丢）。 */
    @Test
    fun refresh_keepsCarryOverRows() {
        val userData = tmp.newFolder("user-data")
        val file = DshWebserverOverlay.hostFile(userData)
        file.parentFile.mkdirs()
        file.writeText(
            "- id: webserver\n" +
                "  name: '${Constants.DSH_WEBSERVER_ENTRY_NAME}'\n" +
                "  config:\n" +
                "    host: '127.0.0.1'\n" +
                "    port: 3091\n" +
                "    tls: null\n" +
                "    compression: gzip\n" +
                "    compressionLevel: 1\n" +
                "    compressionThresholdBytes: 1024\n" +
                "- insert:\n" +
                "    - id: some-external\n" +
                "      name: 'external-plugin'\n",
            Charsets.UTF_8,
        )
        val ok = DshWebserverOverlay.refresh(userData, "127.0.0.1", 3083)
        assertTrue(ok)
        val body = file.readText(Charsets.UTF_8)
        assertTrue(body.contains("    port: 3083\n"))
        // 旧的非本层行保留在后：
        assertTrue(body.contains("- insert:"))
        assertTrue(body.contains("    - id: some-external"))
        assertTrue(body.contains("      name: 'external-plugin'"))
        // 恰好两个顶层条目：新 webserver 覆盖行 + 旧 insert 行（重复条目头）。
        assertEquals(2, body.lineSequence().count { it.startsWith("- ") })
    }

    /** 目录不存在时 refresh 自建目录写入。 */
    @Test
    fun refresh_createsDirAndFile() {
        val dir = File(tmp.root, "fresh" + File.separator + "user-data")
        val ok = DshWebserverOverlay.refresh(dir, "127.0.0.1", 3080)
        assertTrue(ok)
        assertTrue(DshWebserverOverlay.hostFile(dir).isFile)
    }

    /**
     * 落点与 guest 一一映射：宿主文件 = user-data 相对路径
     * （DSH_WEBSERVER_OVERLAY_RELATIVE_PATH），guest 路径 = 绑定根 + 同一
     * 相对路径（DSH_WEBSERVER_OVERLAY_GUEST_PATH）。两个常量一起钉在
     * `/root/projects = user-data` 这个绑定事实之上。
     */
    @Test
    fun hostFile_isUnderUserData_andMatchesGuestPath() {
        val userData = tmp.newFolder("user-data")
        val file = DshWebserverOverlay.hostFile(userData)
        // 宿主落点 = user-data 相对路径（.dsh/dshbox/webserver-port/webserver-port-overlay.yml）。
        assertEquals(
            Constants.DSH_WEBSERVER_OVERLAY_RELATIVE_PATH,
            userData.toPath().relativize(file.toPath()).joinToString("/"),
        )
        assertEquals("webserver-port-overlay.yml", file.name)
        // guest 常量以 `/root/projects` 绑定根为前缀，且去掉绑定根后与宿主
        // 相对路径逐段一致（路径段相等，避免仅靠字符串拼接的两常量互相漂移）。
        val guest = Constants.DSH_WEBSERVER_OVERLAY_GUEST_PATH
        val bindRoot = "/root/projects" // SANDBOX_WORKSPACE 绑定口径（见 Constants）
        assertTrue(guest.startsWith("$bindRoot/"))
        val guestRelative = guest.removePrefix("$bindRoot/")
            .split('/')
        val hostRelative = Constants.DSH_WEBSERVER_OVERLAY_RELATIVE_PATH.split('/')
        assertEquals(hostRelative, guestRelative)
    }

    /** carry-over 判定：本层自己的行不保留（将整体重写），外部行保留。 */
    @Test
    fun isCarryOverRow_discriminates() {
        assertFalse(DshWebserverOverlay.isCarryOverRow("- id: ${Constants.DSH_WEBSERVER_ENTRY_ID}"))
        assertFalse(DshWebserverOverlay.isCarryOverRow("  name: '${Constants.DSH_WEBSERVER_ENTRY_NAME}'"))
        assertFalse(DshWebserverOverlay.isCarryOverRow("    port: 3080"))
        assertTrue(DshWebserverOverlay.isCarryOverRow("- insert:"))
        assertTrue(DshWebserverOverlay.isCarryOverRow("    - id: another-entry"))
        // 空白行不算 carry-over（重写后丢弃）。
        assertFalse(DshWebserverOverlay.isCarryOverRow("   "))
    }
}
