package dshbox.adapter

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 中文本地化资源与默认资源的键对齐。纯 JVM 单测读不到 Android 资源表，这里直接读
 * 源树里的 strings.xml 文件对账；文件定位不到（工作目录布局变化）时按假设跳过，
 * 不让这条守护在任何环境里变成假失败。
 */
class PilotStringsZhTranslationTest {

    /** 从工作目录向上找包含模块资源树的目录；找不到返回 null，测试按假设跳过。 */
    private fun findResRoot(): File? {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            for (relative in listOf(
                "src/main/res",
                "dshbox-adapter/src/main/res",
                "pilot/dshbox-adapter/src/main/res",
            )) {
                val candidate = File(dir, relative)
                if (File(candidate, "values/strings.xml").isFile) return candidate
            }
            dir = dir.parentFile
        }
        return null
    }

    @Test
    fun zhResourcesDeclareEveryErrorAndUiKeyTheDefaultResourcesHave() {
        val resRoot = findResRoot()
        assumeTrue("定位不到 dshbox-adapter 模块资源树，跳过资源对账", resRoot != null)
        val keyPattern = Regex("name=\"(pilot_[A-Za-z0-9_]+)\"")
        val defaultKeys = keyPattern.findAll(File(resRoot, "values/strings.xml").readText())
            .map { it.groupValues[1] }.toSet()
        val zhKeys = keyPattern.findAll(File(resRoot, "values-zh/strings.xml").readText())
            .map { it.groupValues[1] }.toSet()
        assertTrue("zh 缺失的键：${defaultKeys - zhKeys}", (defaultKeys - zhKeys).isEmpty())
    }
}
