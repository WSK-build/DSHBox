package com.dshbox.pluginmanager.market.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预发布通道事实字段（`newerPrerelease`）。
 *
 * 硬约束：该字段只陈述事实，永远不得把 `updateAvailable` 变成 true；
 * 且仅在用户装的不是 `latest` 那条线时才发请求，否则每次刷新多出四倍流量。
 */
class UpdateDetectorPrereleaseTest {

    private class FakeLatest(private val values: Map<String, String>) : LatestVersionLookup {
        val queried = mutableListOf<String>()
        override fun lookup(npmName: String): String? {
            queried += npmName
            return values[npmName]
        }
    }

    private class FakeTags(private val values: Map<String, String>) : TagVersionLookup {
        val queried = mutableListOf<String>()
        override fun lookup(npmName: String, tag: String): String? {
            queried += "$npmName@$tag"
            return values["$npmName@$tag"]
        }
    }

    private fun detector(latest: LatestVersionLookup, tags: TagVersionLookup) =
        UpdateDetector(latest, tagLookup = tags)

    @Test
    fun `稳定版且 latest 更新时不查任何 tag`() {
        val tags = FakeTags(emptyMap())
        val status = detector(FakeLatest(mapOf("p" to "2.0.0")), tags)
            .detect("p", "p@1.0.0", "1.0.0")

        assertTrue(status.updateAvailable)
        assertNull(status.newerPrerelease)
        assertTrue("有更新在 latest 上时不该查通道", tags.queried.isEmpty())
    }

    @Test
    fun `稳定版且已是最新时也不查`() {
        val tags = FakeTags(emptyMap())
        val status = detector(FakeLatest(mapOf("p" to "1.0.0")), tags)
            .detect("p", "p@1.0.0", "1.0.0")

        assertFalse(status.updateAvailable)
        assertNull(status.newerPrerelease)
        assertTrue(tags.queried.isEmpty())
    }

    @Test
    fun `预发布用户会查四个通道并取最高`() {
        // 最高的是 `alpha` 通道里的 .9，而 alpha 在 tag 列表里排第二 ——
        // 这样才验证了「取最高」而不是「取第一个命中」。
        val tags = FakeTags(
            mapOf(
                "p@next" to "0.2.1-alpha.3",
                "p@alpha" to "0.2.1-alpha.9",
                "p@beta" to "0.2.1-alpha.5",
                "p@canary" to "0.1.9",       // 比当前更旧，必须被排除
            ),
        )
        val status = detector(FakeLatest(mapOf("p" to "0.2.1-alpha.2")), tags)
            .detect("p", "p@0.2.1-alpha.2", "0.2.1-alpha.2")

        assertEquals(
            "必须且只查这四个通道（上游无 rc）",
            setOf("p@next", "p@alpha", "p@beta", "p@canary"),
            tags.queried.toSet(),
        )
        assertEquals("0.2.1-alpha.9", status.newerPrerelease)
        assertFalse("这条字段绝不置 updateAvailable", status.updateAvailable)
    }

    @Test
    fun `通道查询全部失败时保持 null`() {
        val tags = FakeTags(emptyMap())
        val status = detector(FakeLatest(mapOf("p" to "0.2.1-alpha.2")), tags)
            .detect("p", "p@0.2.1-alpha.2", "0.2.1-alpha.2")

        assertNull(status.newerPrerelease)
        assertFalse(status.updateAvailable)
        assertEquals(4, tags.queried.size)
    }

    @Test
    fun `当前版本高于 latest 时也算跟另一条线`() {
        // latest 指向更老的一条线：此时不能判成"有更新"。
        val tags = FakeTags(mapOf("p@alpha" to "0.3.1-alpha.1"))
        val status = detector(FakeLatest(mapOf("p" to "0.2.0")), tags)
            .detect("p", "p@0.3.0", "0.3.0")

        assertFalse(status.updateAvailable)
        assertEquals("0.3.1-alpha.1", status.newerPrerelease)
    }

    @Test
    fun `非 npm 来源不查通道`() {
        val tags = FakeTags(emptyMap())
        val status = detector(FakeLatest(emptyMap()), tags)
            .detect("p", "link:../local", "0.2.1-alpha.2")

        assertNull(status.newerPrerelease)
        assertFalse(status.checked)
        assertTrue(tags.queried.isEmpty())
    }

    @Test
    fun `批量检查同样带上该字段且不改变更新结论`() = runBlocking {
        val tags = FakeTags(mapOf("p@alpha" to "0.2.1-alpha.5"))
        val statuses = detector(FakeLatest(mapOf("p" to "0.2.1-alpha.2")), tags)
            .check(
                dependencies = mapOf("p" to "p@0.2.1-alpha.2"),
                currentVersion = { "0.2.1-alpha.2" },
                force = true,
            )

        val status = statuses.getValue("p")
        assertEquals("0.2.1-alpha.5", status.newerPrerelease)
        assertFalse(status.updateAvailable)
        assertTrue(status.checked)
    }

    @Test
    fun `通道查询结果进缓存，重复检测不重复请求`() {
        val tags = FakeTags(mapOf("p@alpha" to "0.2.1-alpha.5"))
        val detector = detector(FakeLatest(mapOf("p" to "0.2.1-alpha.2")), tags)

        repeat(3) { detector.detect("p", "p@0.2.1-alpha.2", "0.2.1-alpha.2") }

        assertEquals("四次通道查询只该发生一次", 4, tags.queried.size)
    }
}
