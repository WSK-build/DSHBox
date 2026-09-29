package interlock.relay.core.exec.a11y

import java.util.concurrent.atomic.AtomicReference

/**
 * 这台机器上，可信虚拟屏的那棵节点树到底取到没有。
 *
 * 为什么要单独记：能力清单把节点级那十一条（十条动作加 ui.snapshot）也声明在 `trusted-display`
 * 上，但这条声明兑现与否是一台机器一件事 —— 取不到树的机器上，一条节点级调用要撞一次才
 * 知道不通，而助手是在规划流程时读能力清单的，那一刻它需要这个词，不是事后一句报错。
 *
 * 只记看过的那一次，并且**跟着屏号走**：换了一块屏（编号变了）就退回未测。拿旧屏的结论替
 * 新屏说话，等于让能力清单在一个从没查过的对象上表态。
 */
internal class TrustedNodeTree {

    private data class Observation(val displayId: Int, val treeFound: Boolean)

    private val last = AtomicReference<Observation?>()

    /** 记一次实际取树的结果。[displayId] 是这次真的去取的那块屏。 */
    fun observe(displayId: Int, treeFound: Boolean) {
        last.set(Observation(displayId, treeFound))
    }

    /**
     * 能力清单上那一个词。[aliveDisplayId] 是当前那块可信屏的编号，没有屏时传负数。
     *
     * 三个词说的都是**上一次取树看到了什么**，不是这台机器的性质：`untested` 是还没人真去取过，
     * 助手据此知道这条还没有证据，而不是把它读成一次否决。
     *
     * 传负数时把记录清掉，而不只是回 `untested`：屏号是可以复用的，只回不清会让"回收后重建的
     * 同一号屏"读起来像是已经查过 —— 那正是这个字段唯一该避免说的那种假话。能力清单每分钟重铺一次，
     * 所以这一次读就把这一次账结掉。
     */
    fun state(aliveDisplayId: Int): String {
        if (aliveDisplayId < 0) {
            last.set(null)
            return STATE_UNTESTED
        }
        val seen = last.get() ?: return STATE_UNTESTED
        if (seen.displayId != aliveDisplayId) return STATE_UNTESTED
        return if (seen.treeFound) STATE_LAST_SEEN else STATE_LAST_EMPTY
    }

    companion object {
        /** 上一次去取，取到了一棵树。 */
        const val STATE_LAST_SEEN = "last-seen"

        /** 上一次去取，那块屏上一颗窗口都没有。 */
        const val STATE_LAST_EMPTY = "last-empty"

        /** 还没为当前这块屏取过。 */
        const val STATE_UNTESTED = "untested"
    }
}
