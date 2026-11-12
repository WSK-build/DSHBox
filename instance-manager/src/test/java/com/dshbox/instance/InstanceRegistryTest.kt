package com.dshbox.instance

import com.dshbox.app.common.AppResult
import com.dshbox.app.common.DshInstance
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 注册表的槽位持久化、应急 home 创建/删除与红线防御。 */
class InstanceRegistryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class MemoryStore(var value: DshInstance = DshInstance.MAIN) : SlotStore {
        var writes = 0
        override fun read(): DshInstance = value
        override fun write(instance: DshInstance) {
            value = instance
            writes++
        }
    }

    private fun newRegistry(userData: File, store: SlotStore = MemoryStore()) =
        InstanceRegistry(userData, store)

    @Test
    fun defaultActive_isMain() {
        assertEquals(DshInstance.MAIN, newRegistry(tmp.newFolder("ud")).active.value)
    }

    @Test
    fun switchTo_persistsAndEmits() {
        val store = MemoryStore()
        val registry = newRegistry(tmp.newFolder("ud"), store)
        registry.switchTo(DshInstance.EMERGENCY)
        assertEquals(DshInstance.EMERGENCY, registry.active.value)
        assertEquals(DshInstance.EMERGENCY, store.value)
        assertEquals(1, store.writes)
    }

    @Test
    fun switchTo_sameSlot_doesNotWrite() {
        val store = MemoryStore()
        val registry = newRegistry(tmp.newFolder("ud"), store)
        registry.switchTo(DshInstance.MAIN)
        assertEquals(0, store.writes)
    }

    @Test
    fun activeSlot_restoredFromStore() {
        val store = MemoryStore(DshInstance.EMERGENCY)
        assertEquals(DshInstance.EMERGENCY, newRegistry(tmp.newFolder("ud"), store).active.value)
    }

    @Test
    fun createEmergencyHome_createsDirAndIsIdempotent() {
        val userData = tmp.newFolder("ud")
        val registry = newRegistry(userData)
        assertFalse(registry.emergencyExists())
        assertTrue(registry.createEmergencyHome() is AppResult.Success)
        assertTrue(registry.emergencyExists())
        assertTrue(registry.createEmergencyHome() is AppResult.Success)
    }

    @Test
    fun createEmergencyHome_fileOccupied_fails() {
        val userData = tmp.newFolder("ud")
        File(userData, ".dsh-emergency").writeText("occupied")
        val result = newRegistry(userData).createEmergencyHome()
        assertTrue(result is AppResult.Failure)
        assertEquals("INSTANCE_HOME_NOT_DIR", (result as AppResult.Failure).error.code)
    }

    @Test
    fun deleteEmergency_removesOnlyEmergencyHome() {
        val userData = tmp.newFolder("ud")
        val mainSession = File(userData, ".dsh/sessions").apply { mkdirs() }
        File(mainSession, "s1.json").writeText("{}")
        val userFile = File(userData, "project.txt").apply { writeText("user data") }
        val registry = newRegistry(userData)
        registry.createEmergencyHome()
        File(userData, ".dsh-emergency/profiles").mkdirs()

        assertTrue(registry.deleteEmergencyHome() is AppResult.Success)
        assertFalse(File(userData, ".dsh-emergency").exists())
        assertEquals("{}", File(mainSession, "s1.json").readText())
        assertEquals("user data", userFile.readText())
    }

    @Test
    fun deleteEmergency_refusedWhileActive() {
        val userData = tmp.newFolder("ud")
        val registry = newRegistry(userData)
        registry.createEmergencyHome()
        registry.switchTo(DshInstance.EMERGENCY)

        val result = registry.deleteEmergencyHome()
        assertTrue(result is AppResult.Failure)
        assertEquals("INSTANCE_EMERGENCY_ACTIVE", (result as AppResult.Failure).error.code)
        assertTrue(File(userData, ".dsh-emergency").isDirectory)
    }

    @Test
    fun deleteEmergency_absentIsSuccess() {
        assertTrue(newRegistry(tmp.newFolder("ud")).deleteEmergencyHome() is AppResult.Success)
    }

    /** 优先符号链接；Windows 无特权时退用 junction（mklink /J 免管理员）。两者皆不支持才跳过。 */
    private fun symlinkOrSkip(link: File, target: File) {
        val symlink = runCatching {
            Files.createSymbolicLink(link.toPath(), target.toPath())
        }.isSuccess
        if (symlink) return
        val junction = runCatching {
            ProcessBuilder("cmd", "/c", "mklink", "/J", link.absolutePath, target.absolutePath)
                .redirectErrorStream(true).start().waitFor() == 0 && link.exists()
        }.getOrDefault(false)
        Assume.assumeTrue("directory links unsupported on this platform", junction)
    }

    @Test
    fun deleteEmergency_rootSymlinkToMainHome_refused() {
        val userData = tmp.newFolder("ud")
        val mainHome = File(userData, ".dsh").apply { mkdirs() }
        File(mainHome, "credentials.yaml").writeText("secret")
        symlinkOrSkip(File(userData, ".dsh-emergency"), mainHome)

        val result = newRegistry(userData).deleteEmergencyHome()
        assertTrue(result is AppResult.Failure)
        assertEquals("INSTANCE_HOME_UNSAFE", (result as AppResult.Failure).error.code)
        assertEquals("secret", File(mainHome, "credentials.yaml").readText())
    }

    @Test
    fun deleteEmergency_innerSymlink_notFollowed() {
        val userData = tmp.newFolder("ud")
        val victim = tmp.newFolder("victim")
        val victimFile = File(victim, "project.txt").apply { writeText("user data") }
        val registry = newRegistry(userData)
        registry.createEmergencyHome()
        symlinkOrSkip(File(userData, ".dsh-emergency/escape"), victim)

        assertTrue(registry.deleteEmergencyHome() is AppResult.Success)
        assertFalse(File(userData, ".dsh-emergency").exists())
        assertEquals("user data", victimFile.readText())
    }

    @Test
    fun createEmergencyHome_symlinkOccupied_refused() {
        val userData = tmp.newFolder("ud")
        val elsewhere = tmp.newFolder("elsewhere")
        symlinkOrSkip(File(userData, ".dsh-emergency"), elsewhere)

        val result = newRegistry(userData).createEmergencyHome()
        assertTrue(result is AppResult.Failure)
        assertEquals("INSTANCE_HOME_UNSAFE", (result as AppResult.Failure).error.code)
    }

    @Test
    fun resetMain_backup_renamesAndKeepsData() {
        val userData = tmp.newFolder("ud")
        File(userData, ".dsh/sessions").mkdirs()
        File(userData, ".dsh/sessions/s.json").writeText("{}")
        val registry = newRegistry(userData)

        assertTrue(registry.resetMainHome(backup = true) is AppResult.Success)
        assertFalse(File(userData, ".dsh").exists())
        val backups = userData.listFiles { f -> f.name.startsWith(".dsh-backup-") }
        assertEquals(1, backups?.size)
        assertEquals("{}", File(backups!!.first(), "sessions/s.json").readText())
    }

    @Test
    fun resetMain_delete_removesOnlyMain() {
        val userData = tmp.newFolder("ud")
        File(userData, ".dsh/sessions").mkdirs()
        File(userData, ".dsh-emergency").mkdirs()
        val userFile = File(userData, "project.txt").apply { writeText("user data") }
        val registry = newRegistry(userData)

        assertTrue(registry.resetMainHome(backup = false) is AppResult.Success)
        assertFalse(File(userData, ".dsh").exists())
        assertTrue(File(userData, ".dsh-emergency").isDirectory)
        assertEquals("user data", userFile.readText())
    }

    @Test
    fun resetMain_absentIsSuccess() {
        assertTrue(newRegistry(tmp.newFolder("ud")).resetMainHome(backup = false) is AppResult.Success)
    }

    @Test
    fun resetMain_symlink_refused() {
        val userData = tmp.newFolder("ud")
        val elsewhere = tmp.newFolder("elsewhere")
        File(elsewhere, "secret").writeText("x")
        symlinkOrSkip(File(userData, ".dsh"), elsewhere)

        val result = newRegistry(userData).resetMainHome(backup = false)
        assertTrue(result is AppResult.Failure)
        assertEquals("INSTANCE_HOME_UNSAFE", (result as AppResult.Failure).error.code)
        assertEquals("x", File(elsewhere, "secret").readText())
    }

    @Test
    fun listBackups_onlyBackupDirs_sortedDesc() {
        val userData = tmp.newFolder("ud")
        File(userData, ".dsh").mkdirs()
        File(userData, ".dsh-emergency").mkdirs()
        File(userData, ".dsh-backup-100").mkdirs()
        File(userData, ".dsh-backup-200").mkdirs()
        File(userData, "project.txt").writeText("x")
        val list = newRegistry(userData).listBackups()
        assertEquals(listOf(".dsh-backup-200", ".dsh-backup-100"), list.map { it.name })
        assertEquals(200L, list[0].createdAtMillis)
    }

    @Test
    fun restoreBackup_copiesKeepsOriginalAndBacksUpCurrent() {
        val userData = tmp.newFolder("ud")
        File(userData, ".dsh").mkdirs()
        File(userData, ".dsh/cur.txt").writeText("current")
        File(userData, ".dsh-backup-100").mkdirs()
        File(userData, ".dsh-backup-100/old.txt").writeText("old")

        val result = newRegistry(userData).restoreBackup(".dsh-backup-100")
        assertTrue(result is AppResult.Success)
        // 主 home 变为备份内容
        assertEquals("old", File(userData, ".dsh/old.txt").readText())
        assertFalse(File(userData, ".dsh/cur.txt").exists())
        // 原备份保留
        assertEquals("old", File(userData, ".dsh-backup-100/old.txt").readText())
        // 还原前的现状被自动备份
        val newBackups = userData.listFiles { f -> f.name.startsWith(".dsh-backup-") && f.name != ".dsh-backup-100" }
        assertEquals(1, newBackups?.size)
        assertEquals("current", File(newBackups!!.first(), "cur.txt").readText())
    }

    @Test
    fun restoreBackup_invalidName_fails() {
        val userData = tmp.newFolder("ud")
        File(userData, ".dsh").mkdirs()
        val result = newRegistry(userData).restoreBackup(".dsh")
        assertTrue(result is AppResult.Failure)
        assertEquals("INSTANCE_BACKUP_INVALID", (result as AppResult.Failure).error.code)
    }

    @Test
    fun restoreBackup_symlinkBackup_refused() {
        val userData = tmp.newFolder("ud")
        val elsewhere = tmp.newFolder("elsewhere")
        File(elsewhere, "secret").writeText("x")
        symlinkOrSkip(File(userData, ".dsh-backup-100"), elsewhere)
        val result = newRegistry(userData).restoreBackup(".dsh-backup-100")
        assertTrue(result is AppResult.Failure)
        assertEquals("x", File(elsewhere, "secret").readText())
    }

    @Test
    fun deleteBackup_removesOnlySelected() {
        val userData = tmp.newFolder("ud")
        File(userData, ".dsh").mkdirs()
        File(userData, ".dsh-backup-100").mkdirs()
        File(userData, ".dsh-backup-200").mkdirs()
        val result = newRegistry(userData).deleteBackup(".dsh-backup-100")
        assertTrue(result is AppResult.Success)
        assertFalse(File(userData, ".dsh-backup-100").exists())
        assertTrue(File(userData, ".dsh-backup-200").isDirectory)
        assertTrue(File(userData, ".dsh").isDirectory)
    }

    @Test
    fun deleteBackup_nonBackupName_fails() {
        val userData = tmp.newFolder("ud")
        File(userData, ".dsh-emergency").mkdirs()
        val result = newRegistry(userData).deleteBackup(".dsh-emergency")
        assertTrue(result is AppResult.Failure)
        assertEquals("INSTANCE_BACKUP_INVALID", (result as AppResult.Failure).error.code)
        assertTrue(File(userData, ".dsh-emergency").isDirectory)
    }

    @Test
    fun restoreBackup_innerSymlink_recreatedNotFollowed() {
        val userData = tmp.newFolder("ud")
        // 还原需重建符号链接；本平台不支持创建符号链接则跳过（Windows 无特权）。
        val probeTarget = tmp.newFolder("probe-target")
        val probeLink = File(userData, "probe-link")
        Assume.assumeTrue(
            "symbolic link creation unsupported on this platform",
            runCatching { Files.createSymbolicLink(probeLink.toPath(), probeTarget.toPath()) }.isSuccess,
        )
        val victim = tmp.newFolder("victim")
        File(victim, "secret").writeText("x")
        File(userData, ".dsh-backup-100").mkdirs()
        File(userData, ".dsh-backup-100/real.txt").writeText("real")
        Files.createSymbolicLink(File(userData, ".dsh-backup-100/lnk").toPath(), victim.toPath())

        val result = newRegistry(userData).restoreBackup(".dsh-backup-100")
        assertTrue(result is AppResult.Success)
        assertEquals("real", File(userData, ".dsh/real.txt").readText())
        // 链接被重建为链接本身，而非跟随目标复制成真实目录。
        assertTrue(Files.isSymbolicLink(File(userData, ".dsh/lnk").toPath()))
        assertEquals("x", File(victim, "secret").readText())
    }

    @Test
    fun resetBackup_readableName_parsedByList() {
        val userData = tmp.newFolder("ud")
        File(userData, ".dsh").mkdirs()
        File(userData, ".dsh/x").writeText("x")
        val reg = newRegistry(userData)
        assertTrue(reg.resetMainHome(backup = true) is AppResult.Success)
        val list = reg.listBackups()
        assertEquals(1, list.size)
        // 新命名为可读日期（含 '-' 分隔），非纯数字 epoch；listBackups 能解析出时间。
        assertTrue(list[0].name.removePrefix(".dsh-backup-").contains("-"))
        assertTrue(list[0].createdAtMillis > 0)
    }

    @Test
    fun listBackups_legacyEpochName_stillParsed() {
        val userData = tmp.newFolder("ud")
        File(userData, ".dsh-backup-1730000000000").mkdirs()
        val list = newRegistry(userData).listBackups()
        assertEquals(1, list.size)
        assertEquals(1730000000000L, list[0].createdAtMillis)
    }
}