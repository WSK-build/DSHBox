package com.dshbox.app.dsh

import com.dshbox.dsh.renderer.api.DshRendererAdapter
import com.dshbox.dsh.renderer.gecko.GeckoDshRendererAdapter

internal object DshRendererFactory {
    fun adapter(): DshRendererAdapter = GeckoDshRendererAdapter()
}
