package com.dshbox.app.dsh

import com.dshbox.dsh.renderer.api.DshRendererAdapter
import com.dshbox.dsh.renderer.system.SystemDshRendererAdapter

internal object DshRendererFactory {
    fun adapter(): DshRendererAdapter = SystemDshRendererAdapter()
}
