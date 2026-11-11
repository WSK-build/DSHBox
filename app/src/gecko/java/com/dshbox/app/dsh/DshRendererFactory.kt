package com.dshbox.app.dsh

import com.dshbox.dsh.renderer.api.DshRendererAdapter
import com.dshbox.dsh.renderer.gecko.GeckoDshRendererAdapter

internal object DshRendererFactory {
    fun adapter(): DshRendererAdapter = GeckoDshRendererAdapter()

    /**
     * 本构建变体（gecko）的渲染器：其状态桥扩展（manifest 的 match 规则、
     * content/background 脚本的 `TRUSTED_ORIGIN`）与构建期 page-compat 配置
     * 都固化本机 origin `http://127.0.0.1:3080`，本机 DSH 的 webserver 端口
     * **在此变体下固定 3080、不启用端口自适应**（`SandboxConfig.
     * dshPortAdaptationEnabled` 据此关闭）；3080 被占用时由输出解析/健康
     * 检查显式暴露故障，不静默挪端口。
     */
    const val isGeckoVariant: Boolean = true
}
