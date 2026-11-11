package com.dshbox.remote.model

/**
 * DSH 页签显示槽的指向：本机页面或某个远程端点的会话。
 *
 * 这是显示槽的唯一真相：同一时刻只有一个指向生效（单一活跃不变量），
 * 由 [com.dshbox.remote.RemoteConnectionController.activeDisplay] 承载。
 */
sealed interface DisplayTarget {
    /** 本机 DSH：显示槽交还给既有本机链路，行为与无远程会话时一致。 */
    data object Local : DisplayTarget

    /** 远程会话：显示槽由该端点的会话表面接管。 */
    data class Session(val endpointId: EndpointId) : DisplayTarget
}
