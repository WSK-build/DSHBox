package interlock.relay.core.interlock

import interlock.relay.core.spi.RelayPrefs

/**
 * `sys.shell` 里「会改系统」那几条动词的逐条授权。
 *
 * 与档位分开存：档位管的是「这条能力要不要每次问人」，这里管的是「这一条动词有没有被用户
 * 打开过」。两者混在一张表里就会出现"为了放开蓝牙而把整条能力设成免确认"那种连带放松。
 *
 * 默认一律关闭，且只有用户在界面上拨过才为真 —— agent 侧没有任何路径能替它写这个偏好，
 * 写它的唯一入口是界面上的开关。通道停止也不清这些：它是用户对这台设备做的长期选择，
 * 不是本会话的临时许可。
 */
class ShellOptIns(private val prefs: RelayPrefs) {

    private val file = RelayPrefs.FILE_SHELL_VERBS

    fun isOpen(verb: String): Boolean = prefs.getBoolean(file, key(verb), false)

    fun set(verb: String, open: Boolean) {
        prefs.putBoolean(file, key(verb), open)
    }

    private fun key(verb: String) = PREFIX + verb

    private companion object {
        const val PREFIX = "verb_"
    }
}
