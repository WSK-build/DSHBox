package com.dshbox.app.common

/**
 * 版本号比较（1.1.0 从 DshLayer / RuntimeUpdateManager 的两份重复实现收敛而来）。
 *
 * 处理 "0.1.0-rc.6" / "v0.1.2" / "0.1.1-rc.2-patched" 风格：
 * - 忽略前导 v；数值段（首个 '-' 之前）逐段比较；
 * - 数值段相同时按 semver 惯例：正式版（无预发布段）新于同版本号的预发布版；
 * - 预发布段**按段比较，纯数字段按数值比**（见 [comparePrerelease]）。
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
            else -> comparePrerelease(aPre, bPre)
        }
    }

    /**
     * 预发布段比较（如 "rc.2" vs "rc.10"、"alpha.2" vs "rc.1"）。
     *
     * 逐段比较：**双方均为纯数字时按数值比**，否则按字典序。整段字典序是错的——
     * `"rc.9".compareTo("rc.10") > 0`（字符 '9' > '1'），会把 rc.9 判为新于 rc.10，
     * 于是「已装 rc.9、入站 rc.10」被当成降级而静默跳过升级。上游预发布号会走到两位数，
     * 一旦踩上，停在 rc.9 的设备将永远收不到更新且无任何报错。
     *
     * 段数不同时按 semver：段数多者更大（"rc.2.1" > "rc.2"）。缺段视为最小。
     * 非数字段（rc / alpha / beta）仍按字典序，保持既有行为（"...-patched" > "..."）。
     */
    private fun comparePrerelease(a: String, b: String): Int {
        val sa = a.split('.')
        val sb = b.split('.')
        val shared = minOf(sa.size, sb.size)
        for (i in 0 until shared) {
            val x = sa[i]
            val y = sb[i]
            val nx = x.toIntOrNull()
            val ny = y.toIntOrNull()
            val c = if (nx != null && ny != null) nx.compareTo(ny) else x.compareTo(y)
            if (c != 0) return c
        }
        // 公共段完全相同：段数多者更大（semver 规则），缺段视为最小。
        return sa.size.compareTo(sb.size)
    }

    /** a 是否比 b 新（严格大于）。 */
    fun isNewer(a: String, b: String): Boolean = compare(a, b) > 0

    /** "0.1.1-rc.2" -> ("0.1.1", "rc.2")；无 '-' 时预发布段为空串。 */
    private fun splitPrerelease(s: String): Pair<String, String> {
        val idx = s.indexOf('-')
        return if (idx < 0) s to "" else s.substring(0, idx) to s.substring(idx + 1)
    }
}
