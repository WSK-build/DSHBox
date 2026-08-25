package com.dshbox.app.sandbox

import org.json.JSONObject
import java.io.File

/**
 * Parsed runtime-profile.json — the single source of truth for how the runtime
 * layers (base/node/android-side) are assembled and validated. Parsed from the
 * runtime bundle's manifest; see runtime-bundle/scripts/gen_profile.sh.
 *
 * This is read by DefaultSandboxManager (env assembly + layer validation) and
 * by BundledRuntimeInstaller (per-layer unpack + checksum). DSH is NOT a layer
 * here: it is a separate runtime product managed by the app (RuntimeUpdateManager,
 * installed at runtime-current/dsh) and is never part of this profile.
 */
data class RuntimeProfile(
    val version: String,
    val arch: String,
    val compression: String,
    val zstdLevel: Int,
    val layers: List<RuntimeLayer>,
    val assembly: List<String>,
) {
    fun layer(name: String): RuntimeLayer? = layers.firstOrNull { it.name == name }

    companion object {
        private const val TAG = "RuntimeProfile"

        fun parse(file: File): RuntimeProfile? = try {
            parse(JSONObject(file.readText()))
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "runtime-profile.json unreadable: ${t.message}")
            null
        }

        fun parse(root: JSONObject): RuntimeProfile? = try {
            val bundle = root.optJSONObject("bundle") ?: return null
            val layersJson = root.optJSONArray("layers") ?: return null
            val layers = buildList {
                for (i in 0 until layersJson.length()) {
                    val l = layersJson.optJSONObject(i) ?: continue
                    add(
                        RuntimeLayer(
                            name = l.optString("name"),
                            version = l.optString("version"),
                            compression = l.optString("compression"),
                            sha256 = l.optString("sha256"),
                            sizeBytes = l.optLong("size_bytes", 0L),
                            envFile = l.optString("env_file"),
                            file = l.optString("file"),
                            deps = l.optJSONArray("deps")?.let { arr ->
                                buildList { for (j in 0 until arr.length()) add(arr.optString(j)) }
                            } ?: emptyList(),
                        ),
                    )
                }
            }
            val asmArr = root.optJSONArray("assembly") ?: JSONArrayOf(layers.map { it.name })
            RuntimeProfile(
                version = bundle.optString("version"),
                arch = bundle.optString("arch"),
                compression = bundle.optString("compression"),
                zstdLevel = bundle.optInt("zstd_level", 19),
                layers = layers,
                assembly = buildList { for (j in 0 until asmArr.length()) add(asmArr.optString(j)) },
            )
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "runtime-profile.json parse failed: ${t.message}")
            null
        }

        private fun JSONArrayOf(items: List<String>) = org.json.JSONArray(items)
    }
}

/** One runtime layer description. */
data class RuntimeLayer(
    val name: String,
    val version: String,
    val compression: String,
    val sha256: String,
    val sizeBytes: Long,
    val envFile: String,
    val file: String,
    val deps: List<String>,
) {
    val isZstd: Boolean get() = compression.equals("zstd", ignoreCase = true)
    val isGzip: Boolean get() = compression.equals("gzip", ignoreCase = true)
}
