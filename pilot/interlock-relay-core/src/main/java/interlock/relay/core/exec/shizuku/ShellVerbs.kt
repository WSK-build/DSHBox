package interlock.relay.core.exec.shizuku

import org.json.JSONObject

/**
 * `sys.shell` 的封闭动词表。
 *
 * 本模块其余 shell 类能力（`sys.settings.write`、`appops.set`、`app.install`…）都是
 * 「一条能力拼一条命令」；这一条不同，它把一批命令一次交给助手，所以把关只能做在**动词**上：
 * 表外的动词不存在，表内的动词参数还要各自过形状。原始命令行一律不收——shell 身份的权限并集
 * 覆盖其余全部能力，收下一条自由填写的命令串就等于一次批准替所有闸门背书。
 *
 * 两档归属：
 * - 只读（[readOnlyNames]）：看系统此刻的状态，不写任何东西。用户打开这条能力就能用。
 * - 需逐条授权（[gatedNames]）：会改系统状态。每条要在「危险能力」页里自己拨开关，
 *   没拨就回 [Plan.NeedsOptIn]。
 * - 删除一类（[FORBIDDEN]）：不进表，也不给任何把它加进来的路径。列在这里只是把
 *   "为什么不开放"写成一条可测断言；真正的把关是表里没有它。
 *
 * `am` / `screenrecord` / `setprop` 刻意不进表：前两条与 `app.launch`、`sys.intent`、
 * `screen.record` 重复，走这条通路会丢掉那几条能力自带的执行面记账（落哪块屏、是否降级、
 * 产物与配额）；第三条是任意系统属性写入，没有可校验的参数形状。
 */
object ShellVerbs {

    const val KEY_VERB = "verb"
    const val KEY_ARGS = "args"

    private const val MAX_ARGS = 6
    private const val MAX_ARG_CHARS = 256

    private val NAME = Regex("[a-z][a-z0-9_-]{0,31}")
    private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+){1,}")
    private val SETTINGS_KEY = Regex("[A-Za-z0-9_:.]{1,128}")
    private val NUMERIC = Regex("\\d{1,10}")
    private val COORDINATE = Regex("\\d{1,5}")

    val SETTINGS_NAMESPACES = setOf("secure", "system", "global")

    /**
     * 一条表内条目：[shape] 是界面上那一行显示的字面命令写法，[check] 判参数形状
     * （合式回 null，否则回一句指名该怎么写的理由）。
     */
    class Verb(val shape: String, val check: (List<String>) -> String?)

    /** 只读动词。`screencap` 是其中唯一有返回件的一条：帧经 fd 落成产物，不进回包文本。 */
    private val READ_ONLY: LinkedHashMap<String, Verb> = linkedMapOf(
        "dumpsys" to Verb("dumpsys <service>") { args ->
            // 恰好一个服务名：多给的那几个 token 会把它变成写操作（`dumpsys deviceidle force-idle`、
            // `dumpsys batterystats --reset` 都是现成的例子），只读组的arity 不收"顺手多一个参数"。
            if (args.size != 1 || !NAME.matches(args[0])) "dumpsys needs exactly one service name, e.g. [battery]" else null
        },
        "pm" to Verb("pm list <packages|features|permissions|libraries>") { args ->
            if (args.size != 2 || args[0] != "list" || !NAME.matches(args[1])) {
                "pm only accepts list <what>"
            } else null
        },
        "settings" to Verb("settings get <secure|system|global> <key>") { args ->
            if (args.firstOrNull() != "get" || args.size < 2 || args[1] !in SETTINGS_NAMESPACES ||
                (args.size > 2 && !SETTINGS_KEY.matches(args[2]))
            ) "settings only accepts get <namespace> <key>" else null
        },
        "getprop" to Verb("getprop [key]") { args ->
            if (args.size > 1 || (args.size == 1 && !SETTINGS_KEY.matches(args[0]))) {
                "getprop accepts at most one property name"
            } else null
        },
        "appops" to Verb("appops get <package>") { args ->
            if (args.size != 2 || args[0] != "get" || !PACKAGE_NAME.matches(args[1])) {
                "appops only accepts get <package>"
            } else null
        },
        "wm" to Verb("wm size|density") { args ->
            // 只读组里的 `wm` 不收第二个参数：`wm size reset` 与 `wm density 160` 都是**写**
            // （清掉覆盖值 / 改分辨率），放在这一组就等于给"随能力开、不需逐条授权"的通路
            // 开了一条改系统显示的口子。要改显示，走需授权那一档。
            if (args.size != 1 || args[0] !in setOf("size", "density")) "wm only accepts size|density (query)" else null
        },
        "screencap" to Verb("screencap  (returns an artifact file)") { args ->
            if (args.isNotEmpty()) "screencap takes no args; the frame comes back as an artifact" else null
        },
    )

    /** 会改系统的动词：每条一个开关，默认关。 */
    private val GATED: LinkedHashMap<String, Verb> = linkedMapOf(
        "svc" to Verb("svc wifi|bluetooth|data enable|disable") { args ->
            if (args.firstOrNull() !in setOf("wifi", "bluetooth", "data") ||
                args.getOrNull(1) !in setOf("enable", "disable") || args.size > 2
            ) "svc only accepts wifi|bluetooth|data enable|disable" else null
        },
        "media" to Verb("media volume --stream <0..7> --set <0..100>") { args ->
            if (args.firstOrNull() != "volume" || !args.contains("--stream") || !args.contains("--set") ||
                args.any { it.startsWith("--") && it !in setOf("--stream", "--set", "--show") } ||
                args.count { !it.startsWith("--") } > 3
            ) "media only accepts volume --stream <n> --set <n>" else null
        },
        "input" to Verb("input tap|swipe|text|keyevent <args>") { args ->
            when (args.firstOrNull()) {
                "tap" -> if (args.size != 3 || args.drop(1).any { !COORDINATE.matches(it) }) "input tap needs two coordinates" else null
                "swipe" -> if (args.size != 5 || args.drop(1).any { !COORDINATE.matches(it) }) "input swipe needs x1 y1 x2 y2" else null
                "text" -> if (args.size != 2 || args[1].isEmpty()) "input text needs one string" else null
                "keyevent" -> if (args.size != 2 || !(NUMERIC.matches(args[1]) || NAME.matches(args[1]))) {
                    "input keyevent needs one key code"
                } else null
                else -> "input only accepts tap|swipe|text|keyevent"
            }
        },
    )

    /** 永不开放的一类：不进表，也不给开关。 */
    val FORBIDDEN: Set<String> = setOf(
        "rm", "rmdir", "dd", "mkfs", "format", "sh", "su", "pm_clear", "pm_uninstall",
        "settings_delete", "content_delete", "am", "screenrecord", "setprop",
    )

    val readOnlyNames: List<String> get() = READ_ONLY.keys.toList()
    val gatedNames: List<String> get() = GATED.keys.toList()

    /** 界面按这两张表逐行呈现；形状是字面命令写法，不进翻译。 */
    val readOnlyShapes: List<Pair<String, String>> get() = READ_ONLY.map { it.key to it.value.shape }
    val gatedShapes: List<Pair<String, String>> get() = GATED.map { it.key to it.value.shape }

    sealed interface Plan {
        data class Run(val command: String, val verb: String) : Plan

        /** 动词在表内但这条还没拨开：回包要点名差的是哪一个开关，以及它对应的那条形。 */
        data class NeedsOptIn(val verb: String, val shape: String) : Plan
        data class Bad(val reason: String) : Plan
    }

    /**
     * [optInOpen] 问的是「这条会改系统的动词，用户打开了吗」，由装配根从偏好里供上来。
     *
     * 判据不写在本对象里：表只懂形状，谁被授权是设备上的用户状态。两处混在一起，
     * 单测里的表就会与界面上的开关各说一套。
     */
    fun plan(args: JSONObject, optInOpen: (String) -> Boolean = { false }): Plan {
        val verb = args.optString(KEY_VERB).trim()
        if (verb.isEmpty()) return Plan.Bad("missing arg: $KEY_VERB")
        if (!NAME.matches(verb)) return Plan.Bad("verb must be a single command name, got '$verb'")
        if (verb in FORBIDDEN) return Plan.Bad("verb refused by policy and not configurable: $verb")
        val entry = READ_ONLY[verb] ?: GATED[verb] ?: return Plan.Bad(
            "unknown verb: $verb, read-only set is [${readOnlyNames.joinToString(", ")}] " +
                "and the opt-in set is [${gatedNames.joinToString(", ")}]",
        )
        if (verb !in READ_ONLY && !optInOpen(verb)) return Plan.NeedsOptIn(verb, entry.shape)
        val rest = args.optJSONArray(KEY_ARGS)?.let { array ->
            (0 until array.length()).map { array.optString(it, "") }
        } ?: emptyList()
        if (rest.size > MAX_ARGS) return Plan.Bad("$KEY_ARGS accepts at most $MAX_ARGS entries, got ${rest.size}")
        rest.forEach { token ->
            if (token.length > MAX_ARG_CHARS) return Plan.Bad("each arg must be at most $MAX_ARG_CHARS characters")
            // 参数可以带点、冒号这类结构字符，但不允许 shell 元字符与空白：拼装时它们会被
            // 单引号包住，这一问只是把"看起来像命令拼接"的输入提前打回去。
            if (token.any { it in "\"'`\$;&|<>\n\r\t \\" || it == '*' || it == '?' }) {
                return Plan.Bad("arg contains shell metacharacters or whitespace: '$token'")
            }
            // 只读组一律不收选项旗标：`dumpsys batterystats --reset`、`pm list -u` 之外，
            // 真正要紧的是"旗标就是第二动词的入口"——`--reset` 这类会把一条查询变成写入。
            if (verb in READ_ONLY && token.startsWith("-")) {
                return Plan.Bad("read-only verbs accept no option flags, got '$token'")
            }
        }
        entry.check(rest)?.let { return Plan.Bad(it) }
        return Plan.Run("$verb${rest.joinToString("") { " ${shellQuote(it)}" }}", verb)
    }

    /** 与其余 shell 能力同一种包裹方式：单引号成对，内部单引号按 '\'' 断开。 */
    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
