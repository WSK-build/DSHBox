package interlock.relay.core.spi

import interlock.relay.core.protocol.CapabilityDescriptor
import interlock.relay.core.protocol.CapabilityId
import interlock.relay.core.protocol.CapabilityRegistry

/**
 * 能力表的装配口。core 内建全表是缺省值；宿主可按自己的档位与可用性追加、裁剪或
 * 整体替换。闸门、能力清单与资产重铺都以这一口的返回值为准，不再各自持表。
 */
interface RelayCapabilities {

    /** 全量能力描述符。返回顺序即清单顺序。 */
    fun all(): List<CapabilityDescriptor>

    /**
     * 能力此刻是否按宿主策略可用。缺省恒真；宿主按档位/可用性裁剪时，裁掉的条目
     * 会出现在能力清单的 usable=false 里。
     */
    fun isUsable(id: CapabilityId): Boolean = true

    /** 缺省实现：core 内建全表，逐条可用。 */
    object BuiltIn : RelayCapabilities {
        override fun all(): List<CapabilityDescriptor> = CapabilityRegistry.allDescriptors
    }
}
