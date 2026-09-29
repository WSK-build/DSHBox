package interlock.relay.core.spi

import interlock.relay.core.exec.RelayBackend
import interlock.relay.core.protocol.BackendId

/**
 * 执行后端的装配口。core 在装配时先备好三个内建后端（a11y / direct / shizuku），
 * 再把整张表交给宿主；宿主可原样返回、追加自研后端或替换某一条。分发器只认
 * 返回表里存在的后端标识。
 *
 * 返回表里**少掉**的标识既不报错、也不回落到内建那条：分发器按标识选路，缺的那一条即
 * 「该后端在本装配下不存在」，靠它承担的能力逐条降级为不可用。要保留一条后端，
 * 就得把它放进返回表。
 */
fun interface RelayExecutor {

    /** 对内建后端表做增删改；返回的表即分发器的可用集。 */
    fun configure(builtIn: Map<BackendId, RelayBackend>): Map<BackendId, RelayBackend>

    companion object {
        /** 缺省实现：原样返回内建表。 */
        val BUILT_IN: RelayExecutor = RelayExecutor { it }
    }
}
