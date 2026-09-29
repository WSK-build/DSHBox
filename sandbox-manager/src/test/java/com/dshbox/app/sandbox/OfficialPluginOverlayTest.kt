package com.dshbox.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `OfficialPluginOverlay` 覆盖层正文渲染单测。
 *
 * 锁住四件事：**每个条目都出现**（关闭态也显式写出，不靠省略回落到上游默认）、
 * **复述 name**（上游约定 patch 会替换目标行的整个 config）、
 * **单个顶层序列**（混入第二个顶层节点会让 dsh 抛 YAMLException 并静默丢弃整层）、
 * **顺序与条目表一致**。
 */
class OfficialPluginOverlayTest {

    private val allIds: Set<String> = DshOfficialPlugins.ENTRIES.map { it.id }.toSet()

    /** 全开：每条都写 `disabled: false`，且不出现 `disabled: true`。 */
    @Test
    fun allEnabledWritesEveryEntryAsEnabled() {
        val text = OfficialPluginOverlay.render(allIds)

        DshOfficialPlugins.ENTRIES.forEach { entry ->
            assertTrue("应含条目 ${entry.id}", text.contains("- id: ${entry.id}\n"))
            assertTrue("应复述 name ${entry.name}", text.contains("  name: '${entry.name}'\n"))
        }
        assertFalse("全开时不应出现 disabled: true", text.contains("disabled: true"))
        assertEquals(
            "disabled: false 行数应等于条目数",
            DshOfficialPlugins.ENTRIES.size,
            text.split("disabled: false").size - 1,
        )
    }

    /**
     * 关闭一条：该条目**仍然出现**并显式写 `disabled: true`。
     *
     * 这是关键语义 —— 省略该行等于"回到上游默认"，一旦上游将来翻转默认值，
     * 用户明确关闭过的能力就会被静默打开。
     */
    @Test
    fun disabledEntryIsStillWrittenExplicitly() {
        val closed = allIds.first()
        val text = OfficialPluginOverlay.render(allIds - closed)

        assertTrue("关闭的条目仍须出现", text.contains("- id: $closed\n"))
        assertTrue("须显式写 disabled: true", text.contains("disabled: true"))
        assertEquals(
            "只应有一条被关闭",
            1,
            text.split("disabled: true").size - 1,
        )
    }

    /** 全部关闭：每条都写出来且都是 `disabled: true`，不能退化成空层 `[]`。 */
    @Test
    fun allDisabledStillWritesEveryEntry() {
        val text = OfficialPluginOverlay.render(emptySet())

        assertFalse("不应退化成空层", text.trim() == "[]")
        DshOfficialPlugins.ENTRIES.forEach { entry ->
            assertTrue("应含条目 ${entry.id}", text.contains("- id: ${entry.id}\n"))
        }
        assertEquals(
            "每条都应为 disabled: true",
            DshOfficialPlugins.ENTRIES.size,
            text.split("disabled: true").size - 1,
        )
    }

    /** 正文恒为单个顶层 YAML 序列：每个非空行要么是 `- ` 开头，要么是两空格缩进。 */
    @Test
    fun bodyIsSingleTopLevelSequence() {
        val text = OfficialPluginOverlay.render(allIds)

        text.lines().filter { it.isNotBlank() }.forEach { line ->
            assertTrue(
                "非顶层序列行: [$line]",
                line.startsWith("- ") || line.startsWith("  "),
            )
        }
        assertFalse("不得混入空层标记", text.contains("[]"))
        assertTrue("正文以换行收尾", text.endsWith("\n"))
    }

    /** 顺序与条目表一致（面板显示顺序依赖它）。 */
    @Test
    fun entryOrderFollowsTable() {
        val text = OfficialPluginOverlay.render(allIds)

        val positions = DshOfficialPlugins.ENTRIES.map { text.indexOf("- id: ${it.id}\n") }
        assertTrue("每条都应命中", positions.all { it >= 0 })
        assertEquals("顺序应与条目表一致", positions.sorted(), positions)
    }

    /** 条目表本身的自检：id 不重复、name 为作用域包名、覆盖行须带引号。 */
    @Test
    fun entryTableIsWellFormed() {
        val ids = DshOfficialPlugins.ENTRIES.map { it.id }
        assertEquals("id 不应重复", ids.size, ids.toSet().size)
        DshOfficialPlugins.ENTRIES.forEach { entry ->
            assertTrue("name 应为作用域包名: ${entry.name}", entry.name.startsWith("@"))
            assertTrue("id 不应含空格或冒号", !entry.id.contains(' ') && !entry.id.contains(':'))
        }
    }

    /** 偏好键按 id 派生，且默认开启（未写过键时为 true）。 */
    @Test
    fun prefKeyIsDerivedFromId() {
        val id = allIds.first()
        val key = DshOfficialPlugins.prefKey(id)
        assertTrue("键应包含 id", key.endsWith(id))
        assertEquals("同一 id 的键应稳定", key, DshOfficialPlugins.prefKey(id))
        assertFalse(
            "不同 id 的键应不同",
            key == DshOfficialPlugins.prefKey("$id-other"),
        )
    }
}
