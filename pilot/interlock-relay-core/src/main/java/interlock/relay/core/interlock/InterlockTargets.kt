package interlock.relay.core.interlock

import interlock.relay.core.protocol.CapabilityDescriptor
import interlock.relay.core.protocol.CapabilityId
import interlock.relay.core.exec.a11y.NodeSelector
import org.json.JSONObject

/**
 * 确认框上要替用户点出来的内容。
 *
 * 「每次询问」只有在用户看得见问的是哪个对象时才有意义：只写「要改安全设置」
 * 等于让用户为任意键背书。这里按能力逐个取字段，取不到就留空由界面省略，
 * 不把整个参数对象塞进对话框——那会把剪贴板文本、通知正文这类隐私原样摊开。
 */
object InterlockTargets {

    /**
     * [Pair.first] 为操作对象，[Pair.second] 为本次要写入的具体内容摘要。
     *
     * [charCount] 由调用方用界面语言格式化：对话框里冒出英文的 "chars" 是六种语言
     * 用户都会直接看到的错，而本模块的界面文案一律走资源，不在这里例外。
     */
    fun of(
        descriptor: CapabilityDescriptor,
        args: JSONObject,
        charCount: (Int) -> String,
    ): Pair<String?, String?> =
        when (descriptor.id) {
            // 这两条都是"一次批准覆盖一批命令/多个入口"，卡上必须把**这一条**写清楚：
            // 用户批的从来不是"shell 这个能力"，而是眼下这一行命令、这一个模板与这几个参数。
            CapabilityId.SYS_SHELL -> {
                val verb = args.optString("verb")
                val rest = args.optJSONArray("args")?.let { a ->
                    (0 until a.length()).joinToString(" ") { a.optString(it, "") }
                }.orEmpty()
                verb.takeIf { it.isNotEmpty() }?.take(MAX_DETAIL_CHARS) to
                    "$verb${if (rest.isEmpty()) "" else " $rest"}".take(MAX_DETAIL_CHARS)
            }

            // 特权通路的对象落到**命令的命令词**上：单段命令（如 reboot）本身就是对象；
            // 多段命令只取首段，赋值前缀（NAME=value）的值与重定向的落点一律不进 target，
            // 整条命令由 argsDigest 按参数原文（仅执行成功时）另行记录。取段判据见
            // [fullShellTargetToken]。
            CapabilityId.SYS_SHELL_FULL ->
                fullShellTargetToken(args.opt("command") as? String) to null

            CapabilityId.SYS_INTENT -> {
                // 卡上那一行要看得出「这是哪一件事、对谁做」。八条模板共用一个 template 名，
                // 只写模板名就等于让用户批一个看不懂的词；而按默认分支去格式化时间，
                // 打开设置页那几条会被印成 `-01:-01`。
                val template = args.optString("template")
                template.takeIf { it.isNotEmpty() }?.take(MAX_DETAIL_CHARS) to when (template) {
                    "alarm.set" -> "%02d:%02d".format(args.optInt("hour", -1), args.optInt("minute", -1))
                    "timer.set" -> "length=${args.opt("length")}s"
                    "settings.open" -> args.optString("page")
                    "app.info" -> args.optString("package")
                    "dial" -> args.optString("number")
                    "web.open" -> args.optString("url")
                    else -> ""
                }.take(MAX_DETAIL_CHARS)
            }

            CapabilityId.SECURE_SETTINGS -> {
                // 确认框上要看得见写的是哪一层：secure 与 system/global 是三张不同的表，
                // 用户批的是"改这个键"，不该由我们替他决定落在哪一层。
                val key = args.optString("key")
                val layer = args.optString("namespace").ifBlank { "secure" }
                key.takeIf { it.isNotEmpty() }
                    ?.let { "$layer/$it" }
                    ?.take(MAX_DETAIL_CHARS) to
                    args.optString("value").take(MAX_DETAIL_CHARS)
            }

            CapabilityId.APPOPS_SET ->
                args.optString("package").takeIf { it.isNotEmpty() }?.take(MAX_DETAIL_CHARS) to
                    "${args.optString("op")} -> ${args.optString("mode")}".take(MAX_DETAIL_CHARS)

            CapabilityId.APP_STOP,
            CapabilityId.PKG_QUERY,
            CapabilityId.APP_LAUNCH,
            -> args.optString("package").takeIf { it.isNotEmpty() }?.take(MAX_DETAIL_CHARS) to null

            // 建屏要用户当场看清「开一块多大的屏、做什么动作」：
            // 这块屏一旦存在，后续所有后台操作都发生在一个他看不见的画面上。
            CapabilityId.SURFACE_VIRTUAL ->
                args.optString("action").takeIf { it.isNotEmpty() }?.take(MAX_DETAIL_CHARS) to
                    "${args.optInt("width")}x${args.optInt("height")}".take(MAX_DETAIL_CHARS)

            // 剪贴板与输入框内容属于用户自己的数据，只报长度不报原文。
            CapabilityId.CLIPBOARD_WRITE -> null to charCount(args.optString("text").length)
            CapabilityId.UI_TEXT -> null to charCount(args.optString("text").length)

            CapabilityId.CONTACT_WRITE ->
                args.optString("name").takeIf { it.isNotEmpty() }?.take(MAX_DETAIL_CHARS) to
                    args.optString("number").take(MAX_DETAIL_CHARS)

            CapabilityId.CALENDAR_WRITE,
            CapabilityId.NOTIFY_POST,
            -> args.optString("package").takeIf { it.isNotEmpty() }?.take(MAX_DETAIL_CHARS) to
                args.optString("title").take(MAX_DETAIL_CHARS)

            // 递交文件：用户必须看到是哪个文件、进哪一类媒体集合，
            // 否则「每次询问」退化成对一个空框点允许 —— 这条能力的参数里没有 package。
            CapabilityId.MEDIA_WRITE ->
                args.optString("file").takeIf { it.isNotEmpty() }?.take(MAX_DETAIL_CHARS) to
                    args.optString("kind", "image").takeIf { it.isNotEmpty() }?.take(MAX_DETAIL_CHARS)

            // 安装：把要装的那个文件名点出来。真实包名要等后端解析 apk 才知道，
            // 而确认发生在解析之前——这里留空等于让用户对一个空框点「允许」。
            CapabilityId.PKG_INSTALL ->
                args.optString("upload").takeIf { it.isNotEmpty() }?.take(MAX_DETAIL_CHARS) to null

            // 节点级动作的对象在 selector / nodeId 里，不在 package 里。不把它点出来的话，
            // 「每次询问」就退化成对着一个空框点允许，而这类调用是真的会动别的 App 的界面。
            CapabilityId.UI_CLICK,
            CapabilityId.UI_LONG_CLICK,
            CapabilityId.UI_SELECT,
            CapabilityId.UI_DISMISS,
            CapabilityId.UI_SCROLL,
            CapabilityId.UI_SET_VALUE,
            CapabilityId.UI_NODE,
            CapabilityId.UI_WAIT_FOR,
            CapabilityId.UI_SET_PROGRESS,
            CapabilityId.UI_IME_ACTION,
            -> nodeTarget(args) to when (descriptor.id) {
                CapabilityId.UI_SET_VALUE -> charCount(args.optString("text").length)
                // 设值那条也要把值交上去：只写"目标 nodeId=8"，用户批的其实是"给这颗节点
                // 填一个数"，而数字本身是助手在沙盒里挑的 —— 音量、进度、闹钟时分都在这
                // 一条上，看不到值就等于把范围交给了调用方。
                CapabilityId.UI_SET_PROGRESS -> when {
                    args.has("value") -> "value=${args.opt("value")}".take(MAX_DETAIL_CHARS)
                    args.has("percent") -> "percent=${args.opt("percent")}%".take(MAX_DETAIL_CHARS)
                    else -> null
                }

                else -> null
            }

            // 包名一律截断：这些字符串同时进确认框与授权记录，未收口就等于让沙盒
            // 用一条参数决定宿主界面与记录文件里落多长。
            else -> args.optString("package").takeIf { it.isNotEmpty() }?.take(MAX_DETAIL_CHARS) to null
        }

    /**
     * 特权通路 target 的**命令词扫描**判据。单段命令词本身就是对象（`reboot` 的对象就是
     * reboot）；多段命令取真正的命令词，三类前缀不算：
     * - `NAME=value` 赋值前缀（含带引号的 `NAME="secret value"`）：等号右边的值可能是
     * 机密，绝不落 target，继续找命令词；
     * - 重定向操作符及其落点（`>file`、`>>file`、`2>&1`、`&>file`，无论出现在开头还是
     * 赋值前缀之后）：落点不进 target；
     * - 分隔符（`|` `;` `&`）：越过，找下一段。
     * token 读取按引号成段——引号内的空白不切分，引号本身不进 target。
     * 找不到命令词（空命令、只剩赋值与重定向）时回固定标识 [FULL_SHELL_TARGET_FALLBACK]
     * 而不是 null：授权记录里「对象未知」要与「没有对象」分得开。
     * 抽成纯函数是为了在 JVM 上把 tab、换行、无分隔长串、引号赋值与重定向这些场景
     * 穷举掉——真执行路径不可达。
     */
    internal fun fullShellTargetToken(command: String?): String? {
        if (command.isNullOrEmpty()) return null
        var index = 0
        while (index < command.length) {
            while (index < command.length && command[index].isWhitespace()) index++
            if (index >= command.length) break
            val c = command[index]
            // 重定向操作符及其落点：`>` `<` `>>` `2>` `&>` `>&2` `2>&1`，无论出现在开头
            // 还是赋值前缀之后。fd 复制（括号后带 &数字）没有文件落点，不吞下一个词。
            if (c == '<' || c == '>' || isFdRedirectStart(command, index)) {
                var bracket = false
                var dupAfterBracket = false
                while (index < command.length) {
                    val ch = command[index]
                    when {
                        ch == '<' || ch == '>' -> { bracket = true; index++ }
                        ch == '&' -> { index++; if (bracket) dupAfterBracket = true }
                        ch.isDigit() -> index++
                        else -> break
                    }
                }
                while (index < command.length && command[index].isWhitespace()) index++
                if (!dupAfterBracket && index < command.length &&
                    !startsAnotherRedirect(command, index) &&
                    command[index] !in FULL_SHELL_TOKEN_BREAKS
                ) {
                    index = readShellToken(command, index).second
                }
                continue
            }
            if (c in FULL_SHELL_TOKEN_BREAKS) {
                // 停在分隔符上：越过它找下一段，分隔符本身不是对象。
                index++
                continue
            }
            val (token, next) = readShellToken(command, index)
            index = next
            if (token.contains('=')) {
                // NAME=value 赋值前缀：值可能是机密，跳过继续找命令词；
                // 其余带等号的形状（+=、数组元素、--opt=value）认不出命令词，回固定标识。
                if (NAME_PREFIX.matches(token.substringBefore('='))) continue
                return FULL_SHELL_TARGET_FALLBACK
            }
            // 命令替换（$(…)）与以选项开头的残句：对象无法从参数读出，回固定标识。
            if (token.startsWith("$") || token.startsWith("-")) return FULL_SHELL_TARGET_FALLBACK
            if (token.isNotEmpty()) return token.take(MAX_DETAIL_CHARS)
        }
        return FULL_SHELL_TARGET_FALLBACK
    }

    /** `2>` / `2<` 形态的数字前缀重定向：数字后面紧跟括号才算，`2file` 这样的词不算。 */
    private fun isFdRedirectStart(command: String, index: Int): Boolean =
        command[index].isDigit() && index + 1 < command.length &&
            (command[index + 1] == '<' || command[index + 1] == '>')

    /** 落点位置是否是又一个重定向操作符（如 `> 2>file`），避免把下一个操作符当成文件名。 */
    private fun startsAnotherRedirect(command: String, index: Int): Boolean =
        command[index] == '<' || command[index] == '>' || isFdRedirectStart(command, index) ||
            (command[index] == '&' && index + 1 < command.length && command[index + 1] == '>')

    /** 引号成段地读一个 token：引号内的空白不切分，返回（去引号内容，停止下标）。 */
    private fun readShellToken(command: String, start: Int): Pair<String, Int> {
        var i = start
        val token = StringBuilder()
        while (i < command.length) {
            val c = command[i]
            if (c.isWhitespace() || c in FULL_SHELL_TOKEN_BREAKS) break
            if (c == '"' || c == '\'') {
                val quote = c
                i++
                while (i < command.length && command[i] != quote) {
                    token.append(command[i])
                    i++
                }
                if (i < command.length) i++
                continue
            }
            token.append(c)
            i++
        }
        return token.toString() to i
    }

    /**
     * 节点目标的可读写法：`nodeId=3`，或把选择器的条件列出来（`package=com.x class=Button`）。
     *
     * 只列选择器里那几个键：`direction`、`times` 这类本能力自己的参数与「对谁做」无关，
     * 堆进确认框只会把目标淹没。
     *
     * 呈现顺序与助手该写的顺序**相反**，因为这里要答的是另一个问题：用户要先看见
     * 「在动哪个 App」，而唯一能回答它的就是 package —— 它必须排最前。text 既最长又最可能
     * 是用户刚输入的隐私，排最后。逐值限长而不是整串截断：整串截断会让一条长文本
     * 把后面所有条件一起挤掉，那正是"对着一个空框点允许"的另一种形态。
     */
    private fun nodeTarget(args: JSONObject): String? {
        if (args.has(NodeSelector.KEY_NODE_ID)) {
            return "${NodeSelector.KEY_NODE_ID}=${args.opt(NodeSelector.KEY_NODE_ID)}".take(MAX_DETAIL_CHARS)
        }
        val selector = args.optJSONObject(NodeSelector.KEY_SELECTOR) ?: return null
        return NODE_TARGET_KEYS
            .filter { selector.has(it) }
            .joinToString(" ") { "$it=${selector.opt(it)?.toString()?.take(MAX_VALUE_CHARS)}" }
            .takeIf { it.isNotEmpty() }
            ?.take(MAX_DETAIL_CHARS)
    }

    /**
     * 这一次调用的**目标能不能跨调用钉得住同一个东西**。
     *
     * 判据是「效果落在哪里」，不是「参数写得够不够具体」：只要效果要等执行那一刻去控件树上
     * 现解，解到的就未必是用户点头时那一颗 —— 列表滚一格，`nodeId` 会换人，
     * `package`+`class`+`index` 会换人，连看起来唯一的 `text` / `id` 也会在 RecyclerView
     * 里换人（同一时刻只有一行在屏上，命中的仍然恰好是一个，却是另一行的那一颗）。
     * 坐标与焦点同理：它们指的也是"此刻这块屏上的这里"。
     *
     * 这类调用的迟到答案一律不缓冲：代价是下一次重试重新问一遍，收益是不会把用户对 A 的
     * 同意花到 B 上。反过来，落在数据上的调用（读通讯录、写日历、剪贴板、改设置）目标由
     * 参数本身说全，那一份答案才留得住。
     */
    fun isVolatileTarget(descriptor: CapabilityDescriptor, args: JSONObject): Boolean =
        volatileFor(
            descriptor.id,
            args.has(NodeSelector.KEY_NODE_ID) || args.has(NodeSelector.KEY_SELECTOR),
        )

    /** [isVolatileTarget] 的纯判据：参数形状那一路只给两个布尔，好让 JVM 侧也测得到。 */
    internal fun volatileFor(capability: CapabilityId, hasTreeOrPointTarget: Boolean): Boolean =
        capability in SCREEN_BOUND_CAPABILITIES || hasTreeOrPointTarget

    /**
     * 效果取决于**执行那一刻从设备上读到什么**的能力。
     *
     * 两类：一类落在屏上（节点、坐标、焦点、那块屏的画面），一类落在当下那份数据上 ——
     * 剪贴板、位置、通知列表、麦克风，以及递交上去的那个文件的字节。它们的共同点是参数
     * 说全了"读哪一格、装哪个路径"，却没说全"读到的是什么"：一份迟到 49 秒的同意，
     * 放行的是另一段时间、另一个内容。路径同名不等于字节没换 —— 沙盒在两次调用之间
     * 重写 `uploads/app.apk`，装上去的就是另一个二进制，而核验是在执行时才做的。
     *
     * 五条"把此刻设备上有什么列出来"的读同属这一类：应用清单、通讯录、日历、相册，
     * 以及一条一次读一组基础状态的设备状态——电量、网络、屏幕这些读数每一刻都在变，
     * 参数里没有任何一个字段能把它说尽。判据不是"读还是写"，是**内容有没有被参数说尽**。
     */
    private val SCREEN_BOUND_CAPABILITIES = setOf(
        CapabilityId.AUDIO_CAPTURE,
        CapabilityId.CALENDAR_READ,
        CapabilityId.CLIPBOARD_READ,
        CapabilityId.CONTACT_READ,
        CapabilityId.DEVICE_STATUS,
        CapabilityId.LOCATION_READ,
        CapabilityId.MEDIA_READ,
        CapabilityId.MEDIA_WRITE,
        CapabilityId.NOTIFY_READ,
        CapabilityId.PKG_INSTALL,
        CapabilityId.PKG_QUERY,
        CapabilityId.UI_NODE,
        CapabilityId.UI_CLICK,
        CapabilityId.UI_LONG_CLICK,
        CapabilityId.UI_SELECT,
        CapabilityId.UI_DISMISS,
        CapabilityId.UI_SCROLL,
        CapabilityId.UI_SET_VALUE,
        CapabilityId.UI_SET_PROGRESS,
        CapabilityId.UI_IME_ACTION,
        CapabilityId.UI_WAIT_FOR,
        CapabilityId.UI_TAP,
        CapabilityId.UI_SWIPE,
        CapabilityId.UI_TEXT,
        CapabilityId.UI_KEY,
        CapabilityId.UI_SNAPSHOT,
        CapabilityId.SCREEN_CAPTURE,
        CapabilityId.SCREEN_OBSERVE,
        CapabilityId.SCREEN_RECORD,
    )

    private const val MAX_DETAIL_CHARS = 80

    /** 特权通路取段的分隔符：空白之外，这些 shell 元字符一出现即认为首段结束。 */
    private const val FULL_SHELL_TOKEN_BREAKS = "<>|;&"

    /**
     * 赋值前缀的 NAME 形状：变量名（字母/下划线开头，后接字母数字下划线）加等号。
     * `--opt=value`、`ARR+=x`、`arr[0]=x` 一类不匹配，按「认不出命令词」回固定标识。
     */
    private val NAME_PREFIX = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /**
     * 特权通路没有命令词时的固定对象标识（重定向开头、只剩赋值前缀、分隔符残句）。
     * 不用 "shell"：那与授权记录里的执行身份署名（uid=shell）撞词，问责时读不准。
     */
    internal const val FULL_SHELL_TARGET_FALLBACK = "command"

    /** 单个条件值的上限：够看清"点的是哪一个"，又不至于让一行吞掉整条目标。 */
    private const val MAX_VALUE_CHARS = 24

    /** 确认框里的呈现顺序：先说在动哪个 App，再说是什么控件，最后才是它上面的文字。 */
    private val NODE_TARGET_KEYS = listOf(
        NodeSelector.KEY_PACKAGE,
        NodeSelector.KEY_CLASS,
        NodeSelector.KEY_ID,
        NodeSelector.KEY_DESC,
        NodeSelector.KEY_TEXT,
        NodeSelector.KEY_INDEX,
    )
}
