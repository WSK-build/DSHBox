package com.dshbox.remote.render

import com.dshbox.remote.model.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 渲染器宿主重建判定测试：代次推进只发生在「终态/断连/释放后的重新建立」。
 * Compose 侧以「端点 + 代次」为键重建宿主，端点切换的换节点行为属运行时
 * 重组语义，不在 JVM 覆盖。
 */
class SessionSurfaceRestartTest {

    @Test fun restartOnlyFromReleasedLikeStatesIntoConnecting() {
        assertTrue(isSessionRestart(SessionState.Failed, SessionState.Connecting))
        assertTrue(isSessionRestart(SessionState.Disconnected, SessionState.Connecting))
        assertTrue(isSessionRestart(SessionState.Released, SessionState.Connecting))
    }

    @Test fun liveStatesDoNotTriggerRebuild() {
        // 同端点内 Ready/Loading/Connecting 不会直接回到 Connecting
        assertFalse(isSessionRestart(SessionState.Ready, SessionState.Connecting))
        assertFalse(isSessionRestart(SessionState.Loading, SessionState.Connecting))
        assertFalse(isSessionRestart(SessionState.Connecting, SessionState.Connecting))
        assertFalse(isSessionRestart(SessionState.Idle, SessionState.Connecting))
        assertFalse(isSessionRestart(SessionState.Terminated, SessionState.Connecting))
    }

    @Test fun transitionsNotEnteringConnectingAreNotRebuilds() {
        assertFalse(isSessionRestart(SessionState.Failed, SessionState.Released))
        assertFalse(isSessionRestart(SessionState.Loading, SessionState.Ready))
        assertFalse(isSessionRestart(SessionState.Ready, SessionState.Disconnected))
        assertFalse(isSessionRestart(SessionState.Connecting, SessionState.Failed))
    }

    @Test fun refreshUsesEngineReloadOnlyForTicketCarryingInitialLoad() {
        val origin = "http://127.0.0.5:51000"
        val credentialed = "$origin/pair#s3cret"
        // 首载不带凭据地址（含 full-remote 的注入式凭据会话）：恒走引擎内重载
        assertTrue(refreshUsesReload(initialLoadCarriesTicket = false, loadTarget = origin, loopbackOrigin = origin))
        // 首载带凭据地址且票据未消费（未降级）：引擎内重载重放合理
        assertTrue(refreshUsesReload(initialLoadCarriesTicket = true, loadTarget = credentialed, loopbackOrigin = origin))
        // 首载带凭据地址且已降级为裸 origin：必须显式重载（引擎重载会重放消费过的票据）
        assertFalse(refreshUsesReload(initialLoadCarriesTicket = true, loadTarget = origin, loopbackOrigin = origin))
    }

    @Test fun hostParamsAssemblyCoversFullRemoteCombination() {
        val origin = "http://127.0.0.5:51000"
        // full-remote：无首载地址 + 消费门控前提成立（凭据走注入器改写）
        val fullRemote = buildSessionHostParams(
            loopbackOrigin = origin,
            controllerInitialLoadUrl = null,
            sessionUsesConsumableCredential = true,
            pairingCredentialPending = true,
        )
        assertNull(fullRemote.initialLoadUrl)
        assertTrue(fullRemote.sessionUsesConsumableCredential)
        assertTrue(fullRemote.pairingCredentialPending)

        // gateway：首载地址保留（fragment 携带 secret）且属票据型首载
        val gateway = buildSessionHostParams(
            loopbackOrigin = origin,
            controllerInitialLoadUrl = "$origin/pair#s3cret",
            sessionUsesConsumableCredential = true,
            pairingCredentialPending = true,
        )
        assertEquals("$origin/pair#s3cret", gateway.initialLoadUrl)
        assertTrue(gateway.sessionUsesConsumableCredential)
        assertTrue(gateway.initialLoadCarriesTicket)

        // 已消费的 generic：路径型首载（不参与降级）
        val consumedGeneric = buildSessionHostParams(
            loopbackOrigin = origin,
            controllerInitialLoadUrl = "$origin/pair",
            sessionUsesConsumableCredential = false,
            pairingCredentialPending = false,
        )
        assertEquals("$origin/pair", consumedGeneric.initialLoadUrl)
        assertFalse(consumedGeneric.initialLoadCarriesTicket)

        // 与回环 origin 相同的首载地址视为无独立首载
        val redundant = buildSessionHostParams(
            loopbackOrigin = origin,
            controllerInitialLoadUrl = origin,
            sessionUsesConsumableCredential = false,
            pairingCredentialPending = false,
        )
        assertNull(redundant.initialLoadUrl)
        assertFalse(redundant.sessionUsesConsumableCredential)
        assertFalse(redundant.pairingCredentialPending)
        assertFalse(redundant.initialLoadCarriesTicket)
    }

    @Test fun pairingRejectionRequiresPendingCredentialAnd401or403() {
        // 未消费凭据集合覆盖首载式与注入式两类插件：bridge 端点 401 同样命中引导
        assertTrue(PairingFailurePolicy.isPairingRejected(401, credentialPending = true))
        assertTrue(PairingFailurePolicy.isPairingRejected(403, credentialPending = true))
        // 已消费（集合摘除）后的 401 是普通上游认证失败：不引导重配对
        assertFalse(PairingFailurePolicy.isPairingRejected(401, credentialPending = false))
        assertFalse(PairingFailurePolicy.isPairingRejected(403, credentialPending = false))
        // 非 401/403 与无状态失败维持原始错误
        assertFalse(PairingFailurePolicy.isPairingRejected(404, credentialPending = true))
        assertFalse(PairingFailurePolicy.isPairingRejected(null, credentialPending = true))
        assertFalse(PairingFailurePolicy.isPairingRejected(500, credentialPending = true))
    }
}
