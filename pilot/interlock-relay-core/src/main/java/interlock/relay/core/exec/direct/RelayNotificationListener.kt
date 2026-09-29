package interlock.relay.core.exec.direct

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger

/**
 * 通知读取通路。系统只有在用户于「通知使用权」列表里放行本应用后才会绑定这个服务，
 * 因此「服务已连接」本身就是系统权限可达的判据，不需要再去读一次设置项。
 *
 * 读的是**已发布通知的快照**，不主动点任何通知：连接时先按系统当前那一栏补一次历史，
 * 之后由回调积累，助手调用时取走窗口内的条目（窗口见 [Holder] 的条数与年龄限界）。
 */
class RelayNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        // 先把历史补完再 attach：isConnected() 一为真，闸门就放行 notify.read，
        // 那一刻窗口若只填了一半，助手拿到的就是一张看着像"没有通知"的表。
        // 取不到快照不算失败：回调那一路还在，只是这一屏的历史读不到。
        runCatching { activeNotifications?.toList() }.getOrNull()
            ?.sortedBy { it.postTime }
            ?.forEach { Holder.record(Entry.of(it)) }
        Holder.attach(this)
    }

    override fun onListenerDisconnected() {
        Holder.detach(this)
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        Holder.record(Entry.of(sbn))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val key = sbn?.let { Entry.keyOf(it) } ?: return
        Holder.retire(key)
    }

    data class Entry(
        val key: String,
        val packageName: String,
        val title: String,
        val text: String,
        val postedAtMs: Long,
        val ongoing: Boolean,
    ) {
        companion object {
            fun keyOf(sbn: StatusBarNotification): String =
                "${sbn.packageName}/${sbn.id}/${sbn.tag}"

            fun of(sbn: StatusBarNotification): Entry {
                val notification = sbn.notification
                val extras = notification.extras
                return Entry(
                    key = keyOf(sbn),
                    packageName = sbn.packageName.orEmpty(),
                    title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString().orEmpty(),
                    text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString().orEmpty(),
                    postedAtMs = sbn.postTime,
                    ongoing = notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0,
                )
            }
        }
    }

    /**
     * 服务实例与快照窗口。
     *
     * 快照按条数与年龄双重限界，且只存内存：通知正文属于用户数据，
     * 既不落盘也不无限累积。
     */
    object Holder {
        @Volatile
        private var connected: RelayNotificationListener? = null

        private val entries = ConcurrentLinkedDeque<Entry>()
        private val count = AtomicInteger(0)

        /**
         * 连接状态变化的通知口子。放行使本服务被系统绑上，收权时被解绑，两者都只有
         * 这里知道；能力清单要把 `notify.read` 的可用状态跟着这次变化走，否则用户刚给的
         * 授权要等到下一次巡检才反映出去。
         */
        @Volatile
        var onBoundChanged: (() -> Unit)? = null

        fun isConnected(): Boolean = connected != null

        fun snapshot(limit: Int, packageFilter: String?, includeOngoing: Boolean): List<Entry> {
            pruneExpired()
            val out = ArrayList<Entry>(limit)
            for (entry in entries) {
                if (!includeOngoing && entry.ongoing) continue
                if (packageFilter != null && entry.packageName != packageFilter) continue
                out += entry
                if (out.size >= limit) break
            }
            return out
        }

        /**
         * 过期条目要主动删。只在读取时按年龄过滤的话，没人调用本能力时
         * 200 条通知正文会长期常驻内存 —— 与「条数与年龄双重限界」的说法不符。
         */
        private fun pruneExpired() {
            val cutoff = System.currentTimeMillis() - WINDOW_MS
            while (true) {
                val last = entries.peekLast() ?: break
                if (last.postedAtMs >= cutoff) break
                if (entries.pollLast() == null) break
                count.decrementAndGet()
            }
        }

        internal fun attach(service: RelayNotificationListener) {
            connected = service
            onBoundChanged?.invoke()
        }

        internal fun detach(service: RelayNotificationListener) {
            if (connected === service) {
                connected = null
                clear()
                onBoundChanged?.invoke()
            }
        }

        internal fun record(entry: Entry) {
            if (entry.title.isEmpty() && entry.text.isEmpty()) return
            // 同一个 key 重新发布要覆盖而不是并排两份：进度类通知每更新一次就多出一条，
            // 一次"下载中 10%→90%"会被数成几十条通知；连接时补的历史快照与随后到来的
            // 那一条也是同一件事，不能算两条。
            retire(entry.key)
            entries.addFirst(entry)
            if (count.incrementAndGet() > MAX_ENTRIES) trimToMax()
        }

        /** 同一 key 可能有多条（同包同 id 复用），扣减必须按实际删除数，否则计数与队列漂移。 */
        internal fun retire(key: String) {
            var removed = 0
            val iterator = entries.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().key == key) {
                    iterator.remove()
                    removed++
                }
            }
            if (removed > 0) count.addAndGet(-removed)
        }

        private fun trimToMax() {
            // 取不到节点就必须停：count 与队列之间有一个"节点已摘、计数未扣"的非原子窗口，
            // 只靠 count 收尾时这里会在空队列上死转。pruneExpired 同一个口径。
            while (count.get() > MAX_ENTRIES) {
                if (entries.pollLast() == null) break
                count.decrementAndGet()
            }
        }

        private fun clear() {
            entries.clear()
            count.set(0)
        }

        private const val MAX_ENTRIES = 200
        private const val WINDOW_MS = 30L * 60L * 1000L
    }
}
