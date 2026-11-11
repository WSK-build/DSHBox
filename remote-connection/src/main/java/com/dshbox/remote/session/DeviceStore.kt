package com.dshbox.remote.session

import com.dshbox.remote.model.Device
import com.dshbox.remote.model.DeviceId
import com.dshbox.remote.model.Endpoint
import com.dshbox.remote.model.EndpointId
import com.dshbox.remote.model.SessionMode
import com.dshbox.remote.model.retentionPolicyFromArchive
import com.dshbox.remote.model.toArchiveValue

/**
 * 设备与端点档案的持久化：配对解析产出的设备清单落盘于此，
 * 端点档案中记录会话内核写入的稳定端口（见 [SlotAllocator]）。
 *
 * 存储格式为模块私有的单行定长字段编码（字段以竖线分隔，特殊字符转义），
 * 每台设备一条键值记录；凭据与 cookie jar 不在此层存储（见 [CredentialVault]）。
 */
class DeviceStore(private val store: KeyValueStore) {

    /** 全部已建档设备（按存档顺序）。 */
    fun devices(): List<Device> = store.keys()
        .filter { it.startsWith(DEVICE_KEY_PREFIX) }
        .mapNotNull { DeviceArchiveCodec.decode(store.getString(it) ?: return@mapNotNull null) }
        .sortedBy { it.addedAtMillis }

    /** 读取单台设备；不存在返回 null。 */
    fun find(deviceId: DeviceId): Device? =
        store.getString(DEVICE_KEY_PREFIX + deviceId.value)?.let { DeviceArchiveCodec.decode(it) }

    /** 新增或更新设备档案（端点地址可编辑更新，条目身份不变）。 */
    fun save(device: Device) {
        store.putString(DEVICE_KEY_PREFIX + device.id.value, DeviceArchiveCodec.encode(device))
    }

    /** 删除设备档案（槽位/端口与存档的清理由调用方协调）。 */
    fun delete(deviceId: DeviceId) {
        store.remove(DEVICE_KEY_PREFIX + deviceId.value)
    }

    private companion object {
        const val DEVICE_KEY_PREFIX = "device."
    }
}

/** 设备档案的单行编码：`版本|id|名称|添加时间|端点数|端点…`。
 *
 * 端点组字段随版本不同：v1 每端点四字段（id/标签/地址/解析器），v2 起追加
 * 第五字段代次，v3 起追加第六字段保留档覆盖（空串 = 继承全局默认），v4 起
 * 追加第七字段会话模式（空串 = PROXIED）；读取兼容四个版本（v1 端点代次按
 * 0 处理，v1/v2 保留档覆盖按 null 补齐，v1~v3 会话模式按 null 补齐，不使
 * 既有设备掉档），损坏行由 [DeviceStore.devices] 跳过不入清单。
 */
internal object DeviceArchiveCodec {

    private const val VERSION = 4
    private const val SEPARATOR = '|'
    private const val V1_ENDPOINT_FIELDS = 4
    private const val V2_ENDPOINT_FIELDS = 5
    private const val V3_ENDPOINT_FIELDS = 6
    private const val V4_ENDPOINT_FIELDS = 7

    fun encode(device: Device): String = buildString {
        append(VERSION)
        field(device.id.value)
        field(device.name)
        field(device.addedAtMillis.toString())
        field(device.endpoints.size.toString())
        for (endpoint in device.endpoints) {
            field(endpoint.id.value)
            field(endpoint.channelLabel)
            field(endpoint.targetUrl)
            field(endpoint.parserId)
            field(endpoint.generation.toString())
            field(endpoint.retentionOverride ?: "")
            field(endpoint.sessionMode ?: "")
        }
    }

    fun decode(text: String): Device? {
        val fields = splitFields(text)
        if (fields.size < 5) return null
        val (versionText, id, name, addedAt, endpointCount) = fields
        val version = versionText.toIntOrNull() ?: return null
        if (id.isEmpty() || version !in 1..VERSION) return null
        val addedAtMillis = addedAt.toLongOrNull() ?: return null
        val count = endpointCount.toIntOrNull() ?: return null
        val endpointFields = fields.drop(5)
        val fieldsPerEndpoint = when (version) {
            1 -> V1_ENDPOINT_FIELDS
            2 -> V2_ENDPOINT_FIELDS
            3 -> V3_ENDPOINT_FIELDS
            else -> V4_ENDPOINT_FIELDS
        }
        if (count < 0 || endpointFields.size != count * fieldsPerEndpoint) return null
        val endpoints = endpointFields.chunked(fieldsPerEndpoint).map { group ->
            val endpoint = Endpoint(
                id = EndpointId(group[0]),
                // 旧档案中的中文标签归一化为通道枚举名（展示层经资源映射回六语）
                channelLabel = normalizeChannelLabel(group[1]),
                targetUrl = group[2],
                parserId = group[3],
            )
            when (fieldsPerEndpoint) {
                V2_ENDPOINT_FIELDS -> endpoint.copy(generation = group[4].toLongOrNull() ?: return null)
                V3_ENDPOINT_FIELDS -> endpoint.copy(
                    generation = group[4].toLongOrNull() ?: return null,
                    // 未知覆盖值按继承默认处理，不因脏数据丢设备
                    retentionOverride = group[5].takeIf { it.isNotEmpty() }
                        ?.let(::retentionPolicyFromArchive)?.toArchiveValue(),
                )
                V4_ENDPOINT_FIELDS -> endpoint.copy(
                    generation = group[4].toLongOrNull() ?: return null,
                    // 未知覆盖值按继承默认处理，不因脏数据丢设备
                    retentionOverride = group[5].takeIf { it.isNotEmpty() }
                        ?.let(::retentionPolicyFromArchive)?.toArchiveValue(),
                    // 未知会话模式按默认代理档处理，不因脏数据丢设备
                    sessionMode = group[6].takeIf { it.isNotEmpty() }
                        ?.let { value ->
                            runCatching { SessionMode.valueOf(value) }.getOrNull()
                        }?.name,
                )
                else -> endpoint
            }
        }
        return Device(
            id = DeviceId(id),
            name = name,
            endpoints = endpoints,
            addedAtMillis = addedAtMillis,
        )
    }

    // 旧档案中的通道标签为中文界面时期的持久化数据（非界面文案）：
    // 以 Unicode 转义书写以通过硬编码文案扫描，语义与原字符串完全一致
    private val legacyLanLabel = "局域网"       // i18n-skip：旧档案持久化标签，非界面文案
    private val legacyPublicLabel = "公网"           // i18n-skip：旧档案持久化标签，非界面文案
    private val legacyTunnelLabel = "隧道"           // i18n-skip：旧档案持久化标签，非界面文案

    /** 旧档案中文标签 → 通道枚举名；未知标签原样保留（用户备注语义）。 */
    private fun normalizeChannelLabel(label: String): String = when (label) {
        legacyLanLabel -> com.dshbox.remote.parse.ChannelKind.LAN.name
        legacyPublicLabel -> com.dshbox.remote.parse.ChannelKind.PUBLIC.name
        legacyTunnelLabel -> com.dshbox.remote.parse.ChannelKind.TUNNEL.name
        "URL" -> com.dshbox.remote.parse.ChannelKind.URL.name
        else -> label
    }

    private fun StringBuilder.field(value: String) {
        if (isNotEmpty()) append(SEPARATOR)
        append(value.escape())
    }

    private fun splitFields(text: String): List<String> =
        text.split(SEPARATOR).map { it.unescape() }

    // 字段值转义：分隔竖线与换行映射为内部占位控制符，避免与内容字符歧义。
    private fun String.escape(): String =
        replace("\\", "\u0000").replace(SEPARATOR.toString(), "\u0001").replace("\n", "\u0002")

    private fun String.unescape(): String =
        replace("\u0002", "\n").replace("\u0001", SEPARATOR.toString()).replace("\u0000", "\\")
}
