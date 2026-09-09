package com.dshbox.app.ui.files

import com.dshbox.app.R
import com.dshbox.app.util.Layer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 风险弹窗文案选择必须与入口筛选同源（Layer 判定）。
 * 此前移动文案用 entry.risk（名称口径），node/dsh 层内文件命中 NORMAL 落到
 * 错误兜底（显示 DSH 数据文案），恰在 R1 最高风险场景上张冠李戴。
 */
class RiskDialogTextTest {

    @Test
    fun moveActionMapsLayerToMoveTexts() {
        assertEquals(R.string.files_risk_move_dsh, riskDialogTextRes(Layer.DSH_DATA, isMoveAction = true, sandboxRunning = false))
        assertEquals(R.string.files_risk_move_layer, riskDialogTextRes(Layer.NODE, isMoveAction = true, sandboxRunning = false))
        assertEquals(R.string.files_risk_move_layer, riskDialogTextRes(Layer.DSH, isMoveAction = true, sandboxRunning = false))
    }

    @Test
    fun moveActionAppendsSandboxRunningHintForLayers() {
        assertEquals(
            R.string.files_risk_move_layer_running,
            riskDialogTextRes(Layer.NODE, isMoveAction = true, sandboxRunning = true),
        )
        assertEquals(
            R.string.files_risk_move_layer_running,
            riskDialogTextRes(Layer.DSH, isMoveAction = true, sandboxRunning = true),
        )
    }

    @Test
    fun nonMoveActionMapsLayerToLayerTexts() {
        // §4.2 复查补充：非移动动作（新建/导入等）也要有运行环境层语义与停机建议
        assertEquals(R.string.files_risk_layer, riskDialogTextRes(Layer.NODE, isMoveAction = false, sandboxRunning = false))
        assertEquals(R.string.files_risk_layer, riskDialogTextRes(Layer.DSH, isMoveAction = false, sandboxRunning = false))
        assertEquals(
            R.string.files_risk_layer_running,
            riskDialogTextRes(Layer.NODE, isMoveAction = false, sandboxRunning = true),
        )
    }

    @Test
    fun systemAndDshDataLayersKeepTexts() {
        assertEquals(R.string.files_risk_system, riskDialogTextRes(Layer.SYSTEM_DIR, isMoveAction = false, sandboxRunning = false))
        // 复查第八轮：DSH_DATA 用 §4.2 定稿措辞的文件向文案（旧 key 把文件称作「目录」）
        assertEquals(R.string.files_risk_dsh_data, riskDialogTextRes(Layer.DSH_DATA, isMoveAction = false, sandboxRunning = false))
    }

    @Test
    fun moveActionFallsBackToSystemTextForSystemDir() {
        // 移动入口已在源侧拒绝系统目录，此处仅锁定兜底不再落 DSH 文案
        assertEquals(R.string.files_risk_system, riskDialogTextRes(Layer.SYSTEM_DIR, isMoveAction = true, sandboxRunning = false))
    }

    @Test
    fun harmlessLayersFallBackToGenericText() {
        assertEquals(R.string.files_risk_generic, riskDialogTextRes(Layer.WORKSPACE, isMoveAction = false, sandboxRunning = false))
        assertEquals(R.string.files_risk_generic, riskDialogTextRes(Layer.BASE, isMoveAction = false, sandboxRunning = false))
    }
}
