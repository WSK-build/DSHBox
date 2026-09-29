package interlock.relay.core.exec

import interlock.relay.core.protocol.CapabilityDescriptor
import interlock.relay.core.protocol.CapabilityId
import interlock.relay.core.protocol.RelayError
import interlock.relay.core.protocol.SurfaceKind
import interlock.relay.core.storage.RelayPaths
import org.json.JSONObject

/** 一次已获授权、已定执行模式的具体调用。 */
data class BackendCall(
    val requestId: String,
    val descriptor: CapabilityDescriptor,
    val args: JSONObject,
    val surface: SurfaceKind,
    val paths: RelayPaths,
    /**
     * 交给后端时这条请求在通道里还剩多少时间（客户端 ttl 扣掉已耗与回写余量）。
     *
     * 需要它的是"自己也要等"的能力（`ui.waitFor`）：预算一到，信箱会把整条请求判成
     * `E_TRANSPORT_TIMEOUT`，助手读到的是"宿主没应答"，而真相是"界面还没变成那样"。
     * 后端按这份预算自己收口，才能把真实原因交回去。
     */
    val budgetMs: Long,
)

sealed class BackendResult {
    /**
     * 后端不回传执行模式：模式在裁决处就已定下，后端无权改道，
     * 让它自报用过哪个表面等于给同一字段开第二个真值来源。
     */
    data class Ok(
        val data: JSONObject = JSONObject(),
        val artifacts: List<String> = emptyList(),
        /** 产物字节数由写入方报，授权记录侧的 artifacts 是沙盒地址、在宿主进程里 stat 不到。 */
        val artifactBytes: Long = 0L,
        val reason: String? = null,
    ) : BackendResult()

    /**
     * [retryable] 由错误码决定，助手侧据此判断要不要显式重试。
     *
     * [consentKind] 标注这条失败卡在哪一种许可上（字面量见
     * `RelayResponse.CONSENT_KIND_*`）：本模块自己的审批与 Android 系统的采集同意
     * 互不代替，各自失败要给用户的是不同的下一步。空表示这一趟不涉及许可归类。
     */
    data class Failed(
        val error: RelayError,
        val reason: String? = null,
        val consentKind: String? = null,
    ) : BackendResult()
}

/**
 * 三条执行通路的共同契约。
 *
 * 后端只接收数据与「已裁决的执行模式」，不参与模式选择，也不得自行改道；
 * 跨后端的公共判定一律上收到表面层与裁决层。后端之间互不可见。
 */
interface RelayBackend {
    /** 挂起而非阻塞：手势与截屏等平台接口以回调形式返回结果。 */
    suspend fun execute(call: BackendCall): BackendResult

    /**
     * 该后端能否在指定执行模式下承担此能力。分发时按注册表偏好顺序询问。
     *
     * 只回答「这个后端实没实现」，不回答「现在能不能跑」：后者属于系统权限闸门，
     * 混进来会让能力清单和闸门各有两套真值。运行时不可用请在 [execute] 里返回失败。
     */
    fun supports(capability: CapabilityId, surface: SurfaceKind): Boolean

    /**
     * 只读探测，不发起任何操作，也**不得有副作用**。
     * 用于状态展示与熔断判据。能力清单每秒问一次，它顺手改一次连接状态的话，
     * 状态上报就成了连接的驱动方。
     */
    fun available(): Boolean

    /**
     * 掉线自愈的钩子：需要维持连接的后端在这里把线接回来。
     *
     * 只有两个调用时机：真有一次调用要用这个后端（分发处），以及通道周期巡检。
     * 与 [available] 分开的理由就一条——能力清单那一趟读取如果顺带重连，重连回调又会
     * 触发一次重铺能力清单，这是一条自己喂自己的循环，只靠冷却挡着。
     * 默认什么都不做：多数后端没有需要修的连接。
     */
    fun reconnect() {}

    /**
     * 只读回查「这个节点编号现在属于哪个应用、是哪个控件」，不得有副作用。
     *
     * 闸门要判断目标是不是系统授权界面，而助手常常只递一个 `nodeId` —— 光看参数认不出来，
     * 必须按当前控件树回查一次。默认回 null：多数后端没有「节点」这个概念。
     */
    fun targetOwner(nodeId: Int): TargetOwner? = null

    /** 节点归属。两个字段都可能取不到，取不到时由调用方按"不确定"处理。 */
    data class TargetOwner(val packageName: String?, val viewId: String?)
}
