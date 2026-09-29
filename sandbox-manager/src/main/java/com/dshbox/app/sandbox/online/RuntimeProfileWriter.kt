package com.dshbox.app.sandbox.online

/**
 * 在线组装 S9：在设备上生成 runtime-profile.json。
 *
 * Schema 与构建期 `runtime-bundle/scripts/gen_profile.sh` 产物完全一致（`RuntimeProfile`
 * 解析端无差别），唯一差异是 hash 来源：离线流程是压缩归档 hash，这里是对层目录现算
 * （[DirHash]）。字段值全部来自受控来源（64 位十六进制 hash / 整数 / 有界字符集的
 * 版本号与时间戳），因此直接手工拼 JSON，不引入任何 JSON 依赖。
 *
 * node 项可空：在线导入拆分为「Linux（精简 Debian）层」与「node 层」两个独立入口，
 * 仅装 Debian 层时 profile 不含 node（assembly = [base, android-side]），node 层
 * 导入后由 [DpkgDbWriter 同族的 profile 再生成流程] 重写为含 node 的完整 profile。
 */
object RuntimeProfileWriter {

    private val SHA = Regex("^[0-9a-f]{64}$")
    /** 版本/时间戳/架构字段的可信字符集（防注入，也保证 JSON 无需转义）。 */
    private val SAFE = Regex("^[A-Za-z0-9._:+-]+$")

    fun build(
        bundleVersion: String,
        arch: String,
        baseVersion: String,
        baseSha256: String,
        baseSizeBytes: Long,
        nodeVersion: String?,
        nodeSha256: String?,
        nodeSizeBytes: Long?,
        asideVersion: String,
        asideSha256: String,
        asideSizeBytes: Long,
        builtAtIsoUtc: String,
    ): String {
        check(SHA.matches(baseSha256) && SHA.matches(asideSha256)) {
            "layer sha256 must be 64 hex chars"
        }
        check(nodeSha256 == null || SHA.matches(nodeSha256)) { "node sha256 must be 64 hex chars" }
        listOf(bundleVersion, arch, baseVersion, asideVersion, builtAtIsoUtc)
            .forEach { check(SAFE.matches(it)) { "unbounded profile field: $it" } }
        nodeVersion?.let { check(SAFE.matches(it)) { "unbounded profile field: $it" } }
        check(baseSizeBytes > 0 && asideSizeBytes > 0) { "layer size must be positive" }
        check(nodeSizeBytes == null || nodeSizeBytes > 0) { "node size must be positive" }
        check((nodeSha256 == null) == (nodeVersion == null) && (nodeSha256 == null) == (nodeSizeBytes == null)) {
            "node layer fields must be all null or all set"
        }
        val hasNode = nodeSha256 != null
        val nodeEntry = if (hasNode) """
    ,
    {
      "name": "node",
      "version": "$nodeVersion",
      "compression": "zstd",
      "sha256": "$nodeSha256",
      "size_bytes": $nodeSizeBytes,
      "env_file": ".dshbox/env.d/node.sh",
      "file": "node.tar.zst",
      "deps": ["base"]
    }""" else ""
        val assembly = if (hasNode) "[\"base\", \"node\", \"android-side\"]" else "[\"base\", \"android-side\"]"
        return """
{
  "bundle": {
    "kind": "runtime",
    "name": "dshapp-runtime-debian-$arch",
    "version": "$bundleVersion",
    "arch": "$arch",
    "compression": "zstd",
    "zstd_level": 19,
    "built_at": "$builtAtIsoUtc"
  },
  "layers": [
    {
      "name": "base",
      "version": "$baseVersion",
      "compression": "zstd",
      "sha256": "$baseSha256",
      "size_bytes": $baseSizeBytes,
      "env_file": ".dshbox/env.d/base.sh",
      "file": "base.tar.zst",
      "deps": []
    }$nodeEntry
    ,
    {
      "name": "android-side",
      "version": "$asideVersion",
      "compression": "zstd",
      "sha256": "$asideSha256",
      "size_bytes": $asideSizeBytes,
      "env_file": ".dshbox/env.d/android-side.sh",
      "file": "android-side.tar.zst",
      "deps": ["base"]
    }
  ],
  "assembly": $assembly
}"""
    }
}
