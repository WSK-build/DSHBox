package interlock.relay.core.exec.direct

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.StatFs
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.AlarmClock
import android.provider.Settings
import android.webkit.MimeTypeMap
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import interlock.relay.core.R
import interlock.relay.core.spi.RelayNotificationChannelSpec
import interlock.relay.core.spi.RelayText
import interlock.relay.core.protocol.CapabilityId
import interlock.relay.core.protocol.ArgErrors
import interlock.relay.core.protocol.RelayError
import interlock.relay.core.protocol.SurfaceKind
import interlock.relay.core.exec.BackendCall
import interlock.relay.core.exec.BackendResult
import interlock.relay.core.exec.RelayBackend
import interlock.relay.core.exec.a11y.RelayAccessibilityService
import interlock.relay.core.storage.RelayPaths
import interlock.relay.core.storage.StorageReaper
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.channels.Channels

/**
 * 宿主进程内直接调用平台接口。覆盖普通运行时权限即可达的数据与系统能力。
 *
 * 需要无障碍或 shell 身份的能力不在此处实现，由各自后端承担；
 * 未被任何后端支持的能力会在能力清单中被隐藏，而不是显示一个点不动的开关。
 */
class DirectBackend(
    context: Context,
    reaper: StorageReaper,
    private val clipboardUsable: () -> Boolean = { false },
    private val projection: ScreenProjection,
    /**
     * 本应用此刻是否已经在前台（助手页可见，或本进程处于前台）。
     * 只给「自指启动」一条判据用：用户已经在里面时不再抬一次主界面。
     */
    private val hostInFront: () -> Boolean = { false },
    /** notify.post 的通知渠道配置与文案口，由装配根按 [interlock.relay.core.spi.RelaySurfaces] 解析后递进来。 */
    private val notifyChannel: RelayNotificationChannelSpec = RelayNotificationChannelSpec.defaults()
        .first { it.role == interlock.relay.core.spi.RelayChannelRole.NOTIFY_POST },
    private val text: RelayText = RelayText.Default(context),
    /**
     * 媒体库落点的相册目录名，来自 [interlock.relay.core.spi.RelayPathPolicy.mediaAlbumDir]：
     * 写入落点与占用对账（MediaLibraryUsage 的查询前缀）必须指向同三个目录，两处各写一份
     * 就会在宿主换目录名时只改一半。
     */
    private val mediaAlbumDir: String = interlock.relay.core.spi.RelayPathPolicy.BuiltIn.mediaAlbumDir,
) : RelayBackend {

    private val appContext = context.applicationContext
    private val appPackage = appContext.packageName
    private val audioCapture = AudioCapture(reaper)

    /**
     * 落点目录与 MIME 由本端固定：`kind` 之外不接受沙盒给出的路径。
     * 目录名经 [mediaAlbumDir] 从路径策略取，不在此写死 —— 宿主换相册目录名时
     * 这里必须跟着变，否则写入与占用对账（MediaLibraryUsage）就指到两个地方。
     */
    private val uploadDirs = mapOf(
        "image" to "Pictures/$mediaAlbumDir/",
        "video" to "Movies/$mediaAlbumDir/",
        "audio" to "Music/$mediaAlbumDir/",
    )

    override fun available(): Boolean = true

    override fun supports(capability: CapabilityId, surface: SurfaceKind): Boolean {
        // 可信虚拟屏的前提是**先有那块屏**。本后端只用真屏：startActivity、ContentResolver、
        // 以及镜像真屏的 MediaProjection。对 trusted-display 报「支持」会让响应写着
        // trusted-display 而动作发生在用户眼前，等于把「后台操控」报成已实现。
        // 裁决层据此退回前台并如实标降级。
        if (surface == SurfaceKind.BACKEND_TRUSTED) return false
        return when (capability) {
            CapabilityId.PKG_QUERY,
            CapabilityId.APP_LAUNCH,
            CapabilityId.CONTACT_READ,
            CapabilityId.CONTACT_WRITE,
            CapabilityId.CALENDAR_READ,
            CapabilityId.CALENDAR_WRITE,
            CapabilityId.LOCATION_READ,
            CapabilityId.MEDIA_READ,
            CapabilityId.MEDIA_WRITE,
            CapabilityId.NOTIFY_POST,
            CapabilityId.NOTIFY_READ,
            CapabilityId.AUDIO_CAPTURE,
            CapabilityId.SYS_INTENT,
            -> true

            // 「本应用是否持有输入焦点」是执行期的事实，不是「有没有实现」：写进 supports()
            // 会让一次后台调用被判成 E_NOT_IMPLEMENTED（退出码 1，反过来教助手改脚本）。
            // 判据移到 execute，那里回 E_GATE_NO_FOREGROUND。
            //
            // 剪贴板两条的能力范围限制就是这一行：Android 10 起系统不允许非前台应用读写剪贴板，
            // 所以它们只有前台一个执行面。界面文案只写动作（读出/写入系统剪贴板），
            // 「仅本应用处于前台时可用」这句限制以这里与助手说明为准。
            CapabilityId.CLIPBOARD_READ,
            CapabilityId.CLIPBOARD_WRITE,
            -> surface == SurfaceKind.FOREGROUND

            // 三条屏幕采集能力走 MediaProjection：无障碍没开时它们照样可用，
            // 代价是每次会话要用户点一次系统弹框（能力上限 SESSION_ONLY 即由此而来）。
            CapabilityId.SCREEN_CAPTURE,
            CapabilityId.SCREEN_RECORD,
            -> true

            CapabilityId.SCREEN_OBSERVE -> surface == SurfaceKind.OBSERVE_ONLY

            else -> false
        }
    }

    override suspend fun execute(call: BackendCall): BackendResult {
        // 形状判定排在所有系统调用之前：采集要弹系统授权框、通讯录与媒体库要跑几百毫秒，
        // 参数写错时这些成本一分都不该付（见 [argSpec]）。
        argsError(call)?.let { return it }
        return when (call.descriptor.id) {
            CapabilityId.PKG_QUERY -> queryLaunchableApps(call.args)
            CapabilityId.APP_LAUNCH -> launchApp(call.args.optString(KEY_PACKAGE))
            CapabilityId.CONTACT_READ -> readContacts(limitOf(call.args))
            CapabilityId.CONTACT_WRITE -> writeContact(call.args)
            CapabilityId.CALENDAR_READ -> readCalendar(limitOf(call.args))
            CapabilityId.CALENDAR_WRITE -> writeCalendar(call.args)
            CapabilityId.LOCATION_READ -> readLocation()
            CapabilityId.MEDIA_READ -> readMedia(call.args.optString(KEY_KIND, "image"), limitOf(call.args))
            CapabilityId.MEDIA_WRITE -> writeMedia(call)
            CapabilityId.NOTIFY_POST -> postNotification(call.args)
            CapabilityId.NOTIFY_READ -> readNotifications(call.args)
            CapabilityId.AUDIO_CAPTURE -> audioCapture.capture(call)
            CapabilityId.CLIPBOARD_READ -> readClipboard()
            CapabilityId.CLIPBOARD_WRITE -> writeClipboard(call.args.optString(KEY_TEXT))
            CapabilityId.SYS_INTENT -> semanticIntent(call.args)
            CapabilityId.SCREEN_CAPTURE,
            CapabilityId.SCREEN_OBSERVE,
            -> projection.captureFrame(call)

            CapabilityId.SCREEN_RECORD -> projection.record(call)
            else -> BackendResult.Failed(RelayError.CAPABILITY_NOT_IMPLEMENTED, call.descriptor.id.wire)
        }
    }

    /**
     * 表内每个页面名对应的系统设置 action。全部是公开常量、全部不需要权限，
     * 全部只是**把那一页摆到用户眼前**——不改任何一个开关。
     */
    private fun settingsAction(page: String?): String = when (page) {
        "wifi" -> Settings.ACTION_WIFI_SETTINGS
        "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
        "display" -> Settings.ACTION_DISPLAY_SETTINGS
        "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
        "sound" -> Settings.ACTION_SOUND_SETTINGS
        "apn" -> Settings.ACTION_APN_SETTINGS
        "developer" -> Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
        else -> Settings.ACTION_NFC_SETTINGS
    }

    /**
     * 交接成功不等于事情办成，两类模板要分开说。
     *
     * 设闹钟/计时器那两条带 `SKIP_UI`，接收方不回报结果，所以只能声明"交出去了"；
     * 打开页面那几条一定上屏，但**上屏到谁家**由系统选定的默认应用决定（实测本机的
     * `SET_ALARM` 有两条候选、系统只能给选择器，而 `SET_TIMER` 直达时钟）。
     */
    private fun handoffNote(template: String): String = when (template) {
        IntentTemplates.ALARM_SET, IntentTemplates.TIMER_SET ->
            "the clock app took the request without a screen (SKIP_UI); it does not report back, " +
                "so this says the hand-off happened, not that a specific alarm now exists"
        IntentTemplates.DIAL ->
            "the dialer opened with this number; nothing was called - ACTION_DIAL never places the call"
        else ->
            "the system page or app opened; this says the hand-off happened, not what the user did next"
    }

    /** 一条能力接受的键集，以及其中不可缺、不可为空的键。 */
    internal data class ArgSpec(val allowed: Set<String> = emptySet(), val required: Set<String> = emptySet())

    /**
     * 表内的语义快捷入口：参数定形之后交给系统，action 与 extra 的名字都不由助手给。
     *
     * 发之前先判这一发能不能落到屏幕上。Android 10 起，非前台应用的 `startActivity` 会被
     * 系统**静默**拦下：不抛异常、没有回包，用户那边什么都没发生。把那种现场报成成功等于
     * 替系统撒一次谎，所以既不在前台又没有悬浮窗豁免时直接回 `E_GATE_NO_FOREGROUND`，
     * 把要做的那一步交回给人。豁免只有这两条是平台公开的口径，本模块两条都判得到。
     */
    private fun semanticIntent(args: org.json.JSONObject): BackendResult {
        val params = when (val outcome = IntentTemplates.validate(args)) {
            is IntentTemplates.Outcome.Bad ->
                return BackendResult.Failed(RelayError.TRANSPORT_MALFORMED, outcome.reason)
            is IntentTemplates.Outcome.Ok -> outcome.params
        }
        // `SKIP_UI` 只对那两条"替我设一下"的入口有语义；打开页面那一类本来就一定要上屏，
        // 给它们塞这个 extra 只是让回包看起来都一样。
        val intent = when (params.template) {
            IntentTemplates.ALARM_SET -> Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, params.hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, params.minute)
                .apply { params.message?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) } }
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            IntentTemplates.TIMER_SET -> Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, params.lengthSeconds)
                .apply { params.message?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) } }
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            IntentTemplates.ALARM_SHOW -> Intent(AlarmClock.ACTION_SHOW_ALARMS)
            IntentTemplates.TIMER_SHOW -> Intent(AlarmClock.ACTION_SHOW_TIMERS)
            IntentTemplates.APP_INFO -> Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", params.packageName, null),
            )
            IntentTemplates.DIAL -> Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", params.number, null))
            IntentTemplates.WEB_OPEN -> Intent(Intent.ACTION_VIEW, Uri.parse(params.url))
            else -> Intent(settingsAction(params.page))
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val target = runCatching {
            appContext.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }.getOrNull()
            ?: return BackendResult.Failed(
                RelayError.BACKEND_UNAVAILABLE,
                "no app on this device handles ${intent.action}: a template that is in the table can still " +
                    "have no receiver here, which is a property of this device, not of the argument",
            )
        val top = foregroundPackage()
        if (top != appPackage && !overlayGranted()) {
            return BackendResult.Failed(
                RelayError.GATE_NO_FOREGROUND,
                "would be blocked as a background activity start (foreground is ${top ?: "unknown"}): " +
                    "grant the overlay permission, or send this while the assistant page is open",
            )
        }
        // 发出去的这一步不能只回"被拒了"：系统在这一层抛的异常类名与那一句就是病历
        // （实测同一张表里两条模板走两种结局：一条落到时钟应用被拒，另一条交给系统选择器却成功）。
        // 丢掉它，助手与我们都只能猜。
        val failure = runCatching { appContext.startActivity(intent) }.exceptionOrNull()
        if (failure != null) {
            return BackendResult.Failed(
                RelayError.BACKEND_UNAVAILABLE,
                "startActivity refused for ${target.activityInfo.packageName}: " +
                    "${failure.javaClass.simpleName}:${failure.message?.take(160)?.replace('\n', ' ')}",
            )
        }
        return BackendResult.Ok(
            data = org.json.JSONObject()
                .put("template", params.template)
                .put("resolvedPackage", target.activityInfo.packageName)
                .apply {
                    // 只有那两条设闹钟/设计时器真的带 SKIP_UI，别照抄成"每条都不上屏"。
                    if (params.hour >= 0) put("skipUi", true).put("hour", params.hour).put("minute", params.minute)
                    if (params.lengthSeconds >= 0) put("skipUi", true).put("lengthSeconds", params.lengthSeconds)
                    params.page?.let { put("page", it) }
                    params.packageName?.let { put("package", it) }
                    params.number?.let { put("numberLength", it.length) }
                    params.url?.let { put("url", it) }
                    params.message?.let { put("message", it) }
                }
                .put("note", handoffNote(params.template)),
        )
    }

    /** 悬浮窗授权是否在手：它是系统对后台启动 Activity 的公开豁免之一，与确认卡能否上屏同源。 */
    private fun overlayGranted(): Boolean =
        runCatching { Settings.canDrawOverlays(appContext) }.getOrDefault(false)

    /**
     * 每条能力接受的参数键，逐条见 [ARG_SPECS]。null 表示不由本后端实现，
     * 交给分发处回 `E_NOT_IMPLEMENTED`。
     */
    internal fun argSpec(id: CapabilityId): ArgSpec? = ARG_SPECS[id]

    /**
     * 参数来自沙盒，是不可信边界：未知键与空值一律拒，错误码用通道约定的
     * `E_TRANSPORT_MALFORMED`（退出码 1 = 改脚本，不是重试）。
     */
    private fun argsError(call: BackendCall): BackendResult? {
        val spec = argSpec(call.descriptor.id) ?: return null
        val args = call.args
        for (key in args.keys()) {
            if (key !in spec.allowed) {
                return BackendResult.Failed(RelayError.TRANSPORT_MALFORMED, ArgErrors.unknown(key, spec.allowed))
            }
        }
        for (key in spec.required) {
            if (args.optString(key).isBlank()) {
                return BackendResult.Failed(RelayError.TRANSPORT_MALFORMED, "missing arg: $key")
            }
        }
        return null
    }

    /**
     * 读取条数一律收敛到 [1, DEFAULT_LIMIT]。`optInt` 只保证拿到整数，不保证有界：
     * 未夹紧时 `{"limit":2147483647}` 会让宿主为一台八万张照片的机器构造几十 MB 的
     * JSONArray 再写进绑定目录，先倒下的是宿主。
     */
    private fun limitOf(args: JSONObject): Int = args.optInt(KEY_LIMIT, DEFAULT_LIMIT).coerceIn(1, DEFAULT_LIMIT)

    /** 读监听服务已积累的通知快照。正文只在内存里按窗口保留，不落盘。 */
    private fun readNotifications(args: JSONObject): BackendResult {
        // 「用户没开通知监听」在门禁处已被拦下，走到这里说明设置里是开的而服务此刻没绑上
        // （刚拨过开关、进程刚被回收）：稍后重来即可，不需要用户再去设置里动一次。
        if (!RelayNotificationListener.Holder.isConnected()) {
            return BackendResult.Failed(RelayError.BACKEND_UNAVAILABLE, "notification listener not bound")
        }
        val limit = args.optInt(KEY_LIMIT, DEFAULT_NOTE_LIMIT).coerceIn(1, DEFAULT_LIMIT)
        val filter = args.optString(KEY_PACKAGE).takeIf { it.isNotBlank() }
        val entries = RelayNotificationListener.Holder.snapshot(
            limit = limit,
            packageFilter = filter,
            includeOngoing = args.optBoolean(KEY_INCLUDE_ONGOING, false),
        )
        val items = JSONArray()
        entries.forEach { entry ->
            items.put(
                JSONObject().apply {
                    put("package", entry.packageName)
                    put("title", entry.title)
                    put("text", entry.text)
                    put("postedAtMs", entry.postedAtMs)
                },
            )
        }
        return ok(JSONObject().put("count", entries.size).put("notifications", items))
    }

    /**
     * 可启动应用清单。`keyword` 是可选的子串过滤（包名或显示名，大小写不敏感）。
     *
     * 加这一道过滤是因为列表本身可以很长：回包给调用方截断之前，先在宿主这一侧把范围缩小，
     * 否则调用方只能绕过受管通路去设备上 grep。`total` 与 `count` 分着给，
     * 是为了让"过滤后只剩三条"不被读成"这台机器只有三条应用"。
     */
    private fun queryLaunchableApps(args: JSONObject): BackendResult {
        val keyword = args.optString("keyword").trim()
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = appContext.packageManager.queryIntentActivities(intent, 0)
        val items = JSONArray()
        var matched = 0
        resolved.forEach { info ->
            val pkg = info.activityInfo.packageName
            val label = info.loadLabel(appContext.packageManager).toString()
            if (appMatches(keyword, pkg, label)) {
                matched++
                items.put(JSONObject().put("package", pkg).put("name", label))
            }
        }
        val data = JSONObject().put("count", matched).put("apps", items)
        if (keyword.isNotEmpty()) {
            data.put("keyword", keyword)
            data.put("total", resolved.size)
        }
        return ok(data)
    }

    /**
     * 启动并核验前台归属。
     *
     * `startActivity` 返回只代表系统接下了请求，落地是异步的：冷启动与分身应用的窗口要
     * 一到两秒之后才建起来，那期间最前面还是桌面。把「没抛异常」当成「已启动」直接回 ok，
     * 助手就会拿这个结论去操作桌面。
     */
    private suspend fun launchApp(packageName: String): BackendResult {
        // 起的是本应用自己、而本应用已经在前台：什么都不做，直接回"已在前台"。
        // 唯一的自指启动是收尾用的「把宿主带回前台」；此时再 startActivity 一次会按
        // singleTask 把主界面抬到栈顶，把正开着的助手页顶掉——用户本来就在里面，
        // 这一下等于把他从助手页赶回主界面。判据只覆盖自指，不影响启动别的应用。
        if (packageName == appPackage && hostInFront()) {
            // 达到这里说明辅助功能没读到聚焦窗口（读到了就会走下面 observed == packageName
            // 那条真实核验的支）。按本模块的纪律：没核验过就不能回 verified:true，
            // 但"什么都没启动"这件事是确定的，因此把结论拆开说清——助手可以据此收尾。
            val observed = foregroundPackage()
            return ok(
                data = JSONObject().put(KEY_PACKAGE, packageName)
                    .put("verified", observed == appPackage)
                    .put("alreadyInFront", true)
                    .apply { if (observed != null) put("observed", observed) },
                reason = "this app was already in the foreground; nothing was started" +
                    if (observed == appPackage) "" else " (no foreground observation: verified is false)",
            )
        }
        // 查不到启动入口属参数错误：调用侧可能把 pkg.query 里的显示名当成包名递进来。
        // 回 E_TRANSPORT_MALFORMED 让调用方改参数；回「用户去开权限」是误导，重试多少次都一样。
        val intent = appContext.packageManager.getLaunchIntentForPackage(packageName)
            ?: return BackendResult.Failed(
                RelayError.TRANSPORT_MALFORMED,
                "not launchable: $packageName (expected a package name from pkg.query, not a display name)",
            )
        val started = runCatching {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent)
        }
        if (started.isFailure) {
            // 系统按自己的策略或状态拒了这次跳转，与本应用在前台无关，也不是用户拒绝过：
            // 异常类名就是这里唯一的事实，交给宿主侧故障码如实上报。
            return BackendResult.Failed(
                RelayError.INTERNAL,
                "startActivity threw ${started.exceptionOrNull()?.javaClass?.simpleName}",
            )
        }
        val observed = awaitForeground(packageName)
        val inCloneContainer = observed != null && observed in CLONE_CONTAINERS
        val data = JSONObject().put(KEY_PACKAGE, packageName)
        return when {
            // 核验通过：看到的正是请求要起的那个包。
            observed == packageName -> ok(data.put("verified", true))

            // 确实看到另一个应用停在最前面：不声称成功。这一支与"还在冷启动"不是一回事 ——
            // 那种情况下 observed 为 null，走下面那条 verified:false 的分支。
            // 这里不用 E_BACKEND_UNAVAILABLE：同一件事在可信屏那一路回的是 E_LAUNCH_NOT_LANDED，
            // 一义两码会让调用方只认一支；而那个码的处置建议是去查后端连接，此刻后端好好的。
            observed != null && !inCloneContainer -> BackendResult.Failed(
                RelayError.LAUNCH_NOT_LANDED,
                "the launch was accepted but another app is confirmed to hold the front: asked for " +
                    "$packageName, foreground is $observed. This is not a cold start still running " +
                    "(that case answers verified:false); read ui.snapshot to see what is up, or ask " +
                    "the user to leave that screen, before sending this again",
            )

            // 认不出分身容器里面那个包，或这一路根本没有可信观察：仍算成功，
            // 但 verified 必须是 false ——「没核验」不能伪装成「核验通过」。
            else -> {
                if (observed != null) data.put("observed", observed)
                ok(
                    data = data.put("verified", false),
                    reason = when {
                    // 中介包停在最前面时，"认不出是哪个分身"只是次要信息，更要紧的是那可能
                    // 是一个等人选图标的系统选择框：它不进控件树、不报错，只有截帧看得见。
                    // 这一句要带着下一步动作，否则调用方拿着 verified:false 只会原样重试。
                    inCloneContainer -> "foreground is clone container $observed, cloned app not " +
                        "identifiable; a system chooser may be waiting for an icon pick. Read " +
                        "ui.snapshot first - this ROM does expose that box's tree - and use " +
                        "screen.capture to tell the original icon from the clone, then answer " +
                        "with ui.tap (no display argument: the surface is the user's choice) " +
                        "and launch again"
                        RelayAccessibilityService.current() == null -> "no observation: accessibility service not connected"
                        else -> "no foreign window seen yet: still launching or blocked by this app"
                    },
                )
            }
        }
    }

    /**
     * 轮询「启动落地后停在最前面的应用」。观察渠道只有已绑定的无障碍服务：
     * 免 shell 的通路里没有查询前台栈的公开接口。这里只**读**它的窗口归属，
     * 不把任何操作交给它。
     *
     * 返回值的三种可信观察由调用方判定语义；null 表示这一路没有可信观察。
     */
    private suspend fun awaitForeground(target: String): String? {
        var observed: String? = null
        var samples = 0
        var blankSamples = 0
        while (samples < FOREGROUND_POLL_TRIES && blankSamples < FOREGROUND_POLL_BLANK_LIMIT) {
            samples++
            val seen = foregroundPackage()
            if (seen == null) {
                blankSamples++
            } else {
                when {
                    // 命中就停：正常路径不该为已经确认的事等满预算。
                    seen == target -> return seen

                    // 分身容器里读不到真实包名，继续等也许能等到目标自己的窗口。
                    seen in CLONE_CONTAINERS -> observed = seen

                    // 本应用自己的窗口（确认框、宿主界面）既不是「目标已起来」也不是
                    // 「别的应用抢了前台」：只留原值，不计入放弃轮询的空白次数——
                    // 助手带着宿主界面发起启动时，这一屏会连续读到好几次。
                    seen == appPackage -> Unit

                    else -> observed = seen
                }
            }
            if (samples < FOREGROUND_POLL_TRIES && blankSamples < FOREGROUND_POLL_BLANK_LIMIT) {
                delay(FOREGROUND_POLL_MS)
            }
        }
        return observed
    }

    /**
     * 此刻最前面的应用包名，观察不到时为 null。
     *
     * 取聚焦窗口而不是活动窗口：分屏与投屏下「活动」可能停在别一屏上，而用户正在
     * 交互的那一屏才是启动的落地结果。窗口根节点的包名是唯一可信的归属来源。
     */
    private fun foregroundPackage(): String? {
        val service = RelayAccessibilityService.current() ?: return null
        val focused = service.windows.orEmpty().firstOrNull { window -> window.isFocused }
        return focused?.root?.packageName?.toString()
            ?: service.rootInActiveWindow?.packageName?.toString()
    }

    private fun readContacts(limit: Int): BackendResult {
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        val items = JSONArray()
        appContext.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext() && items.length() < limit) {
                items.put(
                    JSONObject().apply {
                        put("name", cursor.getString(0))
                        put("number", cursor.getString(1))
                    },
                )
            }
        }
        return ok(JSONObject().put("count", items.length()).put("contacts", items))
    }

    private fun writeContact(args: JSONObject): BackendResult {
        val name = args.optString(KEY_NAME)
        val number = args.optString(KEY_NUMBER)
        val ops = arrayListOf(
            ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                .build(),
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                .build(),
        )
        if (number.isNotBlank()) {
            ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, number)
                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                .build()
        }
        return runCatching {
            val result = appContext.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            ok(JSONObject().put("operations", result.size))
        }.getOrElse {
            // 缺权限在门禁处已被拦下：提供者可写入却抛出来，是宿主侧的故障，不是用户动作。
            BackendResult.Failed(RelayError.INTERNAL, "contact write rejected: ${it.javaClass.simpleName}")
        }
    }

    private fun readCalendar(limit: Int): BackendResult {
        val projection = arrayOf(
            CalendarContract.Events.CALENDAR_ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
        )
        val items = JSONArray()
        appContext.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            projection,
            null,
            null,
            "${CalendarContract.Events.DTSTART} ASC",
        )?.use { cursor ->
            while (cursor.moveToNext() && items.length() < limit) {
                items.put(
                    JSONObject().apply {
                        put("calendarId", cursor.getLong(0))
                        put("title", cursor.getString(1))
                        put("startMs", cursor.getLong(2))
                        put("endMs", cursor.getLong(3))
                    },
                )
            }
        }
        return ok(JSONObject().put("count", items.length()).put("events", items))
    }

    private fun writeCalendar(args: JSONObject): BackendResult {
        val title = args.optString(KEY_TITLE)
        val calendarId = firstCalendarId()
            ?: return BackendResult.Failed(RelayError.GATE_SYSTEM_MISSING, "no calendar account")
        val start = args.optLong(KEY_START_MS, System.currentTimeMillis())
        return runCatching {
            val uri = appContext.contentResolver.insert(
                CalendarContract.Events.CONTENT_URI,
                android.content.ContentValues().apply {
                    put(CalendarContract.Events.CALENDAR_ID, calendarId)
                    put(CalendarContract.Events.TITLE, title)
                    put(CalendarContract.Events.DTSTART, start)
                    put(CalendarContract.Events.DTEND, args.optLong(KEY_END_MS, start + DEFAULT_EVENT_MS))
                    put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
                },
            )
            ok(JSONObject().put("eventId", uri?.let { ContentUris.parseId(it) } ?: -1L))
        }.getOrElse {
            BackendResult.Failed(RelayError.INTERNAL, "calendar write rejected: ${it.javaClass.simpleName}")
        }
    }

    private fun firstCalendarId(): Long? =
        appContext.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID),
            null,
            null,
            null,
        )?.use { if (it.moveToFirst()) it.getLong(0) else null }

    private fun readLocation(): BackendResult {
        val manager = appContext.getSystemService<LocationManager>()
            ?: return BackendResult.Failed(RelayError.CAPABILITY_UNAVAILABLE_ON_DEVICE, "no location service")
        val providers = manager.allProviders.filter {
            manager.isProviderEnabled(it) &&
                appContext.checkPermission(Manifest.permission.ACCESS_FINE_LOCATION, Process.myPid(), Process.myUid()) ==
                PackageManager.PERMISSION_GRANTED
        }
        // 一个定位结果都没有：可能是定位总开关关着（enabled 数为 0），也可能是各提供商
        // 还没存下过位置。两者都不是「把本应用切到前台」能解决的，且稍后重问就有。
        val latest = providers.mapNotNull { manager.getLastKnownLocation(it) }
            .maxByOrNull { it.time }
            ?: return BackendResult.Failed(
                RelayError.BACKEND_UNAVAILABLE,
                "no location fix yet (enabled providers: ${providers.size})",
            )
        return ok(
            JSONObject().apply {
                put("latitude", latest.latitude)
                put("longitude", latest.longitude)
                put("accuracyMeters", latest.accuracy)
                put("fixAgeMs", System.currentTimeMillis() - latest.time)
                put("provider", latest.provider)
            },
        )
    }

    /**
     * 把沙盒递交到 `run/uploads/` 的文件写进共享媒体集合。
     *
     * 走 MediaStore 而不是文件路径：API 29 起写自己的新条目零权限，而按路径写要
     * `MANAGE_EXTERNAL_STORAGE`（能力清单里没有，也不打算要）。落点目录与 MIME 由本端
     * 按 `kind` 固定，不接受沙盒指定 —— 让它挑目录等于让共享存储成为可被投放的地点，
     * 而它唯一需要的是"存进相册/媒体库"。
     *
     * `IS_PENDING` 让半成品不会以完整身份被媒体应用扫到；写完才解除，
     * 中途失败连条目一起删掉，不留零字节记录。
     */
    private fun writeMedia(call: BackendCall): BackendResult {
        val name = call.args.optString(KEY_FILE)
        val upload = call.paths.openVettedUpload(name, MAX_UPLOAD_BYTES)
            ?: return BackendResult.Failed(RelayError.TRANSPORT_MALFORMED, "no such upload: $name")
        val bytes = upload.size
        try {
            val kind = call.args.optString(KEY_KIND, "image").lowercase()
            val collection = when (kind) {
                "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                else -> return BackendResult.Failed(
                    RelayError.TRANSPORT_MALFORMED,
                    "kind must be image/video/audio",
                ).also { upload.deleteSource() }
            }
            // MIME 按扩展名取，取不到或与声明的类别不同族时用该类别的默认值：
            // 相册按 MIME 过滤，一条 image/* 条目挂着 text/plain 就永远不会出现。
            val extension = name.substringAfterLast('.', "").lowercase()
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                ?.takeIf { it.startsWith("$kind/") } ?: UPLOAD_MIMES.getValue(kind)
            val resolver = appContext.contentResolver
            // 目标卷剩余空间不够就现在就拒，而不是写一半让 MediaProvider 抛错：
            // 半截条目要靠回滚删除才消失，回滚本身也可能失败。
            val volume = appContext.getExternalFilesDir(null)?.path
                ?.let { runCatching { StatFs(it) }.getOrNull() }
            if (volume != null && volume.availableBytes < bytes + UPLOAD_HEADROOM_BYTES) {
                return BackendResult.Failed(RelayError.STORAGE_FULL, "target volume has no room").also {
                    upload.deleteSource()
                }
            }
            // 内容必须与声明的媒体类别同族。这是**防误用**的判据，不是对抗蓄意对手的
            // 边界：沙盒把 `printf '\211PNG...'` 拼在任意数据前面就能伪装成图片，而它
            // 本来就同 UID 读得到宿主私有文件、也完全可以自己生成同样的字节再递交，
            // 所以"把宿主拿不到的东西发出去"这条越权在此并不存在 —— 魔数只挡住
            // `report.txt + kind=image` 这类会在相册留下坏条目的正常误用。
            if (!headerMatchesKind(upload, kind)) {
                return BackendResult.Failed(
                    RelayError.TRANSPORT_MALFORMED,
                    "content does not match kind=$kind",
                ).also { upload.deleteSource() }
            }
            val values = ContentValues().apply {
                // 名字已由 RelayPaths 的白名单限死长度与字符集，无需再截。
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, uploadDirs.getValue(kind))
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val target = runCatching { resolver.insert(collection, values) }.getOrNull()
                ?: return BackendResult.Failed(RelayError.INTERNAL, "media store insert refused").also {
                    upload.deleteSource()
                }
            // 只复制核验过的那个 inode 上的前 `bytes` 个字节：上限取自 `fstat`，
            // 之后再追加的字节读不到；对手若能截短我们已打开的文件，读到的 EOF 会让
            // `left > 0` 成立并整体回滚，不会留下一条声明了大小却内容不全的条目。
            val written = runCatching {
                resolver.openOutputStream(target, "w").use { output ->
                    if (output == null) false
                    else Channels.newInputStream(upload.openRead()).use { input ->
                        var left = bytes
                        val buffer = ByteArray(COPY_BUFFER_BYTES)
                        while (left > 0L) {
                            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            left -= read
                        }
                        left == 0L
                    }
                }
            }.getOrDefault(false)
            val released = if (!written) 0 else runCatching {
                resolver.update(
                    target,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null,
                )
            }.getOrDefault(0)
            if (released != 1) {
                // 解除 pending 失败等同于没写成：留着就是一条媒体应用永远看不到的死行。
                runCatching { resolver.delete(target, null, null) }
                return BackendResult.Failed(RelayError.INTERNAL, "media publish failed").also {
                    upload.deleteSource()
                }
            }
            upload.deleteSource()
            return ok(JSONObject().put("uri", target.toString()).put("bytes", bytes))
        } finally {
            upload.close()
        }
    }

    /**
     * 文件头是否与声明的媒体类别同族。用定位读，不影响之后从头开始的复制。
     * 判据是魔数而非扩展名：扩展名决定上报给媒体库的 MIME，内容才是事实。
     */
    private fun headerMatchesKind(upload: RelayPaths.VettedUpload, kind: String): Boolean {
        val header = ByteBuffer.allocate(HEADER_BYTES)
        val channel = upload.openRead()
        val filled = runCatching {
            while (header.hasRemaining()) {
                if (channel.read(header, header.position().toLong()) < 0) break
            }
            header.flip()
            true
        }.getOrDefault(false)
        if (!filled || header.remaining() < 4) return false

        val hex = StringBuilder(header.remaining() * 2)
        while (header.hasRemaining()) hex.append("%02x".format(header.get().toInt() and 0xFF))
        val value = hex.toString()
        // ISO-BMFF：第 4-7 字节是 "ftyp"，第 8-11 字节是 brand。
        val majorBrand = if (value.length >= 24 && value.substring(8, 16) == "66747970") {
            value.substring(16, 24)
        } else {
            null
        }
        // RIFF 容器：第 0-3 字节 "RIFF"，第 8-11 字节才是具体形式（WEBP/WAVE/AVI）。
        val riffForm = if (value.length >= 32 && value.startsWith("52494646")) value.substring(24, 32) else null
        return when (kind) {
            "image" -> value.startsWith("89504e47") ||          // PNG
                value.startsWith("ffd8ff") ||                   // JPEG / JFIF
                value.startsWith("47494638") ||                 // GIF8
                value.startsWith("424d") ||                     // BM（BMP）
                value.startsWith("49492a00") ||                 // TIFF 小端
                value.startsWith("4d4d002a") ||                 // TIFF 大端
                riffForm == "57454250" ||                       // RIFF…WEBP
                (majorBrand?.startsWith("6865") == true || majorBrand == "6d696631") // heic/heix/hevc、mif1
            "audio" -> majorBrand != null ||                    // M4A / MP4 系音频容器
                value.startsWith("494433") ||                   // ID3（MP3）
                riffForm == "57415645" ||                       // RIFF…WAVE
                value.startsWith("4f676753") ||                 // OggS
                (value.length >= 4 && value.startsWith("ff") &&
                    (value.substring(2, 4).toInt(16) and 0xE0) == 0xE0)         // MPEG 帧同步
            "video" -> majorBrand != null ||                    // MP4 / MOV / M4V
                riffForm == "41564920" ||                       // RIFF…AVI
                value.startsWith("1a45dfa3")                    // Matroska / WebM
            else -> false
        }
    }

    private fun readMedia(kind: String, limit: Int): BackendResult {
        val collection = when (kind.lowercase()) {
            "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            // 未知 kind 不能落到照片集合：`{"kind":"audio"}` 会拿到一份照片列表，
            // 一条看着合理其实是错的答案比一次失败更难查。
            else -> return BackendResult.Failed(RelayError.TRANSPORT_MALFORMED, "kind must be image/video")
        }
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_MODIFIED)
        val items = JSONArray()
        appContext.contentResolver.query(collection, projection, null, null, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            while (cursor.moveToNext() && items.length() < limit) {
                items.put(
                    JSONObject().apply {
                        put("uri", Uri.withAppendedPath(collection, cursor.getString(idColumn)).toString())
                        put("name", cursor.getString(nameColumn))
                    },
                )
            }
        }
        return ok(JSONObject().put("count", items.length()).put("items", items))
    }

    private fun postNotification(args: JSONObject): BackendResult {
        val manager = appContext.getSystemService<NotificationManager>()
            ?: return BackendResult.Failed(RelayError.CAPABILITY_UNAVAILABLE_ON_DEVICE, "no notification service")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            manager.getNotificationChannel(notifyChannel.id) == null
        ) {
            manager.createNotificationChannel(
                NotificationChannel(
                    notifyChannel.id,
                    text.string(notifyChannel.titleRes),
                    notifyChannel.importance,
                ),
            )
        }
        // 标题与正文来自助手侧参数，属于用户数据，不是本模块的界面文案。
        val notification = NotificationCompat.Builder(appContext, notifyChannel.id)
            .setSmallIcon(R.drawable.ic_relay_notification)
            .setContentTitle(args.optString(KEY_TITLE))
            .setContentText(args.optString(KEY_TEXT))
            .setAutoCancel(true)
            .build()
        manager.notify(args.optInt(KEY_ID, System.currentTimeMillis().toInt()), notification)
        return ok(JSONObject().put("posted", true))
    }

    /**
     * 读剪贴板。可用性只在执行期问：「此刻有没有焦点」是运行时事实，写进 `supports()`
     * 会把一次后台调用报成 `E_NOT_IMPLEMENTED`（退出码 1，反过来教助手改脚本）。
     *
     * Android 12 起，非聚焦应用取到的是一条**只有描述、没有条目**的剪贴板（明文只经
     * 回调下发，本通路没有前台界面去接回调）。所以「剪贴板为空」与「内容被遮蔽」在这里
     * 是两件事：前者是 `primaryClip == null`，后者是有剪贴板却读不出内容，必须如实失败。
     * 把被遮蔽的读成空字符串，助手会拿这个假事实继续往下做。
     */
    private fun readClipboard(): BackendResult {
        if (!clipboardUsable()) {
            return BackendResult.Failed(RelayError.CLIPBOARD_NO_FOCUS, "clipboard needs app focus")
        }
        val manager = appContext.getSystemService<ClipboardManager>()
            ?: return BackendResult.Failed(RelayError.CAPABILITY_UNAVAILABLE_ON_DEVICE, "no clipboard service")
        val clip = manager.primaryClip ?: return ok(JSONObject().put("text", "").put("length", 0))
        val text = clip.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(appContext)?.toString()
        if (text.isNullOrEmpty()) {
            return BackendResult.Failed(RelayError.CLIPBOARD_NO_FOCUS, "clip content masked without focus")
        }
        return ok(JSONObject().put("text", text).put("length", text.length))
    }

    /** 空文本的形状问题已在参数校验处拦下，这里不再把它混成「没有焦点」。 */
    private fun writeClipboard(text: String): BackendResult {
        if (!clipboardUsable()) {
            return BackendResult.Failed(RelayError.CLIPBOARD_NO_FOCUS, "clipboard needs app focus")
        }
        appContext.getSystemService<ClipboardManager>()
            ?.setPrimaryClip(ClipData.newPlainText(ClipLabel, text))
        return ok(JSONObject().put("written", text.length))
    }

    private fun ok(data: JSONObject, reason: String? = null) =
        BackendResult.Ok(data = data, reason = reason)

    internal companion object {
        /**
         * 每条能力接受的参数键。null 表示不由本后端实现，交给分发处回 `E_NOT_IMPLEMENTED`。
         *
         * 一张表加一条规则，而不是各写各的判据：多余键被静默忽略时，助手会以为它生效了，
         * 于是「参数名写错」表现为另一条能力的结果莫名其妙，排查方向整个错掉。
         * 表以 internal 暴露：分发前后的两张键表必须逐格对齐，对齐判据要能跨层引用同一份。
         */
        internal val ARG_SPECS: Map<CapabilityId, ArgSpec> = mapOf(
            CapabilityId.PKG_QUERY to ArgSpec(allowed = setOf("keyword")),
            CapabilityId.APP_LAUNCH to ArgSpec(setOf(KEY_PACKAGE), setOf(KEY_PACKAGE)),
            // 模板名必填，其余键由 IntentTemplates 按模板各自判：形状把关在两处不重复，
            // 但"这条模板要哪几个参数"只有表自己知道，写在这里就会与表各跑一套。
            CapabilityId.SYS_INTENT to ArgSpec(
                setOf(
                    IntentTemplates.KEY_TEMPLATE, IntentTemplates.KEY_HOUR, IntentTemplates.KEY_MINUTE,
                    IntentTemplates.KEY_LENGTH, IntentTemplates.KEY_MESSAGE, IntentTemplates.KEY_PAGE,
                    IntentTemplates.KEY_PACKAGE, IntentTemplates.KEY_NUMBER, IntentTemplates.KEY_URL,
                ),
                setOf(IntentTemplates.KEY_TEMPLATE),
            ),
            CapabilityId.CONTACT_READ to ArgSpec(setOf(KEY_LIMIT)),
            CapabilityId.CONTACT_WRITE to ArgSpec(setOf(KEY_NAME, KEY_NUMBER), setOf(KEY_NAME)),
            CapabilityId.CALENDAR_READ to ArgSpec(setOf(KEY_LIMIT)),
            CapabilityId.CALENDAR_WRITE to ArgSpec(setOf(KEY_TITLE, KEY_START_MS, KEY_END_MS), setOf(KEY_TITLE)),
            CapabilityId.LOCATION_READ to ArgSpec(),
            CapabilityId.MEDIA_READ to ArgSpec(setOf(KEY_KIND, KEY_LIMIT)),
            CapabilityId.MEDIA_WRITE to ArgSpec(setOf(KEY_FILE, KEY_KIND), setOf(KEY_FILE)),
            CapabilityId.NOTIFY_POST to ArgSpec(setOf(KEY_TITLE, KEY_TEXT, KEY_ID), setOf(KEY_TITLE)),
            CapabilityId.NOTIFY_READ to ArgSpec(setOf(KEY_LIMIT, KEY_PACKAGE, KEY_INCLUDE_ONGOING)),
            CapabilityId.AUDIO_CAPTURE to ArgSpec(setOf(KEY_SECONDS)),
            CapabilityId.CLIPBOARD_READ to ArgSpec(),
            CapabilityId.CLIPBOARD_WRITE to ArgSpec(setOf(KEY_TEXT), setOf(KEY_TEXT)),
            CapabilityId.SCREEN_CAPTURE to ArgSpec(),
            CapabilityId.SCREEN_OBSERVE to ArgSpec(),

            CapabilityId.SCREEN_RECORD to ArgSpec(setOf(KEY_SECONDS)),
        )

        const val KEY_PACKAGE = "package"
        const val KEY_FILE = "file"

        /** 单个入站文件上限。信箱有 256 KiB 封顶，文件走中转区，必须另设一道。 */
        private const val MAX_UPLOAD_BYTES = 32L * 1024L * 1024L

        /** 剩余空间判定留出的余量：媒体库还会为缩略图等另占块。 */
        private const val UPLOAD_HEADROOM_BYTES = 8L * 1024L * 1024L
        private const val COPY_BUFFER_BYTES = 8 * 1024

        /** 魔数比对要覆盖到 RIFF 尾部的 WEBP/WAVE/AVI 标记与 ISO-BMFF 的 brand 字段。 */
        private const val HEADER_BYTES = 32

        private val UPLOAD_MIMES = mapOf(
            "image" to "image/png",
            "video" to "video/mp4",
            "audio" to "audio/m4a",
        )
        const val KEY_LIMIT = "limit"
        const val KEY_INCLUDE_ONGOING = "includeOngoing"
        const val DEFAULT_NOTE_LIMIT = 20
        const val KEY_TEXT = "text"
        const val KEY_TITLE = "title"
        const val KEY_ID = "id"
        const val KEY_KIND = "kind"
        const val KEY_NAME = "name"
        const val KEY_NUMBER = "number"
        const val KEY_START_MS = "startMs"
        const val KEY_END_MS = "endMs"

        /** 采集时长：消费方是 AudioCapture 与 ScreenProjection，键名在此只用于形状校验。 */
        const val KEY_SECONDS = "seconds"
        const val DEFAULT_LIMIT = 200
        const val DEFAULT_EVENT_MS = 3_600_000L
        const val ClipLabel = "relay"

        /**
         * 前台核验的轮询预算：startActivity 到目标窗口建起来约需 0.1~2 秒，命中即提前返回，
         * 因此这笔等待只花在确实没落地的调用上。
         */
        const val FOREGROUND_POLL_TRIES = 10
        const val FOREGROUND_POLL_MS = 200L

        /** 连续读不到任何窗口就不再等：无障碍没绑的机器上，等满预算换不来一条信息。 */
        const val FOREGROUND_POLL_BLANK_LIMIT = 3

        /**
         * 应用分身的宿主容器。分身应用跑在这个进程的克隆实例里，窗口归属只读得到容器
         * 包名，因此「看到容器」既不能当成目标没起来（会误拒一次真实成功的分身启动），
         * 也不能当成目标已起来（容器不等于里面那个包）。
         * 名单只收已确认的容器包名：加入未经核实的候选，等于允许任意前台以「核验不了」
         * 为名放过一次错误结论。
         */
        private val CLONE_CONTAINERS = setOf("com.vivo.doubleinstance")
    }
}

/**
 * `pkg.query` 的可选过滤：包名或显示名里含 [keyword] 才算命中，大小写不敏感；
 * [keyword] 为空白时不过滤。写成顶层纯函数，是为了让这条判据能在 JVM 单测里直接断言。
 */
internal fun appMatches(keyword: String, pkg: String, label: String): Boolean {
    if (keyword.isBlank()) return true
    val needle = keyword.trim().lowercase()
    return pkg.lowercase().contains(needle) || label.lowercase().contains(needle)
}
