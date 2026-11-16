package interlock.relay.core.exec.direct

/**
 * 数据面：`notify.read` 不带 `package` 的"读全部"按名单过滤（纯函数，JVM 单测穷举，
 * 见 ProtectedEntriesFilterTest）。
 *
 * 带 `package` 且命中名单的调用在入口就被拒（[interlock.relay.core.interlock.protectedTarget]）；
 * "读全部"不能整条拒绝——那会把用户允许读的其它应用一并挡掉——所以通知快照在
 * DirectBackend.readNotifications 组装回包之前在这里收口：包名命中名单的条目整个丢弃，
 * 其余条目**原样保留**（字段不动、顺序不动），回包 count 与列表同源。名单为空时原样
 * 返回，零开销。
 *
 * 只按精确相等过滤，与入口的 [interlock.relay.core.interlock.ProtectedAppsStore.blocks]
 * 同一口径：包名读不到（空串）的条目不丢——通知快照的包名来自系统的 StatusBarNotification，
 * 空包名不是"读到受保护应用"，把它丢掉等于把读不到说成命中。
 */
internal fun <T> filterProtectedEntries(
    entries: List<T>,
    packageNameOf: (T) -> String,
    packages: Set<String>,
): List<T> = if (packages.isEmpty()) entries else entries.filter { packageNameOf(it) !in packages }
