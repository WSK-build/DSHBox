package com.dshbox.pluginmanager.market.data

import com.dshbox.pluginmanager.market.model.HostCompatibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * semver 区间判定。
 *
 * 两条纪律：**读不懂的区间一律未知**（不能当成不满足），以及
 * **`^`/`~` 推算出来的上界不是宿主的天花板**——生态里大量插件写着 `^0.0.1`，
 * 把它的上界当成"不能在更新的宿主上跑"会让整页插件都变红。
 */
class SemverTest {

    @Test
    fun comparesVersions() {
        assertTrue(Semver.compare("0.2.0", "0.1.9") > 0)
        assertTrue(Semver.compare("0.1.0", "0.2.0") < 0)
        assertEquals(0, Semver.compare("1.2.3", "1.2.3"))
    }

    @Test
    fun prereleaseSortsBeforeItsRelease() {
        assertTrue(Semver.compare("0.1.5-rc.3", "0.1.5") < 0)
        assertTrue(Semver.compare("0.1.5-rc.3", "0.1.4") > 0)
    }

    @Test
    fun acceptsCaretRange() {
        assertEquals(true, Semver.satisfies("0.1.5", "^0.1.0"))
        // DSH 的发布线本身就是预发布，必须能被区间接纳。
        assertEquals(true, Semver.satisfies("0.1.5-rc.3", "^0.1.0"))
        assertEquals(false, Semver.satisfies("0.2.0", "^0.1.0"))
    }

    @Test
    fun acceptsTildeAndComparatorRanges() {
        assertEquals(true, Semver.satisfies("0.1.5", "~0.1.0"))
        assertEquals(false, Semver.satisfies("0.2.0", "~0.1.0"))
        assertEquals(true, Semver.satisfies("0.2.0", ">=0.1.0"))
        assertEquals(false, Semver.satisfies("0.2.0", "<=0.1.0"))
        assertEquals(true, Semver.satisfies("0.1.0", "0.1.0"))
        assertEquals(false, Semver.satisfies("0.1.1", "0.1.0"))
    }

    @Test
    fun acceptsAlternativeRanges() {
        assertEquals(true, Semver.satisfies("0.3.0", "^0.1.0 || ^0.3.0"))
        assertEquals(false, Semver.satisfies("0.2.0", "^0.1.0 || ^0.3.0"))
    }

    @Test
    fun treatsWildcardAsEverything() {
        assertEquals(true, Semver.satisfies("9.9.9", "*"))
        assertEquals(true, Semver.satisfies("9.9.9", ""))
    }

    @Test
    fun unparseableRangeIsUnknownNotUnsatisfied() {
        for (range in listOf("workspace:^", "catalog:default", "^", ">=abc", "not a range")) {
            assertNull("区间读不懂必须是未知：$range", Semver.satisfies("0.1.5", range))
        }
    }

    @Test
    fun unparseableVersionIsUnknown() {
        assertNull(Semver.satisfies("latest", "^0.1.0"))
    }
}

/**
 * 兼容性结论的推导。
 *
 * 取不到信息一律 UNKNOWN；只有声明确实表明不满足才判 INCOMPATIBLE。
 */
class HostCompatJudgeTest {

    private fun engine(range: String) = HostRequirement(HostRequirement.Kind.ENGINE, range)

    private fun peer(range: String) =
        HostRequirement(HostRequirement.Kind.PEER, range, "@deepseek-ai/dsh")

    @Test
    fun noDeclarationsIsUnknownUndeclared() {
        val result = HostCompatJudge.judge(emptyList(), "0.1.5")
        assertEquals(HostCompatibility.Status.UNKNOWN, result.status)
        assertEquals(HostCompatibility.Basis.UNDECLARED, result.basis)
    }

    @Test
    fun missingHostVersionIsUnknownManifest() {
        val result = HostCompatJudge.judge(listOf(engine(">=0.1.0")), null)
        assertEquals(HostCompatibility.Status.UNKNOWN, result.status)
        assertEquals(HostCompatibility.Basis.MANIFEST, result.basis)
        assertEquals(">=0.1.0", result.requirement)
    }

    @Test
    fun allSatisfiedIsCompatible() {
        val result = HostCompatJudge.judge(listOf(engine(">=0.1.0"), peer("^0.1.0")), "0.1.5")
        assertEquals(HostCompatibility.Status.COMPATIBLE, result.status)
    }

    @Test
    fun belowDeclaredFloorIsIncompatible() {
        val result = HostCompatJudge.judge(listOf(engine(">=0.2.0")), "0.1.5")
        assertEquals(HostCompatibility.Status.INCOMPATIBLE, result.status)
    }

    @Test
    fun explicitUpperCeilingViolationIsIncompatible() {
        val result = HostCompatJudge.judge(listOf(peer("<=0.1.0")), "0.2.0")
        assertEquals(HostCompatibility.Status.INCOMPATIBLE, result.status)
    }

    @Test
    fun implicitCaretCeilingDoesNotMakeItIncompatible() {
        // `^0.0.1` 的上界从来不是宿主的天花板，高于它只能是未知或兼容。
        val result = HostCompatJudge.judge(listOf(peer("^0.0.1")), "0.5.0")
        assertEquals(HostCompatibility.Status.COMPATIBLE, result.status)
    }

    @Test
    fun engineCaretCeilingIsTakenLiterally() {
        // `engines.dsh` 是显式宿主要求，按字面判定。
        val result = HostCompatJudge.judge(listOf(engine("^0.1.0")), "0.2.0")
        assertEquals(HostCompatibility.Status.INCOMPATIBLE, result.status)
    }

    @Test
    fun unparseableDeclarationKeepsTheResultUnknown() {
        val result = HostCompatJudge.judge(listOf(engine("workspace:^")), "0.1.5")
        assertEquals(HostCompatibility.Status.UNKNOWN, result.status)
        assertEquals(HostCompatibility.Basis.MANIFEST, result.basis)
    }

    @Test
    fun definiteMismatchIsNotErasedByAnUnknownDeclaration() {
        val result = HostCompatJudge.judge(
            listOf(engine(">=0.2.0"), engine("workspace:^")),
            "0.1.5",
        )
        assertEquals(HostCompatibility.Status.INCOMPATIBLE, result.status)
    }

    @Test
    fun satisfiedPlusUnknownIsUnknown() {
        val result = HostCompatJudge.judge(
            listOf(engine(">=0.1.0"), engine("catalog:default")),
            "0.1.5",
        )
        assertEquals(HostCompatibility.Status.UNKNOWN, result.status)
    }

    @Test
    fun requirementJoinsDistinctDeclarations() {
        val result = HostCompatJudge.judge(
            listOf(engine(">=0.1.0"), peer("^0.1.0"), peer(">=0.1.0")),
            "0.1.5",
        )
        assertEquals(">=0.1.0 ∩ ^0.1.0", result.requirement)
    }
}
