package com.dshbox.pluginmanager.market.data

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 更新检测。
 *
 * 这一组用例的核心只有一条：**"没查到"不能被说成"已是最新"**。所以每个用例
 * 除了断言 `updateAvailable`，还要断言 `checked`——后者才是"我们到底比过没有"
 * 的答案，UI 的「已是最新」分支只认它。
 */
class UpdateDetectorTest {

    /** 可编程的假查询：记录调用次数与被查的包名。 */
    private class FakeLookup(
        private val answers: Map<String, String?>,
        private val delayMs: Long = 0,
    ) : LatestVersionLookup {
        val calls = AtomicInteger()
        val queried = mutableListOf<String>()
        val inFlight = AtomicInteger()
        val maxInFlight = AtomicInteger()

        override fun lookup(npmName: String): String? {
            calls.incrementAndGet()
            synchronized(queried) { queried.add(npmName) }
            val now = inFlight.incrementAndGet()
            maxInFlight.updateAndGet { maxOf(it, now) }
            try {
                if (delayMs > 0) Thread.sleep(delayMs)
                return answers[npmName]
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }

    private fun detector(
        lookup: LatestVersionLookup,
        ttlMs: Long = 600_000,
        now: () -> Long = { 0L },
        negativeTtlMs: Long = 120_000,
    ) = UpdateDetector(
        lookup,
        ttlMs = ttlMs,
        maxConcurrency = 4,
        clock = now,
        negativeTtlMs = negativeTtlMs,
    )

    // ── 单包判定：checked 的三种取值 ────────────────────────────────

    @Test
    fun partialLocalVersionIsComparedAsAVersionNotLexicographically() {
        // 字典序会把 "0.9" 排在 "0.10.0" 之后（'9' > '1'），于是显示"已是最新"、
        // 用户永远看不到更新入口。缺位版本补 0 后按版本序比，就能看出更新。
        val subject = detector(FakeLookup(mapOf("foo" to "0.10.0")))
        val status = subject.detect("foo", "^0.9.0", "0.9")
        assertTrue("0.10.0 比 0.9 新", status.updateAvailable)
        assertTrue("两端都能解析，就算真的比过", status.checked)
    }

    @Test
    fun unparseableLocalVersionIsNotChecked() {
        // "v1.0" 不是 semver：不能拿字典序替我们下结论，只能报"未检测"。
        val subject = detector(FakeLookup(mapOf("foo" to "2.0.0")))
        val status = subject.detect("foo", "^1.0.0", "v1.0")
        assertFalse(status.checked)
        assertFalse(status.updateAvailable)
    }

    @Test
    fun unparseableRemoteVersionIsNotChecked() {
        val subject = detector(FakeLookup(mapOf("foo" to "latest")))
        val status = subject.detect("foo", "^1.0.0", "1.0.0")
        assertFalse("对端版本读不懂就不能说已是最新", status.checked)
        assertFalse(status.updateAvailable)
    }

    @Test
    fun newerRemoteVersionIsACompletedCheckWithAnUpdate() {
        val subject = detector(FakeLookup(mapOf("foo" to "2.0.0")))
        val status = subject.detect("foo", "^1.0.0", "1.0.0")
        assertTrue(status.checked)
        assertTrue(status.updateAvailable)
        assertEquals("2.0.0", status.latest)
        assertEquals("npm", status.kind)
    }

    @Test
    fun sameRemoteVersionIsCheckedAndUpToDate() {
        val subject = detector(FakeLookup(mapOf("foo" to "1.0.0")))
        val status = subject.detect("foo", "^1.0.0", "1.0.0")
        assertTrue("拿到了对端版本才算比过", status.checked)
        assertFalse(status.updateAvailable)
    }

    @Test
    fun olderRemoteVersionIsNotReportedAsAnUpdate() {
        val subject = detector(FakeLookup(mapOf("foo" to "0.9.0")))
        val status = subject.detect("foo", "^1.0.0", "1.0.0")
        assertTrue(status.checked)
        assertFalse("只向前进：latest 指向旧版不能变成一次降级", status.updateAvailable)
    }

    @Test
    fun failedLookupIsNotCheckedAndNeverSaysUpToDate() {
        val subject = detector(FakeLookup(emptyMap()))
        val status = subject.detect("foo", "^1.0.0", "1.0.0")
        assertFalse("查询失败绝不能置 checked", status.checked)
        assertFalse(status.updateAvailable)
        assertNull(status.latest)
    }

    @Test
    fun lookupFailureIsSwallowedIntoAnUncheckedResult() {
        val throwing = LatestVersionLookup { throw IllegalStateException("network down") }
        val status = detector(throwing).detect("foo", "^1.0.0", "1.0.0")
        assertFalse(status.checked)
        assertFalse(status.updateAvailable)
    }

    @Test
    fun unknownLocalVersionIsNotChecked() {
        // 拿到了对端版本，但本地版本读不出来 —— 还是没比过。
        val subject = detector(FakeLookup(mapOf("foo" to "2.0.0")))
        val status = subject.detect("foo", "^1.0.0", null)
        assertFalse(status.checked)
        assertEquals("2.0.0", status.latest)
    }

    @Test
    fun nonNpmSourcesAreNeverCheckedAndNeverQueried() {
        val lookup = FakeLookup(mapOf("foo" to "2.0.0"))
        val subject = detector(lookup)
        for (spec in listOf("link:../foo", "file:/tmp/foo", "github:owner/foo", "git+https://x/y.git")) {
            val status = subject.detect("foo", spec, "1.0.0")
            assertFalse("$spec 没有「最新版」可言", status.checked)
            assertFalse(status.updateAvailable)
        }
        assertEquals("非 npm 来源不该联网查询", 0, lookup.calls.get())
    }

    @Test
    fun aliasSpecQueriesTheRealPackageName() {
        val lookup = FakeLookup(mapOf("real-pkg" to "2.0.0"))
        val status = detector(lookup).detect("alias", "npm:real-pkg@1.0.0", "1.0.0")
        assertTrue(status.checked)
        assertTrue(status.updateAvailable)
        assertEquals(listOf("real-pkg"), lookup.queried)
    }

    // ── 批量：缓存与并发 ────────────────────────────────────────────

    @Test
    fun checkMarksUncheckedWhenTheQueryFails() = runBlocking {
        val subject = detector(FakeLookup(emptyMap()))
        val result = subject.check(mapOf("foo" to "^1.0.0"), currentVersion = { "1.0.0" })
        assertFalse(result.getValue("foo").checked)
        assertFalse(result.getValue("foo").updateAvailable)
    }

    @Test
    fun checkRecomputesAgainstTheCurrentLocalVersion() = runBlocking {
        // 缓存只缓存"对端版本"：本地版本变了（刚更新完），结论必须跟着变，
        // 否则刚更新完的行还在显示"有更新"。
        val lookup = FakeLookup(mapOf("foo" to "2.0.0"))
        val subject = detector(lookup)
        val before = subject.check(mapOf("foo" to "^1.0.0"), currentVersion = { "1.0.0" })
        assertTrue(before.getValue("foo").updateAvailable)
        val after = subject.check(mapOf("foo" to "^2.0.0"), currentVersion = { "2.0.0" })
        assertFalse(after.getValue("foo").updateAvailable)
        assertTrue(after.getValue("foo").checked)
        assertEquals("对端版本只查一次（TTL 内）", 1, lookup.calls.get())
    }

    @Test
    fun successfulLookupsAreCachedUntilTheTtlExpires() = runBlocking {
        var now = 0L
        val lookup = FakeLookup(mapOf("foo" to "2.0.0"))
        val subject = detector(lookup, ttlMs = 1_000, now = { now })
        subject.check(mapOf("foo" to "^1.0.0"), currentVersion = { "1.0.0" })
        now = 500
        subject.check(mapOf("foo" to "^1.0.0"), currentVersion = { "1.0.0" })
        assertEquals(1, lookup.calls.get())
        now = 1_001
        subject.check(mapOf("foo" to "^1.0.0"), currentVersion = { "1.0.0" })
        assertEquals("TTL 过期后必须重新查", 2, lookup.calls.get())
    }

    @Test
    fun forceBypassesTheCache() = runBlocking {
        val lookup = FakeLookup(mapOf("foo" to "2.0.0"))
        val subject = detector(lookup)
        subject.check(mapOf("foo" to "^1.0.0"), currentVersion = { "1.0.0" })
        subject.check(mapOf("foo" to "^1.0.0"), currentVersion = { "1.0.0" }, force = true)
        assertEquals(2, lookup.calls.get())
    }

    @Test
    fun failuresAreNegativelyCachedSoLocalReloadsDoNotRefetch() = runBlocking {
        // 拨一次开关就会重跑一轮更新检测；失败若不缓存，每个包 8 秒超时 × 有限
        // 并发 = 几十秒的无反馈卡顿。失败进 2 分钟负缓存，期内不重试。
        var now = 0L
        val lookup = FakeLookup(emptyMap())
        val subject = detector(lookup, ttlMs = 600_000, now = { now }, negativeTtlMs = 120_000)
        subject.check(mapOf("foo" to "^1.0.0"), currentVersion = { "1.0.0" })
        assertEquals(1, lookup.calls.get())
        now = 1
        subject.check(mapOf("foo" to "^1.0.0"), currentVersion = { "1.0.0" })
        assertEquals("负缓存期内不该重试", 1, lookup.calls.get())
        now = 119_999
        subject.check(mapOf("foo" to "^1.0.0"), currentVersion = { "1.0.0" })
        assertEquals("仍未过期", 1, lookup.calls.get())
    }

    @Test
    fun negativeCacheExpiresAndThenRetries() = runBlocking {
        var now = 0L
        val lookup = FakeLookup(emptyMap())
        val subject = detector(lookup, ttlMs = 600_000, now = { now }, negativeTtlMs = 120_000)
        subject.check(mapOf("foo" to "^1.0.0"), currentVersion = { "1.0.0" })
        assertEquals(1, lookup.calls.get())
        now = 120_001
        subject.check(mapOf("foo" to "^1.0.0"), currentVersion = { "1.0.0" })
        assertEquals("负缓存过期后必须重试", 2, lookup.calls.get())
    }

    @Test
    fun forceBypassesTheNegativeCacheToo() = runBlocking {
        var now = 0L
        val lookup = FakeLookup(emptyMap())
        val subject = detector(lookup, ttlMs = 600_000, now = { now }, negativeTtlMs = 120_000)
        subject.check(mapOf("foo" to "^1.0.0"), currentVersion = { "1.0.0" })
        subject.check(mapOf("foo" to "^1.0.0"), currentVersion = { "1.0.0" }, force = true)
        assertEquals("显式刷新必须绕过负缓存", 2, lookup.calls.get())
    }

    @Test
    fun nonNpmDependenciesSkipTheNetworkEntirely() = runBlocking {
        val lookup = FakeLookup(mapOf("foo" to "2.0.0"))
        val result = detector(lookup).check(
            mapOf("foo" to "link:../foo", "bar" to "file:/tmp/bar"),
            currentVersion = { "1.0.0" },
        )
        assertEquals(0, lookup.calls.get())
        assertFalse(result.getValue("foo").checked)
        assertFalse(result.getValue("bar").checked)
    }

    @Test
    fun npmQueriesRunWithBoundedConcurrency() = runBlocking {
        val names = (1..10).associate { "pkg$it" to "^1.0.0" }
        val lookup = FakeLookup(names.keys.associateWith { "2.0.0" }, delayMs = 30)
        val result = detector(lookup).check(names, currentVersion = { "1.0.0" })
        assertEquals(10, result.size)
        assertTrue("并发上限不该被突破，实际=${lookup.maxInFlight.get()}", lookup.maxInFlight.get() <= 4)
        assertTrue("应该真的并行，实际=${lookup.maxInFlight.get()}", lookup.maxInFlight.get() > 1)
    }

    @Test
    fun invalidateDropsTheCache() = runBlocking {
        val lookup = FakeLookup(mapOf("foo" to "2.0.0"))
        val subject = detector(lookup)
        subject.check(mapOf("foo" to "^1.0.0"), currentVersion = { "1.0.0" })
        subject.invalidate()
        subject.check(mapOf("foo" to "^1.0.0"), currentVersion = { "1.0.0" })
        assertEquals(2, lookup.calls.get())
    }

    // ── 一键更新的目标判定 ──────────────────────────────────────────

    @Test
    fun updateTargetUsesTheRealNpmName() {
        assertEquals(UpdateTarget.Npm("foo"), updateTargetFor("foo", "^1.0.0"))
        assertEquals(UpdateTarget.Npm("real-pkg"), updateTargetFor("alias", "npm:real-pkg@1.0.0"))
        assertEquals(UpdateTarget.Npm("foo"), updateTargetFor("foo", null))
    }

    @Test
    fun nonNpmSourcesCannotBeUpdated() {
        for (spec in listOf("link:../foo", "file:/tmp/foo", "github:owner/foo", "git+https://x/y.git")) {
            assertEquals("$spec 不该被允许一键更新", UpdateTarget.Unsupported, updateTargetFor("foo", spec))
        }
    }

    @Test
    fun kindOfSpecMatchesTheUpstreamVocabulary() {
        assertEquals("npm", kindOfSpec(null))
        assertEquals("npm", kindOfSpec("^1.0.0"))
        assertEquals("linked", kindOfSpec("link:../foo"))
        assertEquals("file", kindOfSpec("file:/tmp/foo"))
        assertEquals("github", kindOfSpec("github:owner/repo"))
    }
}
