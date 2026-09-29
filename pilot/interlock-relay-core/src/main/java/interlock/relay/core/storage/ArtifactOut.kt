package interlock.relay.core.storage

/**
 * 产物交付目录（`out/`）的分类与回收参数。
 *
 * 为什么单列一处：这三个映射与四个数字在**入口 CLI 里另有一份**（入口脚本
 * 的 `OUT_SUBDIRS` 与 `OUT_*` 常量）——CLI 在缺省 `--out` 时要算出与宿主同一个落点，
 * 两边必须一致。`ArtifactOutTest` 逐条比对两侧的字面量，改一侧必须改另一侧。
 *
 * 为什么产物要有独立配额：`out/` 是**交付物**（助手与用户直接消费），
 * `run/artifacts/` 是宿主自己的原始副本；两者的寿命需求不同（原始副本 24 小时足够，
 * 交付物要留到用户拿走），因此配额/保留分开设，见下面的常量。
 */
object ArtifactOut {

    /** 屏幕截图（`screen.capture`）。 */
    const val SHOT = "shot"

    /** 屏幕录制（`screen.record`）。 */
    const val VIDEO = "video"

    /** 录音（`audio.capture`）。 */
    const val AUDIO = "audio"

    /** 其它交付文件（兜底：不在上面三类的产物一律落这里）。 */
    const val FILE = "file"

    /** 全部子目录，顺序即回收时的遍历顺序。 */
    val SUBDIRS = listOf(SHOT, VIDEO, AUDIO, FILE)

    /** 能力 → 子目录。没列到的能力一律落 [FILE]（宁可粗一点，也不要猜错类别）。 */
    fun subdirFor(capability: String): String = when (capability) {
        "screen.capture" -> SHOT
        "screen.record" -> VIDEO
        "audio.capture" -> AUDIO
        else -> FILE
    }

    /**
     * 交付目录的总量上限。超过就从最旧的开始回收（每类至少保留最新一份）。
     * 选 256 MB：一次 30 秒录屏（约 20–60 MB）加上一组截图都装得下，
     * 又远小于任何机型的可用空间告警线。
     */
    const val MAX_TOTAL_BYTES = 256L * 1024L * 1024L

    /** 每个子目录保留的最近份数；与插件既有策略（`SNAPSHOT_KEEP = 20`）取同一个值。 */
    const val KEEP_PER_KIND = 20

    /** 龄期上限：超过这个年龄的交付物回收（在份数与总量都够松时兜底）。 */
    const val MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L

    /**
     * 宽限期：最近这段时间内改动过的文件一律不动。
     * 录制中的视频可能正在被写入，删掉正在写的那一个等于把一次录制作废。
     */
    const val RECENT_GRACE_MS = 10L * 60L * 1000L
}
