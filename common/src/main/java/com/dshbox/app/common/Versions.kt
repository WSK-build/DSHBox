package com.dshbox.app.common

/**
 * 版本号比较（1.1.0 从 DshLayer / RuntimeUpdateManager 的两份重复实现收敛而来）。
 *
 * 处理 "0.1.0-rc.6" / "v0.1.2" / "0.1.1-rc.2-patched" 风格：
 * - 忽略前导 v；数值段（首个 '-' 之前）逐段比较；
 * - 数值段相同时按 semver 惯例：正式版（无预发布段）新于同版本号的预发布版；
 *   两个预发布段按字典序比较（"rc.2-patched" > "rc.2"，兼容既有 -patched 标记）。
 *
 * 与 1.0.0 实现的差异：旧实现在数值段相同时把「无预发布段」当作空串参与字典序，
 * 导致正式版被判旧于预发布版（如 0.1.1 < 0.1.1-rc.2）；此处已修正（VersionsTest 覆盖）。
 */
object Versions {

    fun compare(a: String, b: String): Int {
        val clean = { s: String -> s.trim().trimStart('v') }
        val (aNum, aPre) = splitPrerelease(clean(a))
        val (bNum, bPre) = splitPrerelease(clean(b))
        val pa = aNum.split('.').mapNotNull { it.toIntOrNull() }
        val pb = bNum.split('.').mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        return when {
            aPre == bPre -> 0
            aPre.isEmpty() -> 1
            bPre.isEmpty() -> -1
            else -> aPre.compareTo(bPre)
        }
    }

    /** a 是否比 b 新（严格大于）。 */
    fun isNewer(a: String, b: String): Boolean = compare(a, b) > 0

    /** "0.1.1-rc.2" -> ("0.1.1", "rc.2")；无 '-' 时预发布段为空串。 */
    private fun splitPrerelease(s: String): Pair<String, String> {
        val idx = s.indexOf('-')
        return if (idx < 0) s to "" else s.substring(0, idx) to s.substring(idx + 1)
    }
}
