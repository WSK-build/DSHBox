package com.dshbox.remote.scan

import org.junit.Assert.assertEquals
import org.junit.Test

/** 相机权限流状态机测试：授予/一次拒绝（可再弹）/永久拒绝三阶段判定。 */
class QrScanPermissionTest {

    @Test fun grantedEntersCamera() {
        assertEquals(
            QrPermissionPhase.CAMERA,
            QrScanPermissionLogic.phaseAfterRequest(granted = true, shouldShowRationale = false),
        )
        assertEquals(
            QrPermissionPhase.CAMERA,
            QrScanPermissionLogic.phaseAfterRequest(granted = true, shouldShowRationale = true),
        )
    }

    @Test fun denialWithRationaleOffersRetry() {
        assertEquals(
            QrPermissionPhase.RATIONALE,
            QrScanPermissionLogic.phaseAfterRequest(granted = false, shouldShowRationale = true),
        )
    }

    @Test fun permanentDenialOnlyOffersManualFallback() {
        // 拒绝后系统不再弹框（勾选"不再询问"或厂商策略锁定）：仅手动输入出口
        assertEquals(
            QrPermissionPhase.PERMANENT,
            QrScanPermissionLogic.phaseAfterRequest(granted = false, shouldShowRationale = false),
        )
    }
}
