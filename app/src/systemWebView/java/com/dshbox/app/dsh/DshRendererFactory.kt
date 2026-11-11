package com.dshbox.app.dsh

import com.dshbox.dsh.renderer.api.DshRendererAdapter
import com.dshbox.dsh.renderer.system.SystemDshRendererAdapter

internal object DshRendererFactory {
    fun adapter(): DshRendererAdapter = SystemDshRendererAdapter()

    /**
     * 本构建变体（systemWebView）的渲染器按运行期地址加载：可信 origin 由
     * 容器构造时的实际 baseUrl 决定，端口自适应（`SandboxConfig.
     * dshPortAdaptationEnabled`）启用。
     */
    const val isGeckoVariant: Boolean = false
}
