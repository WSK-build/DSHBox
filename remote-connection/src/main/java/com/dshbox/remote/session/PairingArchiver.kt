package com.dshbox.remote.session

import com.dshbox.remote.model.Device
import com.dshbox.remote.model.DeviceId
import com.dshbox.remote.model.EndpointChoice
import com.dshbox.remote.model.EndpointId
import com.dshbox.remote.model.PairingTarget
import com.dshbox.remote.parse.ParsedPairing

/**
 * 配对建档出口：把解析产出的设备档案落盘（设备/端点标识全局唯一、代次
 * 归零、端点凭据入加密存档）并支持端点地址编辑（代次自增）。
 *
 * 由生产会话工厂实现（存档层集中在一个工厂实例内）；控制器的配对入口经
 * 此接口建档，测试以假实现驱动。
 */
interface PairingArchiver {

    /**
     * 把一次配对解析的产出建档：设备与端点生成全局唯一标识，端点代次归零，
     * 非空凭据按端点写入加密存档。失败抛异常（调用方以失败返回呈现）。
     * [displayName] 非空时覆盖解析产出的默认设备名（手动输入的设备备注）。
     *
     * [target] 为确认卡收集的归属目标：新建设备（既有行为）、并入既有设备
     * 或并入「无分组」（名称为空串的档案条目，没有则现建）。并入既有目标
     * 不在档时抛异常（设备可能在确认期间被删除）。
     *
     * [choices] 为确认卡收集的每端点选择（会话模式与保留档覆盖），数组序
     * 与解析端点顺序一一对应；已提供前缀逐条生效、缺项按解析默认值补齐、
     * 多余项丢弃（调用方先经选择核对收口，null 选择即全部默认）。会话模式
     * 落档前一律经直连口径函数收口（非 https 目标回落代理档），`非 https
     * + 直连` 死档不得经建档出口落进档案。选择一次写入端点档案（会话模
     * 式与保留档覆盖随建档同批落盘，不再二次调用覆盖入口）。
     */
    fun archivePairing(
        pairing: ParsedPairing,
        displayName: String? = null,
        choices: List<EndpointChoice>? = null,
        target: PairingTarget = PairingTarget.NewGroup(null),
    ): Device

    /**
     * 重新配对端点：粘贴的配对载荷经解析归一——剥离凭据的地址入清单、
     * 端点代次自增（以旧代次登记的在途建立与在用会话一并失效）。
     *
     * 端点对齐按主机匹配：触发端点对齐载荷中其 host 对应的端点（无命中
     * 沿用首个）；同设备与载荷解析器同类的其余端点，host 命中载荷端点时
     * 一并更新（多通道端点共享同一份长期凭据，只更新触发端点会让兄弟端
     * 点的旧凭据静默失效）。行为对齐（解析器标识/通道标签）只在凭据真变
     * 更时发生（带凭据载荷才是重新配对）；裸地址编辑保留原解析器与通道
     * 标签——隐式启发式不得把注入式端点重分类。凭据语义同理：载荷携带
     * 凭据时替换进加密存档并清除消费标记；裸地址编辑保留原凭据与消费标
     * 记不动。随载荷出现的会话级 CA 证书（如有）同口径更新。
     *
     * 会话模式与显式用户选择：[requestedSessionMode] 非空时为用户在编辑
     * 入口的显式选择，优先于载荷声明并只作用于触发端点；其余端点按
     * `载荷声明 ?: 端点既有` 取值。结果一律经会话模式口径函数收口（直连
     * 要求 https）：存量 `非 https + 直连` 死档由其对齐一并修复为代理档
     * （同一 URL 重确认也能触发该修复）。
     *
     * @return 发生变更（地址/凭据/CA/会话模式任一）的端点标识集合（含触发
     *   端点与同步更新的兄弟端点）：调用方对其中全部在用会话执行释放（兄
     *   弟会话仍持有旧令牌/旧 CA，静默沿用无效）；空集 = 地址与凭据均未变
     *   化且模式未改（无变更信号，不是错误）。
     */
    fun rePairEndpoint(
        deviceId: DeviceId,
        endpointId: EndpointId,
        pairing: ParsedPairing,
        requestedSessionMode: String? = null,
    ): Set<EndpointId>

    /** 当前持有未消费一次性凭据的端点集合（显示槽失败引导判定用）。 */
    fun pendingCredentialEndpoints(): Set<EndpointId>

    /**
     * 端点移动到另一台设备（用户手动整理归属）：端点条目从源设备档案摘出
     * 并入目标设备档案末尾，端点身份（标识/代次/会话模式/保留档覆盖）原样
     * 保留；端点凭据、会话级 CA 与消费标记随端点迁入目标设备的加密存档
     * （后续会话从目标设备读取凭据，凭据不随移动丢失）。设备 cookie jar
     * 为设备级存档不随端点迁移。源或目标设备不在档、两端点同设备或端点
     * 不在源设备下时静默忽略返回 false。
     */
    fun moveEndpoint(fromDeviceId: DeviceId, endpointId: EndpointId, toDeviceId: DeviceId): Boolean

    /**
     * 把端点移入新建分组（设备名 [name]，空名等同无分组）：新设备只承载
     * 该端点，凭据随端点迁移。源设备或端点不在档时返回 false。
     */
    fun moveEndpointToNewDevice(fromDeviceId: DeviceId, endpointId: EndpointId, name: String): Boolean = false

    /**
     * 删除端点（不删设备）：端点条目从设备档案移除，端点凭据、会话级 CA
     * 与消费标记一并清除。设备在档但端点不在其下时静默忽略返回 false；
     * 删除后设备无端点时调用方应转删除设备口径（销毁整档）。
     */
    fun removeEndpoint(deviceId: DeviceId, endpointId: EndpointId): Boolean

    /**
     * 设备重命名：只改设备显示名（无分组条目允许空串），端点、凭据与槽位
     * 均不动。设备不在档时静默忽略返回 false。
     */
    fun renameDevice(deviceId: DeviceId, name: String): Boolean = false

    /**
     * 端点级保留档覆盖写入（设备档案 v3 字段）：[retentionOverride] 为策略
     * 的档案编码值，null = 清除覆盖（继承全局默认）。设备或端点不在档时
     * 静默忽略。默认空实现：无存档出口的骨架工厂不承载覆盖落盘（内存清单
     * 仍即时生效）。
     */
    fun setEndpointRetentionOverride(deviceId: DeviceId, endpointId: EndpointId, retentionOverride: String?) = Unit
}
