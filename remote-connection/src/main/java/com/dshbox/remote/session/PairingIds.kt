package com.dshbox.remote.session

import com.dshbox.remote.model.DeviceId
import com.dshbox.remote.model.EndpointId
import java.util.UUID

/**
 * 配对建档的标识生成：设备与端点标识全局唯一（UUID），跨设备不复用——
 * 在途建立的存活判定依赖「设备作废集合 + 端点代次」，标识复用会让旧设备
 * 的作废登记误伤新设备，故 uniqueness 在生成入口一次性保证。
 */
object PairingIds {

    /** 新设备标识：`dev-<uuid>`，全局唯一不复用。 */
    fun newDeviceId(): DeviceId = DeviceId("dev-" + UUID.randomUUID())

    /** 新端点标识：`ep-<uuid>`，全局唯一不复用。 */
    fun newEndpointId(): EndpointId = EndpointId("ep-" + UUID.randomUUID())
}
