package interlock.relay.core.exec.shizuku

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.view.Surface
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Shizuku 用户服务侧。由 Shizuku 服务端在 **shell 身份（uid 2000）** 的独立进程里拉起，
 * 因此这里执行的命令拿到的是 shell 权限集，而不是本应用的权限集。
 *
 * 实例化契约在服务端：它按类名反射取 `(Context)` 构造器，取不到才退无参构造器创建实例，
 * 再把**实例本身**当作 `IBinder` 交给宿主，全程不经过 `Service#onBind`。
 * 所以这个类必须直接继承 [Binder]：写成 `android.app.Service` 时服务端强转即抛
 * `ClassCastException: … cannot be cast to android.os.IBinder`，用户服务进程当场退出，
 * 绑定永远停在「连不上」。类名与构造器由消费者混淆规则保留，不在能力清单里声明。
 *
 * 那个 `(Context)` 构造器是必需的：建可信屏要 `DisplayManager`，而本进程既没有 Activity
 * 也没有本应用的 Application，只用 `ActivityThread.systemMain()` 取不到系统服务
 * （回 `no display service`）。服务端递来的 Context 是这条通路的第一选择。
 *
 * binder 只会被 Shizuku 交给发起绑定的那个应用进程，不对其他进程可发现，
 * 所以协议里不再放自造的「鉴权字段」——那只会给人已经有鉴权的错觉。
 * 真正的授权判定全部在宿主裁决层完成，本类只负责执行已经裁决过的命令。
 */
class RelayShellService @JvmOverloads constructor(
    private val injectedContext: android.content.Context? = null,
) : Binder() {

    /**
     * 事务入口。这里的每一行都不许把异常放出去：Android 10 起 binder 线程上的未捕获
     * 异常直接走默认处理器打死本进程，而这一个进程同时服务命令执行、安装与那块可信屏——
     * 一次读坏参数就能把三条通路一起带走，且宿主侧只会看到「后端不可用」。
     * 放不出去就回 false：调用方按「服务没答」处理，是一条可重试的错误。
     */
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean =
        runCatching { dispatch(code, data, reply, flags) }.getOrDefault(false)

    private fun dispatch(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean = when (code) {
        ShellProtocol.CODE_RUN -> {
            val command = data.readString()
            val timeoutMs = data.readLong()
            if (command.isNullOrBlank()) {
                false
            } else {
                val outcome = ShellRunner.run(command, timeoutMs)
                reply?.writeInt(outcome.exitCode)
                reply?.writeString(outcome.stdout)
                reply?.writeString(outcome.stderr)
                true
            }
        }

        ShellProtocol.CODE_INSTALL -> {
            val outcome = ShellRunner.install(data.readFileDescriptor(), data.readString(), data.readLong())
            reply?.writeInt(outcome.exitCode)
            reply?.writeString(outcome.stdout)
            reply?.writeString(outcome.stderr)
            true
        }

        ShellProtocol.CODE_DISPLAY_CREATE -> {
            val created = TrustedDisplay.create(injectedContext, data.readInt(), data.readInt(), data.readInt())
            reply?.writeInt(created.first)
            reply?.writeString(created.second)
            true
        }

        ShellProtocol.CODE_DISPLAY_RELEASE -> {
            val released = TrustedDisplay.release()
            reply?.writeInt(released.first)
            reply?.writeString(released.second)
            true
        }

        ShellProtocol.CODE_DISPLAY_CAPTURE -> {
            // 截图与录屏共用这一条事务。后两个参数是「给 JPEG 时的质量」与「先缩到多宽」，
            // 都传 0 即无损单帧、不缩放；录屏一帧一次往返，不缩不压就给不到可用的帧率。
            val captured = TrustedDisplay.capture(data.readLong(), data.readInt(), data.readInt())
            // 帧走 fd 不走路径：截图落在 shell 的临时目录里哪怕只有一瞬全局可读，
            // 就是一份「谁都能来拿的别人屏幕」。发完描述符立即删路径，
            // 已打开的 fd 仍可读，数据活到宿主关它为止。
            reply?.writeInt(if (captured.second.isEmpty()) 0 else -1)
            reply?.writeString(captured.second)
            captured.first?.let { descriptor ->
                // writeFileDescriptor 走的是 dup：本进程这一份写完就该关。录屏一帧一次
                // 往返，攒到 finalizer 就是几十上百个待回收的 fd。写在 try 里、关在
                // finally 里 —— 这一步抛（`nativeWriteFileDescriptor` 在 NO_MEMORY 上抛）
                // 时若不在 finally 关，漏的正是这条改动本来要防的那个 fd。
                try {
                    reply?.writeFileDescriptor(descriptor.fileDescriptor)
                } finally {
                    runCatching { descriptor.close() }
                }
            }
            true
        }

        ShellProtocol.CODE_SCREENSHOT -> {
            // 用户眼前这块真屏的一帧，由 shell 身份的 `screencap` 取。落 shell 临时目录、
            // 开只读 fd、**立刻删路径**：留在那个目录里的就是一张别人屏幕的存档，而它不归
            // 本应用任何清理入口管。数据由已打开的 fd 续命，宿主关掉的为止。
            val target = File(SHELL_TMP, "relay-shot-${System.nanoTime()}.png")
            val failure = runCatching {
                val process = ProcessBuilder("/system/bin/screencap", "-p", target.absolutePath)
                    .redirectErrorStream(true).start()
                if (!process.waitFor(5_000L, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                    "screencap timed out"
                } else if (process.exitValue() != 0 || !target.isFile || target.length() <= 0L) {
                    "screencap exit=${process.exitValue()}"
                } else {
                    ""
                }
            }.getOrElse { "screencap failed: ${it.javaClass.simpleName}" }
            var descriptor: ParcelFileDescriptor? = null
            var reason = if (failure.isNotEmpty()) failure else runCatching {
                descriptor = ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_ONLY)
                ""
            }.getOrElse { "screencap open failed: ${it.javaClass.simpleName}" }
            // 帧的权限收到只有属主能读：`/data/local/tmp` 是全机 shell 身份共用的目录，
            // 按默认 umask 落盘就是组内可读，而从进程跑到删路径之间那几秒里放的是一张别人的屏幕。
            if (reason.isEmpty()) runCatching {
                java.nio.file.Files.setPosixFilePermissions(
                    target.toPath(),
                    setOf(java.nio.file.attribute.PosixFilePermission.OWNER_READ),
                )
            }
            // 删不掉就是留了一张全局可寻的截图而不说一句：这条必须让调用方看见，
            // 不能只在这里默默丢一个返回值。
            if (!target.delete() && reason.isEmpty()) {
                reason = "frame was captured but ${target.name} could not be removed from the shell temp dir"
            }
            // 任何一步坏都只回一个负状态 + 原因，不把异常抛出 onTransact：那会带走整个用户服务，
            // 连带三条 shell 能力与那块屏一起没命（见本类里 startPump 的同一说明）。
            reply?.writeInt(if (reason.isEmpty()) 0 else -1)
            reply?.writeString(reason)
            descriptor?.let { fd ->
                try {
                    reply?.writeFileDescriptor(fd.fileDescriptor)
                } finally {
                    runCatching { fd.close() }
                }
            }
            true
        }

        ShellProtocol.CODE_DISPLAY_QUERY -> {
            reply?.writeInt(TrustedDisplay.displayId)
            reply?.writeString(TrustedDisplay.describe())
            true
        }

        /**
         * Shizuku 说「该退了」：绑我的那个应用进程没了，或它显式要求把我移除。
         *
         * Shizuku 的约定是：服务侧不实现这个方法，它就杀不掉这个进程。缺这一条时宿主每
         * 重启一次就多留下一个用户服务进程。接上之后进程随会话结束自行退出。
         *
         * 退之前把那块可信屏收掉：屏归本进程持有，进程走了它还会挂在 SurfaceFlinger 上。
         * 退出要等这条事务先有回执（见 [scheduleExit]）。
         */
        ShellProtocol.CODE_DESTROY -> {
            runCatching { TrustedDisplay.release() }
            scheduleExit()
            true
        }

        else -> super.onTransact(code, data, reply, flags)
    }

    /**
     * 让这条 destroy 事务先结掉再退进程。
     *
     * 调用侧发这条事务只想知道服务端收摊了；在 `onTransact` 里直接 `System.exit` 会让它拿到
     * `DEAD_OBJECT`，一次正常的关闭因此报成失败。
     */
    private fun scheduleExit() {
        Thread {
            runCatching { Thread.sleep(EXIT_GRACE_MS) }
            System.exit(0)
        }.apply { isDaemon = true; name = "relay-service-exit"; start() }
    }

    internal object ShellRunner {
        /**
         * 安装：把随 binder 递来的 fd 落进 shell 自己的临时目录，再交给 pm。
         *
         * 不用「宿主写一份全局可读文件、shell 按路径去读」：这类路径在部分厂商 ROM 上走不通
         * （shell 不能穿越 `/data/data/<pkg>`，Android 11 起也建不出应用专属外部目录）。
         * fd 由 binder 驱动直接 dup 进本进程，与两侧各自的路径策略无关，也不需要任何一份
         * 副本变成全局可读 —— 那等于让任何进程都能替换待安装的 apk。
         *
         * 落点名只认白名单形状，且由宿主生成：这里没有任何一个字节的路径来自沙盒侧参数。
         */
        fun install(descriptor: ParcelFileDescriptor?, name: String?, timeoutMs: Long): Outcome {
            val staged = name?.takeIf { STAGED_NAME.matches(it) }
                ?: return Outcome(EXIT_REJECTED, "", "bad staged name")
            val source = descriptor ?: return Outcome(EXIT_REJECTED, "", "no file descriptor")
            val target = File(SHELL_TMP, staged)
            return try {
                val copied = runCatching { source.use { writeCopy(it, target) } }.getOrDefault(false)
                if (!copied) {
                    runCatching { target.delete() }
                    Outcome(EXIT_REJECTED, "", "staged copy failed or exceeded size cap")
                } else {
                    run("pm install -r $SHELL_TMP/$staged", timeoutMs)
                }
            } finally {
                runCatching { target.delete() }
            }
        }

        /** 逐块复制并当场卡上限：超了立即停，不把过大的文件留在 shell 的临时目录里。 */
        private fun writeCopy(descriptor: ParcelFileDescriptor, target: File): Boolean =
            FileInputStream(descriptor.fileDescriptor).use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_STAGED_BYTES) return false
                        output.write(buffer, 0, read)
                    }
                    total > 0L
                }
            }

        fun run(command: String, timeoutMs: Long): Outcome {
            // 落在那块屏上的点击、滑动与启动都是这里的命令：空闲计时必须以真实事务为准，
            // 只看显式的 display 调用会把一个正在被操作的屏判成没人用。
            TrustedDisplay.touch()
            val process = runCatching {
                ProcessBuilder("/system/bin/sh", "-c", command)
                    .redirectErrorStream(false)
                    .start()
            }.getOrElse { return Outcome(-1, "", "spawn failed: ${it.javaClass.simpleName}") }

            val lock = Any()
            val stdout = StringBuilder()
            val stderr = StringBuilder()
            // 两个流各起一个读线程：先读干 stdout 再去读 stderr 时，
            // 子进程写满 stderr 管道缓冲便会卡住直到超时，看起来像命令跑死。
            val readers = listOf(
                startReader("relay-shell-out") { readBounded(process.inputStream, stdout, lock) },
                startReader("relay-shell-err") { readBounded(process.errorStream, stderr, lock) },
            )

            val finished = runCatching { process.waitFor(timeoutMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
            if (!finished) process.destroyForcibly()
            // join 也会被中断：这一条已经跑成功的命令不该因为叫不醒读线程就整个丢回去，
            // 更不该把异常送到事务入口之外。
            readers.forEach { runCatching { it.join(TIMEOUT_JOIN_MS) } }
            // 命令留下孙进程握着管道写端时（`nohup`、结尾的 `&`），destroyForcibly 只杀得到
            // 直接子进程，读线程会一直等在 read 上。关掉流让 read 抛回 readBounded 自己的
            // runCatching，线程当场结束；否则每条超时命令都留下两个线程加两个 fd，
            // 直到这个用户服务进程退出为止。
            if (readers.any { it.isAlive }) runCatching {
                process.inputStream.close()
                process.errorStream.close()
            }

            val snapshot = synchronized(lock) { stdout.toString() to stderr.toString() }
            return Outcome(
                exitCode = if (finished) process.exitValue() else EXIT_TIMED_OUT,
                stdout = snapshot.first,
                stderr = snapshot.second,
            )
        }

        private fun startReader(name: String, body: () -> Unit): Thread =
            Thread(body, name).apply { isDaemon = true; start() }

        /**
         * [sink] 由读线程写、由 binder 线程快照，join 超时后仍可能在读，故一律持锁。
         * 超限之后仍继续读但丢弃：停下来不读会让子进程堵在满管道上，
         * 一条本来成功的命令会被判成超时。
         */
        private fun readBounded(stream: java.io.InputStream, sink: StringBuilder, lock: Any) {
            runCatching {
                stream.bufferedReader().use { reader ->
                    val buffer = CharArray(8 * 1024)
                    var storing = true
                    while (true) {
                        val read = reader.read(buffer)
                        if (read < 0) break
                        if (!storing) continue
                        storing = synchronized(lock) {
                            val room = MAX_OUTPUT_CHARS - sink.length
                            when {
                                room >= read -> {
                                    sink.append(buffer, 0, read)
                                    true
                                }

                                room > 0 -> {
                                    sink.append(buffer, 0, room)
                                    sink.append(TRUNCATED_MARK)
                                    false
                                }

                                else -> {
                                    sink.append(TRUNCATED_MARK)
                                    false
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    data class Outcome(val exitCode: Int, val stdout: String, val stderr: String) {
        val success: Boolean get() = exitCode == 0
    }

    /**
     * 可信虚拟屏：后台操控的执行面。
     *
     * 三条从设备上读出来的事实决定这段代码的形状，凭记忆写任何一条都会静默失效：
     * 1. `VIRTUAL_DISPLAY_FLAG_TRUSTED` 是隐藏常量，本机取值 1024。少了它屏建得起来，
     *    但第三方应用进不去（`am start --display` 会把任务放回默认屏）——
     *    表现就是「有后台屏」而实际仍在抢真屏。
     * 2. 建屏走 `DisplayManager` 上那个 8 参重载。本代框架里 `DisplayManagerGlobal`
     *    只剩一个要 `VirtualDisplayConfig` 的形态，而那个类在本机只有一个 12 参构造器，
     *    参数语义从 dex 里读不出来，不能猜。
     * 3. Context 必须申报成 shell 自己的包。用 system context 直接建会被
     *    DisplayManagerService 以 `packageName must match the calling uid` 挡下。
     *
     * 画面不走 `screencap -d`：本机对虚拟屏固定回 `Status: -2`（默认屏正常），
     * 而这屏的表面本来就是本进程持有的 ImageReader，帧一直就在手里。
     * 也因此必须有一个持续取帧的线程：`acquireLatestImage` 会丢掉更老的帧，
     * 没人消费时两块缓冲填满，生产端堵死，那块屏上的界面就不再刷新——
     * 表现是「应用确实启动了，画面永远停在第一帧」。
     */
    internal object TrustedDisplay {

        @Volatile
        var displayId: Int = -1
            private set

        @Volatile
        private var display: VirtualDisplay? = null

        /** 建屏那天从这块屏上读到的 `uniqueId`；[displayPresent] 用它认屏号有没有被回收重发。 */
        @Volatile
        private var displayUniqueId: String? = null

        /**
         * `create` 的一个特殊负值：现有那块屏**问不成**（不是不在了）。宿主对它的处置与
         * "系统拒绝建屏"不同 —— 屏可能还在跑，既不该清掉缓存的编号，也不该让用户去动手机。
         */
        internal const val CODE_UNVERIFIED = -2

        @Volatile
        private var images: ImageReader? = null

        @Volatile
        private var pump: Thread? = null

        @Volatile
        private var watchdog: Thread? = null

        @Volatile
        private var looperThread: HandlerThread? = null
        private var cachedContext: android.content.Context? = null
        private val held = java.util.concurrent.atomic.AtomicReference<android.media.Image?>()

        /** 无人问津到点就收屏，见 [startWatchdog]；每次收屏递增，用来认不出自己就退场。 */
        @Volatile
        private var generation = 0

        /** 最近一次真正办事的时刻：空闲到点就自动收屏，见 [startWatchdog]。 */
        @Volatile
        private var lastUsedMs = 0L

        fun touch() {
            lastUsedMs = android.os.SystemClock.elapsedRealtime()
        }

        fun size(): Pair<Int, Int> = images?.let { it.width to it.height } ?: (0 to 0)

        fun describe(): String = if (displayId < 0) "" else {
            val (w, h) = size()
            "id=$displayId ${w}x${h}"
        }

        /** 按屏号回问 DisplayManager 的三种可能结果，见 [displayPresent]。 */
        internal enum class Presence { PRESENT, ABSENT, UNKNOWN }

        /**
         * 那块屏还在不在，三态分开。**「问不成」不等于「不在」**：拿不到 DisplayManager、
         * 或 `getDisplay` 抛异常时按"不在"处理，就会收掉一块正在使用的屏连同它的取帧管线，
         * 而调用方看到的是一次成功的建屏。
         *
         * [expected] 是建屏时记下的 `uniqueId`：显示编号会被回收重发，只比编号时可能把另一块
         * 同号的屏认成自己那块，于是每次建屏都答 already exists，而本进程这块早已没人取帧。
         * 任一侧读不到 uid 时退回只比编号，不因此否决现存的屏。
         */
        private fun displayPresent(injected: android.content.Context?, id: Int, expected: String?): Presence {
            if (id < 0) return Presence.ABSENT
            val manager = runCatching {
                hostContext(injected)?.getSystemService(DisplayManager::class.java)
            }.getOrNull() ?: return Presence.UNKNOWN
            val found = runCatching { manager.getDisplay(id) }
            if (found.isFailure) return Presence.UNKNOWN
            val display = found.getOrNull() ?: return Presence.ABSENT
            val actual = uniqueIdOf(display)
            if (expected != null && actual != null && actual != expected) return Presence.ABSENT
            return Presence.PRESENT
        }

        /**
         * 那块屏的稳定标识。`Display#getUniqueId` 不在公开 API 里（`toString()` 才是），
         * 只能按名反射取；取不到就回 null，[displayPresent] 随即退回只比编号。
         */
        private fun uniqueIdOf(display: android.view.Display?): String? = runCatching {
            display?.javaClass?.getMethod("getUniqueId")?.invoke(display) as? String
        }.getOrNull()

        /** 建一块屏。返回 `<0` 与一句原因，调用方据此如实回错，不留半开的屏。 */
        fun create(injected: android.content.Context?, width: Int, height: Int, dpi: Int): Pair<Int, String> = synchronized(this) {
            if (display != null) {
                // 「已经有一块屏」不能只看本进程的字段：那块屏可能被系统收掉了（宿主被杀、
                // 显示器被回收、token 作废），而这里不核对就会永远答 "display already exists"，
                // 之后每一次建屏都撞在同一句上 —— 那块屏在本进程活着期间再也建不起来。
                // 公共 API 没有"屏被销毁"的回调（`VirtualDisplay.Callback` 只有
                // onPaused/onResumed/onStopped），只能自己按屏号回问 DisplayManager。
                when (displayPresent(injected, displayId, displayUniqueId)) {
                    Presence.PRESENT -> return@synchronized displayId to "display already exists"
                    // 问不成就不动现有那块：收掉它是一次不可逆的破坏，而支撑"它没了"的证据并没有拿到。
                    // 回错并点名 release 这条路，调用方仍能把屏收掉再建（不落入"永远建不起来"）。
                    Presence.UNKNOWN -> return@synchronized CODE_UNVERIFIED to
                        "cannot verify existing display $displayId, left it running: release it with action=release, then create again"
                    Presence.ABSENT -> {
                        // 死状态先清干净（含收掉那条没人再取帧的读线程），再往下建新的。
                        // 清不掉就不建第二块：留着两块屏与两个 ImageReader 时，本进程说不出
                        // 哪一块是现在这块，比"这次建屏失败"更难查。
                        val cleared = release()
                        if (cleared.first < 0) {
                            return@synchronized -1 to "stale display $displayId could not be cleared: ${cleared.second}"
                        }
                    }
                }
            }
            val w = width.coerceIn(MIN_SIDE, MAX_SIDE)
            val h = height.coerceIn(MIN_SIDE, MAX_SIDE)
            val d = dpi.coerceIn(MIN_DPI, MAX_DPI)
            exemptHiddenMembers()
            val thread = HandlerThread(DISPLAY_THREAD).also { it.start() }
            looperThread = thread
            val attempt = runCatching {
                val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, MAX_IMAGES)
                val manager = hostContext(injected)?.getSystemService(DisplayManager::class.java)
                    ?: error("no display service")
                val enter = DisplayManager::class.java.getDeclaredMethod(
                    "createVirtualDisplay",
                    String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType, Surface::class.java, Int::class.javaPrimitiveType,
                    VirtualDisplay.Callback::class.java, Handler::class.java,
                ).apply { isAccessible = true }
                // 这里的 callback 传 null 不是漏写：公共 API 的 `VirtualDisplay.Callback` 只有
                // onPaused/onResumed/onStopped（javap 对 compileSdk 的 stub 实测），
                // **没有任何"屏被销毁"的回调可挂**，屏真被系统收掉时只能靠上面那次
                // DisplayManager 回问发现。
                @Suppress("UNCHECKED_CAST")
                val created = enter.invoke(
                    manager, DISPLAY_NAME, w, h, d, reader.surface, FLAGS, null, Handler(thread.looper),
                ) as VirtualDisplay
                val id = created.display?.displayId ?: error("created display has no id")
                images = reader
                display = created
                displayId = id
                displayUniqueId = uniqueIdOf(created.display)
                // 上一块屏可能留了一帧没人收（收屏时那条读线程没在 1 秒内让开）：不清就会
                // 成为新屏的第一张截图，看着像「屏卡住了」。
                runCatching { held.getAndSet(null)?.close() }
                touch()
                startPump(reader)
                startWatchdog()
                id to ""
            }
            attempt.getOrElse {
                // 半开的屏必须一起收掉：只关 ImageReader 会留下 display != null，
                // 之后每次建屏都答"already exists"，而那块屏没人取帧，永远是一幅静止画面。
                runCatching { display?.release() }
                display = null
                displayId = -1
                displayUniqueId = null
                runCatching { held.getAndSet(null)?.close() }
                thread.quitSafely()
                looperThread = null
                runCatching { images?.close() }
                images = null
                -1 to "${it.javaClass.simpleName}: ${it.message ?: "no message"}"
            }
        }

        /**
         * 收屏。回 (1=收掉了一块, "")、(0=本来就没有，重复收不算错) 或 (<0, 原因)。
         * 「已经没有屏」必须与「收屏失败」分开：空闲到点会自动收一次，助手随后按自己的
         * release 拿到 -1 就会被读成后端不可用，而它其实已经做完了想做的事。
         */
        fun release(): Pair<Int, String> = synchronized(this) {
            val current = display ?: return@synchronized 0 to "no display"
            val reader = images
            // 状态先归零，再收资源，且每一步都不许抛：留着「displayId 说还有屏、屏已经没了」
            // 这个中间态，空闲计时器就会按那个编号一次次重试收屏而永远收不掉——它在
            // `displayId < 0` 时才肯退场，那块屏在本进程活着期间也就再也建不起来了。
            display = null
            displayId = -1
            displayUniqueId = null
            images = null
            // 收屏即作废此刻所有空闲计时器：见 startWatchdog 里那句"认不出自己就退场"。
            generation++
            stopThread(pump)
            pump = null
            stopThread(watchdog)
            watchdog = null
            val failure = runCatching { current.release() }.exceptionOrNull()
            runCatching { reader?.close() }
            runCatching { held.getAndSet(null)?.close() }
            runCatching { looperThread?.quitSafely() }
            looperThread = null
            if (failure == null) {
                1 to ""
            } else {
                -1 to "release failed: ${failure.javaClass.simpleName}"
            }
        }

        /**
         * 收线程只到「叫醒它、给它一点时间自己退」为止，绝不强杀：
         * 这两条线程都可能在持有一帧图像，硬停会把 ImageReader 留在半关闭状态。
         * 空闲计时器自己来收屏时不能等自己——那是一次持锁的自join，只能放行让它自己退出。
         */
        private fun stopThread(thread: Thread?) {
            thread ?: return
            if (thread === Thread.currentThread()) return
            runCatching { thread.interrupt() }
            runCatching { thread.join(THREAD_JOIN_MS) }
        }

        /**
         * 睡一觉，返回「还要不要继续跑」。中断必须在这里咽下：
         * 这个进程同时服务其余 shell 能力，一条逃出线程的异常会连带整条通道失效：
         * 未捕获的 InterruptedException 会终止用户服务，之后的每一次 shell 调用都只剩
         * 「后端不可用」。
         */
        private fun sleepQuietly(ms: Long): Boolean = try {
            Thread.sleep(ms)
            true
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

        /**
         * 无人问津到点就收屏：宿主被杀、被划掉时没有人会来按 release。
         *
         * 线程醒来后先认一次自己（[generation]）再动手：`release()` 里的 `stopThread` 只等到
         * [THREAD_JOIN_MS]，超时会把 `watchdog` 置空而这条线程仍然活着；随后建起来的新屏会把
         * 字段换成另一条线程。不认自己就收屏，那块刚建好、正在用的屏会被按旧的空闲时刻收掉，
         * 而回包说的是"建屏成功"。
         */
        private fun startWatchdog() {
            if (watchdog?.isAlive == true) return
            val own = generation
            watchdog = Thread {
                var alive = true
                while (alive && displayId >= 0) {
                    alive = sleepQuietly(IDLE_CHECK_MS)
                    if (!alive || displayId < 0) continue
                    if (own != generation) break
                    val idleMs = android.os.SystemClock.elapsedRealtime() - lastUsedMs
                    if (idleMs > IDLE_RELEASE_MS) runCatching { releaseIfCurrent(own) }
                }
            }.apply { isDaemon = true; name = "relay-display-idle"; start() }
        }

        /**
         * 空闲计时器专用的"认了才收"：**校验与收屏必须在同一把锁里做**。
         *
         * 线程在锁外看过 `generation` 之后，仍可能阻塞在 [release] 的进入队列上（建屏那条
         * 一直持着锁）。这期间 `release()` 已经把自己从 `watchdog` 上摘掉、`create()` 又建好
         * 一块新屏，它随后拿到锁就会收掉那块刚建成、正在用的屏，而回包说的是"建屏成功"。
         */
        private fun releaseIfCurrent(own: Int): Pair<Int, String> = synchronized(this) {
            if (own != generation) return@synchronized 0 to "stale idle timer"
            release()
        }

        /**
         * 把此刻停在屏上的那一帧编码后以 fd 交回；没有帧就给原因。
         *
         * `jpegQuality` 传 0 给 PNG（无损，截图要看得清字）。录屏不是：一次要走几十上百帧，
         * 1080p 的 PNG 单帧压缩就要一两秒，帧率会掉到 1 以下，所以那一档给 JPEG，
         * 并且先按 [maxWidth] 缩一道 —— 服务端省一次压缩、宿主侧省一次解码，两头都赚，
         * 而"看清助手在干什么"用得上的是内容不是像素数。
         */
        fun capture(timeoutMs: Long, jpegQuality: Int, maxWidth: Int): Pair<ParcelFileDescriptor?, String> {
            val reader = images ?: return null to "no display"
            touch()
            val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs.coerceIn(1L, MAX_CAPTURE_WAIT_MS)
            var frame = held.getAndSet(null)
            while (frame == null && android.os.SystemClock.elapsedRealtime() < deadline) {
                if (!sleepQuietly(FRAME_POLL_MS)) return null to "interrupted"
                // 等帧期间屏可能被收掉：读已关闭的 ImageReader 抛的是 IllegalStateException，
                // 逃出去就是整个用户服务没命（同 startPump 的说明）。
                frame = runCatching { reader.acquireLatestImage() }.getOrNull() ?: held.getAndSet(null)
            }
            if (frame == null) return null to "no frame before deadline"
            val target = File(SHELL_TMP, "relay-cap-${System.nanoTime()}." +
                (if (jpegQuality > 0) "jpg" else "png"))
            var descriptor: ParcelFileDescriptor? = null
            val failure = try {
                // 编码直接落进文件流：先在堆里按「未压缩像素数」开一份缓冲、再整体复制
                // 一次，等于让一次取帧要两倍于整屏帧的堆。这个进程一倒，三条 shell 能力
                // 与那块屏一起没命，所以按帧走的录屏尤其不能这么占。
                val compressed = FileOutputStream(target).use {
                    encodeInto(frame, jpegQuality, maxWidth, it)
                }
                if (!compressed) "frame encode failed: compress" else runCatching {
                    descriptor = ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_ONLY)
                    ""
                }.getOrElse { "frame encode failed: ${it.javaClass.simpleName}" }
            } catch (t: Throwable) {
                "frame encode failed: ${t.javaClass.simpleName}"
            } finally {
                runCatching { frame.close() }
            }
            val written = descriptor
            // 失败路径同样要删那个文件：留在 shell 临时目录里的就是一张别人屏幕的存档，
            // 而那个目录不归本应用的任何清理入口管，写坏了还会把磁盘越占越满。
            target.delete()
            if (failure.isNotEmpty()) {
                runCatching { written?.close() }
                return null to failure
            }
            // 立刻删路径：数据由已打开的 fd 续命，别人拿不到第二份。
            return written to ""
        }

        /** 把这一帧按 [jpegQuality]/[maxWidth] 编码写进 [out]；返回 `false` 表示编码器没出图。 */
        private fun encodeInto(
            frame: android.media.Image,
            jpegQuality: Int,
            maxWidth: Int,
            out: java.io.OutputStream,
        ): Boolean {
            val plane = frame.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowPadding = plane.rowStride - pixelStride * frame.width
            val bitmap = Bitmap.createBitmap(
                frame.width + rowPadding / pixelStride,
                frame.height,
                Bitmap.Config.ARGB_8888,
            ).also { it.copyPixelsFromBuffer(buffer) }
            var cropped = if (rowPadding == 0) bitmap else Bitmap.createBitmap(bitmap, 0, 0, frame.width, frame.height)
            // 两次 recycle 必须落在 finally 里：`createScaledBitmap` 与 `compress` 都会抛
            // （前者在 `maxWidth * height / width == 0` 这类退化比例上抛），而调用侧只把异常
            // 收成一行错误 —— 不在这里回收，每抛一次就漏一张整屏 ARGB_8888。
            return try {
                if (maxWidth in 1 until cropped.width) {
                    val scaled = Bitmap.createScaledBitmap(cropped, maxWidth, maxWidth * cropped.height / cropped.width, true)
                    if (scaled !== cropped && cropped !== bitmap) cropped.recycle()
                    cropped = scaled
                }
                if (jpegQuality > 0) {
                    cropped.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)
                } else {
                    cropped.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
                }
            } finally {
                if (cropped !== bitmap) runCatching { cropped.recycle() }
                runCatching { bitmap.recycle() }
            }
        }

        /** 持续把最新一帧换进 [held]：更老的帧由 acquireLatestImage 自己丢掉。 */
        private fun startPump(reader: ImageReader) {
            pump = Thread {
                var running = true
                while (running && !Thread.currentThread().isInterrupted && reader == images) {
                    val frame = runCatching { reader.acquireLatestImage() }.getOrNull()
                    if (frame != null) {
                        // 取这一帧期间可能已经换过屏（release 把 images 置空、create 换新的），
                        // 那时这一帧属于上一块屏：放进 held 就成了新屏的第一张截图。
                        if (reader == images) held.getAndSet(frame)?.close() else runCatching { frame.close() }
                    }
                    running = sleepQuietly(FRAME_POLL_MS)
                }
            }.apply { isDaemon = true; name = "relay-frame-pump"; start() }
        }

        /**
         * 建屏用的 Context。顺序是硬的：
         * 1. 服务端递来的那个——它才是这个进程真正注册过的上下文；
         * 2. `ActivityThread.systemMain()` 的 system context；
         * 3. 两者都要再换成 shell 自己的包：DisplayManagerService 会核对
         *    「申报的包名必须属于调用 uid」，用 `android` 包直接建会被挡下。
         */
        private fun hostContext(injected: android.content.Context?): android.content.Context? {
            cachedContext?.let { return it }
            val base = injected ?: systemContext()
            cachedContext = runCatching {
                base?.createPackageContext(SHELL_PACKAGE, android.content.Context.CONTEXT_IGNORE_SECURITY)
            }.getOrNull() ?: base
            return cachedContext
        }

        private fun systemContext(): android.content.Context? = runCatching {
            val activityThread = Class.forName("android.app.ActivityThread")
            val main = activityThread.getDeclaredMethod("systemMain").apply { isAccessible = true }.invoke(null)
            activityThread.getDeclaredMethod("getSystemContext").apply { isAccessible = true }
                .invoke(main) as android.content.Context
        }.getOrNull()

        /** 用户服务进程由 Shizuku 起，隐藏成员本已放开；这里再放一次，不依赖那一条前提。 */
        private fun exemptHiddenMembers() {
            runCatching {
                val vmRuntime = Class.forName("dalvik.system.VMRuntime")
                val runtime = vmRuntime.getDeclaredMethod("getRuntime").apply { isAccessible = true }.invoke(null)
                vmRuntime.getDeclaredMethod("setHiddenApiExemptions", Array<String>::class.java)
                    .apply { isAccessible = true }
                    .invoke(runtime, arrayOf("L"))
            }
        }

        private const val DISPLAY_NAME = "relay-bg"
        private const val DISPLAY_THREAD = "relay-display"
        private const val SHELL_PACKAGE = "com.android.shell"

        // 取值来自本机 framework 的常量表（dexdump 抄出，`dumpsys package` 不公开这些位）；
        // 这一组是能承载第三方应用、且让无障碍后端读到该屏窗口的组合。
        private const val FLAG_PUBLIC = 1
        private const val FLAG_OWN_CONTENT_ONLY = 8
        private const val FLAG_DESTROY_CONTENT_ON_REMOVAL = 256
        private const val FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS = 512
        private const val FLAG_TRUSTED = 1024
        private const val FLAG_OWN_DISPLAY_GROUP = 2048
        private const val FLAG_ALWAYS_UNLOCKED = 4096
        private val FLAGS = FLAG_TRUSTED or FLAG_OWN_DISPLAY_GROUP or FLAG_ALWAYS_UNLOCKED or
            FLAG_OWN_CONTENT_ONLY or FLAG_DESTROY_CONTENT_ON_REMOVAL or
            FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS or FLAG_PUBLIC
        // FLAG_PUBLIC 决定这块屏能不能被无障碍后端读到，两侧的后果都要写清。
        //
        // 没有这一位时这块屏是 FLAG_PRIVATE：屏上的窗口在系统侧确实存在（`dumpsys window
        // windows` 查得到目标应用与 SecondaryDisplayLauncher），但 `getWindowsOnAllDisplays()`
        // 的按屏分组里没有它的条目，挂在可信屏上的十一条节点级能力全部读不到树。加上之后读侧可用。
        // 私有屏为何不被枚举属于平台服务端行为，这里只记录观测到的对应关系，不写成平台定义。
        //
        // 代价是三样，第一样是内容级的：
        // ① 这条按屏枚举对机器上**每一个**已启用的无障碍服务同样开放：开了读屏、输入法一类
        //    服务的机器上，这块屏上第三方应用的节点树对它们可读，包括助手替用户输入的内容。
        // ② 其他 uid 能自己往这块屏上开窗，而输入按焦点窗口派发：`input -d` 与节点点击可能
        //    打在别人的窗上。读侧只拒绝指向本包的窗口，对这块屏上非本应用的窗没有白名单。
        // ③ 这块屏进入全局可枚举面（名字、尺寸、密度都在 `getDisplays()` 里），且带着
        //    ALWAYS_UNLOCKED 与系统装饰，锁屏判定对它不成立。
        // 把任意第三方应用放上来跑本就是这条通路的目的（没有 TRUSTED 反而做不到），所以
        // ①②越过的都不是对用户承诺过的隔离：界面与助手说明里没有一处说过这块屏私有。
        // 收窄的前提是让无障碍后端在非公开屏上也能枚举，目前没有那样的公开通路。

        private const val MAX_IMAGES = 2
        private const val MIN_SIDE = 320
        private const val MAX_SIDE = 2560
        private const val MIN_DPI = 120
        private const val MAX_DPI = 640
        private const val FRAME_POLL_MS = 40L
        private const val MAX_CAPTURE_WAIT_MS = 5_000L
        private const val PNG_QUALITY = 100

        // 宿主被杀或被划掉时没有人来 release，那块屏会连着自己那份 ImageReader 一直留在
        // 这个进程里。助手会话的调用间隔在秒级，五分钟没有任何事务即可判定会话结束。
        private const val IDLE_CHECK_MS = 30_000L
        private const val IDLE_RELEASE_MS = 5L * 60L * 1000L
        private const val THREAD_JOIN_MS = 1_000L
    }

    private companion object {
        /**
         * 回包走 binder，而 binder 的缓冲是**进程级共享**的一兆。
         * 两个字符流按 UTF-16 计，上限取到几十 KB 量级，给并发调用留出余量。
         */
        const val MAX_OUTPUT_CHARS = 32_000
        const val TRUNCATED_MARK = "\n…[output truncated]"
        const val TIMEOUT_JOIN_MS = 2_000L
        const val EXIT_TIMED_OUT = 124
        const val EXIT_REJECTED = -1

        /** shell 身份唯一确定可写的落点；`pm` 也读得到它。 */
        const val SHELL_TMP = "/data/local/tmp"
        val STAGED_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}\\.apk")
        const val COPY_BUFFER_BYTES = 64 * 1024
        const val MAX_STAGED_BYTES = 128L * 1024L * 1024L
    }
}

/** 退出前留给 destroy 事务的回执时间：太短会让调用侧拿到 DEAD_OBJECT，太长只是白等。 */
private const val EXIT_GRACE_MS = 120L
