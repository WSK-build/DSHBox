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
import interlock.relay.core.exec.AvcEncoderFactory
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

        ShellProtocol.CODE_DISPLAY_PREPARE -> {
            // 建屏前的主屏快照：读不全不算失败（unknown 由判定端容忍），恒回 0。
            val prepared = TrustedDisplay.prepareMainState()
            reply?.writeInt(prepared.first)
            reply?.writeString(prepared.second)
            true
        }

        ShellProtocol.CODE_DISPLAY_SELF_CHECK -> {
            // 建屏后的自检与兼容：全屏窗口/IME 兜底 + 前后对账；观察到破坏时屏已当场释放。
            // 回包正文是自检结果 JSON，宿主侧负责解析、落运行日志与标记「可信屏不安全」。
            val checked = TrustedDisplay.selfCheck(data.readInt())
            reply?.writeInt(checked.first)
            reply?.writeString(checked.second)
            true
        }

        ShellProtocol.CODE_TASKS_LIST -> {
            // 面板任务列表：列**指定显示**上的任务（参数是 displayId——虚拟屏方向
            // 列可信屏，主屏方向列 0）。displayId < 0 回 -1 + 原因，宿主侧回 409，
            // 不在本进程造「空屏上有一个任务」的假象；主屏（0）恒可列。
            val requested = data.readInt()
            val display = if (requested == ShellProtocol.MAIN_DISPLAY_ID) ShellProtocol.MAIN_DISPLAY_ID else TrustedDisplay.displayId
            if (display < 0) {
                reply?.writeInt(-1)
                reply?.writeString("no trusted display")
            } else {
                val outcome = ShellRunner.run(
                    // 设备格式：任务行是 `  * Task{...}`（星号后有空格），
                    // 顶层活动行是 `topResumedActivity=ActivityRecord{...}` —— grep 不能按
                    // `\*Task{` 锚定，按裸的 Task{/ActivityRecord{ 撒网，行形状由解析端收。
                    "dumpsys activity activities | grep -aE 'Display #[0-9]+ \\(|Task\\{|ActivityRecord\\{'",
                    TASKS_DUMP_TIMEOUT_MS,
                )
                val entries = if (outcome.success) TrustedTasks.parse(outcome.stdout, display) else emptyList()
                reply?.writeInt(outcome.exitCode)
                // 回包正文里的 displayId 是**本进程实际解析**的那个号：宿主不得再覆盖，
                // 换屏竞态下两边各说各话就查不出谁错了。
                reply?.writeString(TrustedTasks.tasksJson(display, entries))
            }
            true
        }

        ShellProtocol.CODE_DISPLAY_RESIZE -> {
            // 面板旋转的本体 —— resize 那块屏（不 release/不重建，屏上任务
            // 保留）。参数 (宽, 高, dpi)，服务端夹到安全区间后执行；回 (0 或 <0, 原因)。
            val w = data.readInt()
            val h = data.readInt()
            val d = data.readInt()
            val resized = TrustedDisplay.resize(w, h, d)
            reply?.writeInt(resized.first)
            reply?.writeString(resized.second)
            true
        }

        ShellProtocol.CODE_TASKS_MOVE -> {
            // 面板搬迁：把一个 RootTask 整体搬到目标显示。root 解析在宿主侧
            // （TrustedTasks.rootOf），这里只执行命令并把成败判定如实回。
            val rootTaskId = data.readInt()
            val target = data.readInt()
            val outcome = ShellRunner.run(
                "am display move-stack $rootTaskId $target",
                TASKS_MOVE_TIMEOUT_MS,
            )
            val ok = TrustedTasks.moveSucceeded(outcome.exitCode, outcome.stdout, outcome.stderr)
            reply?.writeInt(if (ok) 0 else outcome.exitCode.takeIf { it != 0 } ?: -1)
            reply?.writeString(if (ok) "" else (outcome.stderr.trim().ifEmpty { outcome.stdout.trim() }.ifEmpty {
                "move-stack exit=" + outcome.exitCode
            }))
            true
        }

        ShellProtocol.CODE_STREAM_START -> {
            // 宿主建好 pipe 把写端递进来；元数据同步回，帧循环在会话线程里跑。
            // fd 的所有权自此归会话：回 <0 时这里当场关掉，不让宿主侧再关第二遍。
            val (status, note) = StreamSession.start(data.readFileDescriptor())
            reply?.writeInt(status)
            reply?.writeString(note)
            true
        }

        ShellProtocol.CODE_STREAM_STOP -> {
            val (status, note) = StreamSession.stop()
            reply?.writeInt(status)
            reply?.writeString(note)
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
            // 先停流再收屏：流会话正消费那块屏的帧，收屏把它晾在一块不存在的屏上。
            runCatching { StreamSession.stop() }
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
     * 一次命令事务的递交结局。三种必须分开：「没送出去」（服务端拒收，动作未出本端）、
     * 「送出之后才死/递交结果无法证实」（命令可能已在远端跑起来）、以及拿到回执。
     * 副作用状态（effectState）的 dispatched 标记只认这里的分类，不猜 reason。
     */
    sealed class CommandTxn {
        /** 服务端没有接单：动作未出本端。 */
        object NotSent : CommandTxn()

        /**
         * 送出之后才死，或本端在收发两侧抛出、递交结果无法证实：命令可能已经开始执行，
         * 也可能没有——只能按「已提交」归类。
         */
        data class Submitted(val note: String) : CommandTxn()

        /** 远端回了话：成败由退出码说。 */
        data class Replied(val outcome: Outcome) : CommandTxn()
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

        /**
         * `create` 的一个特殊负值：**系统当场拒绝建这块屏**（trusted 建屏被平台/ROM 拒回，
         * 建屏调用本身没能落成）。宿主对它的处置与"旧屏收不掉/内部异常"分开：前者是这台
         * 机器上的稳定结论（改前台模式，不再重试建屏），后两类是瞬时状态（等一等还能成）。
         */
        internal const val CODE_SYSTEM_DENIED = -3

        @Volatile
        private var images: ImageReader? = null

        /**
         * 建屏时的 dpi：[resize] 交换宽高时要原样带上（不重算），与
         * [size] 同为"这块屏当前参数"的读数。-1 = 尚未建屏。
         */
        @Volatile
        private var currentDpi: Int = -1

        @Volatile
        private var pump: Thread? = null

        @Volatile
        private var watchdog: Thread? = null

        @Volatile
        private var looperThread: HandlerThread? = null
        private var cachedContext: android.content.Context? = null
        /**
         * 最新一帧的持有器：**读不消费**。pump 写入时替换并 close 旧帧，
         * 截图/录屏/流会话都在锁内把像素拷走；读方持锁期间帧不会被换掉。
         *
         * 旧实现是 `AtomicReference.getAndSet(null)`：一次预览/截图把最后一帧取走后，
         * 静止画面不再产生新帧，新流会话拿到 null，编码器 30 秒没有输入——设备日志
         * `bytes=0 durationMs=30312` 就是这个场景。
         */
        private val latestFrame = LatestFrameHolder<android.media.Image> { runCatching { it.close() } }

        /** 无人问津到点就收屏，见 [startWatchdog]；每次收屏递增，用来认不出自己就退场。 */
        @Volatile
        private var generation = 0

        /** 最近一次真正办事的时刻：空闲到点就自动收屏，见 [startWatchdog]。 */
        @Volatile
        private var lastUsedMs = 0L

        /**
         * 屏的租约簿：流会话持租约期间，空闲计时器不得收屏。引用计数解决的是
         * 「面板开着、AI 也在用」与「面板关了、AI 还在用」两件事——前者不能让 5 分钟
         * 空闲把正在编码的屏收走，后者不能让面板关闭顺手释放别人还在用的屏（面板关闭
         * 只停流，见 [StreamSession.stop]）。纯 JVM 类，单测穷举领/还/清。
         */
        internal class DisplayLeases {
            private val ids = HashSet<Int>()
            private var seq = 0

            @Synchronized
            fun acquire(): Int = (++seq).also { ids.add(it) }

            @Synchronized
            fun release(id: Int): Boolean = ids.remove(id)

            @Synchronized
            fun clear() {
                ids.clear()
            }

            @Synchronized
            fun isEmpty(): Boolean = ids.isEmpty()
        }

        private val leases = DisplayLeases()

        fun acquireStreamLease(): Int = leases.acquire()

        fun releaseStreamLease(id: Int) {
            leases.release(id)
        }

        internal fun hasLeases(): Boolean = !leases.isEmpty()

        /** 流会话收场时丢掉最后一帧：没有消费者还留着这块屏的全屏 Image（内存归零）。 */
        fun dropHeldFrame() {
            latestFrame.drop()
        }

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
                // 先交给字段持有：反射/建屏在中途抛错时，失败清理关的必须是这个刚建出来的
                // reader（旧实现只在 `id` 校验之后才赋字段，早段失败会把它漏掉）。
                images = reader
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
                    manager, DISPLAY_NAME, w, h, d, reader.surface,
                    createFlags(android.os.Build.VERSION.SDK_INT, trusted = true),
                    null, Handler(thread.looper),
                ) as VirtualDisplay
                // 反射返回即由字段持有：随后 `displayId` 校验失败时，清理才能收掉这块真屏
                // （否则它只在局部变量里，失败分支看不见，成了没人收的孤儿屏）。
                display = created
                val id = created.display?.displayId ?: error("created display has no id")
                displayId = id
                displayUniqueId = uniqueIdOf(created.display)
                currentDpi = d
                // 上一块屏可能留了一帧没人收（收屏时那条读线程没在 1 秒内让开）：不清就会
                // 成为新屏的第一张截图，看着像「屏卡住了」。
                latestFrame.drop()
                touch()
                startPump(reader)
                startWatchdog()
                id to ""
            }
            attempt.getOrElse {
                // 半开的屏必须处置：`display` 已赋值而收屏也可能失败 ——
                // **收成功才清引用**。收屏抛异常时保留 `display/displayId/displayUniqueId/
                // images` 引用与已起来的 pump/watchdog（按"屏还在"处理），下一次 release
                // 仍指向这块真屏、能原样再收。清了引用再答错，那块没人管的屏就成了孤儿，
                // 缓存的编号替一块没人取帧的屏说话。
                val halfOpen = display
                if (halfOpen != null) {
                    val released = runCatching { halfOpen.release() }
                    if (released.isSuccess) {
                        // 收屏**已经完成**，这里只做字段归零与资源收尾——
                        // 不再调 [release]（那会对同一个 VirtualDisplay 再 release 一次，
                        // 第二次抛异常时还会跳过资源收尾）。
                        display = null
                        displayId = -1
                        displayUniqueId = null
                        val reader = images
                        images = null
                        clearHeldResources(reader)
                    }
                    return@getOrElse createFailureReply(it)
                }
                // display 还没赋上（建屏反射那步就抛了）：没有屏需要收，只清线程与 reader。
                displayId = -1
                displayUniqueId = null
                val reader = images
                images = null
                clearHeldResources(reader)
                createFailureReply(it)
            }
        }

        /**
         * 反射建屏失败的真实成因。`InvocationTargetException` 只当信封：平台把
         * 「系统拒绝建屏」包成 `SecurityException`（`createVirtualDisplay` 的运管侧
         * 权限检查）从它里面抛出，不拆开就分不出"拒绝"与"普通的场内异常"。
         */
        private fun unwrapInvocation(failure: Throwable): Throwable {
            var cause = failure
            // **只用 `InvocationTargetException` 这一种信封**（反射器的壳），拆完不再
            // 沿因果链往下走：`SecurityException("...", cause)` 这类把拒绝与另一件事
            // 一起包着抛的场景，拒绝本身就在顶层；穿透整条链反而会把一个拒绝读成
            // "它 cause 里的间发性错误"，或把两种失败各说各话的链条并成一句。
            while (cause is java.lang.reflect.InvocationTargetException && cause.cause != null) {
                cause = cause.cause ?: break
            }
            return cause
        }

        /**
         * 建屏场内异常 → (结果码, 原因)，纯判据供 JVM 单测穷举：
         * 成因是 `SecurityException`（系统拒绝建屏）→ [CODE_SYSTEM_DENIED]；
         * 其余异常（内部故障等瞬时失败）→ `-1`。「旧屏收不掉」不在场内异常里，
         * 那条路在 release 失败处直接返回 `-1`，同属瞬时失败。
         */
        internal fun createFailureReply(failure: Throwable): Pair<Int, String> {
            val cause = unwrapInvocation(failure)
            val code = if (cause is SecurityException) CODE_SYSTEM_DENIED else -1
            return code to "${cause.javaClass.simpleName}: ${cause.message ?: "no message"}"
        }

        /**
         * 收屏。回 (1=收掉了一块, "")、(0=本来就没有，重复收不算错) 或 (<0, 原因)。
         * 「已经没有屏」必须与「收屏失败」分开：空闲到点会自动收一次，助手随后按自己的
         * release 拿到 -1 就会被读成后端不可用，而它其实已经做完了想做的事。
         *
         * **收成功之前不动任何引用**——`current.release()` 抛异常时
         * `display/displayId/displayUniqueId/images`（与已起来的 pump/watchdog）全部保留，
         * 回 -1，让下一次 release 原样再指向同一块屏重试（原有先清账再收，失败后引用已丢，
         * 第二次 release 必然答 0 "no display"、宿主就据 0 解除 unsafe——留一块正破坏主屏
         * 的屏在手上）。失败路径唯一要防死循环的是空闲计时器：它只在 `displayId < 0` 时退场，
         * 而失败场景屏确实还在，由下一次 release 或 [RelayShellService.ShellRunner.run] 的
         * `touch()` 续期——无法释放是真实场景，不是需要绕开的中间态。
         */
        fun release(): Pair<Int, String> = synchronized(this) {
            val current = display ?: return@synchronized 0 to "no display"
            // 先只做收屏这一个动作：成了才清状态、停线程、关资源；抛异常时全部留在原处。
            val failure = runCatching { current.release() }.exceptionOrNull()
            if (failure != null) {
                return@synchronized -1 to "release failed: ${failure.javaClass.simpleName}"
            }
            val reader = images
            // 收成了才清账（原有"先清账再收"的顺序已废）：归零引用，再做资源收尾。
            display = null
            displayId = -1
            displayUniqueId = null
            images = null
            currentDpi = -1
            // 屏没了，附在它上面的租约也一并作废：下一次建屏不该继承上一块屏的引用计数。
            leases.clear()
            clearHeldResources(reader)
            1 to ""
        }

        /**
         * 把这块屏 resize 到新尺寸（面板旋转的本体；`VirtualDisplay.resize` +
         * 换 Surface）。**不 release、不重建**：屏号不变、屏上任务不销毁，应用按新尺寸
         * 跟随配置变化重排；流编码取的是 ImageReader 的帧，换 reader 会中断取帧 ——
         * 流会话由宿主在调用前先停（控制端点 /rotate 的顺序铁律）。
         *
         * 步骤（与 create/release 同一把对象锁互斥）：
         * 1. 新尺寸夹到 [MIN_SIDE]/[MAX_SIDE]/[MIN_DPI]/[MAX_DPI]（与 create 同一判据）；
         * 2. 新 `ImageReader.newInstance(w,h,RGBA_8888,MAX_IMAGES)`；
         * 3. `VirtualDisplay.resize(w,h,dpi)`（Kotlin 无此属性，走反射；
         * `setSurface(newReader.surface)` 同理）；
         * 4. `images = newReader`（旧 pump 线程按 `reader != images` 自退）、
         * `latestFrame.drop()`、关旧 reader、`startPump(newReader)`、`touch()`；
         * 5. 任一步失败：关新 reader、保留旧屏与旧 reader/旧 pump，回 (失败码, 原因)。
         * **不回落 release/recreate** —— 那会销毁屏上任务，违背旋转的语义边界。
         *
         * 回 (0, "") 成功，(<0, 原因) 失败；成功后宿主可用 CODE_DISPLAY_QUERY 对账新尺寸。
         */
        fun resize(width: Int, height: Int, dpi: Int): Pair<Int, String> = synchronized(this) {
            val current = display
                ?: return@synchronized -1 to "no trusted display to resize"
            if (displayId < 0) {
                return@synchronized -1 to "no trusted display to resize (displayId=$displayId)"
            }
            val w = width.coerceIn(MIN_SIDE, MAX_SIDE)
            val h = height.coerceIn(MIN_SIDE, MAX_SIDE)
            // dpi = -1 是宿主"沿用当前值"的占位（旋转不改密度）：取建屏/上次 resize 记下
            // 的 currentDpi；读不到（旧版本字段缺失）再落 MIN_DPI 兜底夹取。
            val d = (if (dpi == -1) currentDpi else dpi)
                .takeIf { it > 0 }?.coerceIn(MIN_DPI, MAX_DPI) ?: MIN_DPI
            val oldReader = images
            val newReader: ImageReader
            try {
                newReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, MAX_IMAGES)
            } catch (failure: Throwable) {
                return@synchronized -1 to "resize failed: new ImageReader could not be created" +
                    " (${failure.javaClass.simpleName}: ${failure.message ?: "no message"})"
            }
            // 单飞失败点在 resize/setSurface：失败时只收新 reader，旧屏/旧 reader 原样保留。
            fun resizeMethod() = current.javaClass.getMethod(
                "resize",
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            ).apply { isAccessible = true }
            fun setSurfaceMethod() = current.javaClass.getMethod("setSurface", Surface::class.java)
                .apply { isAccessible = true }

            // 记下当前参数：setSurface 失败时的回滚目标（尺寸已变、Surface 还是旧的）。
            val (oldW, oldH) = size()
            val oldDpi = currentDpi.takeIf { it > 0 } ?: MIN_DPI

            exemptHiddenMembers()
            // 第一步：resize 本体。失败=屏没变，只收新 reader 即可。
            val resizedOnly = runCatching { resizeMethod().invoke(current, w, h, d) }
            if (resizedOnly.isFailure) {
                runCatching { newReader.close() }
                val failure = resizedOnly.exceptionOrNull()
                    ?: return@synchronized -1 to "resize failed: unknown error"
                val cause = unwrapInvocation(failure)
                return@synchronized -1 to "resize failed: ${cause.javaClass.simpleName}: ${cause.message ?: "no message"}"
            }
            // 第二步：换 Surface。失败=尺寸已变而帧源还是旧 reader（新 reader 的
            // surface 与显示尺寸不符， pump 会拿不到帧）——best-effort 回滚：
            // resize(oldW,oldH,oldDpi) + 旧 surface；回滚也失败就在 reason 里写清。
            val surfaceSet = runCatching { setSurfaceMethod().invoke(current, newReader.surface) }
            if (surfaceSet.isFailure) {
                runCatching { newReader.close() }
                val rollback = runCatching {
                    resizeMethod().invoke(current, oldW, oldH, oldDpi)
                    setSurfaceMethod().invoke(current, oldReader?.surface ?: error("old reader surface unavailable"))
                }
                val failure = surfaceSet.exceptionOrNull()
                    ?: return@synchronized -1 to "resize failed: unknown error"
                val cause = unwrapInvocation(failure)
                return@synchronized if (rollback.isSuccess) {
                    -1 to ("resize failed at setSurface (display rolled back to ${oldW}x${oldH}): " +
                        "${cause.javaClass.simpleName}: ${cause.message ?: "no message"}")
                } else {
                    val rollbackFailure = rollback.exceptionOrNull()
                        ?: return@synchronized -1 to "resize failed at setSurface AND rollback failed for an unknown error"
                    val rollbackCause = unwrapInvocation(rollbackFailure)
                    -1 to ("resize failed at setSurface AND rollback failed — display may be at ${w}x${h} " +
                        "with the old frame source; recreate the display (surface.virtual create) to recover. " +
                        "setSurface: ${cause.javaClass.simpleName}; rollback: ${rollbackCause.javaClass.simpleName}")
                }
            }
            // 成功：换 reader 账，退掉旧 pump（旧线程按 reader != images 自退，join 一下防重叠）。
            images = newReader
            currentDpi = d
            latestFrame.drop()
            stopThread(pump)
            pump = null
            runCatching { oldReader?.close() }
            touch()
            startPump(newReader)
            0 to ""
        }

        /**
         * 典当资产归位的**唯一**收尾序（抽出，`release()` 成功路径与
         * `create()` 的失败清理共用）：作废空闲计时器（generation++）、停 pump/watchdog、
         * 关 reader、关 held 里那一帧、收 looper。调用前必须已把
         * `display/displayId/displayUniqueId/images` 归零（这里不重复清那几个字段）——
         * 引用清不清的判据（收屏成功与否）留在两个调用方各自手里。
         */
        private fun clearHeldResources(reader: ImageReader?) {
            // 收屏即作废此刻所有空闲计时器：见 startWatchdog 里那句"认不出自己就退场"。
            generation++
            stopThread(pump)
            pump = null
            stopThread(watchdog)
            watchdog = null
            runCatching { reader?.close() }
            latestFrame.drop()
            runCatching { looperThread?.quitSafely() }
            looperThread = null
        }

        // ── 主屏自检与建屏后兼容──
        //
        // 读侧在这里（shell 身份的 dumpsys 才读得到主屏的账），解析与判定在
        // [MainDisplayCheck]、兜底编排在 [DisplayPolicy]（都是纯 Kotlin，JVM 单测穷举）。
        // before 快照由宿主在建屏**之前**经 CODE_DISPLAY_PREPARE 抓走；建屏之后宿主再发
        // CODE_DISPLAY_SELF_CHECK，这里先做全屏窗口与 IME 兜底（尽力而为、成败只
        // 记录），再抓 after 对账。观察到破坏**不立即下结论**：隔一小段复采一次（PREPARE
        // 与 SELF_CHECK 之间用户切应用/息屏会造成一次性的假差异），仍破坏才当场释放这块
        // 屏并回 unsafe 清单——绝不能把用户留在手势失效的主屏上，也不能把一次用户自己的
        // 操作读成"这台设备建屏必坏"。释放后的主屏读数随回包带给宿主，落 DISPLAY_RELEASE_STATE。

        @Volatile
        private var pendingMainState: MainDisplayCheck.MainState? = null

        /** 建屏前的主屏状态快照。读不全不报错：unknown 由判定端容忍，恒回 (0, "")。 */
        fun prepareMainState(): Pair<Int, String> {
            pendingMainState = runCatching { snapshotMainState() }.getOrNull()
            return 0 to ""
        }

        /**
         * 建屏后的自检与兼容。回 (0, 结果 JSON) 或 (<0, 原因)。
         *
         * JSON 形状（宿主侧解析并落运行日志）：`selfCheck` = pass|unsafe|unverified
         * （全 unknown 时是 unverified，不伪装 pass）；`violations` 是破坏清单（unsafe 时
         * 才有，且是**复采后仍在**的；复采没读到任何判定证据时含
         * [MainDisplayCheck.RESAMPLE_UNVERIFIED] 的 fail-closed）；`transientRecovered`
         * 是首轮见到、复采已恢复的破坏（只记录、不锁死）；`before`/`after`/`afterSample`/
         * `afterRelease` 各是一份主屏读数（afterSample 只在复采过时出现）；`released`
         * 三态 true/false/unknown；`coverage` 是虚拟屏窗口覆盖率（读不到即 unknown）；
         * 窗口模式与 IME 兜底的成败与原因在 `windowingOk`/`windowingNote`/`imePolicyOk`/`imePolicyNote`。
         */
        fun selfCheck(id: Int): Pair<Int, String> = synchronized(this) {
            if (id < 0 || displayId != id || display == null) {
                return@synchronized -1 to "no trusted display $id here (current: $displayId)"
            }
            val policy = applyWindowingAndImePolicy(id)
            val (width, height) = size()
            val before = pendingMainState
            pendingMainState = null
            val after = runCatching { snapshotMainState() }.getOrDefault(MainDisplayCheck.MainState())
            val firstViolations = MainDisplayCheck.evaluate(MAIN_DISPLAY_ID, id, before, after, width, height)
            // 复采窗口：一次差异可能是用户自己的切应用/息屏，隔一小段再重新检查一次——
            // 恢复了就只记录（不锁死设备），仍在才是这台机器的稳定结论。
            // 判定收口在 [MainDisplayCheck.settle]（纯函数，JVM 测试穷举同走）：
            // 首轮干净 → pass（全 unknown 时 unverified，不伪装 pass）；首轮有破坏 →
            // 只认复采**读到证据**的那一份：恢复了＝transientRecovered、仍在＝unsafe；
            // 复采没读到任何证据（快照读不到/全 unknown）→ fail-closed：按 unsafe 收场、
            // 当场 release，violations 落 RESAMPLE_UNVERIFIED——"没读到"不能被读成"恢复了"。
            // 复采只在**首轮确实见到破坏**时才值得等：首轮干净时没有"差异"要复核，
            // 隔 1.5 秒再重新检查一次只会把每次正常建屏拖长（设备上这条延迟落在调用预算里），
            // 判定仍统一走 [MainDisplayCheck.settle]（首轮干净 → Pass，证据口径不变）。
            val afterSample = if (firstViolations.isEmpty()) {
                after
            } else {
                sleepQuietly(RESAMPLE_DELAY_MS)
                runCatching { snapshotMainState() }.getOrDefault(after)
            }
            when (val settled = MainDisplayCheck.settle(id, before, firstViolations, afterSample, width, height)) {
                is MainDisplayCheck.Settled.Pass -> {
                    // 屏保留：普通建屏本来没观察到破坏，unknown 不判死（不伪装 pass 由
                    // selfCheckJson 的 level 决定）。没有复采项要记。
                    0 to selfCheckJson(
                        id, policy, before, afterSample, emptyList(), emptyList(),
                        released = null, afterRelease = null, resampleUnverified = false,
                    )
                }
                is MainDisplayCheck.Settled.Transient -> {
                    0 to selfCheckJson(
                        id, policy, before, afterSample, emptyList(), settled.recovered,
                        released = null, afterRelease = null, resampleUnverified = false,
                    )
                }
                is MainDisplayCheck.Settled.ResampleUnverified -> {
                    // fail-closed：当场收屏。violations 至少含 RESAMPLE_UNVERIFIED 明确标记，
                    // selfCheck 写 unsafe——宿主侧看到 violations 非空即按 unsafe 收场。
                    val released = release()
                    val afterRelease = runCatching { snapshotMainState() }.getOrNull()
                    0 to selfCheckJson(
                        id, policy, before, afterSample, emptyList(), emptyList(),
                        released, afterRelease, resampleUnverified = true,
                    )
                }
                is MainDisplayCheck.Settled.Unsafe -> {
                    // 破坏复采仍在，当场收屏：这块屏正把用户的主屏往坏里带，多留一秒多害一秒。
                    val released = release()
                    val afterRelease = runCatching { snapshotMainState() }.getOrNull()
                    0 to selfCheckJson(
                        id, policy, before, afterSample, settled.remaining, emptyList(),
                        released, afterRelease, resampleUnverified = false,
                    )
                }
            }
        }

        /**
         * 一条快照命令抓全六段主屏读数。命令本身失败时把 stderr 也并进去解析：
         * 解析端对「什么都没读到」按全 unknown 处理，从不因此判破坏。
         */
        private fun snapshotMainState(): MainDisplayCheck.MainState {
            val outcome = ShellRunner.run(MainDisplayCheck.MAIN_STATE_COMMAND, MAIN_STATE_TIMEOUT_MS)
            val text = if (outcome.success) outcome.stdout else outcome.stdout + "\n" + outcome.stderr
            return MainDisplayCheck.parse(text)
        }

        /** 兜底的真 IO：两次隐藏 IWindowManager 调用 + shell 回退命令。 */
        private val policyIo = object : DisplayPolicy.PolicyIo {
            override fun hiddenSetWindowingMode(id: Int): Result<Unit> = runCatching {
                val wms = windowManagerService() ?: error("no window manager service")
                wms.javaClass
                    .getMethod("setWindowingMode", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    .apply { isAccessible = true }
                    .invoke(wms, id, DisplayPolicy.WINDOWING_MODE_FULLSCREEN)
                Unit
            }

            override fun runShell(command: String, timeoutMs: Long): RelayShellService.Outcome =
                ShellRunner.run(command, timeoutMs)

            override fun hiddenSetImePolicy(id: Int): Result<Unit> = runCatching {
                val wms = windowManagerService()
                    ?: error("no window manager service for setDisplayImePolicy")
                wms.javaClass
                    .getMethod("setDisplayImePolicy", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    .apply { isAccessible = true }
                    .invoke(wms, id, DisplayPolicy.IME_POLICY_LOCAL)
                Unit
            }
        }

        private fun windowManagerService(): Any? = runCatching {
            Class.forName("android.view.WindowManagerGlobal")
                .getDeclaredMethod("getWindowManagerService")
                .apply { isAccessible = true }
                .invoke(null)
        }.getOrNull()

        private fun applyWindowingAndImePolicy(id: Int): DisplayPolicy.Outcome =
            DisplayPolicy.apply(id, policyIo, POLICY_SHELL_TIMEOUT_MS)

        private fun selfCheckJson(
            id: Int,
            policy: DisplayPolicy.Outcome,
            before: MainDisplayCheck.MainState?,
            after: MainDisplayCheck.MainState,
            violations: List<MainDisplayCheck.Violation>,
            transientRecovered: List<MainDisplayCheck.Violation>,
            released: Pair<Int, String>?,
            afterRelease: MainDisplayCheck.MainState?,
            /* fail-closed 的复采没读到任何证据：violations 落 RESAMPLE_UNVERIFIED、selfCheck=unsafe */
            resampleUnverified: Boolean = false,
        ): String {
            val (width, height) = size()
            val level = when {
                violations.isNotEmpty() || resampleUnverified -> "unsafe"
                // pass 判据只看建屏后快照（before 有读数不算健康证明）。
                MainDisplayCheck.hasEvidence(after) -> "pass"
                else -> "unverified"
            }
            val json = org.json.JSONObject()
                .put("selfCheck", level)
                .put("display", id)
                .put("windowingOk", policy.windowingOk)
                .put("windowingNote", policy.windowingNote)
                .put("imePolicyOk", policy.imePolicyOk)
                .put("imePolicyNote", policy.imePolicyNote)
            if (violations.isNotEmpty()) {
                json.put("violations", org.json.JSONArray(violations.map { it.name }))
            }
            if (resampleUnverified) {
                // fail-closed：violations 数组必在，且至少含 RESAMPLE_UNVERIFIED 这一条；
                // 与首轮破坏同数组（出去的清单是"为什么 unsafe"的完整答案）。
                val residual = json.optJSONArray("violations") ?: org.json.JSONArray()
                residual.put(MainDisplayCheck.RESAMPLE_UNVERIFIED)
                json.put("violations", residual)
            }
            if (transientRecovered.isNotEmpty()) {
                json.put("transientRecovered", org.json.JSONArray(transientRecovered.map { it.name }))
            }
            // 覆盖率三态：读不到如实 unknown，不让回包把"没读到"说成"没问题"。
            val ratio = MainDisplayCheck.coverageRatio(after, id, width, height)
            json.put("coverage", ratio?.let { Math.round(it * 100.0) / 100.0 } ?: "unknown")
            before?.let { json.put("before", stateJson(it, id)) }
            json.put("after", stateJson(after, id))
            released?.let { json.put("released", releaseState(it.first)) }
            afterRelease?.let { json.put("afterRelease", stateJson(it, id)) }
            return json.toString()
        }

        /** 自动收屏回执的三态：1=收掉了、0=本无屏、负=收屏失败（结局未知）。 */
        private fun releaseState(code: Int): Any = when {
            code == 1 -> true
            code == 0 -> false
            else -> "unknown"
        }

        /** 一份主屏读数压成 JSON；没读到的项一律 NULL，宿主侧同样按 unknown 呈现。 */
        private fun stateJson(state: MainDisplayCheck.MainState, virtualDisplay: Int): org.json.JSONObject =
            org.json.JSONObject()
                .put("top0", state.topPackages[MAIN_DISPLAY_ID] ?: org.json.JSONObject.NULL)
                .put("topTask0", state.topTaskIds[MAIN_DISPLAY_ID] ?: org.json.JSONObject.NULL)
                .put("focus", state.focusedWindow ?: org.json.JSONObject.NULL)
                .put("focusDisplay", state.focusDisplayId ?: org.json.JSONObject.NULL)
                .put("imeDisplay", state.imeFocusedDisplayId ?: org.json.JSONObject.NULL)
                .put("imeShown", state.imeShown ?: org.json.JSONObject.NULL)
                .put("launcher", state.launcherPackage ?: org.json.JSONObject.NULL)
                .put("group0", state.displayGroups[MAIN_DISPLAY_ID] ?: org.json.JSONObject.NULL)
                .put("topVirtual", state.topPackages[virtualDisplay] ?: org.json.JSONObject.NULL)
                .put("groupVirtual", state.displayGroups[virtualDisplay] ?: org.json.JSONObject.NULL)

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
                    // 有流会话租约时不收屏；判据在 [releaseIfCurrent] 的同一把锁内
                    // 复查（锁外先看、拿锁再收，中间可能刚好有人领了租约）。
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
        private fun releaseIfCurrent(own: Int): Pair<Int, String> {
            // 空闲自动收屏与手动 release **同一语义、同一路径** ——
            // 只 [release]() 一次，不枚举任务、不 move-stack、不 force-stop。Android 17 上
            // move-stack→release 的 BLAST 过渡重叠会把 system_server 打出 NPE（与手动
            // release 同一禁令，见 ShizukuBackend.panelTaskRelease 的注释）；屏上任务由
            // `DESTROY_CONTENT_ON_REMOVAL` 随收屏由 AOSP 自己 finish，不再有第二条过渡。
            synchronized(this) {
                if (own != generation) return 0 to "stale idle timer"
                // 租约复查必须在同一把对象锁里做——锁外判"无人使用"之后，StreamSession
                // 可能刚好领了租约并起线程；此时收屏会让 start 回 0、流随即因 generation
                // 变化退出。"display → leases" 的锁顺序不变（簿锁是叶子锁）。
                if (hasLeases()) return 0 to "leased by a stream session"
                return release()
            }
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
            // 读不消费：在持有器锁内把像素复制成 Bitmap，帧本身仍留给下一次截图/预览。
            var bitmap: Bitmap? = runCatching {
                latestFrame.with { frame -> bitmapFromFrame(frame, maxWidth) }
            }.getOrNull()
            while (bitmap == null && android.os.SystemClock.elapsedRealtime() < deadline) {
                if (!sleepQuietly(FRAME_POLL_MS)) return null to "interrupted"
                // 等帧期间屏可能被收掉：读已关闭的 ImageReader 抛的是 IllegalStateException，
                // 逃出去就是整个用户服务没命（同 startPump 的说明）。
                val fresh = runCatching { reader.acquireLatestImage() }.getOrNull() ?: continue
                bitmap = runCatching { bitmapFromFrame(fresh, maxWidth) }.getOrNull()
                runCatching { fresh.close() }            }
            val frameBitmap = bitmap ?: return null to "no frame before deadline"
            val target = File(SHELL_TMP, "relay-cap-${System.nanoTime()}." +
                (if (jpegQuality > 0) "jpg" else "png"))
            var descriptor: ParcelFileDescriptor? = null
            val failure = try {
                // 编码直接落进文件流：先在堆里按「未压缩像素数」开一份缓冲、再整体复制
                // 一次，等于让一次取帧要两倍于整屏帧的堆。这个进程一倒，三条 shell 能力
                // 与那块屏一起没命，所以按帧走的录屏尤其不能这么占。
                val compressed = FileOutputStream(target).use {
                    encodeBitmap(frameBitmap, jpegQuality, it)
                }
                if (!compressed) "frame encode failed: compress" else runCatching {
                    descriptor = ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_ONLY)
                    ""
                }.getOrElse { "frame encode failed: ${it.javaClass.simpleName}" }
            } catch (t: Throwable) {
                runCatching { frameBitmap.recycle() }
                "frame encode failed: ${t.javaClass.simpleName}"
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

        /**
         * 把 Image 的像素复制成一张（可选缩放的）Bitmap。
         *
         * 调用方负责 recycle 返回的 Bitmap。**必须在 [latestFrame] 的锁内调用**：
         * 复制期间帧不会被 pump 换掉/close 掉。中间产生的 Bitmap 在这里就地回收。
         */
        private fun bitmapFromFrame(frame: android.media.Image, maxWidth: Int): Bitmap {
            val plane = frame.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowPadding = plane.rowStride - pixelStride * frame.width
            val bitmap = Bitmap.createBitmap(
                frame.width + rowPadding / pixelStride,
                frame.height,
                Bitmap.Config.ARGB_8888,
            ).also { it.copyPixelsFromBuffer(buffer) }
            var cropped = if (rowPadding == 0) {
                bitmap
            } else {
                Bitmap.createBitmap(bitmap, 0, 0, frame.width, frame.height).also { bitmap.recycle() }
            }
            if (maxWidth in 1 until cropped.width) {
                val scaled = Bitmap.createScaledBitmap(cropped, maxWidth, maxWidth * cropped.height / cropped.width, true)
                if (scaled !== cropped) cropped.recycle()
                cropped = scaled
            }
            return cropped
        }

        /** 把 Bitmap 按 [jpegQuality] 压缩进 [out]；无论成败都回收 Bitmap。 */
        private fun encodeBitmap(bitmap: Bitmap, jpegQuality: Int, out: java.io.OutputStream): Boolean =
            try {
                if (jpegQuality > 0) {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)
                } else {
                    bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
                }
            } finally {
                runCatching { bitmap.recycle() }
            }

        /**
         * 实时流会话读「此刻最新一帧」的唯一入口：在持有器锁内把帧交给 block，
         * **读完不消费、不 close**——下一场预览/截图仍能拿到同一帧。
         */
        internal fun <R> withLatestFrame(block: (android.media.Image) -> R): R? = latestFrame.with(block)

        /** 带代际的读：帧没换过时返回的 version 不变，流会话据此区分“新帧/还是上一帧”。 */
        internal fun <R> withLatestFrameVersioned(block: (android.media.Image, Long) -> R): R? =
            latestFrame.withVersion(block)

        /** 那块屏的 ImageReader，无屏时 null。流会话用它判定「还有没有可消费的源」。 */
        internal fun currentReader(): ImageReader? = images

        /** 收屏/换屏时递增；流会话用它认出「屏已经不是建会话时那一块」。 */
        internal fun generationSnapshot(): Int = generation

        /** 持续把最新一帧换进 [held]：更老的帧由 acquireLatestImage 自己丢掉。 */
        private fun startPump(reader: ImageReader) {
            pump = Thread {
                var running = true
                while (running && !Thread.currentThread().isInterrupted && reader == images) {
                    val frame = runCatching { reader.acquireLatestImage() }.getOrNull()
                    if (frame != null) {
                        // 取这一帧期间可能已经换过屏（release 把 images 置空、create 换新的），
                        // 那时这一帧属于上一块屏：放进 held 就成了新屏的第一张截图。
                        if (reader == images) latestFrame.put(frame) else runCatching { frame.close() }
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

        // ── 主屏自检与建屏后兼容的常量 ──
        /** 用户眼前那块屏的编号，本模块的落点与自检口径都写死 0。 */
        private const val MAIN_DISPLAY_ID = 0

        /** 主屏快照命令的等待上限：里面串着几条 dumpsys，设备秒级。 */
        private const val MAIN_STATE_TIMEOUT_MS = 4_000L

        /** `wm` 回退命令的等待上限。 */
        private const val POLICY_SHELL_TIMEOUT_MS = 3_000L

        /**
         * 复采窗口：首轮见到破坏后隔这么久再重新检查一次（复核）——用户自己的切应用/
         * 息屏是一次性差异，恢复就只记录；仍在才是稳定结论。
         */
        private const val RESAMPLE_DELAY_MS = 1_500L

        // 取值来自本机 framework 的常量表（dexdump 抄出，`dumpsys package` 不公开这些位）；
        // 组合不再写死成一个 val，按 SDK 与 trusted 条件生成（见 [createFlags]），不按厂商名分支。
        private const val FLAG_PUBLIC = 1
        private const val FLAG_OWN_CONTENT_ONLY = 8
        private const val FLAG_SUPPORTS_TOUCH = 1 shl 6
        private const val FLAG_ROTATES_WITH_CONTENT = 1 shl 7
        private const val FLAG_DESTROY_CONTENT_ON_REMOVAL = 256
        private const val FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS = 512
        private const val FLAG_TRUSTED = 1024
        private const val FLAG_OWN_DISPLAY_GROUP = 2048
        private const val FLAG_ALWAYS_UNLOCKED = 4096
        private const val FLAG_TOUCH_FEEDBACK_DISABLED = 1 shl 13
        private const val FLAG_OWN_FOCUS = 1 shl 14
        private const val FLAG_STEAL_TOP_FOCUS_DISABLED = 1 shl 16

        /** `TOUCH_FEEDBACK_DISABLED` 从 Android 13 起（Build.VERSION_CODES.T，编译期内联）。 */
        private const val FLAG_TOUCH_FEEDBACK_SDK = 33

        /** `OWN_FOCUS | STEAL_TOP_FOCUS_DISABLED` 从 Android 14 起，且只在 trusted 屏上给。 */
        private const val FLAG_OWN_FOCUS_SDK = 34

        /**
         * 建屏 flags 按 SDK 与 trusted 条件生成。纯函数，JVM 单测逐位断言。
         *
         * - 基础集：`PUBLIC | OWN_CONTENT_ONLY | DESTROY_CONTENT_ON_REMOVAL |
         * SHOULD_SHOW_SYSTEM_DECORATIONS | SUPPORTS_TOUCH | ROTATES_WITH_CONTENT`。
         * `SHOULD_SHOW_SYSTEM_DECORATIONS` 先保留为兼容项，不在这里写根因，也不删除——
         * 它留到有设备时做「保留/去掉」的。
         * - trusted 补 `TRUSTED | OWN_DISPLAY_GROUP | ALWAYS_UNLOCKED`。
         * - Android 13+ 补 `TOUCH_FEEDBACK_DISABLED`。
         * - Android 14+ 且 trusted 补 `OWN_FOCUS | STEAL_TOP_FOCUS_DISABLED`：
         * 焦点留在虚拟屏自己的窗口上、又不准它抢主屏的顶层焦点。
         *
         * `SUPPORTS_TOUCH | ROTATES_WITH_CONTENT` 是原有缺的两位：无它们时部分 ROM 把
         * 虚拟屏当不可触摸显示对待，输入落点与显示旋转行为和真屏不一致。
         */
        internal fun createFlags(sdkInt: Int, trusted: Boolean): Int {
            var flags = FLAG_PUBLIC or FLAG_OWN_CONTENT_ONLY or FLAG_DESTROY_CONTENT_ON_REMOVAL or
                FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS or FLAG_SUPPORTS_TOUCH or FLAG_ROTATES_WITH_CONTENT
            if (trusted) {
                flags = flags or FLAG_TRUSTED or FLAG_OWN_DISPLAY_GROUP or FLAG_ALWAYS_UNLOCKED
            }
            if (sdkInt >= FLAG_TOUCH_FEEDBACK_SDK) {
                flags = flags or FLAG_TOUCH_FEEDBACK_DISABLED
            }
            if (trusted && sdkInt >= FLAG_OWN_FOCUS_SDK) {
                flags = flags or FLAG_OWN_FOCUS or FLAG_STEAL_TOP_FOCUS_DISABLED
            }
            return flags
        }
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

    /**
     * 可信虚拟屏的任务枚举与搬迁（面板三动作），纯判据下沉：读侧在 [TrustedDisplay]
     * 上层（`dumpsys activity activities` / `am display move-stack` 走 [ShellRunner]），
     * 解析与失败判定收在本对象，JVM 单测可直接穷举。
     *
     * `dumpsys activity activities` 的相关行（Android 10+）形状：
     * ```
     * Display #45 (activities from top to bottom):
     * *Task{5df1a3e #45 type=standard A=10364:com.ss.android.ugc.aweme ...}
     * *Task{... #46 type=standard A=10364:com.ss.android.ugc.aweme ...}
     * *ActivityRecord{... u0 com.ss.android.ugc.aweme/.main.MainActivity} t46}
     * ```
     * - `Display #N (` 开一个显示段；只认 `displayId == trusted display` 的段；
     * - `*Task{hash #id …}` 一行是一个任务；**缩进深度 0 = RootTask**，嵌套更深的
     * `*Task` 是它的叶子任务；`am display move-stack` 只认 RootTask 号；
     * - 任务的包名优先取行内 ` A=uid:package`（叶子任务都有）；RootTask 行可能没有，
     * 那就落回该 RootTask 辖区（到下一个同深或更浅任务为止）里第一条
     * `ActivityRecord{… uN package/component}` 的包名。
     */
    internal object TrustedTasks {

        /** 显示段首：`Display #45 (`。与 MainDisplayCheck 同一款正则口径。 */
        private val DISPLAY_SECTION = Regex("^Display #(\\d+) \\(")

        /** 不参与面板搬迁/释放的固定排除包 —— 宿主自家 App 与 systemui。 */
        private const val HOST_PACKAGE = "com.dshbox.app"
        private const val SYSTEMUI_PACKAGE = "com.android.systemui"

        /**
         * 任务行：`*Task{hash #id …}` 或设备的 `* Task{hash #id …}`（星号后可有空格，
         * ），`type=`（task type，识别 home 根任务用）与 `A=` 段都
         * 可有可无。`Task{` 前的 `[*\s]*` 同时吞掉行首的星号与空格（find 语义，锚不
         * 落在行首），缩进深度由调用方按原始行宽另行计算。
         */
        private val TASK_LINE = Regex(
            "[*\\s]*Task\\{\\S+ #(\\d+)" +
                "(?:.*?\\btype=([A-Za-z_-]+))?" +
                "(?:.*?\\bA=(\\d+):([A-Za-z][A-Za-z0-9_.]*))?",
        )

        /**
         * 活动行：`*ActivityRecord{hash uN package/component}`、设备的
         * `topResumedActivity=ActivityRecord{...}`与同段其他
         * `ActivityRecord` 行 —— `ActivityRecord{` 前允许 `* ` 与任意 `key=` 前缀，
         * 包名/短名从花括号里的 `uN package/` 取。
         */
        private val ACTIVITY_RECORD =
            Regex("(?:[*\\s]|[^\\s\\{]*=)*ActivityRecord\\{\\S+ u\\d+ ([A-Za-z][A-Za-z0-9_.]*)/([^}\\s]+)")

        /** 一屏任务的枚举读数：任务号、包名（可空）、顶层活动短名（可空）与所属 RootTask 号。 */
        data class Entry(
            val taskId: Int,
            val packageName: String?,
            val activity: String?,
            /** `am display move-stack` 只认 RootTask 号：root 行是自己，叶子是它的父 root。 */
            val rootTaskId: Int,
            /** 任务行里的 `type=`（standard/home/…；行内没有或 JSON 回读时为 null）。 */
            val taskType: String? = null,
        )

        /**
         * 任务枚举摊成回包正文 JSON（协议的一部分，宿主按它回 /tasks）：
         * `{"displayId":45,"tasks":[{"taskId":703,"package":"…","label":"…","activity":"…"}]}`。
         * label 由**宿主** PackageManager 映射（服务端进程不持包名→标签表），这里先原样
         * 给 package，宿主拿不到 label 时也用它兜底显示。纯函数，JVM 直测。
         */
        internal fun tasksJson(displayId: Int, entries: List<Entry>): String {
            val array = StringBuilder("[")
            entries.forEachIndexed { index, entry ->
                if (index > 0) array.append(',')
                array.append("{\"taskId\":").append(entry.taskId)
                array.append(",\"rootTaskId\":").append(entry.rootTaskId)
                // taskType 一并带出（standard/home/…），宿主/测试可对账过滤。
                array.append(",\"taskType\":").append(jsonString(entry.taskType))
                array.append(",\"package\":").append(jsonString(entry.packageName))
                array.append(",\"label\":").append(jsonString(entry.packageName))
                array.append(",\"activity\":").append(jsonString(entry.activity))
                array.append('}')
            }
            array.append(']')
            return "{\"displayId\":$displayId,\"tasks\":$array}"
        }

        /** JSON 字符串转义（org.json 在 JVM 单测里是平台桩，这里手写转义）。 */
        private fun jsonString(value: String?): String {
            value ?: return "null"
            val escaped = value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
            return "\"$escaped\""
        }

        /**
         * [tasksJson] 的回读：把任务回包正文解析回 [Entry] 列表（搬迁路径在宿主侧复用，
         * 叶子任务先解析 root 再发 move）。畸形/缺字段按条目缺省处理，不抛。
         */
        internal fun parseTasksJson(tasksJson: String): List<Entry> {
            val parsed = runCatching { org.json.JSONObject(tasksJson) }.getOrNull() ?: return emptyList()
            val tasks = parsed.optJSONArray("tasks") ?: return emptyList()
            return buildList {
                for (i in 0 until tasks.length()) {
                val task = tasks.optJSONObject(i) ?: continue
                val taskId = task.optInt("taskId", -1)
                if (taskId < 0) continue
                val pkg = task.optString("package")
                val activity = task.optString("activity")
                val root = task.optInt("rootTaskId", taskId)
                add(
                    Entry(
                        taskId,
                        pkg.ifEmpty { null },
                        activity.ifEmpty { null },
                        root,
                        // taskType 回读保留（缺键回 null）。
                        task.optString("taskType").ifEmpty { null },
                    ),
                )
                }
            }
        }

        /**
         * 两遍扫描的中间账：任务行与它辖区（到下一任务行前）里第一条活动的读数。
         */
        private class RawTask(
            val taskId: Int,
            val depth: Int,
            /** 任务行里的 `type=`（standard/home/…；旧 ROM 可能没有）。 */
            val taskType: String?,
            val inlinePackage: String?,
            /** 辖区里第一条 ActivityRecord 的类短名（叶子任务的顶层活动）。 */
            var activity: String? = null,
            /** 没有行内 `A=` 时由辖区第一条活动回填的包名。 */
            var recordPackage: String? = null,
        )

        /**
         * 从一段 `dumpsys activity activities` 输出里枚举**指定显示**的任务。
         *
         * - `Display #N (` 开段，只取 `displayId == 指定` 的段；段内没有任务回空列表
         * （「问成了、屏上没任务」；「命令没跑成」由上层以退出码区分）；
         * - `*Task{hash #id …}` 一行一个任务，行首缩进最浅的是 RootTask，嵌套更深的是
         * 它的叶子任务（`am display move-stack` 只认 RootTask 号）；
         * - 包名优先取行内 ` A=uid:package`（叶子任务都有）；RootTask 行可能没有，
         * 落回该任务辖区（到下一任务行之前）里第一条 `ActivityRecord` 的包名；
         * - 全段任务缩进齐平（某些 ROM 不给嵌套缩进）时每条任务都按 root 处理；
         * - 结果按 [isAppTask] 过滤 —— home 根任务、launcher/宿主/systemui
         * 一律不进列表，避免"移到主屏"把 home/launcher 根任务搬走导致虚拟屏变形，
         * 或把宿主自家 App 搬走导致它重启；
         * - 被排除 root 的**整棵子树一并丢弃** —— 否则叶子仍存在被排除的
         * root 号，`panelTaskMove` 搬叶子时 `rootOf` 会把 move-stack 打到 launcher/home
         * 根上（整棵系统栈被搬走）。
         */
        internal fun parse(dump: String, displayId: Int): List<Entry> {
            if (displayId < 0) return emptyList()
            var section = -1
            val tasks = ArrayList<RawTask>()
            for (raw in dump.lineSequence()) {
                val trimmed = raw.trim()
                DISPLAY_SECTION.find(trimmed)?.let { section = it.groupValues[1].toInt() }
                if (section != displayId) continue
                val task = TASK_LINE.find(trimmed)
                if (task != null) {
                    val id = task.groupValues[1].toIntOrNull() ?: continue
                    tasks.add(
                        RawTask(
                            taskId = id,
                            depth = raw.length - raw.trimStart().length,
                            taskType = task.groupValues[2].ifEmpty { null },
                            // 组没参与匹配时 groupValues 给 ""，必须落 null ——
                            // 否则 `inlinePackage ?: recordPackage` 永远取到空串，辖区
                            // ActivityRecord 的包名回填失效，过滤对宿主/systemui/launcher
                            // 也随之失效（空串既不等于排除包也不含 launcher）。
                            inlinePackage = task.groupValues[4].ifEmpty { null },
                        ),
                    )
                    continue
                }
                val record = ACTIVITY_RECORD.find(trimmed) ?: continue
                val last = tasks.lastOrNull() ?: continue
                if (last.activity == null) last.activity = record.groupValues[2]
                if (last.inlinePackage == null && last.recordPackage == null) {
                    last.recordPackage = record.groupValues[1]
                }
            }
            if (tasks.isEmpty()) return emptyList()
            // 缩进最浅的深度就是 RootTask 深度（dumpsys 的缩进是两个空格一档，但不数档）。
            val rootDepth = tasks.minOf { it.depth }
            val entries = buildList {
                // root 栈：叶子按"最近的、缩进比自己浅的任务"认父；缩进齐平全按 root。
                val stack = ArrayDeque<Pair<Int, Int>>()  // depth to taskId
                for (task in tasks) {
                    while (stack.isNotEmpty() && stack.last().first >= task.depth) stack.removeLast()
                    val root = if (task.depth == rootDepth) {
                        task.taskId
                    } else {
                        stack.lastOrNull()?.second ?: task.taskId
                    }
                    stack.addLast(task.depth to task.taskId)
                    add(
                        Entry(
                            task.taskId,
                            task.inlinePackage ?: task.recordPackage,
                            task.activity,
                            root,
                            task.taskType,
                        ),
                    )
                }
            }
            // 过滤放在 root 认父之后，但**按 root 子树整棵剔**：root 自己被排除
            // （home/launcher/宿主/systemui）时它的全部叶子一并丢弃 —— 叶子若保留，
            // `rootOf` 仍会给出被排除的 root 号，move-stack 就会去搬系统根任务。
            val keptRoots = entries
                .filter { it.rootTaskId == it.taskId && isAppTask(it) }
                .mapTo(HashSet()) { it.taskId }
            return entries.filter { it.rootTaskId in keptRoots && isAppTask(it) }
        }

        /**
         * 只把"真实应用任务"留给面板的搬迁/释放面 —— home 根任务
         * （`type=home`）、launcher（包名/活动名含 launcher，含 SecondaryDisplayLauncher）、
         * 宿主自家 App（`com.dshbox.app`）与 systemui 一律排除。纯判据，JVM 直测。
         */
        internal fun isAppTask(entry: Entry): Boolean {
            // home 根任务先按 task type 排除：包名/活动名都可能缺行内读数，
            // `type=home` 是唯一稳定记号 —— 搬它会掀掉虚拟屏的"桌面层"导致变形。
            if (entry.taskType?.lowercase() == "home") return false
            entry.packageName?.lowercase()?.let { pkg ->
                if (pkg == HOST_PACKAGE || pkg == SYSTEMUI_PACKAGE) return false
                if (pkg.contains("launcher")) return false
            }
            // 包名缺行内 A= 且辖区没有 ActivityRecord 时只能靠 type/activity 兜底：
            // 活动名含 launcher（如 .SecondaryDisplayLauncher）同样不算真实应用。
            entry.activity?.lowercase()?.let { activity ->
                if (activity.contains("launcher")) return false
            }
            return true
        }

        /**
         * 要搬的那条任务落成 `am display move-stack` 的参数（宿主侧，搬前判）：
         * root 原样、叶子换它的 RootTask 号；任务不在枚举里（不在虚拟屏上）回 null，
         * 调用方回 409。纯函数，JVM 直测。
         */
        internal fun rootOf(taskId: Int, entries: List<Entry>): Int? =
            entries.firstOrNull { it.taskId == taskId }?.rootTaskId

        /**
         * `am display move-stack` 的搬迁结果判定（宿主侧，搬后判）：退出码 0 且
         * stderr/stdout 都不带平台的 "Unknown rootTaskId"/"Exception" 字样才算成；
         * `am` 把"任务不存在"写在 stderr 里而退出码仍可能给 0，两侧都要过。
         */
        internal fun moveSucceeded(exitCode: Int, stdout: String, stderr: String): Boolean {
            if (exitCode != 0) return false
            val combined = stdout + stderr
            return !combined.contains("Unknown rootTaskId") && !combined.contains("Exception")
        }

        /**
         * `forceStopCommands` 已随 move-first 释放路径一起删除——当前手动与空闲 release 都是单次收屏（AOSP 按 `DESTROY_CONTENT_ON_REMOVAL` finish
         * 屏上任务），不再有"搬不走的包 force-stop 兜底"这条死路径，也不要恢复它。
         *
         * main 方向搬迁成功后的 windowing mode 校正命令（best-effort，
         * 宿主侧 [ShizukuBackend] 逐条尝试）。优先 `am start --windowingMode 1
         * --display 0 --task <root>`（对既有任务归位，不新建实例）；部分平台不接受
         * 无 `-n` 的形式，兜底 `wm set-display-windowing-mode -d 0 1`（display 0 全局
         * 切标准多窗模式）。非法 rootTaskId 回空列表（无可校正）。纯函数，JVM 直测。
         */
        internal fun windowingFixCommands(rootTaskId: Int): List<String> {
            if (rootTaskId < 0) return emptyList()
            return listOf(
                "am start --windowingMode 1 --display 0 --task $rootTaskId",
                "wm set-display-windowing-mode -d 0 1",
            )
        }
    }


    /**
     * 实时流会话（只读预览）：把可信屏 ImageReader 的最新帧编成 H.264，
     * 以 fMP4 分片写进宿主递来的 pipe。
     *
     * 边界（与设计 / 对齐）：
     * - **不建第二块屏**：帧全部来自 [TrustedDisplay] 现有的 ImageReader 与 pump；
     * - **latest-frame-wins**：只消费 pump 换进 `held` 的那一帧，不排队、不攒缓冲，
     * 编码慢就丢帧，实时性优先于完整性；
     * - 单会话：已在跑时 [start] 拒绝，[stop] 或任一退出条件（屏被收、宿主读端关闭、
     * 会话到期、线程被中断）都释放编码器、pipe 写端与线程 —— 全部收在同一个
     * finally 里，没有"半开的流"。
     *
     * 与截图/录屏的关系：pump 每 40ms 换一帧进 `held`，本会话与 [TrustedDisplay.capture]
     * 从同一个槽位取帧。流开着的时候截图仍能拿到帧（pump 的补给快于两者的取用），
     * 但两者确实在竞争同一个 latest 槽 —— 这是单 ImageReader 的当前取舍，多消费端
     * 的扇出留到  后续工作包。
     *
     * 本对象**没有任何 JVM 可测路径**（MediaCodec、Image、pipe 都是设备成员）；判据的
     * 可测部分（fMP4 形状、Annex-B→AVCC、token/HTTP 契约）全部下沉到了纯 Kotlin 的
     * FragmentedMp4Writer 与 LoopbackStreamServer，这里只在设备上验证。
     */
    internal object StreamSession {

        /**
         * 一代会话的全部可动部件。**收场责任在持有读端的一方（宿主）**：worker 阻塞
         * 在 pipe write 上时，本进程内对写端做 close/flush 都可能自己挂死（close 先
         * flush，flush 对满管道一样阻塞）—— 解阻塞的唯一办法是宿主关掉读端，让内核
         * 把阻塞中的 write 唤醒成 EPIPE。因此本类的 stop/watchdog **只中断、只等哨、
         * 不碰管道**；管道的关闭只发生在 worker 自己的 finally（且先关原始 fd）。
         */
        private class Session(
            @Volatile var pipeOutput: java.io.OutputStream?,
            @Volatile var pipeFd: ParcelFileDescriptor?,
            /** 帧循环收尾哨：finally 里放行；stop/watchdog 等它，不赌线程 join。 */
            val released: java.util.concurrent.CountDownLatch,
            val startedAtMs: Long,
            /** 屏租约：会话在跑期间空闲计时器不得收屏；worker 的 finally 归还。 */
            val leaseId: Int,
            /** fMP4 样本时间轴的会话零点（输出时钟用，见 [nextOutputPtsUs]）。 */
            val sessionStartNs: Long,
        ) {
            /** 线程在入账那一刻补上：stop/watchdog/START 守卫都读它。 */
            @Volatile
            lateinit var thread: Thread

            internal fun copyForThread(thread: Thread): Session = apply { this.thread = thread }

            /** 已写进 fMP4 的最后一个样本时刻（µs，会话时钟）。 */
            @Volatile
            var lastOutputPtsUs = -1L

            /** stop/watchdog 已请求过收场：重复请求回结构化的"仍在排水"，不再叠加动作。 */
            val stopping = java.util.concurrent.atomic.AtomicBoolean(false)

            /** csd 到手建好的封装器：**per-session** —— 旧 worker 的 finally 碰不到新一届的。 */
            @Volatile
            var writer: interlock.relay.core.exec.FragmentedMp4Writer? = null

            /** 最后一帧入队 pts（µs，帧时钟）：EOS 与它同钟。per-session。 */
            @Volatile
            var lastQueuedPtsUs = -1L
        }

        /** 当前一代会话；null 表示没有。start/stop/watchdog/收尾都在 [StreamSession] 的锁内摘/挂账。 */
        @Volatile
        private var session: Session? = null

        /** stop/watchdog 请求了收场但 worker 还在排水的结构化回执。 */
        private const val DRAINING_NOTE =
            "stream stop requested; the encoder is still draining and releases as soon as the host " +
                "closes the pipe read end (its own watchdog does the same) - no handle is left blocking"

        /**
         * 开会话。回 (0, 元数据 JSON) 或 (<0, 原因)。
         *
         * MediaCodec 的挑选与 configure 在 binder 线程上同步做（数十毫秒量级，装得进
         * 显示事务的等待上限）：做不成当场回错并关掉 fd，不让宿主拿着一个"已经开始
         * 却永远没有字节"的管道。帧循环在会话线程里跑，SPS/PPS 要等编码器吐出
         * csd 才有 —— 初始化段在那之后才落笔。
         */
        fun start(writeFd: ParcelFileDescriptor?): Pair<Int, String> = synchronized(this) {
            // 并发的两次 START 在这里串行；**旧 worker 没退场前一律拒绝**（含 stopping
            // 中的）：codec 是有限资源，且两届线程同时活着时，"哪一届在说话"无从对账。
            if (session?.thread?.isAlive == true) {
                runCatching { writeFd?.close() }
                return@synchronized -1 to "a stream session is already running (or still draining); stop it first"
            }
            val descriptor = writeFd ?: return@synchronized -1 to "no pipe write end"
            val (sourceWidth, sourceHeight) = TrustedDisplay.size()
            if (TrustedDisplay.currentReader() == null || sourceWidth <= 0 || sourceHeight <= 0) {
                runCatching { descriptor.close() }
                return@synchronized -1 to "no trusted display to stream from; create it first"
            }
            // 优先按 1280 长边 / 8Mbps 配：虚拟屏源更大时不再被压到 720 发糊。旧设备
            // 的编码器吃不下 1280 时**退回 720 / 4Mbps**——从"能看"降级，而不是直接
            // 拒开流。metadata 里的 width/height 始终写实际编码尺寸，客户端照它判 Level。
            var (width, height) = encodeSize(sourceWidth, sourceHeight)
            // 无 B 帧 + Baseline：预览的每一帧都要"到即播"。编码器吃不下这两个约束时
            // 按原样配（逐项 runCatching 兜）。
            var codec = AvcEncoderFactory.pick(width, height, FPS, BITS_PER_SECOND, I_FRAME_SECONDS, bFrames = false)
            if (codec == null) {
                val (fallbackWidth, fallbackHeight) = encodeSize(sourceWidth, sourceHeight, FALLBACK_TARGET_LONG_SIDE)
                codec = AvcEncoderFactory.pick(fallbackWidth, fallbackHeight, FPS, FALLBACK_BITS_PER_SECOND, I_FRAME_SECONDS, bFrames = false)
                if (codec == null) {
                    runCatching { descriptor.close() }
                    return@synchronized -1 to "no NV12 AVC encoder for " + width + "x" + height +
                        " or " + fallbackWidth + "x" + fallbackHeight + " on this device"
                }
                // 只进 logcat（本进程没有运行日志写口），不进"运行日志"页。
                android.util.Log.w(
                    TAG,
                    "avc encoder rejected " + width + "x" + height + "@" + BITS_PER_SECOND +
                        "; falling back to " + fallbackWidth + "x" + fallbackHeight + "@" + FALLBACK_BITS_PER_SECOND,
                )
                width = fallbackWidth
                height = fallbackHeight
            }
            val started = runCatching {
                codec.start()
                val input = codec.inputFormat
                input.integerOrDefault(android.media.MediaFormat.KEY_STRIDE, width) to
                    input.integerOrDefault(android.media.MediaFormat.KEY_SLICE_HEIGHT, height)
            }.getOrElse {
                runCatching { codec.release() }
                runCatching { descriptor.close() }
                return@synchronized -1 to "encoder start failed: " + it.javaClass.simpleName
            }
            val stride = started.first
            val sliceHeight = started.second
            val frameBytes = stride * sliceHeight * 3 / 2
            val output = java.io.BufferedOutputStream(
                FileOutputStream(descriptor.fileDescriptor),
                OUT_BUFFER_BYTES,
            )
            val latch = java.util.concurrent.CountDownLatch(1)
            val startedAt = android.os.SystemClock.elapsedRealtime()
            val ownGeneration = TrustedDisplay.generationSnapshot()
            val lease = TrustedDisplay.acquireStreamLease()
            val active = Session(
                output, descriptor, latch, startedAt, lease,
                sessionStartNs = android.os.SystemClock.elapsedRealtimeNanos(),
            )
            val runner = Thread({
                run(
                    codec, output, descriptor,
                    plane = ByteArray(frameBytes),
                    stride = stride, sliceHeight = sliceHeight,
                    width = width, height = height,
                    ownGeneration = ownGeneration, latch = latch, active = active,
                )
            }, "relay-stream-encode")
            // 会话先整体入账、线程再 start：账目必须在 worker 可能运行之前全部就位。
            // active 由 start 闭包直接递给 worker（不回读全局），非空性由构造保证。
            val withThread = active.copyForThread(runner)
            session = withThread
            runner.isDaemon = true
            val threadStart = runCatching { runner.start() }
            if (threadStart.isFailure) {
                // 线程没起来：会话账、租约、编码器与管道都要退掉，不留一条"入账了但没人跑"的会话。
                session = null
                TrustedDisplay.releaseStreamLease(lease)
                runCatching { descriptor.close() }
                runCatching { codec.release() }
                return@synchronized -1 to
                    "stream thread did not start: " + (threadStart.exceptionOrNull()?.javaClass?.simpleName ?: "unknown")
            }
            // 服务端 watchdog 只是**有读端时的补充**（中断可中断段）：worker 堵在 pipe
            // write 上时它救不了 —— 那一半在宿主：宿主 watchdog 到点关读端，或宿主进程
            // 死亡由内核关 fd。此处没有任何"关写端解阻塞"的虚假动作。
            Thread({ watchdog(active) }, "relay-stream-watchdog").apply { isDaemon = true; start() }
            0 to metadata(width, height, sourceWidth, sourceHeight)
        }

        /**
         * 停会话。**不持监视器等哨、不碰管道**：close 会先 flush，对满管道同样阻塞，
         * 挂死的是 binder 线程。这里只置 stopping、中断、等哨：
         * - 哨放行（宿主已关读端 → EPIPE 把 worker 唤醒并走完 finally）→ 1，全部已释放；
         * - 哨没放行（worker 还堵在写上）→ **-2 + 结构化说明**，明确"收场由宿主关读端
         * 完成"，绝不挂死 binder 线程，也绝不留一个说谎的 1。幂等：重复 stop 回 -2。
         */
        fun stop(): Pair<Int, String> {
            val current = synchronized(this) { session } ?: return 0 to "no stream session"
            if (!current.stopping.compareAndSet(false, true)) {
                return -2 to DRAINING_NOTE
            }
            current.thread.interrupt()
            val drained = current.released.await(STOP_JOIN_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (drained) {
                synchronized(this) { if (session === current) session = null }
                return 1 to ""
            }
            // 账不摘：worker 退场时按身份自查清账；这一届没走完之前 START 会被拒。
            return -2 to DRAINING_NOTE
        }

        /** watchdog 的等待量：纯逻辑，JVM 可测（到点即收、提前收场零等待、过点钳正）。 */
        internal fun watchdogDelayMs(startedAtMs: Long, nowMs: Long): Long =
            (startedAtMs + MAX_SESSION_MS - nowMs).coerceIn(1L, MAX_SESSION_MS)

        /**
         * 服务端 watchdog：**有读端时的补充**。中断让可中断段（sleep、入队前的检查）
         * 尽快收；worker 若堵在 pipe write 上，它救不了 —— 解阻塞在宿主（宿主 watchdog
         * 关读端 / 宿主进程死亡由内核关 fd）。这里绝不关管道，也不声称解了无读端阻塞。
         */
        private fun watchdog(active: Session) {
            val waitMs = watchdogDelayMs(active.startedAtMs, android.os.SystemClock.elapsedRealtime())
            if (active.released.await(waitMs, java.util.concurrent.TimeUnit.MILLISECONDS)) return
            if (active.stopping.compareAndSet(false, true)) {
                active.thread.interrupt()
            }
            // 等一小会儿：worker 若因此退场就按身份清账；没退（堵在写上）就留给宿主。
            if (active.released.await(STOP_JOIN_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                synchronized(this) { if (session === active) session = null }
            }
        }

        // ── 帧循环与退出路径 ──

        private fun run(
            codec: android.media.MediaCodec,
            output: java.io.OutputStream,
            descriptor: ParcelFileDescriptor,
            plane: ByteArray,
            stride: Int,
            sliceHeight: Int,
            width: Int,
            height: Int,
            ownGeneration: Int,
            latch: java.util.concurrent.CountDownLatch,
            active: Session,
        ) {
            var firstSyncQueued = false
            var hasPlane = false
            var lastVersion = -1L
            var lastPtsUs = -1L
            // 会话自己的单调时钟：不再拿 Image.timestamp 当 pts。那个时间戳来自屏幕
            // 合成侧，静止画面/复用帧上会回退或跳变；fMP4 写出器把回退压成 duration=1，
            // MSE 收到两段 tfdt 相同的样本就报 SourceBuffer 错误（tfdt 0,0,...）。
            val sessionStartNs = android.os.SystemClock.elapsedRealtimeNanos()
            fun nowUs(): Long = (android.os.SystemClock.elapsedRealtimeNanos() - sessionStartNs) / 1_000
            var lastPushAt = android.os.SystemClock.elapsedRealtime()
            val startedAt = lastPushAt
            val info = android.media.MediaCodec.BufferInfo()
            try {
                while (!Thread.currentThread().isInterrupted) {
                    // 屏被收掉/换掉（generation 变了）就到此为止：源已经不归这个会话了。
                    if (TrustedDisplay.generationSnapshot() != ownGeneration) break
                    TrustedDisplay.touch()
                    val now = android.os.SystemClock.elapsedRealtime()
                    // 读不消费：帧仍留给下一场预览/截图；锁内只做 NV12 铺装。
                    // 代际没变说明还是上一帧——静止画面不能每 40ms 当新帧重复入队。
                    val gotFrame = TrustedDisplay.withLatestFrameVersioned { frame, version ->
                        if (version == lastVersion) return@withLatestFrameVersioned false
                        lastVersion = version
                        fillPlane(frame, plane, stride, sliceHeight, width, height)
                        lastPtsUs = maxOf(nowUs(), lastPtsUs + 1)
                        true
                    } ?: false
                    if (gotFrame) {
                        if (!queuePlane(codec, plane, lastPtsUs, firstSyncQueued, active)) break
                        firstSyncQueued = true
                        hasPlane = true
                        lastPushAt = now
                        drain(codec, info, output, width, height, active)
                        // 每帧一冲：预览要新鲜度，压在 64K 缓冲里的首帧会让播放端白等。
                        runCatching { output.flush() }
                    } else if (hasPlane && now - lastPushAt >= FRAME_REPEAT_MS) {
                        // 屏不刷新就没有新帧：静止画面就是把上一帧再放一遍，而不是
                        // 让编码器空等 30 秒、由插件空闲看门狗把流掐断。
                        lastPtsUs = maxOf(nowUs(), lastPtsUs + 1)
                        if (!queuePlane(codec, plane, lastPtsUs, firstSyncQueued, active)) break
                        firstSyncQueued = true
                        lastPushAt = now
                        drain(codec, info, output, width, height, active)
                        runCatching { output.flush() }
                    } else if (!hasPlane && now - startedAt >= BLACK_FRAME_AFTER_MS) {
                        // 从未拿到过帧（屏刚建、还没画）：先送一帧黑帧让 MSE 能 open，
                        // 而不是让客户端空等 30 秒后由空闲看门狗掐断。
                        fillBlackPlane(plane, stride, sliceHeight)
                        lastPtsUs = maxOf(nowUs(), 0L)
                        if (!queuePlane(codec, plane, lastPtsUs, firstSyncQueued, active)) break
                        firstSyncQueued = true
                        hasPlane = true
                        lastPushAt = now
                        drain(codec, info, output, width, height, active)
                        runCatching { output.flush() }
                    } else {
                        if (!sleepQuietly(FRAME_POLL_MS)) break
                    }
                }
                // 会话收尾：送 EOS 并把剩余包抽干，让最后一段 fMP4 落出去。
                runCatching {
                    val index = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
                    if (index >= 0) {
                        // EOS 与帧同一块钟（帧时钟的最后一个 pts），换表就是时间倒流。
                        codec.queueInputBuffer(
                            index, 0, 0,
                            active.lastQueuedPtsUs.coerceAtLeast(0L),
                            android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                        )
                    }
                }
                drain(codec, info, output, width, height, active)
            } catch (broken: Throwable) {
                // 这个进程同时服务三条 shell 能力与那块屏：线程里逃出一个未捕获异常就是
                // 整个用户服务没命。任何失败（宿主关读端 → EPIPE、csd 形状不对、铺装
                // 越界、编码器内部错）都在这里收成"会话结束"，后果到此为止。
                if (broken is InterruptedException || broken.cause is InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            } finally {
                runCatching { codec.stop() }
                runCatching { codec.release() }
                // **先关原始 fd**：之后的 flush/close 立刻失败（runCatching 兜住），绝不
                // 给 BufferedOutputStream 一次"对满管道 flush"的自挂机会 —— 收场路径上
                // 任何一次阻塞都是 worker 永远到不了 latch.countDown。
                runCatching { descriptor.close() }
                runCatching { output.flush() }
                runCatching { output.close() }
                active.writer = null
                // 会话结束即归还屏租约。**不丢最后一帧**：静止画面没有新帧，
                // 下一场预览要靠它起步；帧由持有器在换屏/收屏时关闭。
                TrustedDisplay.releaseStreamLease(active.leaseId)
                synchronized(this) { if (session === active) session = null }
                latch.countDown()
            }
        }

        /**
         * 把一帧 Image 铺进调用方的 NV12 plane（不分配整屏，不 close 帧）。
         * 像素格式不兼容时抛错——截短送进编码器是一幅斜掉却绝不报错的画面。
         */
        private fun fillPlane(
            frame: android.media.Image,
            plane: ByteArray,
            stride: Int,
            sliceHeight: Int,
            width: Int,
            height: Int,
        ) {
            if (!interlock.relay.core.exec.Nv12.fillFromImage(frame, plane, stride, sliceHeight, width, height)) {
                throw java.io.IOException("frame plane is not RGBA-compatible for NV12 packing")
            }
        }

        /** 把当前 plane 入队；回 false 表示编码器缓冲装不下一帧，会话要收。 */
        private fun queuePlane(
            codec: android.media.MediaCodec,
            plane: ByteArray,
            ptsUs: Long,
            firstSyncQueued: Boolean,
            active: Session,
        ): Boolean {
            val index = runCatching { codec.dequeueInputBuffer(INPUT_TIMEOUT_US) }.getOrDefault(-1)
            if (index < 0) return true
            val input = runCatching { codec.getInputBuffer(index) }.getOrNull() ?: return true
            if (input.capacity() < plane.size) {
                throw java.io.IOException(
                    "encoder input buffer (" + input.capacity() + ") smaller than one NV12 frame (" + plane.size + ")",
                )
            }
            input.clear()
            input.put(plane, 0, plane.size)
            val flags = if (firstSyncQueued) 0 else android.media.MediaCodec.BUFFER_FLAG_SYNC_FRAME
            runCatching { codec.queueInputBuffer(index, 0, plane.size, ptsUs, flags) }
            active.lastQueuedPtsUs = ptsUs
            return true
        }

        /** 从未有过帧时的占位黑帧：直铺 Y=16、UV=128（limited range 的黑）。 */
        private fun fillBlackPlane(plane: ByteArray, stride: Int, sliceHeight: Int) {
            val luma = (stride * sliceHeight).coerceIn(0, plane.size)
            java.util.Arrays.fill(plane, 0, luma, 16.toByte())
            java.util.Arrays.fill(plane, luma, plane.size, 128.toByte())
        }

        /**
         * 已写进 fMP4 的下一个样本时刻（µs）：会话输出时钟，严格递增。
         *
         * 编码器回传的 `presentationTimeUs` 在设备上会重复/跳变（0,0,4486...760966），
         * 不能作为 fMP4 的 tfdt 来源；写出器只负责封装，时间轴由会话统一给。
         */
        private fun nextOutputPtsUs(active: Session): Long {
            val nowUs = (android.os.SystemClock.elapsedRealtimeNanos() - active.sessionStartNs) / 1_000
            active.lastOutputPtsUs = maxOf(nowUs, active.lastOutputPtsUs + 1)
            return active.lastOutputPtsUs
        }

        /**
         * 抽编码器的产出并落 fMP4。writer 只在**首次** INFO_OUTPUT_FORMAT_CHANGED 建
         * （csd 只在那一次给），且只落在**本会话**身上 —— 后续 format change 忽略
         * （再建一次就是第二份初始化段 + sequence 复位）。
         */
        private fun drain(
            codec: android.media.MediaCodec,
            info: android.media.MediaCodec.BufferInfo,
            output: java.io.OutputStream,
            width: Int,
            height: Int,
            active: Session,
        ) {
            while (true) {
                val code = runCatching { codec.dequeueOutputBuffer(info, 0) }.getOrDefault(
                    android.media.MediaCodec.INFO_TRY_AGAIN_LATER,
                )
                when {
                    code == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (active.writer != null) continue
                        // csd 到手才能落初始化段。csd-1 缺席的实现在 csd-0 里连着 PPS；
                        // 有些实现还在 SPS 前垫 AUD/SEI，逐条按 NAL type 认，不按位置猜。
                        val format = codec.outputFormat
                        val csd0 = format.byteArrayOf("csd-0")
                        val csd1 = format.byteArrayOf("csd-1")
                        val sps = csdNal(csd0, NAL_SPS) ?: csdNal(csd1, NAL_SPS)
                        val pps = csdNal(csd1, NAL_PPS) ?: csdNal(csd0, NAL_PPS)
                        if (sps == null || pps == null) {
                            throw java.io.IOException("encoder emitted no usable SPS/PPS in csd")
                        }
                        val created = interlock.relay.core.exec.FragmentedMp4Writer(
                            width, height, fps = FPS, sps = sps, pps = pps, out = output,
                        )
                        created.writeInit()
                        active.writer = created
                    }

                    code >= 0 -> {
                        val current = active.writer
                        if (info.size > 0 && current != null) {
                            val payload = runCatching { codec.getOutputBuffer(code) }.getOrNull()
                            if (payload != null) {
                                payload.position(info.offset)
                                payload.limit(info.offset + info.size)
                                val bytes = ByteArray(info.size)
                                payload.get(bytes)
                                val key = info.flags and android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                                // 一帧写坏（样本只剩起始码之类）丢这一帧：会话继续。
                                // fMP4 的时间轴用会话输出时钟，**不用** info.presentationTimeUs：
                                // 编码器回传的 pts 会 0,0,... 后跳到 7.2s，写出器压成
                                // duration=1，MSE 直接 SourceBuffer 错误。输出时钟严格递增。
                                runCatching { current.addSample(bytes, key, nextOutputPtsUs(active)) }
                            }
                        }
                        runCatching { codec.releaseOutputBuffer(code, false) }
                        if (info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }

                    else -> return
                }
            }
        }

        /** csd 里按 NAL type 认领一条；认不出回 null，让会话如实收场而不是猜一个错头。 */
        private fun csdNal(csd: ByteArray?, type: Int): ByteArray? {
            csd ?: return null
            val nals = interlock.relay.core.exec.AvcNalUnits.split(csd)
            return nals.firstOrNull { it.isNotEmpty() && (it[0].toInt() and 0x1F) == type }
        }

        /** 编码尺寸：长边压到目标（默认 1280，720 回退时传 [FALLBACK_TARGET_LONG_SIDE]），两边取偶；源不超过目标时只取偶、不放大。 */
        internal fun encodeSize(sourceWidth: Int, sourceHeight: Int, targetLongSide: Int = TARGET_LONG_SIDE): Pair<Int, Int> {
            val longest = maxOf(sourceWidth, sourceHeight)
            if (longest <= targetLongSide) {
                // 不放大，但至少 2×2：1×1 的源取偶会变 0×0，编码器当场拒。
                return (sourceWidth and 1.inv()).coerceAtLeast(2) to
                    (sourceHeight and 1.inv()).coerceAtLeast(2)
            }
            val width = (sourceWidth.toDouble() * targetLongSide / longest).toInt().coerceAtLeast(2)
            val height = (sourceHeight.toDouble() * targetLongSide / longest).toInt().coerceAtLeast(2)
            return (width and 1.inv()) to (height and 1.inv())
        }

        /** 元数据带源尺寸：区分"编码目标 1280"与"虚拟屏源本身更小（不放大）"两种场景。 */
        private fun metadata(width: Int, height: Int, sourceWidth: Int, sourceHeight: Int): String =
            org.json.JSONObject()
                .put("mime", "video/mp4")
                .put("codec", "avc")
                .put("width", width)
                .put("height", height)
                .put("sourceWidth", sourceWidth)
                .put("sourceHeight", sourceHeight)
                .put("fps", FPS)
                .put("keyframeIntervalSeconds", I_FRAME_SECONDS)
                .toString()

        private fun sleepQuietly(ms: Long): Boolean = try {
            Thread.sleep(ms)
            true
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

        private fun android.media.MediaFormat.integerOrDefault(key: String, fallback: Int): Int =
            runCatching { getInteger(key) }.getOrNull() ?: fallback

        /** csd 项取成字节数组：MediaFormat 存的是 ByteBuffer，直接搬一次免得复用踩 position。 */
        private fun android.media.MediaFormat.byteArrayOf(key: String): ByteArray? = runCatching {
            val buffer = getByteBuffer(key) ?: return null
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            bytes
        }.getOrNull()

        private const val FPS = 15
        private const val BITS_PER_SECOND = 8_000_000
        private const val I_FRAME_SECONDS = 2
        private const val TARGET_LONG_SIDE = 1280
        /** 1280 编码器配不上时的回退档：与原有 720 / 4Mbps 相同。 */
        private const val FALLBACK_TARGET_LONG_SIDE = 720
        private const val FALLBACK_BITS_PER_SECOND = 4_000_000
        private const val TAG = "RelayShellService"
        private const val FRAME_POLL_MS = 40L
        /** 静止画面没有新帧时，按这个间隔把上一帧再送一次（避免 30s 零字节）。 */
        private const val FRAME_REPEAT_MS = 500L
        /** 从未拿到过帧时，启动这么久后先送一帧黑帧让 MSE 能 open。 */
        private const val BLACK_FRAME_AFTER_MS = 2_000L
        private const val INPUT_TIMEOUT_US = 10_000L
        private const val OUT_BUFFER_BYTES = 64 * 1024
        private const val STOP_JOIN_MS = 3_000L
        private const val NAL_SPS = 7
        private const val NAL_PPS = 8

        /**
         * 会话时长上限。**它对"堵在 pipe write 上"的会话不构成保证** —— 那种会话的
         * 收场在宿主（宿主 watchdog 到点关读端 / 宿主进程死亡由内核关 fd）；这里的时间
         * 只约束"还走得动"的会话由 watchdog 中断收场。
         */
        private const val MAX_SESSION_MS = 30L * 60L * 1_000L
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

        /** 任务列表的 `dumpsys` 上限（秒级输出，给足一次往返）。 */
        const val TASKS_DUMP_TIMEOUT_MS = 8_000L

        /** `am display move-stack` 的上限（同屏搬迁亚秒级，给足余量）。 */
        const val TASKS_MOVE_TIMEOUT_MS = 8_000L
    }
}


/** 退出前留给 destroy 事务的回执时间：太短会让调用侧拿到 DEAD_OBJECT，太长只是白等。 */
private const val EXIT_GRACE_MS = 120L
