package com.dshbox.pluginmanager.core

import com.dshbox.app.common.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * guest 命令的拼装约束。
 *
 * 钉三件事：环境前缀必须带 git 非交互开关（否则一次性命令会等到软超时）、
 * `dsh` 走绝对路径、资产目录的 bin 排在 PATH 最前。
 */
class GuestCommandsTest {

    private val binPath = Constants.DSHBOX_ASSETS_GUEST_DIR + "/bin"
    private val wrapper = binPath + "/dsh"

    @Test
    fun `插件命令由 timeout、环境前缀、绝对路径和参数依次拼成`() {
        val command = GuestCommands.dshPlugin("default", "install 'pkg'", timeoutSeconds = 120)
        assertEquals(
            "timeout 120 env PATH=$binPath:\$PATH DSH_HOME=/root/projects/.dsh CI=true " +
                "GIT_TERMINAL_PROMPT=0 GIT_SSH_COMMAND='ssh -oBatchMode=yes' " +
                "$wrapper plugin --profile 'default' install 'pkg'",
            command,
        )
    }

    @Test
    fun `git 非交互开关必须在环境前缀里`() {
        val command = GuestCommands.dshPlugin("default", "install 'pkg'")
        assertTrue(command.contains("GIT_TERMINAL_PROMPT=0"))
        assertTrue(command.contains("GIT_SSH_COMMAND='ssh -oBatchMode=yes'"))
    }

    @Test
    fun `不给超时时不套 timeout`() {
        val command = GuestCommands.dshPlugin("default", "list")
        assertFalse(command.startsWith("timeout "))
        assertTrue(command.startsWith("env "))
    }

    @Test
    fun `dump-config 走同一个环境前缀`() {
        val command = GuestCommands.dshDumpConfig("default", timeoutSeconds = 60)
        assertTrue(command.startsWith("timeout 60 env "))
        assertTrue(command.contains("GIT_TERMINAL_PROMPT=0"))
        assertTrue(command.endsWith("$wrapper --profile 'default' --dump-config"))
    }

    @Test
    fun `PATH 首位是资产目录且变量留给 shell 展开`() {
        val command = GuestCommands.dshPlugin("default", "list")
        // 必须写成 `$PATH` 交给 shell 追加系统路径；拼死会导致 pnpm/dsh 都找不到。
        assertTrue(command.contains("PATH=$binPath:\$PATH"))
        assertFalse(command.contains("PATH=$binPath:" + System.getenv("PATH")))
    }
}
