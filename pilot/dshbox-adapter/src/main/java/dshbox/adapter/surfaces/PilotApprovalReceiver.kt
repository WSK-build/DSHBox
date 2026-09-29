package dshbox.adapter.surfaces

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import interlock.relay.core.runtime.RelayRuntime
import interlock.relay.core.runtime.monotonicNow
import interlock.relay.core.interlock.InterlockChoice

/**
 * 通知栏上那三个按钮的落点。
 *
 * 答复只走「回填那张待决请求」这一条路：通知不自己记一次允许，也不把裁决结果写进任何存储，
 * 判定仍然全在闸门那一侧。它与界面里那张确认框回填的是同一个对象，所以两条通路谁先答都算数，
 * 后到的那一条自然落空。
 */
class PilotApprovalReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val choice = when (intent.action) {
            ACTION_ALLOW -> InterlockChoice.ALLOW_ONCE
            ACTION_ALLOW_SESSION -> InterlockChoice.ALLOW_SESSION
            ACTION_DENY -> InterlockChoice.DENY
            else -> return
        }
        val id = intent.getIntExtra(EXTRA_REQUEST_ID, NO_ID)
        // 编号丢了就不答任何一张：0 不是合法的请求编号（队列的基数每次进程启动随机取，
        // 且只取正数），而 -1 在队列那侧
        // 是「答当前挂着的那一张」的通配。广播重投递或组件被复用时拿通配去答，等于让
        // 用户对上一件事的点击答到后来挂上的另一件事上。
        if (id == NO_ID) {
            // 什么都不知道的那一次不去收通知：编号丢了，栏上那一行多半已经是别人的问题，
            // 无条件收掉等于替用户把新问题唯一的露面机会擦掉。它自己会到点收。
            return
        }
        val deadlineAtMs = intent.getLongExtra(EXTRA_DEADLINE, 0L)
        // 到点后的那一下不算答复：确认窗口一过，等这一答的调用早已走开，此时点「允许」
        // 放行的是一次没人在等的操作。收掉那条通知，用户看到它消失，比按下去没反应好查。
        val answered = deadlineAtMs > monotonicNow() &&
            RelayRuntime.resolveApproval(choice, id)
        // 收窗交给呈现对象而不是自己按编号擦：只擦通知的话，它那边的 shownId 与
        // expireAtMs 还停在「挂着」，那条到点回收也会继续空跑。
        if (!answered) RelayRuntime.dismissApprovalNotification(id)
    }

    companion object {
        const val ACTION_ALLOW = "dshbox.adapter.action.APPROVAL_ALLOW"
        const val ACTION_ALLOW_SESSION = "dshbox.adapter.action.APPROVAL_ALLOW_SESSION"
        const val ACTION_DENY = "dshbox.adapter.action.APPROVAL_DENY"
        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_DEADLINE = "deadline_at_ms"
        /** 缺件标记。不能取 -1：那是队列侧「不认编号」的通配值。 */
        private const val NO_ID = 0
    }
}
