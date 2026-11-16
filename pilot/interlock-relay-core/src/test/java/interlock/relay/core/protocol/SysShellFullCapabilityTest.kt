package interlock.relay.core.protocol

import interlock.relay.core.exec.shizuku.ShizukuBackend
import interlock.relay.core.exec.shizuku.RelayShellService.CommandTxn
import interlock.relay.core.interlock.InterlockBroker
import interlock.relay.core.interlock.InterlockChoice
import interlock.relay.core.interlock.InterlockGate
import interlock.relay.core.interlock.InterlockPresenter
import interlock.relay.core.interlock.ApprovalPrompt
import interlock.relay.core.interlock.InterlockTargets
import interlock.relay.core.interlock.TierStore
import interlock.relay.core.interlock.GateResult
import interlock.relay.core.log.RunLog
import interlock.relay.core.runtime.CapabilityManifestEntries
import interlock.relay.core.runtime.RelayCoordinator
import interlock.relay.core.spi.RelayPrefs
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * 完全 Shizuku（`sys.shell.full`）的注册表形状、参数边界、闸门行为与清单口径。
 *
 * 真执行路径依赖 Shizuku 用户服务的 binder，JVM 上跑不了；这里把**不读设备的部分**
 * 全部钉死：注册表成员位、默认档位、预校验与后端键表、命令长度与超时的纯边界、
 * 闸门的拒绝/放行两态（走 authorize 全程，证明关闭时拒绝且不回退、打开时不弹审批）、
 * 审计目标只带命令首 token，以及清单条目在关/开两态下的字段。
 */
class SysShellFullCapabilityTest {

    // ── 注册表形状 ──

    @Test
    fun descriptorIsAShizukuOnlyPrivilegedDangerousCapability() {
        val descriptor = CapabilityRegistry.find(CapabilityId.SYS_SHELL_FULL)
        assertTrue(descriptor != null)
        assertEquals("sys.shell.full", descriptor!!.id.wire)
        assertEquals(CapabilityCategory.DANGEROUS, descriptor.id.category)
        assertEquals(listOf(BackendId.SHIZUKU), descriptor.backends)
        assertTrue("surfaces 为空：与执行模式无关，同 sys.shell", descriptor.surfaces.isEmpty())
        assertEquals(29, descriptor.minSdk)
        assertTrue("必须显式标为特权能力，界面的普通列表按这一位过滤", descriptor.privileged)
        assertFalse("不进全局档位选择器，也不随批量拨档走", descriptor.globalTierEligible)
    }

    /** `ceiling` 只是历史元数据；这里钉住它的存在形状，任何把它读回判据的改动都该在这里露馅。 */
    @Test
    fun ceilingIsHistoricalMetadataOnly() {
        assertEquals(TierCeiling.ANY, CapabilityRegistry.find(CapabilityId.SYS_SHELL_FULL)?.ceiling)
    }

    // ── 默认档位 ──

    @Test
    fun defaultTierIsDeniedAndTheSwitchWritesOnlyTheTwoStates() {
        val tiers = TierStore(MemoryPrefs())
        assertEquals(AccessTier.DENIED, tiers.tierOf(CapabilityId.SYS_SHELL_FULL))
        tiers.setTier(CapabilityId.SYS_SHELL_FULL, AccessTier.ALWAYS)
        assertEquals(AccessTier.ALWAYS, tiers.tierOf(CapabilityId.SYS_SHELL_FULL))
        tiers.setTier(CapabilityId.SYS_SHELL_FULL, AccessTier.DENIED)
        assertEquals(AccessTier.DENIED, tiers.tierOf(CapabilityId.SYS_SHELL_FULL))
    }

    // ── 参数形状（预校验表 × 后端键表）──

    @Test
    fun argsSpecAllowsCommandAndOptionalTimeoutOnly() {
        val backendSpec = ShizukuBackend.ARG_SPECS.getValue(CapabilityId.SYS_SHELL_FULL)
        assertEquals(setOf("command", "timeoutMs"), backendSpec.allowed)
        assertEquals(setOf("command"), backendSpec.required)
    }

    @Test
    fun prevalidationRejectsUnknownKeysAndMissingCommand() {
        assertTrue(
            CapabilityArgsSpec.validate(CapabilityId.SYS_SHELL_FULL, JSONObject())!!.startsWith("missing arg: command"),
        )
        assertTrue(
            CapabilityArgsSpec.validate(CapabilityId.SYS_SHELL_FULL, JSONObject().put("cmd", "ls"))!!
                .startsWith("unknown arg: cmd"),
        )
        assertNull(
            CapabilityArgsSpec.validate(CapabilityId.SYS_SHELL_FULL, JSONObject().put("command", "ls -l")),
        )
        // timeoutMs 是可选键：带上合法值不构成多余键。
        assertNull(
            CapabilityArgsSpec.validate(
                CapabilityId.SYS_SHELL_FULL,
                JSONObject().put("command", "ls").put("timeoutMs", 5_000),
            ),
        )
    }

    @Test
    fun prevalidationRejectsNonStringCommand() {
        val reason = CapabilityArgsSpec.validate(CapabilityId.SYS_SHELL_FULL, JSONObject().put("command", 7))
        assertTrue(reason!!.startsWith("command must be a string"))
        // 显式 null 不许被读成字面量 "null" 当命令放行：JVM 的 org.json 会把 null 读成
        // 空串（落「缺参」）、Android 的实现读成 "null"（落类型档或后端的同源判据），
        // 两种措辞都算拒绝，唯一不许出现的是把这条调用放过去。
        val nullReason = CapabilityArgsSpec.validate(
            CapabilityId.SYS_SHELL_FULL,
            JSONObject("{\"command\": null}"),
        )
        assertTrue(nullReason != null)
        assertTrue(
            nullReason!!.startsWith("missing arg: command") || nullReason.startsWith("command must be a string"),
        )
    }

    /** 拒绝型边界前移到闸门之前：超长命令与越界超时在这里点名怎么改，不占审批与通道。 */
    @Test
    fun prevalidationRejectsOverlongCommandAndOutOfRangeTimeout() {
        val longCommand = CapabilityArgsSpec.validate(
            CapabilityId.SYS_SHELL_FULL,
            JSONObject().put("command", "a".repeat(4097)),
        )
        assertTrue(longCommand!!.startsWith("command too long"))
        assertNull(
            CapabilityArgsSpec.validate(CapabilityId.SYS_SHELL_FULL, JSONObject().put("command", "a".repeat(4096))),
        )

        for (outOfRange in listOf(999, 120001)) {
            val reason = CapabilityArgsSpec.validate(
                CapabilityId.SYS_SHELL_FULL,
                JSONObject().put("command", "ls").put("timeoutMs", outOfRange),
            )
            assertTrue("$outOfRange", reason!!.startsWith("timeoutMs must be between 1000 and 120000 ms"))
        }
        assertNull(
            CapabilityArgsSpec.validate(
                CapabilityId.SYS_SHELL_FULL,
                JSONObject().put("command", "ls").put("timeoutMs", 120_000),
            ),
        )
        val notNumber = CapabilityArgsSpec.validate(
            CapabilityId.SYS_SHELL_FULL,
            JSONObject().put("command", "ls").put("timeoutMs", "soon"),
        )
        assertTrue(notNumber!!.startsWith("timeoutMs must be a number"))
    }

    // ── 后端纯边界：命令长度与超时范围 ──

    @Test
    fun commandLengthLimitIsFourThousandNinetySixChars() {
        assertEquals(4096, ShizukuBackend.MAX_FULL_COMMAND_CHARS)
        assertNull(ShizukuBackend.fullShellCommandError("echo ok"))
        assertNull(ShizukuBackend.fullShellCommandError("a".repeat(4096)))
        assertTrue(
            ShizukuBackend.fullShellCommandError("a".repeat(4097))!!.startsWith("command too long"),
        )
        assertTrue(
            ShizukuBackend.fullShellCommandError("   ")!!.startsWith("missing arg: command"),
        )
    }

    @Test
    fun timeoutRangeIsRefusedNotClamped() {
        assertNull(ShizukuBackend.fullShellTimeoutError(null, 15_000L))
        assertNull(ShizukuBackend.fullShellTimeoutError(1_000L, 1_000L))
        assertNull(ShizukuBackend.fullShellTimeoutError(120_000L, 120_000L))
        assertTrue(ShizukuBackend.fullShellTimeoutError(999L, 999L)!!.startsWith("timeoutMs must be between"))
        assertTrue(ShizukuBackend.fullShellTimeoutError(120_001L, 120_001L)!!.startsWith("timeoutMs must be between"))
        // 小数先于区间：optLong 会把 120000.5 静默截成 120000，原始值必须单独拒。
        assertTrue(
            ShizukuBackend.fullShellTimeoutError(120_000.5, 120_000L)!!.startsWith("timeoutMs must be a whole number"),
        )
        assertTrue(
            ShizukuBackend.fullShellTimeoutError(999.5, 999L)!!.startsWith("timeoutMs must be a whole number"),
        )
    }

    // ── 闸门：关闭拒绝（不回退）、打开不弹 ──

    @Test
    fun closedSwitchRejectsTheCallWithoutAnyApprovalPrompt() = runBlocking {
        val fixture = Fixture()
        val result = fixture.authorize()
        assertFalse("默认禁止档必须拒绝", result.allowed)
        assertEquals(RelayError.GATE_HOST_DENIED, result.error)
        assertEquals("tier_denied", result.decisionLabel)
        assertEquals("禁止档在纯判定处被拒，不该问任何人", 0, fixture.presenter.prompts.size)
    }

    @Test
    fun openSwitchRunsSilentlyWithoutAnyApprovalPrompt() = runBlocking {
        val fixture = Fixture()
        fixture.tiers.setTier(CapabilityId.SYS_SHELL_FULL, AccessTier.ALWAYS)
        val result = fixture.authorize()
        assertTrue(result.allowed)
        assertFalse(result.requiresApproval)
        assertEquals("auto", result.decisionLabel)
        assertEquals("完全访问档不弹审批", 0, fixture.presenter.prompts.size)
    }

    @Test
    fun missingShizukuBlocksAtTheSystemSideBeforeTheTier() {
        val gate = InterlockGate.verdict(SystemGrantState.MANUAL_ONLY, AccessTier.ALWAYS)
        assertTrue(gate != null)
        assertFalse(gate!!.allowed)
        assertEquals(RelayError.GATE_SYSTEM_MISSING, gate.error)
        assertEquals("system_missing", gate.decisionLabel)
    }

    // ── 审计：target 只带命令首段 ──

    @Test
    fun targetLabelCarriesOnlyTheFirstTokenOfTheCommand() {
        val descriptor = CapabilityRegistry.find(CapabilityId.SYS_SHELL_FULL)!!
        val (target, detail) = InterlockTargets.of(
            descriptor,
            JSONObject().put("command", "rm -rf /sdcard/DCIM && cat /data/misc/wifi/wpa.conf"),
            { count -> "$count" },
        )
        assertEquals("rm", target)
        assertNull(detail)
    }

    /** 取段以空白与 shell 分隔符为界：tab、换行、管道都必须截在安全的位置。 */
    @Test
    fun targetLabelStopsAtWhitespaceAndShellSeparators() {
        val descriptor = CapabilityRegistry.find(CapabilityId.SYS_SHELL_FULL)!!
        fun targetOf(command: String): String? = InterlockTargets.of(
            descriptor,
            JSONObject().put("command", command),
            { count -> "$count" },
        ).first

        // tab 与换行同样是界：只按空格切会把第二段放进 target。
        assertEquals("echo", targetOf("echo\tworld"))
        assertEquals("echo", targetOf("echo\nrm -rf /"))
        // 管道/串联出现在首段之后：整条命令不得进 target。
        assertEquals("cat", targetOf("cat /a | sh"))
        // 命令为空：目标省略，不给一个假对象。
        assertNull(targetOf(""))
    }

    /** 单段命令词本身就是对象；赋值前缀（含引号值）与重定向及其落点都不是命令词。 */
    @Test
    fun targetLabelTreatsAssignmentsAndRedirectsAsNonCommandPrefixes() {
        val descriptor = CapabilityRegistry.find(CapabilityId.SYS_SHELL_FULL)!!
        fun targetOf(command: String): String? = InterlockTargets.of(
            descriptor,
            JSONObject().put("command", command),
            { count -> "$count" },
        ).first

        // 单 token 命令：它就是对象（也是整条命令——这是可接受的，对象本就只有一个词）。
        assertEquals("reboot", targetOf("reboot"))
        // 赋值前缀：等号右边的值（可能是机密）绝不落 target，取后面的命令词。
        assertEquals("cmd", targetOf("NAME=value cmd arg"))
        assertEquals("cmd", targetOf("PATH=/system/bin:${'$'}PATH\tcmd"))
        // 带引号的赋值值：引号内的空白不切分，值整体不落 target。
        assertEquals("cmd", targetOf("NAME=\"secret value\" cmd"))
        // 只剩赋值前缀、没有命令词：固定标识，不落值。
        assertEquals(InterlockTargets.FULL_SHELL_TARGET_FALLBACK, targetOf("KEY=secret"))
        assertEquals(InterlockTargets.FULL_SHELL_TARGET_FALLBACK, targetOf("KEY=\"secret value\""))
        // 引导重定向：没有命令词，固定标识，不把落点文件当成对象。
        assertEquals(InterlockTargets.FULL_SHELL_TARGET_FALLBACK, targetOf("> /data/x"))
        assertEquals(InterlockTargets.FULL_SHELL_TARGET_FALLBACK, targetOf("&>/data/x"))
        // 数字前缀重定向：`2>` 是操作符，落点不进 target，也不能把 "2" 当命令词。
        assertEquals(InterlockTargets.FULL_SHELL_TARGET_FALLBACK, targetOf("2>/data/x"))
        assertEquals("cmd", targetOf("FOO=bar 2>/data/x cmd"))
        // fd 复制（>&2 / 2>&1）没有文件落点：后面的命令词不被吞掉。
        assertEquals("cmd", targetOf(">&2 cmd"))
        assertEquals("cmd", targetOf("2>&1 cmd"))
        // 重定向在赋值前缀之后：跳过操作符与落点，取真正的命令词。
        assertEquals("cmd", targetOf("FOO=bar >/data/x cmd"))
        // 重定向之后还有命令词：落点不进 target，命令词才是对象。
        assertEquals("cat", targetOf("< /data/keystore cat"))
        // 只剩分隔符的残句：固定标识，不回空串也不回 null。
        assertEquals(InterlockTargets.FULL_SHELL_TARGET_FALLBACK, targetOf("&& ||"))
        // 认不出命令词的形状（+=、数组元素、命令替换、选项开头）：固定标识，不落原文。
        assertEquals(InterlockTargets.FULL_SHELL_TARGET_FALLBACK, targetOf("ARR+=x"))
        assertEquals(InterlockTargets.FULL_SHELL_TARGET_FALLBACK, targetOf("arr[0]=x cmd"))
        assertEquals(InterlockTargets.FULL_SHELL_TARGET_FALLBACK, targetOf("$(pwd)"))
        assertEquals(InterlockTargets.FULL_SHELL_TARGET_FALLBACK, targetOf("--opt=value cmd"))
    }

    @Test
    fun targetLabelTruncatesAnOverlongFirstToken() {
        val descriptor = CapabilityRegistry.find(CapabilityId.SYS_SHELL_FULL)!!
        val target = InterlockTargets.of(
            descriptor,
            JSONObject().put("command", "x".repeat(200)),
            { count -> "$count" },
        ).first
        assertEquals(80, target!!.length)
    }

    // ── 重放与副作用状态：按「是否已提交远端事务」判定 ──

    @Test
    fun fullShellIsExplicitlyUserDecideOnTheRetryTable() {
        assertEquals(RetryPolicy.USER_DECIDE, retryPolicyOf(CapabilityId.SYS_SHELL_FULL))
    }

    /**
     * 七类场景逐格钉死：未提交远端事务的失败（关闭拒绝、限流、取消、预算不足、
     * 后端不可达、守卫拒绝、参数判据）一律 NOT_STARTED——「没发出去」不许说成
     * 「可能已执行」；已提交后的非零退出与远端超时一律 UNKNOWN。其它能力维持旧表。
     */
    @Test
    fun effectStateFollowsTheDispatchFlagForThePrivilegedPath() {
        val notSubmitted = listOf(
            RelayError.GATE_HOST_DENIED,
            RelayError.RATE_LIMITED,
            RelayError.GATE_CANCELLED,
            // 预算装不下一次最小往返：TRANSPORT_TIMEOUT 但什么都没发出去。
            RelayError.TRANSPORT_TIMEOUT,
            RelayError.BACKEND_SHIZUKU_DEAD,
            RelayError.BACKEND_UNAVAILABLE,
            RelayError.INTERNAL,
            RelayError.TRANSPORT_MALFORMED,
        )
        for (error in notSubmitted) {
            assertEquals(
                "$error 未提交远端事务必须 NOT_STARTED",
                EffectState.NOT_STARTED,
                effectStateFor(ok = false, error = error, capability = CapabilityId.SYS_SHELL_FULL, dispatched = false),
            )
        }
        // 已提交之后的失败：非零退出（含 127/126/1 的 COMMAND_FAILED）、事务递交后对端死亡
        // （BACKEND_SHIZUKU_DEAD + dispatched）与本地放弃等待的超时。
        assertEquals(
            EffectState.UNKNOWN,
            effectStateFor(ok = false, error = RelayError.INTERNAL, capability = CapabilityId.SYS_SHELL_FULL, dispatched = true),
        )
        assertEquals(
            EffectState.UNKNOWN,
            effectStateFor(ok = false, error = RelayError.COMMAND_FAILED, capability = CapabilityId.SYS_SHELL_FULL, dispatched = true),
        )
        assertEquals(
            EffectState.UNKNOWN,
            effectStateFor(ok = false, error = RelayError.COMMAND_NOT_PERMITTED, capability = CapabilityId.SYS_SHELL_FULL, dispatched = true),
        )
        // 共享 run() 的 sys.shell 收到回执且命令失败类：run() 现在按已派发交出
        // （命令至少 sh 本身已经跑过），effectState 答 UNKNOWN——与事实一致。
        assertEquals(
            EffectState.UNKNOWN,
            effectStateFor(ok = false, error = RelayError.COMMAND_FAILED, capability = CapabilityId.SYS_SHELL, dispatched = true),
        )
        assertEquals(
            EffectState.UNKNOWN,
            effectStateFor(ok = false, error = RelayError.COMMAND_NOT_PERMITTED, capability = CapabilityId.SYS_SHELL, dispatched = true),
        )
        // 127（设备没有那条二进制）不在命令失败类：既有口径不变（未递交按 NOT_STARTED）。
        assertEquals(
            EffectState.NOT_STARTED,
            effectStateFor(ok = false, error = RelayError.CAPABILITY_UNAVAILABLE_ON_DEVICE, capability = CapabilityId.SYS_SHELL, dispatched = false),
        )
        assertEquals(
            EffectState.UNKNOWN,
            effectStateFor(
                ok = false,
                error = RelayError.BACKEND_SHIZUKU_DEAD,
                capability = CapabilityId.SYS_SHELL_FULL,
                dispatched = true,
            ),
        )
        assertEquals(
            EffectState.UNKNOWN,
            effectStateFor(
                ok = false,
                error = RelayError.TRANSPORT_TIMEOUT,
                capability = CapabilityId.SYS_SHELL_FULL,
                dispatched = true,
            ),
        )
        // sys.shell 等既有能力维持旧表：共享执行入口不为它们标注 dispatched，
        // 非零退出仍按未派发作答（行为与特权通路接入之前一致）。
        assertEquals(
            EffectState.NOT_STARTED,
            effectStateFor(ok = false, error = RelayError.INTERNAL, capability = CapabilityId.SYS_SHELL, dispatched = false),
        )
        assertEquals(
            EffectState.NOT_STARTED,
            effectStateFor(ok = false, error = RelayError.BACKEND_SHIZUKU_DEAD, capability = CapabilityId.SYS_SHELL, dispatched = false),
        )
        assertEquals(
            EffectState.UNKNOWN,
            effectStateFor(ok = false, error = RelayError.TRANSPORT_TIMEOUT),
        )
    }

    /** 事务结局到「已提交」的分类：拒收不算；递交后死亡、结果无法证实与拿到回执都算。 */
    @Test
    fun transactionClassificationDrivesTheDispatchFlag() {
        // 特权通路（markDispatched=true）：
        assertTrue(ShizukuBackend.dispatchedOnTransaction(true, CommandTxn.Replied(shellOutcome())))
        assertTrue(ShizukuBackend.dispatchedOnTransaction(true, CommandTxn.Submitted("binder died")))
        assertFalse(ShizukuBackend.dispatchedOnTransaction(true, CommandTxn.NotSent))
        assertFalse(ShizukuBackend.dispatchedOnTransaction(true, null))
        // 其它能力（markDispatched=false）：任何结局都不标，旧行为不变。
        assertFalse(ShizukuBackend.dispatchedOnTransaction(false, CommandTxn.Submitted("binder died")))
        assertFalse(ShizukuBackend.dispatchedOnTransaction(false, CommandTxn.Replied(shellOutcome())))
    }

    /**
     * GaveUp 的队列态：事务未被取消，无论此刻已递交（submittedBeforeExpiryMs 非空）还是
     * 仍排在本地队列（null，之后仍可能被递交执行），特权通路一律按「已提交」——
     * 「可能稍后执行」不得说成「可证尚未开始」。非特权通路不标，旧行为不变。
     */
    @Test
    fun gaveUpAlwaysCountsAsDispatchedForThePrivilegedPath() {
        assertTrue(ShizukuBackend.dispatchedAfterGiveUp(true))
        assertFalse(ShizukuBackend.dispatchedAfterGiveUp(false))
        // 队列态（submittedBeforeExpiryMs == null）：full shell 的回包答 UNKNOWN。
        assertEquals(
            EffectState.UNKNOWN,
            effectStateFor(
                ok = false,
                error = RelayError.TRANSPORT_TIMEOUT,
                capability = CapabilityId.SYS_SHELL_FULL,
                dispatched = true,
            ),
        )
        // 递交后死亡走专用错误码：错误码本身携带「已提交、结局无法证实」，旧表同样答 UNKNOWN。
        assertEquals(
            EffectState.UNKNOWN,
            effectStateFor(
                ok = false,
                error = RelayError.TRANSPORT_UNKNOWN,
                capability = CapabilityId.SYS_SHELL_FULL,
                dispatched = true,
            ),
        )
        assertEquals(
            EffectState.UNKNOWN,
            effectStateFor(ok = false, error = RelayError.TRANSPORT_UNKNOWN),
        )
        // sys.shell 维持旧表：GaveUp（TRANSPORT_TIMEOUT）走「超时即未知」的旧口径，
        // 与特权通路接入之前一致；差异只在特权通路自己（GaveUp 队列态也是 UNKNOWN，
        // 但错误码与副作用状态由 dispatched 显式驱动，不靠旧表推断）。
        assertEquals(
            EffectState.UNKNOWN,
            effectStateFor(
                ok = false,
                error = RelayError.TRANSPORT_TIMEOUT,
                capability = CapabilityId.SYS_SHELL,
                dispatched = false,
            ),
        )
    }

    private fun shellOutcome() =
        interlock.relay.core.exec.shizuku.RelayShellService.Outcome(1, "", "some stderr")

    /**
     * 特权通路非零退出的纯判据：1/126/127 同说「已执行、命令自身失败」（E_COMMAND_FAILED，
     * 不再冒充宿主内部错误），不按设备边界解释。0 不参与：`run()` 只在失败出口调用它，
     * 退出码 0 永远到不了这里（前置条件）。
     */
    @Test
    fun fullShellFailureExplainsItselfForEveryNonZeroExitCode() {
        for (exitCode in listOf(1, 126, 127)) {
            val (error, reason) = ShizukuBackend.fullShellCommandFailureOf(exitCode, "some stderr")
            assertEquals("exit=$exitCode", RelayError.COMMAND_FAILED, error)
            assertTrue("exit=$exitCode", reason.contains("the shell itself ran"))
            assertTrue("exit=$exitCode", reason.contains("unknown or partially done"))
        }
    }

    /** 权限拒绝单独归类：只认平台的明确签名，不混进内部错误，也不靠裸 "not allowed" 猜。 */
    @Test
    fun permissionRefusalIsItsOwnClass() {
        for (stderr in listOf(
            "SecurityException: uid 2000 does not have android.permission.X",
            "Permission Denial: cannot start service from shell",
        )) {
            val (error, reason) = ShizukuBackend.fullShellCommandFailureOf(1, stderr)
            assertEquals(stderr, RelayError.COMMAND_NOT_PERMITTED, error)
            assertTrue(stderr, reason.contains("refused it (Permission Denial / SecurityException)"))
        }
        // 无签名照旧归命令失败。
        val (error, _) = ShizukuBackend.fullShellCommandFailureOf(2, "grep: no match")
        assertEquals(RelayError.COMMAND_FAILED, error)
        // 反例：命令自己把 "not allowed" 打进 stderr 且 exit 1——那是命令的输出，
        // 不是服务侧的权限拒绝，按普通命令失败归类。
        val (notPermission, _) = ShizukuBackend.fullShellCommandFailureOf(1, "the user is not allowed to do that")
        assertEquals(RelayError.COMMAND_FAILED, notPermission)
    }

    /**
     * 命令结果的原始事实构造：成功与失败两路同构（output / stderr / exit）。
     * `dumpsys | grep -m1` 之类管道提前关闭时 stdout 是已产出的那部分，一条都不能丢；
     * 成功路径也保留 stderr（告警不是噪声）；回包没有 verified 之类的语义字段——
     * raw 通路不宣称"写成了"，`settings put` 的 rc=0 由调用方自行读回核实。
     */
    @Test
    fun commandResultCarriesAllThreeFactsOnBothPaths() {
        val failed = ShizukuBackend.commandResultData(shellOutcome())
        assertEquals("", failed.getString("output"))
        assertEquals("some stderr", failed.getString("stderr"))
        assertEquals(1, failed.getInt("exit"))
        assertFalse(failed.has("verified"))

        // 管道提前关闭：非零退出但已有输出，一个字节都不丢。
        val piped = ShizukuBackend.commandResultData(
            interlock.relay.core.exec.shizuku.RelayShellService.Outcome(141, "the matched line", ""),
        )
        assertEquals("the matched line", piped.getString("output"))
        assertEquals("", piped.getString("stderr"))
        assertEquals(141, piped.getInt("exit"))

        // 成功路径保留 stderr。
        val ok = ShizukuBackend.commandResultData(
            interlock.relay.core.exec.shizuku.RelayShellService.Outcome(0, "done", "a warning"),
        )
        assertEquals("done", ok.getString("output"))
        assertEquals("a warning", ok.getString("stderr"))
        assertEquals(0, ok.getInt("exit"))
        assertFalse("raw 通路不宣称写入成功", ok.has("verified"))
    }

    /** 授权记录的特权执行身份署名：root / shell / 报数 / 如实 unknown。 */
    @Test
    fun uidRoleNamesTheShizukuIdentityOrOwnsUpToUnknown() {
        assertEquals("root", RelayCoordinator.uidRoleOf(0))
        assertEquals("shell", RelayCoordinator.uidRoleOf(2000))
        assertEquals("uid_20100", RelayCoordinator.uidRoleOf(20100))
        assertEquals("unknown", RelayCoordinator.uidRoleOf(-1))
    }

    // ── 清单条目：关闭/打开两态 ──

    @Test
    fun manifestEntryReportsTheSwitchOffStateAsNotCallable() {
        val json = entry(AccessTier.DENIED, usable = false)
        assertEquals("DENIED", json.getString("assistantTier"))
        assertEquals("denied", json.getString("approvalPolicy"))
        assertFalse(json.getBoolean("usable"))
        assertFalse("禁止档是不可调用，不是待审批", json.getBoolean("requiresApproval"))
        assertFalse(json.getBoolean("sessionGrantable"))
    }

    @Test
    fun manifestEntryReportsTheSwitchOnStateAsSilentlyCallable() {
        val json = entry(AccessTier.ALWAYS, usable = true)
        assertEquals("ALWAYS", json.getString("assistantTier"))
        assertEquals("none", json.getString("approvalPolicy"))
        assertTrue(json.getBoolean("usable"))
        assertFalse("打开后不审批", json.getBoolean("requiresApproval"))
        assertFalse("没有会话授权可谈", json.getBoolean("sessionGrantable"))
    }

    private fun entry(tier: AccessTier, usable: Boolean) = CapabilityManifestEntries.entry(
        id = "sys.shell.full",
        category = "dangerous",
        systemPermission = "GRANTED",
        tier = tier,
        sessionGranted = false,
        usable = usable,
        implemented = true,
        minSdk = 29,
        surfaces = emptyList(),
        guide = "NO_SETTINGS_PAGE",
        surface = null,
        degradeReason = null,
    )

    /** 内存偏好：档位写读都在这里，不落盘。 */
    private class MemoryPrefs : RelayPrefs {
        private val strings = HashMap<String, String>()
        override fun getString(file: String, key: String, defValue: String?): String? =
            strings[key] ?: defValue
        override fun putString(file: String, key: String, value: String) { strings[key] = value }
        override fun getBoolean(file: String, key: String, defValue: Boolean): Boolean = defValue
        override fun putBoolean(file: String, key: String, value: Boolean) {}
    }

    /** 不接任何呈现脚本：特权开关的两态都不该问到它，被问到即失败。 */
    private class NoPromptPresenter : InterlockPresenter {
        val prompts = mutableListOf<ApprovalPrompt>()
        override suspend fun present(prompt: ApprovalPrompt): InterlockChoice {
            prompts += prompt
            error("sys.shell.full must not reach the approval surface: ${prompt.capability.wire}")
        }
    }

    private class Fixture {
        val tiers = TierStore(MemoryPrefs())
        val presenter = NoPromptPresenter()
        private val runLog = RunLog(Files.createTempDirectory("full-shell-gate").toFile())
        private val gate = InterlockGate(
            tiers = tiers,
            broker = InterlockBroker(presenter, runLog),
            runLog = runLog,
            now = { 10_000L },
        )

        fun authorize(): GateResult = runBlocking {
            gate.authorize(
                descriptor = CapabilityRegistry.find(CapabilityId.SYS_SHELL_FULL)!!,
                targetLabel = null,
                paramDetail = null,
                argsIdentity = "identity",
                volatileTarget = false,
                budgetMs = 60_000,
                system = ProbeResult(SystemGrantState.GRANTED, emptyList()),
            )
        }
    }
}
