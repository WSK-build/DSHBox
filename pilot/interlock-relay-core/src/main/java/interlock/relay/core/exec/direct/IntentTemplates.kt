package interlock.relay.core.exec.direct

import org.json.JSONObject

/**
 * `sys.intent` 的封闭模板表。
 *
 * 一条模板 = 一个写死的系统 action + 一张只收白名单键与取值范围的参数表。动作串与 extra
 * 一律不许助手直接给：能被自由填写的 Intent 等于把"向任意组件发任意请求"并进一条能力里，
 * 那条路比通用 shell 更宽 —— 它连 shell 身份都不需要，一次提示词注入就够替用户答应
 * 任意应用的请求。
 *
 * 表内只收两类，共同点是**改动看得见、也撤得掉**：
 * - 系统本就为"别的应用替我设个闹钟/计时器"公开了 action 与 extra（`SKIP_UI` 那两条），
 *   接收方是用户在系统里选定的那个时钟应用。
 * - 只是把系统页面摆到用户眼前、不替他改任何东西的入口：打开时钟的闹钟页/计时器页、
 *   打开某个系统设置页、打开拨号盘（不拨出）、打开浏览器、打开某个应用的详情页。
 *   这类连 `SKIP_UI` 都不适用——它一定上屏，用户看得见自己刚被带到了哪儿。
 *
 * 删除一类的入口（卸载应用、清除数据、抹掉账号）**不进这张表**，也不给任何开关把它加进来的路径。
 * 「停止正在响的闹钟 / 延后闹铃」也不进：前者对单次闹钟等于永久关闭，与上面那条前提相悖；
 * 两者都没有 `SKIP_UI` 语义，多条命中时系统还会弹一个"你指哪一条"的选择界面。
 *
 * 校验全在这个纯函数里做完，后端只负责把已经定形的参数交给系统 —— 与 shell 那几条
 * 「参数过白名单再拼命令」是同一条规矩，落点不同而已。校验不吃 `android.net.Uri`：
 * 它在单测里是不实现的平台桩，把形状把关写在它上面就等于这条表的判据从没被测过。
 */
object IntentTemplates {

    const val KEY_TEMPLATE = "template"
    const val KEY_HOUR = "hour"
    const val KEY_MINUTE = "minute"
    const val KEY_MESSAGE = "message"
    const val KEY_LENGTH = "length"
    const val KEY_PAGE = "page"
    const val KEY_PACKAGE = "package"
    const val KEY_NUMBER = "number"
    const val KEY_URL = "url"

    const val ALARM_SET = "alarm.set"
    const val TIMER_SET = "timer.set"
    const val ALARM_SHOW = "alarm.show"
    const val TIMER_SHOW = "timer.show"
    const val SETTINGS_OPEN = "settings.open"
    const val APP_INFO = "app.info"
    const val DIAL = "dial"
    const val WEB_OPEN = "web.open"

    /** 表内的模板名。界面上的那一行说明与回包里的 `unknown template` 都从这里取。 */
    val names: List<String> = listOf(
        ALARM_SET, TIMER_SET, ALARM_SHOW, TIMER_SHOW, SETTINGS_OPEN, APP_INFO, DIAL, WEB_OPEN,
    )

    /** 能打开的系统设置页。只收公开常量、且都无需任何权限的那几个。 */
    val settingsPages: Set<String> = setOf(
        "wifi", "bluetooth", "display", "location", "sound", "apn", "developer", "nfc",
    )

    /** 闹钟备注与计时器标签共用这一条上限：超长文本会撑掉确认卡上那行对象。 */
    private const val MAX_MESSAGE_CHARS = 100

    /** 计时器时长上下限（秒）。下限排掉 0 与负数，上限排掉"永远走不完"的计时器。 */
    private const val MIN_LENGTH_SECONDS = 1
    private const val MAX_LENGTH_SECONDS = 24 * 60 * 60

    private const val MAX_URL_CHARS = 512
    private const val MAX_NUMBER_CHARS = 32

    /**
     * 拨号盘上打得出的字符：数字与 `+ * # ( ) . ,`，外加一个空格和一个连字符作分隔。
     * 写成字符类而不用反斜杠转义，是为了让这一行在 Kotlin 里就是一个字面字符集。
     * 放开别的字符等于往 `tel:` 里塞别的 scheme。
     */
    private val DIAL_NUMBER = Regex("[0-9+*#()., -]{1,$MAX_NUMBER_CHARS}")

    /** 包名形状与 shell 那张表同源，两处对同一个东西不该有两种说法。 */
    private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+){1,}")

    /**
     * 校验通过后的参数。带哪一个由 [template] 决定：`hour`/`minute` 只对 alarm.set，
     * `lengthSeconds` 只对 timer.set，`page` 只对 settings.open，`packageName` 只对 app.info，
     * `number` 只对 dial，`url` 只对 web.open。未用的那些保持 -1 / null。
     */
    data class Params(
        val template: String,
        val hour: Int = -1,
        val minute: Int = -1,
        val lengthSeconds: Int = -1,
        val message: String? = null,
        val page: String? = null,
        val packageName: String? = null,
        val number: String? = null,
        val url: String? = null,
    )

    sealed interface Outcome {
        data class Ok(val params: Params) : Outcome
        data class Bad(val reason: String) : Outcome
    }

    fun validate(args: JSONObject): Outcome {
        val template = args.optString(KEY_TEMPLATE).trim()
        if (template.isEmpty()) return Outcome.Bad("missing arg: $KEY_TEMPLATE")
        if (template !in names) {
            return Outcome.Bad("unknown template: $template, accepted: $names")
        }
        val message = args.optString(KEY_MESSAGE).takeIf { it.isNotBlank() }?.trim()
        if (message != null && message.length > MAX_MESSAGE_CHARS) {
            return Outcome.Bad("$KEY_MESSAGE must be at most $MAX_MESSAGE_CHARS characters, got ${message.length}")
        }
        return when (template) {
            ALARM_SET -> validateAlarm(args, message)
            TIMER_SET -> validateTimer(args, message)
            ALARM_SHOW, TIMER_SHOW -> Outcome.Ok(Params(template, message = message))
            SETTINGS_OPEN -> validateSettings(args)
            APP_INFO -> validateAppInfo(args)
            DIAL -> validateDial(args)
            else -> validateWeb(args)
        }
    }

    private fun validateAlarm(args: JSONObject, message: String?): Outcome {
        if (!args.has(KEY_HOUR)) return Outcome.Bad("missing arg: $KEY_HOUR")
        if (!args.has(KEY_MINUTE)) return Outcome.Bad("missing arg: $KEY_MINUTE")
        val hour = args.optInt(KEY_HOUR, -1)
        val minute = args.optInt(KEY_MINUTE, -1)
        // 越界一律拒，不做"帮你取模"：25 点被静默折成 1 点之后，用户批准的是卡片上那个
        // 25:00，落进系统里却是另一天的一点，而回包看着像成功。
        if (hour !in 0..23) return Outcome.Bad("$KEY_HOUR must be within 0..23, got $hour")
        if (minute !in 0..59) return Outcome.Bad("$KEY_MINUTE must be within 0..59, got $minute")
        return Outcome.Ok(Params(ALARM_SET, hour = hour, minute = minute, message = message))
    }

    private fun validateTimer(args: JSONObject, message: String?): Outcome {
        if (!args.has(KEY_LENGTH)) return Outcome.Bad("missing arg: $KEY_LENGTH")
        val seconds = args.optInt(KEY_LENGTH, -1)
        if (seconds !in MIN_LENGTH_SECONDS..MAX_LENGTH_SECONDS) {
            return Outcome.Bad(
                "$KEY_LENGTH must be within $MIN_LENGTH_SECONDS..$MAX_LENGTH_SECONDS seconds, got $seconds",
            )
        }
        return Outcome.Ok(Params(TIMER_SET, lengthSeconds = seconds, message = message))
    }

    private fun validateSettings(args: JSONObject): Outcome {
        val page = args.optString(KEY_PAGE).trim().lowercase()
        if (page.isEmpty()) {
            return Outcome.Bad("missing arg: $KEY_PAGE, accepted: [${settingsPages.joinToString(", ")}]")
        }
        if (page !in settingsPages) {
            return Outcome.Bad("$KEY_PAGE '$page' is not one of [${settingsPages.joinToString(", ")}]")
        }
        return Outcome.Ok(Params(SETTINGS_OPEN, page = page))
    }

    private fun validateAppInfo(args: JSONObject): Outcome {
        val pkg = args.optString(KEY_PACKAGE).trim()
        if (pkg.isEmpty()) return Outcome.Bad("missing arg: $KEY_PACKAGE")
        if (!PACKAGE_NAME.matches(pkg)) return Outcome.Bad("$KEY_PACKAGE is not a package name: $pkg")
        return Outcome.Ok(Params(APP_INFO, packageName = pkg))
    }

    private fun validateDial(args: JSONObject): Outcome {
        val number = args.optString(KEY_NUMBER).trim()
        if (number.isEmpty()) return Outcome.Bad("missing arg: $KEY_NUMBER")
        if (!DIAL_NUMBER.matches(number)) {
            return Outcome.Bad(
                "$KEY_NUMBER may only contain digits and + * # ( ) . - , up to $MAX_NUMBER_CHARS characters",
            )
        }
        return Outcome.Ok(Params(DIAL, number = number))
    }

    private fun validateWeb(args: JSONObject): Outcome {
        val url = args.optString(KEY_URL).trim()
        if (url.isEmpty()) return Outcome.Bad("missing arg: $KEY_URL")
        if (url.length > MAX_URL_CHARS) {
            return Outcome.Bad("$KEY_URL must be at most $MAX_URL_CHARS characters, got ${url.length}")
        }
        // 只认 http/https：`file:`、`content:`、`intent:` 这些 scheme 能把一条"打开网页"
        // 变成打开任意私有内容或调起任意组件。
        val scheme = url.substringBefore("://").lowercase()
        if (scheme != "http" && scheme != "https") {
            return Outcome.Bad("$KEY_URL must start with http:// or https://, got scheme '$scheme'")
        }
        if (url.any { it.isWhitespace() }) return Outcome.Bad("$KEY_URL must not contain whitespace")
        return Outcome.Ok(Params(WEB_OPEN, url = url))
    }
}
