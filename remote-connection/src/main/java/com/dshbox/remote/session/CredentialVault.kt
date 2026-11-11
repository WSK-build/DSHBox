package com.dshbox.remote.session

import com.dshbox.remote.model.DeviceId
import com.dshbox.remote.model.EndpointId
import com.dshbox.remote.proxy.CookieJar

/**
 * 凭据存档的加密原语抽象：AES-GCM 等对称加解密的提供方。
 *
 * 生产实现密钥入 AndroidKeyStore（[AndroidKeystoreCipher]）；JVM 单测以
 * 可逆的测试实现替换，验证存档往返而不依赖系统密钥库。
 */
interface VaultCipher {
    fun encrypt(plain: ByteArray): ByteArray

    fun decrypt(cipher: ByteArray): ByteArray
}

/**
 * 凭据存档的原始字节存储抽象：加密后的密文 blob 按名称落盘。
 *
 * 生产实现写模块私有目录（[FileVaultStore]）；JVM 单测以内存实现替换。
 */
interface VaultStore {
    fun read(name: String): ByteArray?

    fun write(name: String, data: ByteArray)

    fun remove(name: String)
}

/**
 * saya-ch 通道兑换出的会话凭据三元组：会话 cookie（名/值）与 CSRF 令牌。
 * 只入设备加密存档，明文零日志零清单落盘。
 */
data class SayaSessionCredential(
    val cookieName: String,
    val cookieValue: String,
    val csrfToken: String,
)

/**
 * 设备级敏感存档：端点凭据、代理 cookie jar、设备门禁令牌的加密持久层。
 *
 * 明文只在内存出现：写入前经 [VaultCipher] 加密、读出后解密，密文经
 * [VaultStore] 落盘；删除设备即整体销毁，无残留。
 */
class CredentialVault(private val store: VaultStore, private val cipher: VaultCipher) {

    /** 保存设备的凭据键值对（覆盖式）；空映射清除凭据。 */
    fun saveCredentials(deviceId: DeviceId, credentials: Map<String, String>) {
        writeDeviceBlob(CREDENTIAL_PREFIX + deviceId.value, encodeMap(credentials))
    }

    /** 读取设备的凭据键值对；无存档返回空映射。 */
    fun loadCredentials(deviceId: DeviceId): Map<String, String> =
        readDeviceBlob(CREDENTIAL_PREFIX + deviceId.value)?.let(::decodeMap) ?: emptyMap()

    /** 保存设备的 cookie jar 序列化快照。 */
    fun saveCookieJar(deviceId: DeviceId, jar: CookieJar) {
        writeDeviceBlob(JAR_PREFIX + deviceId.value, jar.serialize().toByteArray(Charsets.UTF_8))
    }

    /** 读取设备的 cookie jar；无存档返回 null。 */
    fun loadCookieJar(deviceId: DeviceId): CookieJar? {
        val data = readDeviceBlob(JAR_PREFIX + deviceId.value) ?: return null
        return CookieJar().apply {
            load(String(data, Charsets.UTF_8))
        }
    }

    /**
     * 标记端点凭据已消费：一次性配对凭据（配对票/邀请码）完成兑换后置位，
     * 此后该端点的会话不再以凭据重建首次加载地址、注入器不再携带凭据——
     * 消费过的票回配对页只会失败。凭据更新（重新配对）时标记清除。
     */
    fun markCredentialConsumed(deviceId: DeviceId, endpointId: EndpointId) {
        saveCredentials(deviceId, loadCredentials(deviceId) + (consumedKey(endpointId) to "1"))
    }

    /** 端点凭据是否已消费；无标记返回 false。 */
    fun isCredentialConsumed(deviceId: DeviceId, endpointId: EndpointId): Boolean =
        loadCredentials(deviceId)[consumedKey(endpointId)] == "1"

    /** 清除端点凭据的消费标记（凭据更新即重新配对，票据恢复未消费语义）。 */
    fun clearCredentialConsumed(deviceId: DeviceId, endpointId: EndpointId) {
        saveCredentials(deviceId, loadCredentials(deviceId) - consumedKey(endpointId))
    }

    /**
     * 保存端点的会话级 CA 证书（Base64 原文，如自签上游配对描述符下发）：
     * 与端点凭据同存一份加密键值对，随设备删除一并销毁。
     */
    fun saveCaCertificate(deviceId: DeviceId, endpointId: EndpointId, base64: String) {
        saveCredentials(deviceId, loadCredentials(deviceId) + (caCertificateKey(endpointId) to base64))
    }

    /** 读取端点的会话级 CA 证书；无存档返回 null。 */
    fun loadCaCertificate(deviceId: DeviceId, endpointId: EndpointId): String? =
        loadCredentials(deviceId)[caCertificateKey(endpointId)]

    /** 端点会话级 CA 证书在设备凭据键值对中的键（合并写入口共用）。 */
    internal fun caCertificateKey(endpointId: EndpointId) = CA_CERTIFICATE_PREFIX + endpointId.value

    /**
     * saya-ch 通道的端点敏感条目读写（兑换产物与配对实例标识）：
     * `saya.instance.<ep>` = 配对实例标识（CA 指纹比对基准）、
     * `saya.cookieName.<ep>` = 会话 cookie 名、`saya.cookie.<ep>` = 会话
     * cookie 值、`saya.csrf.<ep>` = CSRF 令牌。全部入设备加密键值对，随
     * 端点搬移/删除与设备删除一并清理。
     */
    fun saveSayaPairingInstance(deviceId: DeviceId, endpointId: EndpointId, instance: String) {
        saveCredentials(deviceId, loadCredentials(deviceId) + (sayaInstanceKey(endpointId) to instance))
    }

    fun loadSayaPairingInstance(deviceId: DeviceId, endpointId: EndpointId): String? =
        loadCredentials(deviceId)[sayaInstanceKey(endpointId)]

    fun saveSayaSession(deviceId: DeviceId, endpointId: EndpointId, cookieName: String, cookieValue: String, csrfToken: String) {
        saveCredentials(
            deviceId,
            loadCredentials(deviceId) + mapOf(
                sayaCookieNameKey(endpointId) to cookieName,
                sayaCookieKey(endpointId) to cookieValue,
                sayaCsrfKey(endpointId) to csrfToken,
            ),
        )
    }

    /** 读取兑换出的会话三元组；未兑换（无会话 cookie）返回 null。 */
    fun loadSayaSession(deviceId: DeviceId, endpointId: EndpointId): SayaSessionCredential? {
        val credentials = loadCredentials(deviceId)
        val cookieValue = credentials[sayaCookieKey(endpointId)] ?: return null
        return SayaSessionCredential(
            cookieName = credentials[sayaCookieNameKey(endpointId)] ?: return null,
            cookieValue = cookieValue,
            csrfToken = credentials[sayaCsrfKey(endpointId)] ?: return null,
        )
    }

    /** 保存 saya-ch 的长期设备凭据与到期时间（native-renew 用）。 */
    fun saveSayaDevice(deviceId: DeviceId, endpointId: EndpointId, deviceToken: String, expiresAt: Long?) {
        saveCredentials(
            deviceId,
            loadCredentials(deviceId) +
                (sayaDeviceTokenKey(endpointId) to deviceToken) +
                (sayaSessionExpiresKey(endpointId) to (expiresAt?.toString() ?: "")),
        )
    }

    /** 读取 saya-ch 长期设备凭据；无存档返回 null。 */
    fun loadSayaDeviceToken(deviceId: DeviceId, endpointId: EndpointId): String? =
        loadCredentials(deviceId)[sayaDeviceTokenKey(endpointId)]?.takeIf { it.isNotBlank() }

    /** 读取 saya-ch 会话到期时间（epoch millis）；无存档返回 null。 */
    fun loadSayaSessionExpiresAt(deviceId: DeviceId, endpointId: EndpointId): Long? =
        loadCredentials(deviceId)[sayaSessionExpiresKey(endpointId)]?.toLongOrNull()

    internal fun sayaDeviceTokenKey(endpointId: EndpointId) = SAYA_DEVICE_TOKEN_PREFIX + endpointId.value

    internal fun sayaSessionExpiresKey(endpointId: EndpointId) = SAYA_SESSION_EXPIRES_PREFIX + endpointId.value

    internal fun sayaInstanceKey(endpointId: EndpointId) = SAYA_INSTANCE_PREFIX + endpointId.value

    internal fun sayaCookieNameKey(endpointId: EndpointId) = SAYA_COOKIE_NAME_PREFIX + endpointId.value

    internal fun sayaCookieKey(endpointId: EndpointId) = SAYA_COOKIE_PREFIX + endpointId.value

    internal fun sayaCsrfKey(endpointId: EndpointId) = SAYA_CSRF_PREFIX + endpointId.value

    /** 清除端点的全部 saya 键（重配对收口：旧兑换产物与旧 instance 一并失效）。 */
    fun clearSayaEntries(deviceId: DeviceId, endpointId: EndpointId) {
        saveCredentials(
            deviceId,
            loadCredentials(deviceId) - setOf(
                sayaInstanceKey(endpointId),
                sayaCookieNameKey(endpointId),
                sayaCookieKey(endpointId),
                sayaCsrfKey(endpointId),
                sayaDeviceTokenKey(endpointId),
                sayaSessionExpiresKey(endpointId),
            ),
        )
    }

    /**
     * 端点凭据的规范键（凭据存档键值对内，键 = `endpoint.<端点标识>`）：
     * 建档写入、会话读取、端点随设备迁移与删除清理共用同一构造——键口径
     * 收口在一处，杜绝「写入用一套前缀、搬移/清除用另一套」的漂移。
     */
    internal fun endpointCredentialKey(endpointId: EndpointId) = ENDPOINT_CREDENTIAL_PREFIX + endpointId.value

    /**
     * 端点敏感条目随设备迁移：凭据（`endpoint.`）、会话级 CA 证书
     * （`cacert.`）与消费标记（`consumed.`）从源设备的加密存档整体搬入
     * 目标设备（端点移动的档案重排配套）。源设备无该端点条目时是无损搬
     * 运（搬入空集无副作用），不视为错误。
     */
    internal fun moveEndpointEntries(fromDeviceId: DeviceId, endpointId: EndpointId, toDeviceId: DeviceId) {
        val from = loadCredentials(fromDeviceId)
        val moved = endpointSensitiveKeys(endpointId)
            .mapNotNull { key -> from[key]?.let { key to it } }
            .toMap()
        if (moved.isNotEmpty()) {
            saveCredentials(toDeviceId, loadCredentials(toDeviceId) + moved)
            saveCredentials(fromDeviceId, from - moved.keys)
        }
    }

    /** 清除端点的全部敏感条目（凭据/CA/消费标记三类键；端点删除的配套清理）。 */
    internal fun clearEndpointEntries(deviceId: DeviceId, endpointId: EndpointId) {
        saveCredentials(deviceId, loadCredentials(deviceId) - endpointSensitiveKeys(endpointId))
    }

    /** 端点在设备凭据键值对中的全部敏感键（凭据/CA/消费标记/saya 四类）。 */
    private fun endpointSensitiveKeys(endpointId: EndpointId): Set<String> = setOf(
        endpointCredentialKey(endpointId),
        caCertificateKey(endpointId),
        consumedKey(endpointId),
        sayaInstanceKey(endpointId),
        sayaCookieNameKey(endpointId),
        sayaCookieKey(endpointId),
        sayaCsrfKey(endpointId),
        sayaDeviceTokenKey(endpointId),
        sayaSessionExpiresKey(endpointId),
    )

    /** 销毁设备的全部敏感存档：凭据、cookie jar 一次清空，不可逆。 */
    fun destroyDevice(deviceId: DeviceId) {
        store.remove(CREDENTIAL_PREFIX + deviceId.value)
        store.remove(JAR_PREFIX + deviceId.value)
    }

    internal fun consumedKey(endpointId: EndpointId) = CONSUMED_PREFIX + endpointId.value

    private fun writeDeviceBlob(name: String, plain: ByteArray) {
        if (plain.isEmpty()) {
            store.remove(name)
        } else {
            store.write(name, cipher.encrypt(plain))
        }
    }

    /**
     * 读取设备的密文 blob 并解密。
     *
     * 解密失败（密文损坏、密钥轮换后读到旧密文等，典型如 AEADBadTagException）
     * 降级为空存档：删除损坏条目并按无存档处理，会话建立不因个别条目损坏而
     * 整体失败；后续写入以新密文重建该条目。
     */
    private fun readDeviceBlob(name: String): ByteArray? {
        val cipherText = store.read(name) ?: return null
        return try {
            cipher.decrypt(cipherText)
        } catch (_: Exception) {
            store.remove(name)
            null
        }
    }

    private companion object {
        const val CREDENTIAL_PREFIX = "cred."
        const val JAR_PREFIX = "jar."
        const val CONSUMED_PREFIX = "consumed."
        const val CA_CERTIFICATE_PREFIX = "cacert."
        const val SAYA_INSTANCE_PREFIX = "saya.instance."
        const val SAYA_COOKIE_NAME_PREFIX = "saya.cookieName."
        const val SAYA_COOKIE_PREFIX = "saya.cookie."
        const val SAYA_DEVICE_TOKEN_PREFIX = "saya.deviceToken."
        const val SAYA_SESSION_EXPIRES_PREFIX = "saya.sessionExpires."
        const val SAYA_CSRF_PREFIX = "saya.csrf."

        /**
         * 端点凭据在设备凭据键值对中的键前缀（`endpoint.<端点标识>`）：与
         * 会话工厂的读取口径同源（[endpointCredentialKey] 唯一构造点）。
         */
        const val ENDPOINT_CREDENTIAL_PREFIX = "endpoint."

        // 键值对的存档行格式：`key=value` 每行一条，换行符不入键与值。
        fun encodeMap(map: Map<String, String>): ByteArray =
            map.entries.joinToString("\n") { (key, value) -> "$key=$value" }
                .toByteArray(Charsets.UTF_8)

        fun decodeMap(data: ByteArray): Map<String, String> =
            String(data, Charsets.UTF_8).lineSequence()
                .filter { it.contains('=') }
                .associate { line ->
                    val index = line.indexOf('=')
                    line.substring(0, index) to line.substring(index + 1)
                }
    }
}
