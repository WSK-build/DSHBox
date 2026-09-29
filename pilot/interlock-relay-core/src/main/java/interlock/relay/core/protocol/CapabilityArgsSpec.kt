package interlock.relay.core.protocol

import org.json.JSONArray
import org.json.JSONObject

/**
 * 公共参数预校验表：每条能力一份「允许的键、必填的键、键的类型」。
 *
 * 存在的理由是时序：三条后端的 `argsError` 都在闸门之后才跑，参数拼错的调用会先占掉
 * 一次用户确认（六十秒）与一次通道等待，然后才被 `E_TRANSPORT_MALFORMED` 打回。
 * 这张表把**不读设备、无副作用**的顶层形状检查前移到闸门之前，后端各自的 `argsError`
 * 原样保留作防御性复验——分发前后用同一张键名表，两处各写一套判据就会漂移。
 *
 * 取值规则（与三张后端键表逐条对齐，不是另立一套）：
 * - [Spec.allowed] 是全部服务该能力的后端键表的**并集**：后端一律拒绝未知键，并集之外
 *   的键在这里也一律拒，措辞复用 [ArgErrors.unknown]；
 * - [Spec.required] 是各后端「缺键即拒」的**交集**：只有每条服务通路都拒绝缺失的键才进
 *   必填集，否则预校验会拒掉某条后端本可接受（有默认值）的调用；
 * - [Spec.types] 只在**所有**服务后端都「拒绝」该类型的值时才写：某条后端用 `optInt`/
 *   `optString` 把值悄悄收下（夹紧）时这里就不设类型——预校验拒掉后端本会接受的调用，
 *   等于把「夹紧」升格成「报错」，行为就变了。范围一律不进表：夹紧型范围（如
 *   `seconds`、`durationMs` 的上下限）照旧由后端收敛，拒绝型范围（如 `timeoutMs` 的
 *   0..15000）由后端用一句指名怎么改的话去拒，比一句泛化的类型错更有用。
 */
object CapabilityArgsSpec {

    /** 键值的类型档。仅收「后端明确拒绝其余类型」的那几档，见类注释的取值规则。 */
    enum class ArgType { NUMBER, STRING, BOOLEAN, OBJECT, ARRAY }

    /** 一条能力的形状规范。[allowed] 之外一律未知键；[required] 缺一即拒；[types] 键值必合型。 */
    private data class Spec(
        val allowed: Set<String>,
        val required: Set<String> = emptySet(),
        val types: Map<String, ArgType> = emptyMap(),
    )

    // ── 键名照抄后端键表，来源逐组注明；api 层不反向依赖 backend 包，故以字面量抄录 ──

    /** A11yBackend.POINT_KEYS：点击形状，既是必填也不允许多余键。 */
    private const val KEY_X = "x"
    private const val KEY_Y = "y"

    /** A11yBackend.SWIPE_KEYS / STROKE_KEYS：滑动的四个端点必填，时长可选。 */
    private const val KEY_FROM_X = "fromX"
    private const val KEY_FROM_Y = "fromY"
    private const val KEY_TO_X = "toX"
    private const val KEY_TO_Y = "toY"
    private const val KEY_DURATION_MS = "durationMs"

    /** A11yBackend / ShizukuBackend 的文本与按键键名（ui.text / ui.key / ui.setValue）。 */
    private const val KEY_TEXT = "text"
    private const val KEY_KEY = "key"

    /** NodeSelector 顶层目标两形状 + NodeActions.KEY_RELATIVE（节点级能力的三种指向写法）。 */
    private const val KEY_SELECTOR = "selector"
    private const val KEY_NODE_ID = "nodeId"
    private const val KEY_RELATIVE = "relative"

    /** NodeActions 的节点级修饰键。 */
    private const val KEY_DIRECTION = "direction"
    private const val KEY_TIMES = "times"
    private const val KEY_UNTIL = "until"
    private const val KEY_CHECKED = "checked"
    private const val KEY_ABSENT = "absent"
    private const val KEY_TIMEOUT_MS = "timeoutMs"
    private const val KEY_PERCENT = "percent"
    private const val KEY_VALUE = "value"

    /** DirectBackend 的数据类键名。 */
    private const val KEY_PACKAGE = "package"
    private const val KEY_KEYWORD = "keyword"
    private const val KEY_LIMIT = "limit"
    private const val KEY_INCLUDE_ONGOING = "includeOngoing"
    private const val KEY_TITLE = "title"
    private const val KEY_ID = "id"
    private const val KEY_KIND = "kind"
    private const val KEY_NAME = "name"
    private const val KEY_NUMBER = "number"
    private const val KEY_START_MS = "startMs"
    private const val KEY_END_MS = "endMs"
    private const val KEY_FILE = "file"
    private const val KEY_SECONDS = "seconds"

    /** ShizukuBackend 的 shell 族键名（settings.write / appops.set / surface.virtual / install）。 */
    private const val KEY_UPLOAD = "upload"
    private const val KEY_SETTING_KEY = "key"
    private const val KEY_SETTING_VALUE = "value"
    private const val KEY_NAMESPACE = "namespace"
    private const val KEY_OP = "op"
    private const val KEY_MODE = "mode"
    private const val KEY_ACTION = "action"
    private const val KEY_WIDTH = "width"
    private const val KEY_HEIGHT = "height"
    private const val KEY_DPI = "dpi"

    /** ShellVerbs.KEY_VERB / KEY_ARGS：sys.shell 顶层两键。 */
    private const val KEY_VERB = "verb"
    private const val KEY_ARGS = "args"

    /** 节点级目标键：指向写法只有 selector / nodeId / relative 三种（NodeSelector 判互斥）。 */
    private val TARGET_KEYS = setOf(KEY_SELECTOR, KEY_NODE_ID, KEY_RELATIVE)

    /**
     * 节点级目标的类型表。selector 与 relative 必须是对象（NodeSelector 以
     * "must be an object" 拒掉其余类型），nodeId 必须是数字（"must be a whole number"），
     * 且后者另受 [WHOLE_NUMBER_KEYS] 的整数值判据约束。
     */
    private val TARGET_TYPES = mapOf(
        KEY_SELECTOR to ArgType.OBJECT,
        KEY_NODE_ID to ArgType.NUMBER,
        KEY_RELATIVE to ArgType.OBJECT,
    )

    /**
     * 追加「有限且为整数值」判据的键。nodeId 在后端（NodeSelector）按整数值收下：
     * `1.5` 这类小数能过 NUMBER 档、到后端才被拒 —— 白占一次用户确认与通道等待，
     * 正是这张表要前移的那类拒绝。org.json 的分词器也收 NaN/Infinity 字面量，
     * 所以「非有限值」一并挡在这里。
     */
    private val WHOLE_NUMBER_KEYS = setOf(KEY_NODE_ID)

    /**
     * 每条能力一份规范。逐组的对齐依据：
     *
     * - 空 allowed（ui.snapshot / screen.capture / screen.observe）：三条服务后端的 ArgSpec
     *   都是不收任何键的空表，其余能力照三张表的并集抄录；
     * - ui.tap / ui.swipe：A11y 键表必填 {x,y} 与四个端点；Shizuku 的 ArgSpec 虽未列必填，
     *   但 displayTap/displaySwipe 的 intArgsError 对缺键同样报 "missing arg"，交集仍是
     *   全套坐标；坐标键两条通路都以 "must be a number" 拒绝非数字，故有 NUMBER 类型；
     *   durationMs 在 Shizuku 是 optInt 夹紧，故不设类型；
     * - 节点级一组：只挂 A11Y，键集照 A11yBackend.argSpec；required 保留 direction 与
     *   setValue 的 text（后端缺键即拒）；types 照 TARGET_TYPES 加 times/until/percent/
     *   value/timeoutMs 的 NUMBER（numberArg 拒绝非数字）；text/checked/absent 被
     *   optString/`== true` 收下任何类型，故不设类型；
     * - sys.shell：verb 必填（ShellVerbs 缺名即拒）；args 是数组——ShellVerbs 只按
     *   optJSONArray 取参、非数组会被当成空参处理，那会让 `{"verb":"screencap","args":"x"}`
     *   静默成功，这是预校验要堵上的唯一一条「后端夹紧但结果危险」的缝；verb 的取值
     *   由 NAME 正则与动词表拒，这里只锁住「得是字符串」这一层；
     * - 其余 direct/shizuku 条目：键集照抄，数值键一律被 optInt/optLong/optString
     *   收下（夹紧），故都不设类型。
     */
    private val specs: Map<CapabilityId, Spec> = mapOf(
        CapabilityId.UI_SNAPSHOT to Spec(emptySet()),
        CapabilityId.SCREEN_CAPTURE to Spec(emptySet()),
        CapabilityId.SCREEN_OBSERVE to Spec(emptySet()),

        CapabilityId.UI_TAP to Spec(
            allowed = setOf(KEY_X, KEY_Y),
            required = setOf(KEY_X, KEY_Y),
            types = mapOf(KEY_X to ArgType.NUMBER, KEY_Y to ArgType.NUMBER),
        ),
        CapabilityId.UI_SWIPE to Spec(
            allowed = setOf(KEY_FROM_X, KEY_FROM_Y, KEY_TO_X, KEY_TO_Y, KEY_DURATION_MS),
            required = setOf(KEY_FROM_X, KEY_FROM_Y, KEY_TO_X, KEY_TO_Y),
            types = mapOf(
                KEY_FROM_X to ArgType.NUMBER,
                KEY_FROM_Y to ArgType.NUMBER,
                KEY_TO_X to ArgType.NUMBER,
                KEY_TO_Y to ArgType.NUMBER,
            ),
        ),
        CapabilityId.UI_TEXT to Spec(setOf(KEY_TEXT), setOf(KEY_TEXT)),
        CapabilityId.UI_KEY to Spec(setOf(KEY_KEY), setOf(KEY_KEY)),

        CapabilityId.UI_CLICK to Spec(TARGET_KEYS, types = TARGET_TYPES),
        CapabilityId.UI_LONG_CLICK to Spec(TARGET_KEYS, types = TARGET_TYPES),
        CapabilityId.UI_SELECT to Spec(TARGET_KEYS, types = TARGET_TYPES),
        CapabilityId.UI_DISMISS to Spec(TARGET_KEYS, types = TARGET_TYPES),
        CapabilityId.UI_NODE to Spec(TARGET_KEYS, types = TARGET_TYPES),
        CapabilityId.UI_IME_ACTION to Spec(TARGET_KEYS, types = TARGET_TYPES),
        CapabilityId.UI_SCROLL to Spec(
            allowed = TARGET_KEYS + setOf(KEY_DIRECTION, KEY_TIMES, KEY_UNTIL),
            required = setOf(KEY_DIRECTION),
            types = TARGET_TYPES + mapOf(KEY_TIMES to ArgType.NUMBER, KEY_UNTIL to ArgType.NUMBER),
        ),
        CapabilityId.UI_SET_VALUE to Spec(
            allowed = TARGET_KEYS + setOf(KEY_TEXT),
            required = setOf(KEY_TEXT),
            types = TARGET_TYPES,
        ),
        CapabilityId.UI_SET_PROGRESS to Spec(
            allowed = TARGET_KEYS + setOf(KEY_PERCENT, KEY_VALUE),
            types = TARGET_TYPES + mapOf(KEY_PERCENT to ArgType.NUMBER, KEY_VALUE to ArgType.NUMBER),
        ),
        CapabilityId.UI_WAIT_FOR to Spec(
            allowed = TARGET_KEYS + setOf(KEY_TEXT, KEY_CHECKED, KEY_ABSENT, KEY_TIMEOUT_MS),
            types = TARGET_TYPES + mapOf(KEY_TIMEOUT_MS to ArgType.NUMBER),
        ),

        CapabilityId.APP_LAUNCH to Spec(setOf(KEY_PACKAGE), setOf(KEY_PACKAGE)),
        CapabilityId.APP_STOP to Spec(setOf(KEY_PACKAGE), setOf(KEY_PACKAGE)),
        CapabilityId.SURFACE_VIRTUAL to Spec(setOf(KEY_ACTION, KEY_WIDTH, KEY_HEIGHT, KEY_DPI)),

        CapabilityId.SYS_INTENT to Spec(
            // IntentTemplates 的封闭键集（template/hour/minute/length/message/page/
            // package/number/url）：模板名必填，其余键由模板各自判形状与取值范围，
            // 表内不重复那份判据；数值与文本键全被 optInt/optString 收下，故不设类型。
            allowed = setOf(
                "template", "hour", "minute", "length", "message",
                "page", "package", "number", "url",
            ),
            required = setOf("template"),
        ),
        CapabilityId.SYS_SHELL to Spec(
            allowed = setOf(KEY_VERB, KEY_ARGS),
            required = setOf(KEY_VERB),
            types = mapOf(KEY_VERB to ArgType.STRING, KEY_ARGS to ArgType.ARRAY),
        ),
        CapabilityId.PKG_INSTALL to Spec(setOf(KEY_UPLOAD), setOf(KEY_UPLOAD)),
        CapabilityId.SECURE_SETTINGS to Spec(
            allowed = setOf(KEY_SETTING_KEY, KEY_SETTING_VALUE, KEY_NAMESPACE),
            required = setOf(KEY_SETTING_KEY, KEY_SETTING_VALUE),
        ),
        CapabilityId.APPOPS_SET to Spec(
            allowed = setOf(KEY_PACKAGE, KEY_OP, KEY_MODE),
            required = setOf(KEY_PACKAGE, KEY_OP, KEY_MODE),
        ),

        CapabilityId.PKG_QUERY to Spec(setOf(KEY_KEYWORD)),

        CapabilityId.CLIPBOARD_READ to Spec(emptySet()),
        CapabilityId.CLIPBOARD_WRITE to Spec(setOf(KEY_TEXT), setOf(KEY_TEXT)),
        CapabilityId.CONTACT_READ to Spec(setOf(KEY_LIMIT)),
        CapabilityId.CONTACT_WRITE to Spec(setOf(KEY_NAME, KEY_NUMBER), setOf(KEY_NAME)),
        CapabilityId.CALENDAR_READ to Spec(setOf(KEY_LIMIT)),
        CapabilityId.CALENDAR_WRITE to Spec(
            allowed = setOf(KEY_TITLE, KEY_START_MS, KEY_END_MS),
            required = setOf(KEY_TITLE),
        ),
        CapabilityId.LOCATION_READ to Spec(emptySet()),
        CapabilityId.MEDIA_READ to Spec(setOf(KEY_KIND, KEY_LIMIT)),
        CapabilityId.MEDIA_WRITE to Spec(setOf(KEY_FILE, KEY_KIND), setOf(KEY_FILE)),
        CapabilityId.NOTIFY_READ to Spec(setOf(KEY_LIMIT, KEY_PACKAGE, KEY_INCLUDE_ONGOING)),
        CapabilityId.NOTIFY_POST to Spec(setOf(KEY_TITLE, KEY_TEXT, KEY_ID), setOf(KEY_TITLE)),
        CapabilityId.AUDIO_CAPTURE to Spec(setOf(KEY_SECONDS)),

        CapabilityId.SCREEN_RECORD to Spec(setOf(KEY_SECONDS)),
    )

    /**
     * 纯函数预校验：null=通过，否则给出一条可直接回给助手的措辞（与后端 argsError 同源）。
     *
     * 判定顺序与后端一致：先报未知键、再报缺失键、最后验类型——后端在一种形状内
     * 就是这个次序，同一次拼错在两处应当拿到同一种回答。
     */
    fun validate(id: CapabilityId, args: JSONObject): String? {
        val spec = specs[id] ?: return null
        for (key in args.keys()) {
            if (key !in spec.allowed) return ArgErrors.unknown(key, spec.allowed)
        }
        for (key in spec.required) {
            if (args.optString(key).isBlank()) return "missing arg: $key"
        }
        for ((key, type) in spec.types) {
            if (!args.has(key)) continue
            val value = args.opt(key) ?: continue
            if (!matches(type, value)) return typeMismatch(key, type, value)
        }
        // 整数值判据排在类型档之后：字符串先按「must be a number」报，数字里不构成
        // 整数值的才落到这里，同一次拼错在两处拿到的回答各有其位。
        for (key in spec.allowed) {
            if (key !in WHOLE_NUMBER_KEYS) continue
            val number = args.opt(key) as? Number ?: continue
            val d = number.toDouble()
            if (!d.isFinite() || d % 1.0 != 0.0) return notWholeNumber(key, number)
        }
        return null
    }

    /** org.json 的解析结果里，Number/Boolean/String/JSONObject/JSONArray 之外只剩 NULL 哨兵。 */
    private fun matches(type: ArgType, value: Any): Boolean = when (type) {
        ArgType.NUMBER -> value is Number
        ArgType.STRING -> value is String
        ArgType.BOOLEAN -> value is Boolean
        ArgType.OBJECT -> value is JSONObject
        ArgType.ARRAY -> value is JSONArray
    }

    /** 整数值判据不合的措辞：与类型档同一句式，带实际收到的值与类型。 */
    private fun notWholeNumber(key: String, value: Number): String =
        "$key must be a whole number, got $value (${value.javaClass.simpleName})"

    /**
     * 类型不合的措辞。数值键直接复用 [ArgErrors.notNumber]——那正是后端同一条判据
     * 用的原话；其余档按同一句式补齐，带**实际收到的值与类型**，让调用方分得清
     * 「改脚本」与「查通道」。
     */
    private fun typeMismatch(key: String, type: ArgType, value: Any?): String {
        if (type == ArgType.NUMBER) return ArgErrors.notNumber(key, value)
        val wanted = when (type) {
            ArgType.STRING -> "a string"
            ArgType.BOOLEAN -> "true or false"
            ArgType.OBJECT -> "an object"
            ArgType.ARRAY -> "an array"
            ArgType.NUMBER -> return ArgErrors.notNumber(key, value)
        }
        val got = when {
            value == null || value === JSONObject.NULL -> "nothing"
            value is String -> "\"${value.take(40)}\" (string)"
            else -> "$value (${value.javaClass.simpleName})"
        }
        return "$key must be $wanted, got $got"
    }
}
