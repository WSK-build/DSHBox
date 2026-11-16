package interlock.relay.core.exec.shizuku

/**
 * 「最新一帧」的持有器：**读不消费**，写入替换并把旧的收掉。
 *
 * 为什么不是一个 `AtomicReference`：`ImageReader` 的帧是**借来的缓冲**——
 * `Image.close()` 之后像素就没了，而"谁该 close 它"必须只有一个答案。
 * 原子引用上的 `getAndSet(null)` 把「取走」与「销毁」绑成了一步，于是
 * **两个消费者会抢同一帧**：谁先取谁拿走，另一个这一轮空手。
 * 的场景正是这样：上一次预览/截图把那一帧取走了，新一场流会话拿到 null，
 * 编码器 30 秒没有输入 → 插件的空闲看门狗断开，面板显示 abort。
 *
 * 这里把两件事拆开：
 * - **读**（[with]）：在锁内把当前那一帧交给调用方，**读完不 close、不清空**；
 * 调用方必须在锁内把像素拷走（拷走之后那一帧就能被换掉、被关掉了）。
 * - **写**（[put]）：换入新的一帧，**旧的那一帧在同一把锁里被 close 掉**。
 * 读方持锁期间写方等着 —— 于是「读到一半被换掉/被关掉」这件事不可能发生。
 *
 * 泛型 + 关闭回调：帧的类型与「怎么关」都由调用方给（设备侧是 `Image.close`），
 * 于是这份逻辑能在纯 JVM 上直接测（见 `LatestFrameHolderTest`）。
 */
internal class LatestFrameHolder<T>(
    /** 换下旧值 / 丢弃当前值时怎么把它关掉（设备侧：`Image.close`）。 */
    private val close: (T) -> Unit,
) {

    private val lock = Any()
    private var current: T? = null
    private var version = 0L

    /**
     * 换入新的一帧：旧的那一帧在同一把锁里被 close 掉。
     *
     * 关闭动作放在锁内是刻意的：读者（[with]）持锁期间不会被换掉，
     * 而换了之后旧帧立刻失效 —— 两者之间的窗口必须是零。
     */
    fun put(value: T) {
        synchronized(lock) {
            val previous = current
            current = value
            version += 1
            if (previous != null) close(previous)
        }
    }

    /**
     * 在锁内把当前那一帧交给 [block]：读完**不 close、不清空**。
     *
     * 手上没有帧时 [block] 不会被调用，回 null。
     * [block] 必须在锁内把要用的内容拷走（像素/字节），别把那一帧本身带出锁外 ——
     * 出锁之后它随时会被换掉、被关掉。
     */
    fun <R> with(block: (T) -> R): R? = synchronized(lock) {
        current?.let(block)
    }

    /**
     * 带代际的读：帧没换过时 [block] 拿到的还是同一版。
     *
     * 流会话用它区分“读到新帧”和“还是上一帧”——静止画面下读不消费，
     * 不能每 40ms 把同一帧当成新帧重复入队。
     */
    fun <R> withVersion(block: (T, Long) -> R): R? = synchronized(lock) {
        current?.let { block(it, version) }
    }

    /** 丢掉当前那一帧并 close 它（收屏/换屏/资源回收时统一走这里）。 */
    fun drop() {
        synchronized(lock) {
            val previous = current
            current = null
            if (previous != null) close(previous)
        }
    }

    /** 此刻有没有帧。 */
    fun isEmpty(): Boolean = synchronized(lock) { current == null }
}
