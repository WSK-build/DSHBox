package com.dshbox.pluginmanager.market.data

import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `dsh.profile.bundles` 外科手术式编辑。
 *
 * 固定的核心约束：**bundles 的顺序就是层优先级**，编辑绝不允许打乱既有顺序，
 * 也绝不允许改动数组之外的任何字节；读不懂的形态宁可拒绝编辑。
 */
class ProfileBundlesEditorTest {

    private fun tempManifest(content: String): java.io.File {
        val dir = Files.createTempDirectory("bundles").toFile()
        return java.io.File(dir, "package.json").apply { writeText(content, Charsets.UTF_8) }
    }

    @Test
    fun `多行数组按原顺序读出`() {
        val manifest = """
            {
              "name": "dsh-profile-web",
              "dependencies": {},
              "dsh": {
                "profile": {
                  "bundles": [
                    "@deepseek-ai/dsh-base",
                    "@local/dsh-mobile-adapt",
                    "dshmarket"
                  ]
                }
              }
            }
        """.trimIndent()
        assertEquals(
            listOf("@deepseek-ai/dsh-base", "@local/dsh-mobile-adapt", "dshmarket"),
            ProfileBundlesEditor.readBundles(manifest),
        )
    }

    @Test
    fun `单行数组与空数组都能读`() {
        assertEquals(listOf("a", "b"), ProfileBundlesEditor.readBundles("""{"dsh":{"profile":{"bundles":["a","b"]}}}"""))
        assertEquals(emptyList<String>(), ProfileBundlesEditor.readBundles("""{"dsh":{"profile":{"bundles":[]}}}"""))
    }

    @Test
    fun `缺失或非数组形态返回 null 而不是空`() {
        assertNull(ProfileBundlesEditor.readBundles("""{"dsh":{"profile":{}}}"""))
        assertNull(ProfileBundlesEditor.readBundles("""{"dsh":{}}"""))
        // 形态读不懂（元素不是字符串）：拒绝编辑语义。
        assertNull(ProfileBundlesEditor.readBundles("""{"dsh":{"profile":{"bundles":[1,2]}}}"""))
    }

    @Test
    fun `移除保持其余元素顺序且不动其它字节`() {
        val manifest = "{\n  \"name\": \"p\",\n  \"dsh\": {\n    \"profile\": {\n      \"bundles\": [\n" +
            "        \"first\",\n        \"second\",\n        \"third\"\n      ]\n    }\n  }\n}\n"
        val updated = ProfileBundlesEditor.removeBundle(manifest, "second")
        assertNotNull(updated)
        // 顺序保持；name 键原样。
        assertEquals(listOf("first", "third"), ProfileBundlesEditor.readBundles(updated!!))
        assertTrue(updated.contains("\"name\": \"p\""))
        assertFalse(updated.contains("second"))
        // **严格 JSON**：宿主（Node JSON.parse）拒绝尾逗号，而 Android org.json
        // 宽松放行——必须显式断言数组闭合前没有逗号。
        assertFalse(updated.contains(",\n        ]"))
        assertFalse(updated.contains(",\n      ]"))
    }

    @Test
    fun `追加后同样是严格 JSON 无尾逗号`() {
        val manifest = "{\n  \"dsh\": {\n    \"profile\": {\n      \"bundles\": [\n" +
            "        \"a\"\n      ]\n    }\n  }\n}\n"
        val updated = ProfileBundlesEditor.addBundle(manifest, "b")
        assertNotNull(updated)
        assertEquals(listOf("a", "b"), ProfileBundlesEditor.readBundles(updated!!))
        assertFalse(updated.contains(",\n      ]"))
    }

    @Test
    fun `追加到栈尾且幂等`() {
        val manifest = """{"dsh":{"profile":{"bundles":["a"]}}}"""
        val added = ProfileBundlesEditor.addBundle(manifest, "b")
        assertEquals(listOf("a", "b"), ProfileBundlesEditor.readBundles(added!!))
        // 已存在时原样返回（幂等），不产生重复。
        val again = ProfileBundlesEditor.addBundle(added, "b")
        assertEquals(added, again)
        // 移除不存在的元素同样幂等。
        assertEquals(manifest, ProfileBundlesEditor.removeBundle(manifest, "zzz"))
    }

    @Test
    fun `复核不过返回 null 拒绝写入`() {
        // 正常编辑应通过内置复核；这里验证复核路径确实会拦下坏结果——
        // 构造一个"编辑器读得懂、宿主 schema 不收"的形态是不可达的，
        // 所以只验证非法 JSON 的入口拒绝。
        assertNull(ProfileBundlesEditor.removeBundle("not json", "a"))
        assertNull(ProfileBundlesEditor.addBundle("""{"dsh":{"profile":{"bundles":{"x":1}}}}""", "a"))
    }

    @Test
    fun `store 层在文件上原子往返`() {
        val manifest = tempManifest("""{"dsh":{"profile":{"bundles":["a"]}}}""")
        val store = ProfileBundlesStore(manifest)
        assertTrue(store.remove("zzz", isPresent = false))
        assertTrue(store.add("b", isPresent = false))
        assertEquals(listOf("a", "b"), store.readBundles())
        assertTrue(store.remove("a", isPresent = true))
        assertEquals(listOf("b"), store.readBundles())
        // 文件仍是合法 JSON 且结构未变。
        val root = JSONObject(manifest.readText())
        assertEquals("b", root.optJSONObject("dsh")?.optJSONObject("profile")?.optJSONArray("bundles")?.optString(0))
    }

    @Test
    fun `store 层对缺失文件返回未写入`() {
        val store = ProfileBundlesStore(java.io.File(Files.createTempDirectory("none").toFile(), "package.json"))
        assertFalse(store.add("a", isPresent = false))
        assertNull(store.readBundles())
    }

    @Test
    fun `键定位只认 bundles 后紧跟冒号的数组`() {
        // 值恰为 "bundles" 的字符串（描述文本）不是键：定位必须跳过它，
        // 否则会把后出现的无关字符串数组（files/keywords）当栈。
        val manifest = """
            {
              "description": "bundles",
              "files": ["x", "y"],
              "dsh": { "profile": { "bundles": ["real"] } }
            }
        """.trimIndent()
        assertEquals(listOf("real"), ProfileBundlesEditor.readBundles(manifest))
        // x 不在真栈里：幂等返回原文，绝不改写 files 数组。
        assertEquals(manifest, ProfileBundlesEditor.removeBundle(manifest, "x"))
    }

    @Test
    fun `CRLF 文件编辑后保持 CRLF`() {
        val manifest = "{\r\n  \"dsh\": {\r\n    \"profile\": {\r\n      \"bundles\": [\r\n        \"a\"\r\n      ]\r\n    }\r\n  }\r\n}\r\n"
        val updated = ProfileBundlesEditor.addBundle(manifest, "b")
        assertNotNull(updated)
        assertEquals(listOf("a", "b"), ProfileBundlesEditor.readBundles(updated!!))
        assertTrue(updated.contains("\r\n"))
        assertFalse(updated.contains(",\r\n      ]"))
    }
}
