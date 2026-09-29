package com.dshbox.pluginmanager.market.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 目录 JSON 里的**显式 null** 必须当"没有"处理，不能当成字符串 `"null"`。
 *
 * 这条规则在真机上价值极大：目录里大量条目写的是 `"npm": null`，一旦它们被读成同一个
 * 字符串 `"null"`，去重身份就全撞在一起，**除第一条外全部被丢掉** ——
 * 实测上游目录 4347 → 2185、移动端 45 → 33（正文完整，少的都在解析这一步）。
 *
 * 说明一句能力边界：本文件只用 `org.json` 里两种实现行为一致的入口
 * （`opt` 与 `JSONObject.NULL`）。Android `optString` 对 null 返回 `"null"` 的差异
 * **在 JVM 单测里测不到**（单测挂的是参考实现，它返回空串），所以 [jsonText]
 * 存在的意义就是"不去碰 optString"，这条用例钉的是它的契约。
 */
class RegistryNullFieldTest {

    @Test
    fun jsonTextTreatsJsonNullAsAbsent() {
        val obj = JSONObject("""{"a":null,"b":"  x  ","c":"","d":123,"e":true}""")
        assertEquals("", jsonText(obj.opt("a")))
        assertEquals("", jsonText(obj.opt("missing")))
        assertEquals("x", jsonText(obj.opt("b")))
        assertEquals("", jsonText(obj.opt("c")))
        assertEquals("123", jsonText(obj.opt("d")))
        assertEquals("true", jsonText(obj.opt("e")))
    }

    @Test
    fun entriesWithNullNpmKeepDistinctIdentities() {
        // 三条都没有 npm：身份必须退到 owner/name，而不是一起变成 "null"。
        val body = """
            {"plugins":[
              {"name":"a","owner":"o1","category":"x","npm":null},
              {"name":"b","owner":"o2","category":"x","npm":null},
              {"name":"c","owner":"o3","category":"x","npm":null}
            ]}
        """.trimIndent()
        val registry = RegistrySource().parse(body)
        assertEquals(3, registry.plugins.size)
        assertEquals(listOf("o1/a", "o2/b", "o3/c"), registry.plugins.map { it.displayName })
    }
}
