package developer.access.adb

import java.io.File

/**
 * 已配对设备清单的读取。
 *
 * ## 为什么不直接编辑这个文件
 *
 * `adb_known_hosts.pb` 是 **protobuf 二进制**，由 adb 自己维护。明确：
 * 不直接编辑，需要清空时整体删除让 adb 重建。此处只**读**，用于在面板上如实呈现
 * "这台机器认得哪些设备"——用户据此判断是否需要重新配对。
 *
 * ## 解析方式与其取舍
 *
 * 没有随 adb 发布的 `.proto` schema，故不能反序列化成结构体。这里按 protobuf 的
 * 线格式做**最小遍历**：跳过未知字段，只取出长度分隔字段里的可打印串（设备 GUID
 * 与公钥），足以在界面上标识"有几台、分别是哪个标识"。取不到时如实返回空列表，
 * 不伪造条目。
 */
class DeviceRegistry(private val knownHostsFile: File) {

    /** 一条已配对设备记录。 */
    data class PairedDevice(
        /** 设备标识（GUID 或序列号样式的可打印串）；取不到时为空。 */
        val identifier: String,
    )

    /**
     * 读取已配对设备。
     *
     * @return 条目列表；文件不存在或无法解析时返回空列表。
     */
    fun list(): List<PairedDevice> {
        if (!knownHostsFile.isFile) return emptyList()
        val bytes = runCatching { knownHostsFile.readBytes() }.getOrNull() ?: return emptyList()
        if (bytes.isEmpty()) return emptyList()
        val found = LinkedHashSet<String>()
        collectPrintable(bytes, found)
        return found.map { PairedDevice(it) }
    }

    /** 是否已有配对记录。 */
    fun hasRecords(): Boolean = knownHostsFile.isFile && knownHostsFile.length() > 0

    /**
     * 递归遍历 protobuf 字段，收集长度分隔字段中的可打印串。
     *
     * 只认 wire type 2（length-delimited）：GUID 与公钥都以这种形式存放。
     * 遇到不可解析的片段即停止该层，不外抛——界面不应因一个坏文件整体失败。
     */
    private fun collectPrintable(data: ByteArray, out: MutableSet<String>) {
        var i = 0
        while (i < data.size) {
            val key = readVarint(data, i) ?: return
            i = key.second
            val wireType = (key.first and 0x07).toInt()
            when (wireType) {
                0 -> i = (readVarint(data, i) ?: return).second          // varint
                1 -> i += 8                                              // fixed64
                5 -> i += 4                                              // fixed32
                2 -> {                                                   // length-delimited
                    val len = readVarint(data, i) ?: return
                    i = len.second
                    val size = len.first.toInt()
                    if (size < 0 || i + size > data.size) return
                    val chunk = data.copyOfRange(i, i + size)
                    i += size
                    val text = printableOf(chunk)
                    if (text != null) out += text else collectPrintable(chunk, out)
                }
                else -> return
            }
        }
    }

    /** 整段都是可打印 ASCII 且长度合理时返回该串。 */
    private fun printableOf(chunk: ByteArray): String? {
        if (chunk.size < MIN_IDENTIFIER_CHARS || chunk.size > MAX_IDENTIFIER_CHARS) return null
        val text = chunk.toString(Charsets.US_ASCII)
        return if (text.all { it in ' '..'~' } && text.isNotBlank()) text else null
    }

    /** 读取一个 varint，返回（值, 下一个偏移）；越界返回 null。 */
    private fun readVarint(data: ByteArray, start: Int): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var i = start
        while (i < data.size && shift < 64) {
            val b = data[i].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            i++
            if (b and 0x80 == 0) return result to i
            shift += 7
        }
        return null
    }

    private companion object {
        /** 太短的串通常是解析残片而非设备标识。 */
        const val MIN_IDENTIFIER_CHARS = 8

        /** 公钥 base64 可到数百字符；上限只用于挡住异常大块。 */
        const val MAX_IDENTIFIER_CHARS = 1024
    }
}
