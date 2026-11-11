package com.dshbox.remote.scan

/**
 * 相机权限流状态机（纯逻辑，JVM 可测）：
 *
 * ```
 * 进入（未授权）→ 自动请求 ──授予──→ CAMERA
 *                     └─拒绝──→ RATIONALE（可再次请求 + 手动输入出口）
 *                                └─连续拒绝且不再弹系统框──→ PERMANENT（仅手动输入出口）
 * ```
 */
enum class QrPermissionPhase {

    /** 相机已授权：进入取景。 */
    CAMERA,

    /** 被拒一次且系统仍可再弹框：呈现说明与「再次请求」/「手动输入」双出口。 */
    RATIONALE,

    /** 永久拒绝（连续拒绝且系统不再弹框）：仅手动输入出口。 */
    PERMANENT,
}

object QrScanPermissionLogic {

    /**
     * 请求结果后的阶段判定。
     *
     * @param granted 本次请求是否授予。
     * @param shouldShowRationale 拒绝后系统是否仍愿再弹说明框（false 且已拒
     *   视为永久拒绝——用户勾选了"不再询问"或厂商策略锁定）。
     */
    fun phaseAfterRequest(granted: Boolean, shouldShowRationale: Boolean): QrPermissionPhase = when {
        granted -> QrPermissionPhase.CAMERA
        shouldShowRationale -> QrPermissionPhase.RATIONALE
        else -> QrPermissionPhase.PERMANENT
    }
}
