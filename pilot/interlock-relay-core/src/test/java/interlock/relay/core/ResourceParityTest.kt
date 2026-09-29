package interlock.relay.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 词条对账：默认词条（`values/`）与每个语言目录（`values-<语言>/`）必须逐键对应，
 * 同一个键的格式占位符形状必须一致，且任何语言都不许留下空值。
 *
 * 这些词条是核心层对外可见的一部分，但编译期对它们没有任何约束：少一个键只会让该语言
 * 静默回落到默认文案（中文用户看到中英混排），占位符写错则是运行期崩溃或错位。
 * 所以这里把它变成一条会红的测试，而不是"发布前人工核对一遍"。
 *
 * 只比较键集合、占位符形状与"非空"，不比较文案本身——译文本来就该不一样。
 */
class ResourceParityTest {

    @Test
    fun everyLocaleMatchesTheDefaultKeysAndPlaceholders() {
        val resDir = findResDir()
        val defaultFile = File(resDir, "values/strings.xml")
        assertTrue("默认词条缺失：${defaultFile.absolutePath}", defaultFile.isFile)
        val default = parse(defaultFile)
        assertTrue("默认词条一条都没解析出来，说明解析器或文件形状变了", default.isNotEmpty())

        val locales = resDir.listFiles { file -> file.isDirectory && file.name.startsWith("values-") }
            ?.sortedBy { it.name }
            .orEmpty()
        assertTrue("没有找到任何 values-*/ 语言目录", locales.isNotEmpty())

        for (dir in locales) {
            val file = File(dir, "strings.xml")
            assertTrue("语言目录里没有 strings.xml：${file.absolutePath}", file.isFile)
            val other = parse(file)

            val missing = (default.keys - other.keys).sorted()
            val extra = (other.keys - default.keys).sorted()
            assertTrue(
                "${dir.name}/strings.xml 与默认词条的键不一致：缺少 $missing，多出 $extra",
                missing.isEmpty() && extra.isEmpty(),
            )

            for (key in default.keys.sorted()) {
                val expected = default.getValue(key)
                val actual = other.getValue(key)
                assertTrue("[$key] 在 ${dir.name} 里是空值", actual.isNotBlank())
                assertEquals(
                    "[$key] 的格式占位符在 ${dir.name} 与默认词条不一致",
                    placeholders(expected),
                    placeholders(actual),
                )
            }
        }
    }

    /**
     * 从工作目录向上找模块根。单测的工作目录通常是模块目录，但换一种执行方式
     * （IDE 里单跑、聚合任务、独立的仓库布局）也不能因此静默跳过——找不到就当场失败。
     */
    private fun findResDir(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "src/main/res")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        throw AssertionError("找不到 src/main/res，工作目录：${System.getProperty("user.dir")}")
    }

    /** 词条名 → 词条原文。这些文件都是逐条 `<string>`、无嵌套标签，行内匹配即可。 */
    private fun parse(file: File): Map<String, String> =
        Regex("""<string name="([^"]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(file.readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    /**
     * 占位符形状序列：`%1$d` 与 `%2$d` 算同一形状（序号是各语言自己的语序问题），
     * 但形状本身与出现顺序都要一致——两处 `%s` 与一处 `%s` 不是同一件事。
     */
    private fun placeholders(value: String): List<String> =
        Regex("""%(?:\d+\$)?[a-zA-Z]""")
            .findAll(value)
            .map { it.value.replace(Regex("""\d+\$"""), "") }
            .toList()
}
