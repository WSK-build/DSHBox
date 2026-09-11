package com.dshbox.app.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the shared version comparator (1.1.0, M6 — previously duplicated in
 * DshLayer and RuntimeUpdateManager). Covers the exact strings that flow through
 * the DSH update arbitration.
 */
class VersionsTest {

    @Test
    fun numericComparison() {
        assertTrue(Versions.isNewer("0.2.0", "0.1.1"))
        assertTrue(Versions.isNewer("1.0.0", "0.9.9"))
        assertFalse(Versions.isNewer("0.1.1", "0.1.1"))
        assertEquals(0, Versions.compare("0.1.1", "0.1.1"))
    }

    @Test
    fun preReleaseMarkerBreaksTies() {
        assertTrue(Versions.isNewer("0.1.1-rc.2", "0.1.1-rc.1"))
        assertTrue(Versions.isNewer("0.1.1", "0.1.1-rc.2"))
        assertTrue(Versions.isNewer("0.1.2-alpha.2", "0.1.1-rc.2"))
    }

    @Test
    fun vPrefixIsIgnored() {
        assertEquals(0, Versions.compare("v0.1.1", "0.1.1"))
        assertTrue(Versions.isNewer("v0.2.0", "0.1.1"))
    }

    @Test
    fun patchedSuffixComparesGreaterThanBare() {
        // The bundled asset name derives version "0.1.1-rc.2-patched"; bare npm
        // releases are recorded WITHOUT the marker after the 1.1.0 fix.
        assertTrue(Versions.isNewer("0.1.1-rc.2-patched", "0.1.1-rc.2"))
    }

    @Test
    fun unknownVersionComparesOlderThanAnyRealRelease() {
        // "unknown" has no numeric segments: every segment compares 0 vs real
        // numbers, so any real release wins. This is why a failed version
        // discovery used to let the bundled provision overwrite an offline import.
        assertTrue(Versions.isNewer("0.1.1-rc.2", "unknown"))
        assertTrue(Versions.isNewer("0.0.1", "unknown"))
    }

    /**
     * 预发布段的数字必须按**数值**比较，不能按字典序。
     *
     * 整段字典序下 `"rc.9" > "rc.10"`（字符 '9' > '1'），会把 rc.9 判为新于 rc.10 →
     * 「已装 rc.9、入站 rc.10」被当成降级而静默跳过升级。上游预发布号会走到两位数，
     * 一旦踩上，停在 rc.9 的设备将永远收不到更新，且日志里没有任何报错。
     */
    @Test
    fun multiDigitPrereleaseNumbersCompareNumerically() {
        assertTrue("rc.10 应新于 rc.9", Versions.isNewer("0.1.5-rc.10", "0.1.5-rc.9"))
        assertFalse("rc.9 不应新于 rc.10", Versions.isNewer("0.1.5-rc.9", "0.1.5-rc.10"))
        assertEquals("rc.2 与 rc.02 数值相等", 0, Versions.compare("0.1.5-rc.02", "0.1.5-rc.2"))
        // 同位数不受影响
        assertTrue(Versions.isNewer("0.1.5-rc.2", "0.1.5-rc.1"))
        // 跨整数位
        assertTrue(Versions.isNewer("0.1.5-rc.100", "0.1.5-rc.99"))
        assertTrue(Versions.isNewer("0.1.5-alpha.10", "0.1.5-alpha.2"))
    }

    /** 段数不同按 semver：公共段相同才比较段数，段数多者更大。 */
    @Test
    fun longerPrereleaseWinsWhenSharedSegmentsEqual() {
        assertTrue(Versions.isNewer("0.1.5-rc.2.1", "0.1.5-rc.2"))
        assertFalse(Versions.isNewer("0.1.5-rc.2", "0.1.5-rc.2.1"))
        assertEquals(0, Versions.compare("0.1.5-rc.2", "0.1.5-rc.2"))
    }

    /** 非数字段仍按字典序（alpha < beta < rc），保持既有行为。 */
    @Test
    fun namedPrereleaseSegmentsStayLexicographic() {
        assertTrue(Versions.isNewer("0.1.5-rc.1", "0.1.5-beta.9"))
        assertTrue(Versions.isNewer("0.1.5-beta.1", "0.1.5-alpha.9"))
    }

    /**
     * 本次升级（0.1.1-rc.2 → 0.1.5-rc.2）在真实仲裁条件下的判定。
     *
     * `DshLayer.installFromBundle` 的跳过条件是 `compareVersions(current, incoming) >= 0`，
     * 这里锁定各真实场景都不被误跳过（除刻意不降级的场景）。
     */
    @Test
    fun realUpgradeArbitrationScenarios() {
        // 已装旧内置 → 入站新内置：必须替换
        assertTrue(Versions.compare("0.1.1-rc.2", "0.1.5-rc.2") < 0)
        // 老 APK 留下的 -patched 记录 → 必须替换
        assertTrue(Versions.compare("0.1.1-rc.2-patched", "0.1.5-rc.2") < 0)
        // 在线更新到 latest(=0.1.5-rc.1) 的用户 → 必须替换
        assertTrue(Versions.compare("0.1.5-rc.1", "0.1.5-rc.2") < 0)
        // 已离线导入同版本 → 保留（不白折腾）
        assertEquals(0, Versions.compare("0.1.5-rc.2", "0.1.5-rc.2"))
        // 已装更新版 → 保留（不降级）
        assertTrue(Versions.compare("0.1.6", "0.1.5-rc.2") > 0)
        // 预发布 → 正式版：替换
        assertTrue(Versions.compare("0.1.5-rc.2", "0.1.5") < 0)
        // 正式版 → 预发布：不降级
        assertTrue(Versions.compare("0.1.5", "0.1.5-rc.2") > 0)
    }
}
