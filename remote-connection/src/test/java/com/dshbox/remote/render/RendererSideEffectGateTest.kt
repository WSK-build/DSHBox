package com.dshbox.remote.render

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 隐藏表面的渲染器副作用门控：文件选择器与外链等系统级副作用只在可见表面
 * 发起。UI 与 Android 回调链路无法 JVM 覆盖，判定抽出为纯函数锁定口径。
 */
class RendererSideEffectGateTest {

    @Test fun visibleSurfaceHandlesRendererSideEffects() {
        assertTrue(shouldHandleRendererSideEffects(true))
    }

    @Test fun hiddenSurfaceSuppressesRendererSideEffects() {
        // 隐藏表面（热池驻留）不拉起系统选择器与系统浏览器
        assertFalse(shouldHandleRendererSideEffects(false))
    }
}
