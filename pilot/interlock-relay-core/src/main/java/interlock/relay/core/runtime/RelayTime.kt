package interlock.relay.core.runtime

import android.os.SystemClock

/**
 * 单调时钟。所有超时、寿命、间隔判定一律使用它，避免用户调整系统时间后
 * 出现「全部条目瞬间超龄」或「清扫永不触发」。工程内既有实现同口径。
 */
fun monotonicNow(): Long = SystemClock.elapsedRealtime()

/** 墙钟只用于展示与记录，不参与任何差值计算。 */
fun wallNow(): Long = System.currentTimeMillis()
