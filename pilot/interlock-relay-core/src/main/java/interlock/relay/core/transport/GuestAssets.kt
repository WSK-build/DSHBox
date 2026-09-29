package interlock.relay.core.transport

import interlock.relay.core.log.LogEvent
import interlock.relay.core.protocol.RelayError
import interlock.relay.core.log.LogSubsystem
import interlock.relay.core.log.RunLog
import interlock.relay.core.storage.RelayPaths
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import org.json.JSONObject

/**
 * 沙盒侧资产物化。随每次启动重铺，保证入口脚本与说明书始终和当前安装版本一致。
 *
 * 这些文件只是**告知**：沙盒与宿主同 uid，助手改了它们也改不了宿主的判定，
 * 因此不尝试设只读位或做任何防篡改承诺。
 *
 * 发布形态要诚实地说清：三份文件逐个走 [publish] 的暂存+改名，每一份各自原子，
 * 但「三份同批」没有整体原子性——重铺在「第一份已换、最后一份未换」的窗口里
 * 被放弃时，磁盘上就是一份版本混杂的集合，而且这里**不承诺失败能保留完整旧内容**。
 * 能承诺的是「不把混杂当成已发布」：三份都落下之后，最后一步把三份内容的哈希
 * 写进提交标记 [RelayPaths.versionMarkerFile]；[intact] 拿标记逐份比对哈希，
 * 对不上就整体重铺。标记只是**宿主侧**的完整性判据：沙盒里的 v1 读者（入口 CLI）
 * 不读它，混杂的窗口对它短暂可见。
 *
 * 但写入形态本身是安全边界，不是风格问题：目标全在绑定子树内，宿主不能直接往
 * 那个位置写字节，见 [publish]。
 */
class GuestAssets(
    private val assetLoader: (String) -> InputStream,
    private val paths: RelayPaths,
    private val runLog: RunLog,
) {

    /**
     * 把入口脚本、能力清单与使用说明铺进绑定子树，最后落提交标记。
     *
     * 返回 true 当且仅当三份文件与标记全部落盘。宿主以返回值决定是否把内存里的
     * 「已发布清单」前移：中途任何一步失败都返回 false，让磁盘上可能出现的混版
     * 保留「未完成」的身份，由下一次复查从头重铺，而不是被当成最新状态。
     */
    fun materialize(capabilitiesJson: String): Boolean {
        if (!paths.ensureWritableDirs()) {
            runLog.error(LogSubsystem.TRANSPORT, LogEvent.DIRS_UNAVAILABLE)
            return false
        }
        paths.binDir.mkdirs()
        // 资产先整体读入再写：标记里的哈希必须对着**实际落盘的那份字节**算，
        // 边复制边算会让「复制成功、摘要中途失败」的中间态没有归属。
        val scriptBytes = runCatching { assetLoader(paths.assetClient).use { it.readBytes() } }.getOrNull()
        if (scriptBytes == null) {
            runLog.error(
                LogSubsystem.TRANSPORT,
                LogEvent.REQUEST_DROPPED,
                "file" to paths.assetClient,
                "cause" to "asset copy failed",
            )
            return false
        }
        if (!publish(paths.clientScript) { target ->
                runCatching { target.writeBytes(scriptBytes) }.isSuccess
            }
        ) {
            return false
        }
        // 可执行位是为方便助手直接 exec；取不到也不影响调用，可用 node 显式解释执行。
        runCatching { paths.clientScript.setExecutable(true, false) }
        val capabilitiesBytes = capabilitiesJson.toByteArray()
        if (!publish(paths.capabilitiesFile) { target ->
                runCatching { target.writeBytes(capabilitiesBytes) }.isSuccess
            }
        ) {
            return false
        }
        val usageBytes = usageText().toByteArray()
        if (!publish(paths.usageFile) { target ->
                runCatching { target.writeBytes(usageBytes) }.isSuccess
            }
        ) {
            return false
        }
        // 提交标记是最后一步：它落盘，三份才算「同批发布」；写失败按失败收场，
        // 绝不落一个「看起来成功」的残缺标记。
        val marker = markerJson(
            scriptHash = sha256(scriptBytes),
            capabilitiesHash = sha256(capabilitiesBytes),
            usageHash = sha256(usageBytes),
        )
        return publish(paths.versionMarkerFile) { target ->
            runCatching { target.writeText(marker) }.isSuccess
        }
    }

    /**
     * 那三份文件是否还在、还归我们、且与提交标记同批。
     *
     * 沙盒与宿主同 uid，它可以直接把入口脚本或手册删掉、改写内容，或换成指向宿主
     * 别处的符号链接。存在与链接判据沿用 [publish] 那道 `symlinkFreeFrom` ——
     * **判据与写侧必须同源**，一条路径只要末级或中间级被做成链接就不算"还是我们的
     * 文件"。但只核存在核不出「三份不是一个版本」：上一趟重铺半途而废时磁盘上就是
     * 一份混版。所以再与标记比对——标记缺失、布局不认识、或任何一份哈希对不上，
     * 都算不完整，由调用方触发一次从头重铺；标记是普通文件，写侧对它没有额外承诺。
     */
    fun intact(): Boolean {
        val files = listOf(paths.clientScript, paths.capabilitiesFile, paths.usageFile)
        if (!files.all { it.isFile && RelayPaths.symlinkFreeFrom(paths.entryDir, it) }) return false
        val marker = paths.versionMarkerFile
        if (!marker.isFile || !RelayPaths.symlinkFreeFrom(paths.entryDir, marker)) return false
        val parsed = runCatching { JSONObject(marker.readText()) }.getOrNull() ?: return false
        if (parsed.optInt("layout") != MARKER_LAYOUT) return false
        val recorded = parsed.optJSONObject("files") ?: return false
        // 三份逐一与标记比对：读不到（被并发换走等）按「对不上」处理。
        for ((file, name) in listOf(
            paths.clientScript to scriptMarkerKey(),
            paths.capabilitiesFile to "capabilities.json",
            paths.usageFile to "USAGE.md",
        )) {
            val hash = runCatching { sha256(file.readBytes()) }.getOrNull() ?: return false
            if (recorded.optString(name) != hash) return false
        }
        return true
    }

    /**
     * 先写非绑定的暂存目录，再改名送进绑定子树，两个原因：
     *
     * 1. **不能直接往绑定子树写字节。** 沙盒与宿主同 uid，它只要
     *    `ln -sf ../../../<private-dir>/x.log <entry>/bin/<cli>`，下一次重铺用
     *    `File#outputStream()` 就会**跟随末级符号链接**以 O_TRUNC 把内容写进宿主的
     *    审计记录、配额账本或 `shared_prefs` —— 等于借宿主的手改宿主的约束依据。
     *    `rename(2)` 不跟随末级链接，只会把链接本身替换成我们的文件。
     * 2. 重铺与沙盒侧的并发读取不再互相看见半截文件。
     *
     * 末级之外还有中间级：guest 可以 `rmdir bin && ln -s ../../../shared_prefs bin`，
     * 于是入口脚本的整个落点跑到绑定子树之外，而 `rename` 对**中间**目录链接照跟不误。
     * 所以写入前用 [RelayPaths.symlinkFreeFrom] 逐级查 `entryDir` 以下的每一级。
     * 归一化/比较 canonical 挡不住这种攻击：两侧同源、同时被解析，恒等。
     */
    private fun publish(target: File, write: (File) -> Boolean): Boolean {
        if (!RelayPaths.symlinkFreeFrom(paths.entryDir, target)) {
            runLog.error(LogSubsystem.STORAGE, LogEvent.ATOMIC_WRITE_FAILED, "file" to target.name)
            return false
        }
        val staged = runCatching {
            if (!paths.stagingDir.isDirectory && !paths.stagingDir.mkdirs()) return false
            File.createTempFile("${target.name}-", ".tmp", paths.stagingDir)
        }.getOrNull() ?: return false
        val ok = runCatching { write(staged) && staged.renameTo(target) }.getOrDefault(false)
        runCatching { if (staged.exists()) staged.delete() }
        if (!ok) runLog.error(LogSubsystem.STORAGE, LogEvent.ATOMIC_WRITE_FAILED, "file" to target.name)
        return ok
    }

    /** 入口脚本在提交标记里的键名：`bin/<命令名>`，随路径策略取。 */
    private fun scriptMarkerKey(): String = "bin/" + paths.cliName

    /**
     * 提交标记正文：键为相对 entryDir 的文件名，值为发布那一刻各文件内容的
     * sha256。时间只取发布成功的墙钟秒——这份正文只在重铺成功时写出，
     * 不参与「变了才重铺」的比较，多一个每次都变的字段没有意义。
     */
    private fun markerJson(scriptHash: String, capabilitiesHash: String, usageHash: String): String =
        "{\"layout\":$MARKER_LAYOUT,\"files\":{" +
            "\"${scriptMarkerKey()}\":\"$scriptHash\"," +
            "\"capabilities.json\":\"$capabilitiesHash\"," +
            "\"USAGE.md\":\"$usageHash\"}," +
            "\"publishedAtWall\":${System.currentTimeMillis() / 1000}}"

    private fun usageText(): String = (USAGE_TEMPLATE
        .replace("{{ENTRY}}", paths.guestScript)
        .replace("{{CAPS}}", paths.guestCapabilities)
        .replace("{{UPLOADS}}", paths.guestUploads)
        .replace("{{OUT}}", paths.guestOut)
        .replace("{{ENTRY_DIR}}", paths.guestEntry)
        .replace("{{GATE_DENIED_CODE}}", RelayError.GATE_HOST_DENIED.code))

    companion object {
        /** 提交标记的布局版本；不认识就当标记缺失，触发一次重铺重建。 */
        private const val MARKER_LAYOUT = 1


        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        /**
         * 面向助手的使用说明。内容是给模型读的机器文档，固定英文，
         * 不参与界面本地化——同一份说明要同时喂给六种语言环境下运行的助手。
         */
        private val USAGE_TEMPLATE = """
            # Relay control

            You can call Android-side capabilities of the host phone through a local CLI.
            The CLI talks to the host app through files, not over the network.
            Every single-node read also says which display that tree came from
            (`data.treeDisplay`), and when a `nodeId` resolves to something that is not
            visible on that display it adds `data.treeWarning`. The id is a position in the
            tree that call just read, so the warning means one of two things: the id came from
            an earlier snapshot and the page has since scrolled or refreshed onto different
            content, or it came from another display, which is always a different tree. Treat a
            warned result as not-your-target: take a fresh snapshot on the
            display you mean to act on and use the id from it. Selectors never trigger this,
            they read the tree in front of the call.
            Request id and file name: an id is 1-64 characters of ASCII letters, digits,
            underscore and hyphen, and the request file is named `<id>.json`. A name outside
            that shape (non-ASCII, a dot inside the id, longer than 64) is answered with
            E_TRANSPORT_MALFORMED and removed from the inbox, never left there unanswered.
            The entry point generates compliant ids on its own, so this only matters for
            hand-written request files.
            Where the diagnostics command lives: `{{ENTRY}} doctor` here is a static assembly
            check - the capability manifest readable, the mailbox directories writable - and it
            answers "is the pack assembled", nothing more. The plugin's own entry has its own
            doctor of the sandbox plugin's own entry, which additionally checks where
            the pack mount resolved and the tools API. Neither command proves that a call goes
            through: a live channel only shows in the reply of one real call.

            ## Discover

            Read the live capability list, including which gates are currently blocking:

                cat {{CAPS}}

            Every entry has: id, category, systemPermission, assistantTier, ceiling,
            usable, implemented, minSdk, surfaces, guide; usable entries also carry the
            surface a call would run on, and an entry carries degradeReason when the mode
            the user asked for is not one this call can serve. Only usable=true can be
            called today.
            The list has no timestamp field of its own. The file's modification time only says
            when the last successful write landed - it does not prove the content still matches
            the device. The host re-lays the file the moment a gate or the virtual display
            changes, and otherwise only checks for changes once a minute, writing nothing when
            nothing changed; a refresh caught part-way through leaves a mixed set - some bytes
            new, some old - and the commit marker described below is what makes that state
            recognizable, with the next full re-lay healing it. Judge freshness from the
            content: the `usable` and `backend` fields here, and - when it matters - the reply
            of one real call.
            The three files this tree is made of - the entry script, the manifest and this
            manual - are re-laid by the host one file at a time, and each finished re-lay is
            sealed by a commit marker: `version.json`, right next to them, records the
            hash of each of the three as they were published together. Read it when the batch
            matters. It is the host's own completeness check, not a guarantee this side can
            lean on: a re-lay caught mid-flight leaves the three files briefly from different
            batches, and the CLI does not read the marker - a call started inside that window
            may run against a mixed set for a moment.
            The file also carries a top-level backend.shizuku object: running, authorized,
            bound, shellUid, version and trustedDisplay {alive, displayId, nodeTree}. When a
            shell capability reports E_BACKEND_UNAVAILABLE, read that object first - it says
            which of the four links is broken, instead of leaving you to guess between "Shizuku
            is not running", "the user never granted us", "the user service is not bound" and
            "there is no virtual display yet".
            trustedDisplay.nodeTree is the one field that is measured, not asserted: it says
            what the last real attempt to take that display's node tree found - "last-seen",
            "last-empty", or "untested" when nothing has asked since that display came to be.
            Read it before you plan a node-level flow onto the background surface: an alive
            display is not by itself a display whose controls you can read.

            ## Call

            The entry knows seven commands. `call` runs one capability: it travels the
            v2 control channel whenever the host's control directories exist, and falls
            back to the v1 mailbox only when the submission provably never landed there -
            once a submission is in, a channel that goes missing is reported as UNKNOWN
            (exit 6) and followed up, never re-sent. `status` and `cancel` follow up one
            already-submitted request by id. `doctor` is the static assembly check
            described above; `--probe` adds a v2 control-channel ping and fills
            `channelLive` - the live-channel verdict only a real round trip can give -
            without overturning the static verdict. `capabilities` prints the manifest
            `cat {{CAPS}}` shows:

                {{ENTRY}} doctor [--probe]
                {{ENTRY}} call <capability-id> --json '{"package":"com.example"}'
                {{ENTRY}} call screen.capture
                {{ENTRY}} call screen.capture --out {{OUT}}/shot/now.png
                {{ENTRY}} call screen.record --json '{"seconds":5}' --timeout 120000
                {{ENTRY}} status <request-id>
                {{ENTRY}} cancel <request-id>
                {{ENTRY}} home
                {{ENTRY}} ask --json '{"question":"...","options":["A","B"],"timeoutMs":60000}'

            Output delivery: everything a capability produces is delivered to you under
            {{OUT}}/<kind>/ - `shot/` for screen captures, `video/` for recordings,
            `audio/` for audio captures, `file/` for anything else. Omit `--out` and the file
            gets a generated name in that tree; the reply's `artifacts[0]` is the path it
            actually landed on - **use that, not a path you made up**. `--out` must be an
            absolute path: a relative one is refused (exit 1) precisely because it used to
            land in the command's working directory, and that directory is the user's own
            workspace (`/root/projects`) - do not write assistant output there, it is not a
            delivery location and nothing host-side collects it. The delivery tree keeps the
            latest 20 files per kind, at most 256 MB and 7 days, and never collects anything
            modified within the last 10 minutes.

            `home` brings the host app back to the front - a launcher start of the host
            package, so it lands on the host's own home page, which is where the user
            re-enters the conversation from.
            Use it when you have finished driving other apps on the user's screen and the user
            did not ask to stay where you left them - leaving the phone parked on some third app
            silently strands them. It is the single sanctioned exception to the self-target
            guard (app.launch with nothing but the host package); the host package comes from
            the manifest's `host.package`. Two things to know: it is not gated by your approval
            tier (the shape launches nothing but the host app, so no confirmation dialog is
            raised), and if the host app is already in the foreground the call reports
            `{"verified":true,"alreadyInFront":true}` without starting anything - that is a
            success, not a skipped step. When accessibility is not connected the result may come
            back `verified:false`; for this shape that only means the host could not watch the
            screen settle, so treat it as done rather than retrying.

            `ask` puts a question in front of the user through the host's own overlay
            card: one question, 2..3 option buttons, plus a "Reject all" button. Use it
            while operating other apps - asking inside this conversation reaches nobody
            when the user is looking at another app. Limits: the question is 1..200
            characters and each option 1..60; asking with more is a usage error (exit 1)
            here, and the host refuses the same shapes too. The reply is JSON:
            `{"ok":true,"choice":{"kind":"option","index":0,"label":"A"}}`, or
            `{"choice":{"kind":"reject_all"}}` when the user rejected every option -
            that is a legitimate answer: rework the options and ask again - or
            `{"choice":{"kind":"reask"}}` when the user asked to have the question itself
            put differently: rework the question, not the option set (both rejections
            answer with exit 0 and mean "ask me again", they differ in what was wrong:
            the options or the question). Failure
            shapes (each reply carries `error.code` and the exit code to use, so read
            `error.exitCode` instead of re-deriving it): `E_ASK_TIMEOUT` (exit 3; the cause
            tells you which one: "no answer within the window" means the card was up and
            nobody answered - asking again is fine - while a cause ending in "lost window
            focus while waiting" means the card was very likely hidden by whatever app was in
            front, so have the user leave that screen before asking again), `E_ASK_NO_REPLY` (exit 3,
            the channel was there but no reply came back at all - whether the card ever
            reached the screen is unknown, and asking again is safe), `E_ASK_BUSY`
            (exit 5, another card or an approval is already on screen, or the channel
            was switched off while the card was up - in every case: do not stack a
            second question, answer or wait out what is there first), `E_ASK_NO_SURFACE`
            (exit 4, no overlay permission, background mode, or a foreground app that
            hides system overlays - ask the user to open the assistant page instead).
            A question has no `status`/`cancel` follow-up: it ends by itself when the
            user answers or the window runs out, and the host withdraws the card if the
            channel is stopped underneath it. One question at a time, and the card is
            never put up while an approval card is on screen (an approval that arrives
            while a question card is up goes to the notification shade instead).

            Options are parsed strictly: an unknown option, the same option twice, a
            missing value or an extra positional argument is a usage error (exit 1)
            naming what tripped, never a silent default. --timeout is THIS process's
            wait budget, measured on a monotonic clock and covering both the v2
            acceptance round trip and the polling after it; it is clamped into
            1000..120000 (default 90000).

            Where this lives: {{ENTRY_DIR}} is not a directory inside the Debian guest - it is
            the host app's own files dir, reached through a PRoot path translation. So
            apt-get, dpkg and a re-downloaded runtime have nothing to overwrite or remove
            here, and the host re-lays this tree each time the channel starts. If {{ENTRY_DIR}}
            ever reads as empty, the overlay did not apply - restart the sandbox; it does
            not mean these instructions were deleted.

            ## Arguments

            Every key you send has to be one the capability accepts. An unknown key or a
            missing required key fails the call with E_TRANSPORT_MALFORMED (exit 1); nothing
            is silently ignored, because a call you believed was a swipe must not run as a tap.
            Required keys are marked *. Coordinates are pixels in the space reported by
            ui.snapshot's data.screen; out-of-range and non-numeric values are refused rather
            than accepted and doing nothing.

            No argument may name this app's own package, under any key and at any depth: a call
            that carries it answers {{GATE_DENIED_CODE}} (exit 2, not retryable) and names the
            argument it tripped on. That refuses the filter, not the data - an unfiltered read
            still returns this app's own rows - so dropping that argument is the fix, and
            re-sending the same call unchanged will not go through. The one exception is
            app.launch carrying nothing but this app's package, which brings the assistant's own
            screen forward so a finished task can be handed back to the user.

            - {}, no arguments: pkg.query, clip.read, loc.read, screen.capture,
              screen.observe, ui.snapshot. Sending any key to these is an argument error.
            - app.launch: {"package"*} — a package name from pkg.query; a display name is a
              bad argument, not a missing permission. The reply says where it actually came up:
              verified is confirmation, and a launch that was accepted but not realised answers
              E_LAUNCH_NOT_LANDED (exit 5) naming the app that holds the front. Both paths give
              that one code, and it means a different app is **confirmed** to be on top - not a
              cold start still running (that case answers ok with verified:false). Read
              ui.snapshot to see what is up, or ask the user to leave that screen, before
              sending it again. On the virtual display there is no way to move an
              already-running task onto it, so the answer carries the two ways forward - release
              and re-create the display, or drive it on the display it really came up on. Only
              the virtual-display path adds landedOn and topOnTarget; the foreground path reports
              verified and, when it could not confirm, observed (the package it did see). Never
              read a bare "accepted" as success.
            - contact.read, cal.read, media.read, notify.read: {"limit"}; notify.read also
              takes {"package","includeOngoing"}, media.read takes {"kind":"image|video"}
              The notify.read window is at most the last 30 minutes and 200 rows: everything
              posted since the service connected, plus whatever was already in the shade at that
              moment **whose own posting time is inside those 30 minutes** - a notification that
              has been sitting there for an hour is outside the window and is not returned. A row
              disappears when it is dismissed. So count 0 says nothing readable is inside the
              window, not that none was ever posted. Ongoing (persistent) rows stay out unless
              includeOngoing is true.
            - contact.write: {"name"*,"number"}
            - cal.write: {"title"*,"startMs","endMs"}
            - media.write: {"file"*,"kind"}
            - notify.post: {"title"*,"text","id"}
            - audio.capture, screen.record: {"seconds"} - clamped to 1..30 by the host, so
              asking for 60 records 30 and reports that, it does not fail
            - clip.write: {"text"*}
            - clip.read and clip.write run only while this app holds input focus: Android 10 up
              refuses clipboard access to background apps, so this is the capability's range,
              not a permission the user forgot to grant. A call made while the app is in the
              background answers E_CLIPBOARD_NO_FOCUS. That code is deliberately distinct
              from E_GATE_NO_FOREGROUND: the first says this app had no input focus at the
              moment of the call, the second says no window of this app could present the
              approval dialog. Retrying one is not how you handle the other.
            - ui.text: {"text"*}, into the field that currently has focus; blank text counts
              as a missing argument. On the foreground path the host prefers the focused field
              but falls back to the first editable node in that window, so
              E_NO_EDITABLE_TARGET means there was neither focus nor any editable node to fall
              back to - not merely "nothing was focused". On the off-screen screen it
              cannot: the only channel to that display is an injected keystroke, and nothing
              there reports back whether an editable field holds focus. So on
              surface=trusted-display, confirm the field is focused with screen.capture first;
              an ok from ui.text there means "keys were delivered", not "text appeared".
              Length: that display takes one `input text` per call and is capped at 500
              characters, answered as an argument error; the foreground path has no such cap.
              Write scripts that have to run on both against the 500.
            - ui.key: {"key"*:"back"|"home"|"recents"}. "enter" exists only on the off-screen
              display - the foreground path runs global actions, which have no enter among
              them, and it answers "unknown value for key" with the list it does take.
            - ui.tap: {"x"*,"y"*} for a tap. ui.swipe: {"fromX"*,"fromY"*,"toX"*,"toY"*} with
              optional {"durationMs"}. They are separate capabilities and their keys cannot be
              mixed: a swipe-shaped request sent to ui.tap is rejected, never run as a tap.
            - The ten node-level capabilities (ui.click, ui.longClick, ui.select, ui.dismiss,
              ui.scroll, ui.setValue, ui.node, ui.waitFor, ui.setProgress, ui.imeAction)
              address a control, not a pixel, and synthesise no touch event, so the
              "coordinates missed and the call still said ok" failure cannot happen there.
              On the background surface their tree is taken from the window currently on the
              virtual display (Android 13 up - that is the only release with a way to take
              another display's tree). Whether the system exposes that tree is not something
              the display's existence guarantees: when it does not, these calls answer
              E_BACKEND_UNAVAILABLE, and the reason names which of three things happened -
              "was given no window for trusted display N" (the query answered, and that display
              had no window giving up a root node), "the cross-display window query ... did not
              answer" (the query itself failed, so it says nothing about the display: retry),
              or "no trusted display to read" (there is no screen to read at all, so creating
              one with surface.virtual is the next step). None of the three means "the node is
              gone". When the reason already names a display id, building a second display will
              not help - the answer came from one that was already up: put an app on it and
              retry once, or switch to ui.tap / ui.swipe (coordinates) or screen.capture /
              screen.record (pixels) for that screen.
              You can see that answer before spending a call: capabilities.json carries
              backend.shizuku.trustedDisplay.nodeTree, and "last-empty" means someone already
              tried to take that tree and no window on that display gave up a root node. And note
              these calls do not quietly run on the user's screen once they have been routed to
              the background surface. Below Android 13 they run on the real screen and say so:
              surface=foreground with degraded=true, degradedFrom=trusted-display.
              ui.snapshot follows the same rule on purpose: a node id is a position in one
              tree, so the snapshot and the call that uses its ids must read the same
              display, or an id silently points at another node. Two target shapes, mutually
              exclusive:
              {"nodeId":N} taken from ui.snapshot, or
              {"selector":{"text"|"desc"|"id"|"class"|"index"|"package"}}. Every field you
              give has to match; index counts siblings, not hits.
            - relative targeting: any node-level action can also carry {"relative":
              {"side"*:"right"|"left"|"above"|"below","nth","sameClass"}} next to the usual
              selector or nodeId. With it, that selector names the ANCHOR and the action
              lands on the nearest node on that side ("nth" takes the N-th one, "sameClass"
              limits the pool to the anchor's class). Nodes overlapping the anchor are not
              on a side of it. Only nodes overlapping the anchor's row (for left/right) or column
              (for above/below) compete first; a diagonal one is used only when nothing lines up.
              Two candidates that tie - in gap and in row overlap, within a tolerance scaled to
              the anchor's own size - answer ambiguous and list both nodeIds whatever "nth" is:
              "nth" is an ordinal, not permission to pick one of a tie, because the tie-breaker
              would be tree order and that changes when the screen relayouts. Use this for controls with no text, desc or
              id, instead of computing coordinates in the script.
            - ui.click: one of the two target shapes. ui.longClick, ui.select, ui.dismiss: same.
              The success result says "dispatched": true with "effectVerified": false and the
              "nodeId" it acted on - the node accepted the action, which is NOT a claim that the
              screen changed. Confirm the effect with ui.node or ui.waitFor. The "node" in the
              result is re-read after the action, so it shows where the control ended up.
            - ui.scroll: target shape plus {"direction"*:"forward"|"backward"} and optional
              {"times"} (1..20). The result separates two counts: "dispatched" is how many
              actions the control accepted, "advanced" is how many steps were **observed** on
              its value. A node that reports a range (wheels, sliders) is stepped one at a
              time - the host waits for the value to actually change before dispatching the
              next one, because a wheel mid-animation swallows or merges the dispatches that
              follow, so 20 accepted actions can be 2 real steps. "movementVerified" is true
              only when every accepted dispatch changed the value. A node with no range gets no
              "advanced" at all: movement cannot be measured there, so re-read with ui.snapshot
              or ui.waitFor before believing it. When the run stops before "times", the result
              says so with "stoppedEarly" and a "stoppedReason": scrolling changes what sits at
              a given position in the tree and the host refuses to keep scrolling whatever node
              happens to be there next; a value that stops changing usually means either end.
              To land on a specific value in one shot, use ui.setProgress {"value"} on the same
              node - it jumps instead of rolling. Give {"until":<value>} (with "direction") and
              the host rolls until that value shows up, at most 40 steps, then answers
              "reached":true with the "value" it landed on - one call per wheel instead of
              re-reading between every step. Both of those need the node to **report a range**
              (ui.node's "range"): a control that takes scroll but shows no range cannot be
              aimed, and answers "not actionable" rather than rolling blind - on some devices the
              clock's own hour/minute wheels are exactly that, so verify with ui.node first and
              fall back to counted scrolls plus a re-read there.
            - sys.intent: {"template"*} plus the parameters that template names. Eight, no more:
              "alarm.set" {"hour"* 0..23,"minute"* 0..59,"message"}, "timer.set" {"length"* seconds
              1..86400,"message"}, "alarm.show", "timer.show", "settings.open" {"page"*:
              wifi|bluetooth|display|location|sound|apn|developer|nfc}, "app.info" {"package"*},
              "dial" {"number"* - dial-pad characters only}, "web.open" {"url"* http or https}.
              Fixed system shortcuts only: the action string and extra names are not
              accepted, and anything outside the table answers "unknown template" with the list.
              The last six only bring a system page or the dialer to the screen - nothing is
              toggled, nothing is called, and there is no entry that cancels or deletes an alarm.
              The result reports the hand-off (which app took it), not the resulting alarm -
              SKIP_UI means the clock app takes the request without a screen, so read the alarm
              list back when the task depends on it. Sending from the background without the
              overlay permission answers E_GATE_NO_FOREGROUND on purpose: Android 10+ drops that
              start silently, and calling a silent drop a success would be a lie.
            - sys.shell: {"verb"*,"args"[...]} against a closed table of read-only verbs -
              dumpsys <service>, pm list <what>, settings get <ns> <key>, getprop [key],
              appops get <package>, wm size|density, screencap. The command line is rebuilt
              here from validated tokens: a raw command string is never accepted, and shell
              metacharacters or whitespace inside args are refused. Verbs that change the
              system need their own switch and answer {{GATE_DENIED_CODE}} until it exists;
              delete-class verbs (rm, pm clear, pm uninstall, settings delete) are not in the
              table and cannot be added. Note that "media" drives a binary some devices simply
              do not ship: there the answer is E_CAPABILITY_UNAVAILABLE_ON_DEVICE with
              "this device has no such command", which no switch and no retry can change -
              use another path instead of trying again. "screencap" takes no args and answers with an
              artifact file - the frame comes back over a descriptor, not through the text
              channel a PNG would be eaten by. Text replies echo the exact "command" that
              ran, which is also the line the confirm card shows. This capability is denied
              until the user turns it on, and every call asks again. The shell verbs run as
              remote transactions: when this call's local budget runs out while one is
              submitted, the timeout reply's reason says the transaction was not cancelled
              and its outcome is unknown - that is never a claim that nothing ran (see the
              timeout section below).
            - ui.setValue: target shape plus {"text"*}. Writes into the control without needing
              focus. The result carries "requested" and "readBack". The read-back re-reads that
              same node by its number rather than resolving the selector again - a selector
              usually carries the text from before the write and would find nothing once the
              field holds yours, which would report a good write as a failure. There is no
              "matches" boolean on purpose: controls rewrite input (phone and date masks,
              autocomplete, maxLength), so an unequal read-back is information, not a verdict.
              When the node is off the tree the result carries "readBackUnavailable" and the
              reason instead: the control accepted the text, you just cannot see it from here.
            - ui.node: target shape. Matching is exact today: "matchMode" reports how the
              node was found and it is always "exact" - a selector that only resembles a node
              does not match it, because the next step after a match may be a delete. Returns
              "matchMode", "nodeId" and a "node" object with
              class, package, text, desc, id, enabled, checkable, checked, clickable,
              longClickable, scrollable, selected, visible, focused, editable, the "actions"
              that node itself accepts, and the bounds clipped to the screen (offscreen=true
              instead of bounds when nothing of it is left on screen). "actions" is what makes
              "not actionable" predictable instead of something you discover by trying. A slider
              or progress control also gets a nested "node.range" {min,max,current,type}: type
              is a name (int, float, percent, indeterminate), current is the value the control
              sits at now. That key is absent entirely when the control reports no range. This
              is how you verify a result without taking another screenshot.
            - ui.waitFor: target shape plus optional {"text","checked","absent","timeoutMs"}
              (default 3000ms, max 15000ms). Use it instead of snapshot-and-guess after an action.
              The wait is also cut down to whatever time this request has left in the channel,
              and a **successful** result reports both: "requestedMs" is what you asked for,
              "cappedToBudget" says the cap bit. A timed-out reply carries no data at all - the
              cap is folded into the reason text there - so read those two keys only after ok.
              The host would rather shorten the wait and
              admit it than let the mailbox kill the whole request as E_TRANSPORT_TIMEOUT,
              which reads like "the host stopped answering" when the truth is "the screen did
              not become that". A malformed or ambiguous target fails at once instead of being
              waited on - E_WAIT_TIMEOUT then only ever means the screen did not change.
            - ui.setProgress: target shape plus {"percent"} in 0..100 or {"value"}, a number
              inside that node's own range. Give one of the two - neither is required by
              itself - and giving both means value wins. This is what sliders and
              progress controls need - they are usually ONE node with no per-step child, so
              coordinates are guesswork there. A node that reports no range is "not actionable",
              and a "value" outside that node's own range is an argument error - read the range
              with ui.node first. The result carries "requestedValue", the "range"
              {min,max,current,type} as read before the action went out, and "readBack" - the
              value the node reports after it, or "readBackUnavailable" with the reason when
              the node cannot be found again. That read-back waits briefly for the control to
              settle and adds "readBackRounds" when it looked more than once: a volume slider
              hands its value to the system and then reads it back, so the first look can still
              show the old number. readBack differing from requestedValue is not by itself a
              failure: a control snaps the value to its own step and comes back unequal while
              doing exactly what it was told. Judge the two numbers, do not assume either way.
            - ui.imeAction: target shape. Fires the editor's IME enter action (search / send /
              done, whichever that field declares). Needs Android 11+. The platform usually does
              NOT advertise that action on an editable field, so the host also accepts a node
              whose class is an edit field and dispatches anyway - which of the two happened is
              in the node's own actions list. A target that is neither advertises-it nor an edit
              field answers E_NODE_NO_IME_ACTION (exit 1): that is "this control is not a text
              field, pick another target", different from E_NODE_NOT_ACTIONABLE ("this node does
              not take this action"). It cannot choose WHICH action the keyboard sends.
            - How a node call says no, and what to do about it: E_NODE_AMBIGUOUS (exit 1) means
              the selector matched several controls and the host will not pick one for you -
              guessing which "delete" you meant is not a decision this channel makes; the
              candidates are in the message, so add "index" or a distinguishing field.
              E_NODE_NOT_FOUND (exit 1) means the tree holds nothing matching the selector, or
              that nodeId is not in it any more - node ids move when the screen changes - or
              that the screen changed under the selector. All three mean the tree WAS read and
              held nothing matching. When there is no readable tree at all the answer is a
              different code, E_BACKEND_UNAVAILABLE naming the display whose tree could not be read - that one
              is transient, this one is not. Re-read with
              ui.snapshot and retarget instead of resending. E_NODE_NOT_ACTIONABLE
              (exit 1) means the node is there but does not accept this action; resending
              changes nothing, change the target or the capability. E_ACTION_REJECTED (exit 5)
              means the node took the dispatch and the app refused it - retry that one.
              E_WAIT_TIMEOUT (exit 3) is about the screen, not the channel.
            - surface.virtual: {"action","width","height","dpi"} - all four optional, an empty
              object means action=create. action is one of create|release|query; anything else
              is an argument error. width/height are clamped to 320..2560 by the display host,
              so the reply reports the size the display actually got rather than the one asked
              for. The reply's "state" field, not "action", says what happened: "kept" means an
              alive display was reused instead of building a second one, while "create" means
              this call went through the create step - the host asks for a new display whenever
              its own cache says there is none, so "create" does not prove none existed. query
              is how to re-read the id after the session restarted.
            - app.stop: {"package"*}
            - appops.set: {"package"*,"op"*,"mode"*} where op is one of
              android:system_alert_window, android:camera, android:record_audio,
              android:location, android:post_notification and mode is one of
              allow|deny|ignore|default. Both names are judged after normalising: op accepts
              the short form too (CAMERA, post_notification) - the android: prefix is added
              and case is folded - and mode accepts DEFAULT. What reaches the system is
              always the canonical lower-case android: form. Anything outside those lists is
              an argument error, not a permission to go grant somewhere.
              The result carries "requested" - the mode after normalising, so sending DEFAULT
              reports "default", not the bytes you typed - "readBack" - the whole
              `appops get` line for that package and operation - and "effective": the mode
              that line ends with, which is what actually applies. They can differ on
              purpose: ask for "default" and you get requested "default" with effective set
              to that operation's built-in default (often allow). So a "set to default" call
              is judged from effective, not from requested - if that distinction matters to
              your task, verify the behaviour instead of the setting.
            - sys.settings.write: {"key"*,"value"*,"namespace"} writes one layer of the
              system settings table: "secure" (the default when you omit it), "system" or
              "global". Pick the layer the key actually lives in - brightness and volume are
              under system, most accessibility and lock-screen keys under secure. The result
              carries "namespace" (the layer written), "requested", "readBack" and "verified"
              - verified is the read-back equal to what was written, and readBack is null when
              the key reads back empty. When that same key name also has a value in one of the
              other two layers, the result carries "shadowedBy" and a "note": you may have
              written a copy nothing reads while the effective setting stayed where it was.
              Treat shadowedBy as "check the layer", never as a successful change. A key that
              would hand a system hook to another app (anything named for accessibility, input
              method, listeners, device admin, adb, install sources) is denied instead of
              written, and a denial is final.
            - app.install: {"upload"*}

            ## What a result means on the paths that can lie

            - app.launch reports data.verified. true means the host saw that package in the
              foreground. false means the launch was accepted but not confirmed — the
              accessibility service is off, or the app runs inside a vendor clone container,
              or nothing foreign had come up yet — and the reply then carries "observed" with
              the package it did see, so check ui.snapshot before acting on it. A failed call
              with E_LAUNCH_NOT_LANDED means a different app is confirmed to hold the front.
              On the off-screen display the same call also reports where the app actually came
              up, and it samples the visible screen BEFORE the launch so the two readings can
              be told apart. "verified": true with "landedOn": <that display id> means it landed
              where you aimed. "grabbedUserScreen": true with a "note" means the package is now
              on the user's own screen and was not there before this call - that is the one case
              worth telling the user about. When the pre-launch sample already showed that
              package on top of the aimed display, the reply carries only a "note" and
              "landedOn" is JSON null - it is never 0, so do not branch on a zero display id.
              "topOnTarget" is whichever package really holds the top of the display you aimed
              at and "targetDisplayMoved" says whether that changed against the pre-launch
              sample: a router activity, an alias or a cross-package launch resumes under a
              different package name than the one you passed, and in that shape verified stays
              false while the launch did work - read those two fields before concluding it
              failed. "landingCheck": "failed" means the host could not read any display's top
              activity at all: that is not a failure of the launch and not a claim about where
              it went, and E_BACKEND_UNAVAILABLE with it means the off-screen display itself is
              gone and needs re-creating. Check these before claiming a task ran out of sight:
              `am start` exiting 0 only means the request was taken, not where it landed -
              launchMode, an already-running task and single-instance apps all send it back to
              the visible screen.
            - On the foreground surface a system dialog appears only on the projection path.
              With accessibility bound on Android 11 up, screen.capture is served by that
              backend (takeScreenshot) and raises no dialog at all; screen.observe, and
              screen.capture when accessibility is not available, go through MediaProjection
              and need the user to confirm once per call - the projection token allows one
              virtual display, so the host closes the session after each capture instead of
              leaving a recording session running.
              E_AWAITING_CONSENT (exit 5) means this app's own approval question is still
              unanswered. Where the question is presented varies - a floating card, the
              notification shade, or the card inside the assistant page - and a shade posting
              does not guarantee a banner was shown, so this code asserts "unanswered", never
              "the user has seen it". The Android system screen-capture consent dialog is a
              different kind of permission and is not what this code reports: that permission
              is expressed by E_SURFACE_SYSTEM_CONSENT_REQUIRED and its decline semantics.
              A question waiting for the user does not hold the channel: the host parks such
              a request and writes its reply when the user answers or the question's window
              closes, so later calls keep moving meanwhile. When this code arrives, follow
              the request that raised the question with status <request-id> instead of
              resending it; a resend of an identical call while one is already parked answers
              this code at once and raises nothing new - the parked request's outcome is the
              one that covers it. Do not treat it as a dead backend, and do not change the
              arguments to get around it.
              E_SURFACE_SYSTEM_CONSENT_REQUIRED means the user declined the system dialog.
              E_GATE_NO_FOREGROUND means no window of this app can present the question.
              On Android 13 and below the system additionally asks the user *what* to record;
              from Android 14 up the host requests the whole-display configuration and that
              choice is deliberately not offered. Where the choice does exist, "A single app"
              mirrors only that one window and can deliver no frame at all, which surfaces as
              E_BACKEND_UNAVAILABLE with "the encoder got no frame"; ask for the whole screen
              instead of retrying the same selection.
            - screen.record on the off-screen surface needs no system dialog at all: the host
              takes that display's own frames and encodes them itself (screenrecord cannot be
              used - it accepts physical display ids only). What you get back is
              {frames, actualFps, width, height, bytes} alongside the artifact. The mp4's own
              duration is the measured span between the first and last frame actually taken, so
              it comes out at most the seconds you asked for, never more - do not treat it as
              equal. Read actualFps rather than
              assuming a smooth video: one frame is one binder round trip plus a compression,
              so a measured run on the emulator lands near 3 fps at 720p, and a screen that
              stops redrawing repeats its last frame instead of stalling the recording.
              A capture on a display that is alive but not producing a new frame is answered
              from the previous frame and says so: `data.stale` is true and `data.frameAgeMs`
              is how long ago that frame was actually taken. Treat a stale frame as "nothing
              has changed on that screen since then", never as the current pixels; if you need
              a change, act and re-capture rather than polling. `stale:false` means this reply
              is a frame taken during this call. Only a display that never yielded a frame
              (or was released) answers E_BACKEND_UNAVAILABLE with "no frame from the trusted
              display" - re-create the screen in that case, do not retry the same call in a loop.
            - E_TASK_SUSPENDED_BY_HOST (exit 5) means the assistant's own screen is in front and
              the capabilities that would change another app's UI (the coordinate controls and
              the node-level writes) refuse to drive it. Reads - ui.snapshot, ui.node,
              ui.waitFor - are not refused. That is a pause, not a refusal: bring some other
              app forward and retry. It is about the screen in front of the user: a call that
              runs on the virtual display reads a tree this app has no window on, so an open
              assistant page does not hold that one back.
            - E_BACKEND_UNAVAILABLE with "no window tree to read on display N after a second
              read" on ui.snapshot, ui.node or ui.text: the host already re-read that tree once
              inside the same call, so treat this as "no window holds focus on that display
              right now", not as a blip to hammer. A retry only helps after something else
              becomes the focused window; otherwise switch to ui.tap / ui.swipe /
              screen.capture, which do not need a tree.
              The sentence names the display this call actually queried, so read the two numbers
              from one moment (`ui.snapshot` and `ui.node`): equal means the tree was empty,
              different means the two calls landed on different screens. Retry either way; do not
              ask the user to re-enable accessibility, that is a different answer
              (E_GATE_SYSTEM_MISSING).
              The same code covers a notification listener that is enabled in settings but not
              attached yet, and a location read with no fix; all three are retry-later, not
              something the user must go change.
            - A call that gets no reply at all (no outbox file, not even an error) is not a slow
              backend. The mailbox is drained by a process-wide loop that is deliberately not
              tied to any page: it consumes while the host process is alive, screen on or off.
              While it runs, the host keeps the CPU awake in bounded leases, so a request posted
              with the screen off is still taken and answered - it does not wake the screen for
              you, and a capability that needs a lit screen answers whatever it answers in that
              state. Leaving the assistant page, or an unanswered system dialog sitting on top
              of it, does not stop consumption. What does stop it is the host process being gone
              - opening the assistant page once starts it again.
              Requests posted meanwhile stay queued and are taken in the order they arrived -
              file names only settle the order for files the host has no arrival stamp for -
              but a backlog past 256 pending files has its newest arrivals failed off as
              E_QUEUE_FULL - the reason carries retryAfterMs and queueDepth - so do not repost
              the same call many times.
            - ui.snapshot returns data.screen {width,height,display} and densityDpi when this
              process can read that display's metrics: the coordinate space every ui.tap
              coordinate must be in, plus the node bounds already clipped to it. A missing
              densityDpi means exactly that - do not substitute the device's own density.
              A node marked "offscreen" has no usable area on that display.
            - surface says what actually ran. Check the boolean degraded before concluding that
              a successful call left the user's screen alone: degraded=true means the preferred
              mode was unavailable and the action happened somewhere else - with
              degradedFrom=trusted-display and degradeReason=E_SURFACE_UNAVAILABLE, it ran on the
              user's real screen even though the user asked for background first. Treat a
              degraded success as a failure of the background plan, not as a normal result.
              Capabilities that declare no surface at all (data reads, notifications)
              answer surface=foreground with degraded=false, and on those two keys carry no
              information. That is not true of a capability that declares the foreground only:
              clip.read and clip.write have no background variant to move to, so under
              "background preferred" they come back degraded=true with
              degradedFrom=trusted-display and degradeReason=E_SURFACE_UNAVAILABLE even though the
              call itself succeeded on the user's screen.
            - Off-screen ("background") operation exists but is not the default, and it needs
              Shizuku running and authorised. Create the screen first, then use the coordinate
              controls there - ui.tap, ui.swipe, ui.text, ui.key, app.launch and screen.capture
              all take that surface; while that screen exists those calls report
              surface=trusted-display and do not touch what the user is looking at. The ten
              node-level capabilities are routed there too, and they answer
              E_BACKEND_UNAVAILABLE when the system gives no window tree for that display -
              read which of the three reasons it is (the node-level bullet above spells out what
              each one supports) before concluding the background plan is broken. When the
              reason names a display id, re-creating the display is not the fix: that screen
              was already up, so use the coordinate or pixel calls above for it. None of them fall back to the user's screen silently:

                  {{ENTRY}} call surface.virtual --json '{"action":"create","width":1080,"height":1920,"dpi":420}'
                  {{ENTRY}} call app.launch --json '{"package":"com.example"}'
                  {{ENTRY}} call ui.tap --json '{"x":540,"y":1200}'
                  {{ENTRY}} call screen.capture --out bg.png
                  {{ENTRY}} call surface.virtual --json '{"action":"query"}'
                  {{ENTRY}} call surface.virtual --json '{"action":"release"}'

              surface.virtual itself runs on that screen only: when the user's execution-mode
              preference cannot reach the trusted display, its manifest row carries
              usable=false with degradeReason=E_SURFACE_MODE_REQUIRED and a call answers the
              same code. The fix is on the user's side - switch the execution mode to
              background-preferred and confirm the screen has been created
              (backend.shizuku.trustedDisplay and the four-link read above say which piece is
              missing) - retrying does not build the screen.
              Read the surface field before claiming you worked off-screen: with no such screen
              the same calls run on the real one. On that screen coordinates are in its own
              width and height (from create/query), not the device's.
              What that screen gives you: pixels, coordinates and the control tree. ui.snapshot
              and the node-level capabilities read and act on the window that is on it - read a
              tree, click a node by its text or id, and check the state back. screen.capture
              reads a still from the host's own frame source and screen.record encodes a video
              from the same source; neither goes through the system projection dialog, so
              neither E_AWAITING_CONSENT nor E_SURFACE_SYSTEM_CONSENT_REQUIRED refers to them.
              ui.tap, ui.swipe, ui.text and ui.key act there through the shell service.
              The tree is there but it is not guaranteed on every device: the accessibility
              layer enumerates a virtual display's windows only under the right display flags,
              and if it does not, those calls answer E_BACKEND_UNAVAILABLE. Read
              backend.shizuku.trustedDisplay.nodeTree in this file's manifest before planning:
              "last-seen" is the answer above, "last-empty" means someone tried and no window on
              that display gave up a root node, "untested" means nobody has asked yet on this
              display. A node-level failure there never means "the node is gone" - do not write
              that from it.
              The screen and everything running on it is released when you release it, when the
              channel stops, or after five minutes with no call on it — query reports display=-1
              once that happens. E_BACKEND_UNAVAILABLE on the four coordinate calls above means
              no backend could be selected for that call, or that the off-screen display is gone:
              create again and continue, or fall back to the real screen. A user-service binder
              that dies mid-call answers a different code, E_BACKEND_SHIZUKU_DEAD - that one is
              the "the shell service is gone" case, and it is retry-later, not something to
              re-authorise. And when `input -d` itself fails while the display is still there the
              answer is E_GATE_SYSTEM_MISSING, which does need the user. On the node-level calls
              the same code means what the three reasons say -
              there the shell service can be perfectly alive.

            ## Handing a file to the host

            Capabilities that need file bytes (media.write) read them from an uploads area,
            not from the call arguments. Copy the file there first, then name it:

                cp shot.png {{UPLOADS}}/shot.png
                {{ENTRY}} call media.write --json '{"file":"shot.png","kind":"image"}'

            `kind` is image | video | audio and decides where the entry lands; the name is a
            single file name, no path. The bytes must actually be media of that family: the host
            reads the file's magic bytes and rejects anything it cannot place in a family -
            PNG/JPEG/GIF/WebP/BMP/TIFF/HEIC for images, ISO-BMFF(ftyp)/Matroska/AVI for video,
            ID3 or MPEG frame/Ogg/FLAC for audio. That list is wider than the examples below, so
            do not assume an exotic container is refused. One file is at most 32 MB and must be a
            regular file (not a directory, link or fifo). The file name becomes the media display
            name, and the extension decides the reported MIME type **only when that extension
            belongs to the declared kind's family** - `.txt` sent with `kind=image` is filed as
            image/png, not text/plain. So name it .png / .mp4 / .m4a
            to match what you put in `kind`. A consumed upload is deleted; one nobody consumes
            is deleted by the host's periodic cleanup, which runs at most every 30 minutes.

            `app.install` uses the same area with a different argument name and a larger cap:

                cp app.apk {{UPLOADS}}/app.apk
                {{ENTRY}} call app.install --json '{"upload":"app.apk"}'

            The host copies the upload out of the shared area, parses the copy as an APK and
            rejects anything that is not installable, so no `kind` and no magic-byte check are
            needed here; one file is at most 128 MB. Installing the host app itself is refused,
            and every call needs the user to confirm it on the phone (no standing grant).
            app.install runs as a remote transaction: a timeout mid-install means the outcome
            is unknown - the transaction was submitted and may still complete - never that
            nothing was installed. Follow the request with status/cancel instead of resending
            the upload.

            ## Results
            stdout is a single JSON object. Fields: v (protocol version), id (echoes your
            request id), ok, elapsedMs, data, artifacts (paths), surface,
            degraded, degradedFrom, reason, note, degradeReason, error.code, error.retryable, error.exitCode.
            With --out, the first artifact is copied to that path and reported back in
            artifacts in place of the original. The copy can still fail after the capability
            itself succeeded: artifacts[0] is then an empty string, artifactDelivery is
            "failed" with artifactDeliveryError naming the cause, and the exit code still
            reflects the capability's own result - check the field before assuming the file
            exists.

            Artifacts are files the host wrote for you to read. They are removed once
            stale or when the storage quota is reached. Read them, do not write there.

            ## When a call times out: status, cancel, UNKNOWN

            Two codes end a call without a verdict, and both carry the request id on the
            stderr line. E_TRANSPORT_TIMEOUT (exit 3) means this process's wait budget ran
            out; on the v2 control path the line also says state=UNKNOWN - the host may still
            be executing inside the ttl that call handed it, so the outcome is unproven, not
            negative. E_TRANSPORT_UNKNOWN (exit 6) means the v2 channel lost a submission
            that is already in - the control directories vanished mid-flight - and its
            outcome could not be confirmed either. In both cases follow the SAME request,
            do not resend it:

                {{ENTRY}} status <request-id>
                {{ENTRY}} cancel <request-id>

            - status reports where that one request stands. state=CANCELLED is the host
              confirming it never executed; a settled state comes with the full result;
              found=false (exit 4) means the host never saw that id.
            - cancel asks the host to take the request back. CANCELLED confirms not executed;
              COMPLETED means it already settled (read its result with status);
              CANCEL_REQUESTED (exit 5) means the wish is recorded but the host has not
              confirmed the revocation - follow with status, do not resend.
            - state=UNKNOWN means the outcome could not be confirmed. For the actions whose
              effect is not safe to repeat - contact.write, cal.write, media.write,
              app.install, sys.intent, and the coordinate and node-level writes (ui.tap,
              ui.swipe, ui.text, ui.key, ui.click and the other node-level ones) - do NOT
              re-send the same arguments: either the request already ran or it is about to,
              and a resend would run it twice. Reads may be retried once a status query
              settles the picture.
            A timeout on a remote transaction (the sys.shell verbs, app.install) carries its
            own wording in the reason: the transaction was submitted, it was not cancelled,
            and its outcome is unknown - never read that as "nothing happened". When the host
            has no room for a request at all it answers E_QUEUE_FULL (exit 5) instead, with
            retryAfterMs and queueDepth in the reason - wait that long instead of hammering.

            ## Exit codes

            - 0 success
            - 1 usage error: bad capability name or malformed arguments. Fix the call.
            - 2 denied by the user or by a rule. Retrying gets the same answer.
            - 3 the call ran out of time.
            - 4 the user has to do something on the phone first (grant, switch tier).
              For status, 4 also means no such request.
            - 5 transient, retry later (rate limited, storage full, backend restarting).
              For cancel, 5 also means the cancellation was recorded but not yet confirmed
              by the host.
            - 6 host-side fault. Report it, do not loop on it.

            ## Rules the host enforces

            - Every call is checked twice: the Android system permission, and your
              assistant tier for that capability. A denial is final, not a bug to work around.
            - Tiers that ask show a confirmation dialog before the call runs - unless the user
              already answered "allow for this session" on an earlier one, or this call's
              remaining ttlMs is too short to hold a dialog - the dialog budget is the remaining
              ttl minus 1.5 seconds and it needs 5 seconds to be worth raising, so in practice a
              call arriving with under about 6.5 seconds left raises no dialog at all and
              returns E_AWAITING_CONSENT. There are three places the question can appear, and
              exactly one of them is used at a time, chosen in this order: (1) the card inside
              the assistant page, whenever that page is in front; (2) a floating card above
              everything else, when the page is not in front and the "display over other apps"
              grant is held; (3) the notification shade, where the user answers with an
              "Allow" / "Deny" action after pulling it down - used when neither of the first two
              is available, because the screen in front hides other apps' floating windows
              (system Settings pages do) or a trusted virtual display is running (in that mode
              the assistant's own overlay is hidden across screens) while the page is not in
              front. The assistant never brings its own page forward to ask: that covers the
              screen the user is looking at, and the control call right after the answer would be
              refused as the assistant driving its own window. Failing that, the call fails with
              E_GATE_NO_FOREGROUND - that code means no surface at all can carry the question
              (no floating window, notifications blocked, page not in front), and it is also
              what a capability reports when the *system* dialog it needs (screen-capture
              consent) has no foreground surface to appear on, or when a background activity
              start would be dropped for lack of the overlay grant. Read the reply's reason for
              which of those it is; do not assume it is always about the notification switch.
            - E_AWAITING_CONSENT means this app's own approval question got no answer within
              the call's share of the channel. The question may sit on a floating card, in the
              notification shade or inside the assistant page, and a shade posting does not
              guarantee a banner was shown - so the code asserts "unanswered", not "on
              screen". Notification delivery on that route is likewise only ever reported as
              "delivered": visibility is never claimed, because a posting the user never
              pulled down looks identical to one they ignored.
              While the question waits, the host parks the request and finishes its reply when
              the user answers or the question's window closes - the channel keeps serving
              other calls in the meantime. So the move after this code is to follow the SAME
              request: status <request-id> until the reply settles, cancel <request-id> if it
              must not run. A re-send of the identical call while the original is parked
              answers this code at once (the parked request's outcome is the one that covers
              it) and raises no new dialog. When this code is the final reply of the call that
              raised the question - no room to park it, or a remaining budget too short to
              hold the dialog - the question stays up for its full window anyway: re-calling
              the same capability re-attaches to that question instead of asking the user
              twice, but leave a real gap between attempts.
              What an answer arriving after a call gave up is worth depends on whether the
              call's effect resolves against whatever the screen or the device holds at
              execution time - the node-level and coordinate ones, ui.snapshot, the three
              screen_* ones, audio.capture, app.install, media.write, and the reads whose
              answer is whatever is there at that moment (clip.read, loc.read, notify.read,
              pkg.query, media.read, contact.read, cal.read). For those, such an answer is
              never applied - there is nothing left to hand it to, so re-run the call and
              answer the fresh question; a tap the host had to discard comes back to the next
              attempt as an expired question, not as a spent approval. For everything else
              the answer is kept against the question and the next call for it takes that
              answer instead of asking again. Scope the completed-call case exactly: an
              answer from a call that did run to completion is written to the outbox and
              stays there even if the caller stopped polling first (measured on a real
              device with cal.read and screen.capture). So a late answer sitting in the
              outbox says the call executed; it does not say a reply was cached for a retry.
              What the retry then sees depends on whether the user has any way out of being
              asked again:
              - capabilities that offer "allow for this session": the retry raises a fresh
                dialog. If the user says they already tapped Allow, that is what happened -
                ask them to tap the new one.
              - capabilities that must ask every time (app.install, audio.capture, clip.read,
                loc.read, media.write, and anything aimed at a system consent screen): the
                "system consent screen" test looks at **who owns the window** - a permission
                component family (permissioncontroller, packageinstaller, SystemUI, a vendor
                permission manager) or a package the platform runs as system uid. Where Settings
                itself is system-owned that covers its ordinary pages too, so a node there asks
                even under always-allow; E_AWAITING_CONSENT there is not proof the tier was ignored.
                Once the node an action lands on is resolved, the execution-time guard applies to
                that node: a consent control is refused outright with E_NODE_SYSTEM_CONSENT_TARGET
                (exit 4) instead of being dispatched, and naming it by nodeId or by view id does not
                get around it. Scope, exactly: that guard covers calls that **act on** a node. A call
                that only **reads** one (ui.node, ui.snapshot) is judged from the arguments instead -
                a selector that names no package is not tested against the node's owner, and a nodeId
                is tested against whichever tree is cached at that moment, so an index taken before
                the dialog came up can resolve to the wrong owner. So: do not rely on a read being
                blocked, and do not answer a permission dialog by machine - that is the user's job.
                The retry on a card nobody answered returns E_GATE_APPROVAL_TIMEOUT instead of asking a second time, because
                the user did answer and a fresh dialog would just be answered late again. Do
                not retry that one on your own; start it over with the user.
              A question the user never answers stops being answerable when its window ends: a
              tap on the notification after that point is discarded, not applied.
            - Approval questions queue when several calls want an answer at once: one question
              is on the display slot and at most two wait behind it, so a fourth concurrent
              question answers E_GATE_WAITING_TURN (exit 5) - it was never shown to the user.
              Back off and ask again once the queue drains; re-sending immediately just lands
              behind the same crowd. A request cancelled while it waited for the user answers
              E_GATE_CANCELLED (exit 5): the host took it back before any external action was
              committed, so there are no side effects - the original request is settled for
              good, so starting the task over is a new request that will ask again, not a
              retry of this one.
            - You cannot choose which surface a call runs on; the user's preference decides
              it, and the response tells you what was actually used.
            - Do not pass the host app's own package name as an argument; those calls are
              rejected before they reach a backend. The one exception is app.launch with
              {"package":"<host>"} and no other key, which brings the assistant to the front so
              a finished task can hand control back to the user.
            - Rate limits exist per capability. Hitting one returns E_RATE_LIMITED; back off.
        """.trimIndent()
    }
}
