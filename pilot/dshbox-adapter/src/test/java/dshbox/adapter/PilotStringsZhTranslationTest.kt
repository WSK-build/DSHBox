package dshbox.adapter

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本地化资源键对齐：六个语种（默认、zh、ar、es、fr、ru）与默认资源的键集合必须
 * **完全相等**（双向：不缺键、也不多键）。类名沿用最初的中文校验测试，职责自本起
 * 扩为全量六语对账。
 *
 * 纯 JVM 单测读不到 Android 资源表，这里直接读源树里的 strings.xml 文件对账。
 * 资源树定位不到时按**明确失败**处理：键不齐是发布缺陷，按假设跳过等于不设防。
 */
class PilotStringsZhTranslationTest {

    /** 从工作目录向上找包含模块资源树的目录；找不到返回 null，由调用方显式失败。 */
    private fun findResRoot(): File? {
        val start = System.getProperty("user.dir") ?: return null
        var dir: File? = File(start).absoluteFile
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

    private fun keysOf(file: File) =
        Regex("name=\"([A-Za-z0-9_]+)\"").findAll(file.readText()).map { it.groupValues[1] }.toSet()

    @Test
    fun everyLocaleHasExactlyTheSameStringKeysAsTheDefaultResources() {
        val resRoot = findResRoot()
        assertTrue(
            "定位不到 dshbox-adapter 模块资源树，无法对账（工作目录布局变化时更新查找路径）",
            resRoot != null,
        )
        val defaultKeys = keysOf(File(resRoot, "values/strings.xml"))
        for (locale in listOf("values-zh", "values-ar", "values-es", "values-fr", "values-ru")) {
            val localeKeys = keysOf(File(resRoot, "$locale/strings.xml"))
            val missing = defaultKeys - localeKeys
            val extra = localeKeys - defaultKeys
            assertTrue("$locale 缺失的键：$missing", missing.isEmpty())
            assertTrue("$locale 多出的键：$extra", extra.isEmpty())
        }
    }

    /** 抽一个词条的值区，供占位与整句判据用。 */
    private fun valueOf(file: File, key: String): String? {
        val raw = Regex("<string name=\"$key\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
            .find(file.readText())?.groupValues?.get(1) ?: return null
        // 归一化只是语义读取：XML 实体还原、aapt 的“引号包整段”惯例与转义撇号剥掉——
        // 判据对的是文本本身，不是资源文件的具体写法（本测试不改变资源，只读）。
        return raw
            .replace("&quot;", "")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("\\'", "'")
            .replace("&gt;", ">")
            .replace("&lt;", "<")
            .trim()
    }

    /**
     * 改造后的预设身份文案：第五条目是「常用自定义预设」（保存的那份固定配置，
     * 「配置」只是编辑/保存入口），当前值不再对应任何条目时显示「混合」。
     * 中文按逐字钉死；六语各自校验条目名与状态词不是同一个值——标签混用会让
     * 「使用」之后的状态无法辨认。
     */
    @Test
    fun presetIdentityWordingIsPinnedInEveryLocale() {
        val resRoot = findResRoot()
        assertTrue("定位不到 dshbox-adapter 模块资源树，无法校验预设身份文案", resRoot != null)
        val zh = File(resRoot, "values-zh/strings.xml")
        assertEquals("常用自定义预设", valueOf(zh, "pilot_config_custom"))
        assertEquals("混合", valueOf(zh, "pilot_config_unsaved"))
        assertEquals("常用自定义预设", valueOf(zh, "pilot_config_page_title"))
        assertTrue(
            "编辑页说明必须点出保存后的身份：常用自定义预设",
            valueOf(zh, "pilot_config_note")!!.contains("常用自定义预设"),
        )
        assertTrue(
            "缺省提示必须点出先配置保存的是常用自定义预设",
            valueOf(zh, "pilot_config_missing_hint")!!.contains("常用自定义预设"),
        )
        val en = File(resRoot, "values/strings.xml")
        assertEquals("Saved custom preset", valueOf(en, "pilot_config_custom"))
        assertEquals("Mixed", valueOf(en, "pilot_config_unsaved"))
        // 六语一致判据：条目名与「混合」状态词在各自语种里必须不同值。
        for (locale in listOf("values", "values-zh", "values-ar", "values-es", "values-fr", "values-ru")) {
            val file = File(resRoot, "$locale/strings.xml")
            val custom = valueOf(file, "pilot_config_custom")
            val mixed = valueOf(file, "pilot_config_unsaved")
            assertTrue("[$locale] pilot_config_custom 缺失", custom != null)
            assertTrue("[$locale] pilot_config_unsaved 缺失", mixed != null)
            assertTrue(
                "[$locale] 条目名与混合状态词同值：$custom",
                custom != mixed,
            )
        }
    }
    /**
     * 缺口三环的整句词条（Shizuku 那一片）在**任何语种都不能带格式占位符**：能力行按
     * 整句直接展示（不再进 `·%1$s` 模板拼标签），一处残留 `%1$s` 就会原样显示出来。
     * 缺哪篇就按失败钉死。
     */
    @Test
    fun shizukuGapSentencesCarryNoFormatPlaceholdersInAnyLocale() {
        // 资源树定位不到不是"没有可对账的内容"：它是本测试自己的前提失败，静默返回
        // 会把"没验"记成"验过"。显式失败。
        val resRoot = findResRoot()
        assertTrue("定位不到 dshbox-adapter 模块资源树，无法维护六语整句判据", resRoot != null)
        val sentenceKeys = listOf(
            "pilot_cap_missing_service_shizuku",
            "pilot_cap_missing_grant",
            "pilot_cap_missing_bound",
            "pilot_surface_bg_reason_display_refused",
        )
        for (locale in listOf("values", "values-zh", "values-ar", "values-es", "values-fr", "values-ru")) {
            val file = File(resRoot, "$locale/strings.xml")
            for (key in sentenceKeys) {
                val value = valueOf(file, key)
                assertTrue("[$locale/$key] 词条缺失，无法对账", value != null)
                assertTrue(
                    "[$locale/$key] 残留格式占位符，会原样显示在缺口文案里：$value",
                    value!!.contains("%") == false,
                )
            }
        }
        // "被拒不建屏"那一篇必须在六语里都点名前台方向：不给方向词的建议只报结论，
        // 用户拿到的是"不可用"而没有下一步。
        for (locale in listOf("values", "values-zh", "values-ar", "values-es", "values-fr", "values-ru")) {
            val refused = valueOf(File(resRoot, "$locale/strings.xml"), "pilot_surface_bg_reason_display_refused")
            assertTrue("[$locale] display_refused 词条缺失", refused != null)
            val hasDirection = when (locale) {
                "values" -> refused!!.contains("foreground")
                "values-zh" -> refused!!.contains("前台")
                "values-ar" -> refused!!.contains("الأمامي")
                "values-es" -> refused!!.contains("primer plano")
                "values-fr" -> refused!!.contains("premier plan")
                "values-ru" -> refused!!.contains("переднего плана")
                else -> false
            }
            assertTrue("[$locale] display_refused 里没有前台方向的词：$refused", hasDirection)
        }
    }

    /**
     * Shizuku 三环的整句缺口按**两段**断言：（右一）**否定段**点名"缺的是哪一环"；
     * （右二）**对象段**把"这一环的行业面/去哪个面处理"说到——未运行说特权模式页，
     * 未授权说本应用的授权，未连接说执行侧用户服务。对象段不写成"动作词"（六语整句
     * 的动作长度各不相同，动作方向由 phone_status 的 backend.shizuku 三读与宿主提示
     * 承接，判据挂的是"对象被点名"这一条）。
     */
    @Test
    fun shizukuGapDirectionWordsInEveryLocale() {
        val resRoot = findResRoot()
        assertTrue("定位不到 dshbox-adapter 模块资源树，无法维护三环方向词判据", resRoot != null)
        // 每一环在六语里的方向词短语：短语锚在当前词条方向部分的一个确定子串上，
        // 检查该子串必须在词条里（翻译改词时同步更新图表并使用同样的方向读音下的检查）。
        /** 每一环在六语的**否定段 + 对象段**对：两段缺一不可——只有否定没有对象，
         * 或只有对象没有否定，都不能说明"这一环缺的是这件事"。 */
        val expectations = listOf(
            "pilot_cap_missing_service_shizuku" to mapOf(
                "values" to ("not running" to "start it from the privileged page"),
                "values-zh" to ("未运行" to "请到特权模式页启动"),
                "values-ar" to ("ليس قيد التشغيل" to "شغّله من الصفحة المميزة"),
                "values-es" to ("no está en ejecución" to "inícielo desde la página privilegiada"),
                "values-fr" to ("n'est pas en cours d'exécution" to "démarrez-le depuis la page privilégiée"),
                "values-ru" to ("не запущен" to "запустите его на странице"),
            ),
            "pilot_cap_missing_grant" to mapOf(
                "values" to ("has not granted" to "granted this app"),
                "values-zh" to ("未授权本应用" to "授权"),
                "values-ar" to ("لم يمنح" to "صلاحية لهذا التطبيق"),
                "values-es" to ("no ha concedido" to "permiso a esta aplicación"),
                "values-fr" to ("pas accordé" to "cette application"),
                "values-ru" to ("не авторизовал" to "это приложение"),
            ),
            "pilot_cap_missing_bound" to mapOf(
                "values" to ("is not connected" to "user service"),
                "values-zh" to ("未连接" to "用户服务"),
                "values-ar" to ("غير متصلة" to "خدمة مستخدم"),
                "values-es" to ("no está conectado" to "servicio de usuario"),
                "values-fr" to ("pas connecté" to "service utilisateur"),
                "values-ru" to ("не подключен" to "сервис Shizuku"),
            ),
        )
        for ((key, phrases) in expectations) {
            // 每对 (语种 → 否定段 + 对象段) 单独断言**各占一段**：否定段说明"缺的是哪件事"，
            // 对象段点名"去哪个面处理"。判据挂的是这两个语义段都在，不挂正向词的词内子串。
            for ((locale, pair) in phrases) {
                val value = valueOf(File(resRoot, "$locale/strings.xml"), key)
                assertTrue("[$locale/$key] 整句缺失", value != null)
                assertTrue(
                    "[$locale/$key] 缺口整句没有先否定这一环（否定词：${pair.first}）：$value",
                    value!!.contains(pair.first, ignoreCase = true),
                )
                assertTrue(
                    "[$locale/$key] 缺口整句没有点名处理的那个对象（对象段：${pair.second}）：$value",
                    value.contains(pair.second, ignoreCase = true),
                )
            }
        }
    }
}
