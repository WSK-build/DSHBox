package interlock.relay.core.transport

import interlock.relay.core.log.RunLog
import interlock.relay.core.storage.RelayPaths
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 提交标记是「三份资产同批发布」的判据，这里在纯 JVM 上钉住它的四条行为：
 * 铺成功则标记存在且哈希逐一匹配；任何一份被改写、或标记缺失，都判不完整；
 * 标记写不出来时物化以失败收场（宿主据此不前移「已发布」记录）。
 *
 * 运行日志在失败路径上会记一行，而 JVM 单测环境里 android.util.Log 与
 * SystemClock 未被 mock——写日志那一步会以异常收场。所以标记写不出的那条
 * 用例用 runCatching 包住物化，断言落在**状态**上（标记不是普通文件、
 * 三份已先落位、intact 为假），返回值与异常都算失败。
 */
class GuestAssetsMarkerTest {

    private lateinit var root: File
    private lateinit var paths: RelayPaths
    private lateinit var logsDir: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("guest-assets").toFile()
        paths = RelayPaths(root)
        logsDir = Files.createTempDirectory("guest-assets-logs").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
        logsDir.deleteRecursively()
    }

    /** 物化只从 assets 里读入口脚本这一份：给一段固定字节即可，哈希断言不依赖真实脚本。 */
    private fun assets(scriptBytes: ByteArray = SCRIPT_BYTES): GuestAssets = GuestAssets(
        assetLoader = { ByteArrayInputStream(scriptBytes) },
        paths = paths,
        runLog = RunLog(logsDir),
    )

    @Test
    fun materializePublishesMarkerWithMatchingHashesAndIntactHolds() {
        assertTrue(assets().materialize(CAPABILITIES_JSON))

        val marker = paths.versionMarkerFile
        assertTrue("commit marker must exist after a full publish", marker.isFile)
        val parsed = JSONObject(marker.readText())
        assertEquals(1, parsed.getInt("layout"))
        assertTrue(parsed.getLong("publishedAtWall") > 0)
        val files = parsed.getJSONObject("files")
        assertEquals(sha256Of(paths.clientScript), files.getString("bin/" + paths.cliName))
        assertEquals(sha256Of(paths.capabilitiesFile), files.getString("capabilities.json"))
        assertEquals(sha256Of(paths.usageFile), files.getString("USAGE.md"))

        assertTrue(assets().intact())
    }

    @Test
    fun rewrittenContentOfAnyAssetBreaksIntact() {
        assertTrue(assets().materialize(CAPABILITIES_JSON))
        assertTrue(assets().intact())

        // 沙盒与宿主同 uid：改掉任何一份的内容，三份就不再与标记同批。
        paths.usageFile.writeText("tampered by the guest")
        assertFalse("a rewritten asset must fail the marker check", assets().intact())

        // 重铺一遍要能自愈：标记按新内容重新落成，完整性恢复。
        assertTrue(assets().materialize(CAPABILITIES_JSON))
        assertTrue(assets().intact())
    }

    @Test
    fun changedScriptBytesAlsoBreakIntact() {
        assertTrue(assets().materialize(CAPABILITIES_JSON))
        // 入口脚本换了一批字节重新铺：新标记记录新哈希，随后替换脚本内容即不完整。
        paths.clientScript.writeText("echo different")
        assertFalse(assets().intact())
    }

    @Test
    fun missingMarkerBreaksIntact() {
        assertTrue(assets().materialize(CAPABILITIES_JSON))
        paths.versionMarkerFile.delete()
        assertFalse("no marker means the batch is unproven", assets().intact())
    }

    @Test
    fun unwritableMarkerPathFailsMaterialize() {
        // 标记路径被换成目录：沙盒同 uid 做得到，rename 必然失败。
        // 目录里放一个文件是有意的：Windows 的 rename 对「文件 → 空目录」会整个替换，
        // 对「文件 → 非空目录」才失败；POSIX 两头都失败。非空目录在两边都成立。
        paths.versionMarkerFile.mkdirs()
        File(paths.versionMarkerFile, "occupied").writeText("not a marker")
        val outcome = runCatching { assets().materialize(CAPABILITIES_JSON) }
        assertTrue(
            "materialize must fail when the commit marker cannot be published",
            outcome.isFailure || outcome.getOrNull() == false,
        )
        assertFalse("a directory is not a commit marker", paths.versionMarkerFile.isFile)
        // 标记是最后一步：它失败前三份已各自落位——混版状态在盘上，等下一次重铺。
        assertTrue(paths.clientScript.isFile)
        assertTrue(paths.capabilitiesFile.isFile)
        assertTrue(paths.usageFile.isFile)
        assertFalse(assets().intact())
    }

    @Test
    fun usageMentionsFollowUpCommandsAndNewCodesWithoutTemplateHoles() {
        assertTrue(assets().materialize(CAPABILITIES_JSON))
        val usage = paths.usageFile.readText()

        // 跟进命令与超时后的正确动作。
        assertTrue(usage.contains("status <request-id>"))
        assertTrue(usage.contains("cancel <request-id>"))
        assertTrue(usage.contains("E_TRANSPORT_UNKNOWN"))
        // 两个一等公民命令：收尾回宿主页、当面提问。占位符在物化时已换成真实入口路径。
        assertTrue(usage.contains("${paths.guestScript} home"))
        assertTrue(usage.contains("${paths.guestScript} ask --json"))
        assertTrue(usage.contains("host.package"))
        assertTrue(usage.contains("reject_all"))
        // 新错误码各有其名。
        assertTrue(usage.contains("E_QUEUE_FULL"))
        assertTrue(usage.contains("E_GATE_WAITING_TURN"))
        assertTrue(usage.contains("E_GATE_CANCELLED"))
        assertTrue(usage.contains("E_SURFACE_MODE_REQUIRED"))
        // ask 的四支失败结局：超时 / 无回包 / 屏上有卡 / 无呈现面。
        assertTrue(usage.contains("E_ASK_TIMEOUT"))
        assertTrue(usage.contains("E_ASK_NO_REPLY"))
        assertTrue(usage.contains("E_ASK_BUSY"))
        assertTrue(usage.contains("E_ASK_NO_SURFACE"))
        // 刷新语义与提交标记。
        assertTrue(usage.contains("version.json"))
        // 模板占位符必须全部替换过，机器文档里不能留下未渲染的洞。
        assertFalse(usage.contains("{{"))
    }

    private fun sha256Of(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }

    companion object {
        private val SCRIPT_BYTES = "echo relay-entry-under-test".toByteArray()
        private const val CAPABILITIES_JSON = "{\"protocol\":1,\"capabilities\":[]}"
    }
}
