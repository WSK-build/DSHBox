package com.dshbox.pluginmanager.safemode

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * 「绝对安全模式」条目集合的本地缓存。
 *
 * 取条目集合要跑一次 `dsh --dump-config`，在设备上约 10~30 秒；而绝大多数开关操作
 * 之间profile 并没有变化。缓存用**两个文件系统指纹**判定是否需要重跑：
 * profile 清单（`package.json`）与 `node_modules` 目录的 mtime——安装/卸载插件都会
 * 改变它们。指纹不一致、缓存缺失或读不出一律**重跑**（宁慢不错）。
 *
 * 缓存只存条目 id；不存任何配置内容。
 */
class AbsoluteTargetsCache(private val file: File) {

    data class Fingerprint(val manifestModified: Long, val nodeModulesModified: Long)

    /** 读缓存；指纹不一致或内容不可读时返回 null。 */
    fun read(current: Fingerprint): List<String>? {
        if (!file.isFile) return null
        val json = runCatching { JSONObject(file.readText(Charsets.UTF_8)) }.getOrNull() ?: return null
        if (json.optLong(KEY_MANIFEST) != current.manifestModified) return null
        if (json.optLong(KEY_NODE_MODULES) != current.nodeModulesModified) return null
        val array = json.optJSONArray(KEY_IDS) ?: return null
        val ids = mutableListOf<String>()
        for (index in 0 until array.length()) {
            val id = array.optString(index)
            if (id.isNotBlank()) ids += id
        }
        return ids
    }

    /** 写缓存（仅在一次成功取到条目集合之后调用）。 */
    fun write(current: Fingerprint, ids: List<String>): Boolean = runCatching {
        val json = JSONObject().apply {
            put(KEY_MANIFEST, current.manifestModified)
            put(KEY_NODE_MODULES, current.nodeModulesModified)
            put(KEY_IDS, JSONArray().apply { ids.forEach { put(it) } })
        }
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.toString(), Charsets.UTF_8)
        tmp.renameTo(file) || runCatching {
            file.writeText(json.toString(), Charsets.UTF_8)
            tmp.delete()
            true
        }.getOrDefault(false)
    }.getOrDefault(false)

    private companion object {
        const val KEY_MANIFEST = "manifestModified"
        const val KEY_NODE_MODULES = "nodeModulesModified"
        const val KEY_IDS = "ids"
    }
}
