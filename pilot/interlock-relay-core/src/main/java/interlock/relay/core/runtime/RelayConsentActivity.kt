package interlock.relay.core.runtime

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * 屏幕采集系统弹框的唯一发起者。
 *
 * 系统那条「开始录制/投射」只能由 Activity 发起并收结果，而采集请求来自信箱的后台协程。
 * 这一趟不能由主界面代跑：主界面不在前台时整条采集失败，且用户点完确认会停在助手页
 * （部分机型停在应用内的设置页），而不是他原来的前台界面。
 *
 * 本界面因此不画任何东西：透明、不进最近任务、只有「备好弹框 → 交结果 → 收场」三件事。
 * 栈顶一消失，系统就回到用户原来的前台界面，采集链路上此后再无残留窗口。
 *
 * 系统弹框的结果只有两种来历算数：用户真的按了弹框上的键，或这一页真的结束了。
 * 转屏、深色切换一类的配置重建也会走一遍本界面的销毁与重建，但那不是用户在拒绝——
 * 平台会把 pending result 投递给重建出的实例（见 [onActivityResult]）续接同一次握手，
 * 此时回填取消等于替用户编造一个他从未做出的「拒绝」。
 */
class RelayConsentActivity : Activity() {

    @Volatile
    private var delivered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val projection = RelayRuntime.get(applicationContext).screenProjection
        val pending = projection.consentRequest.value
        // 待启动的意图已由采集侧备好；为空说明这一路被收掉了，别留下一个看不见的界面。
        if (pending == null) {
            finish()
            return
        }
        if (savedInstanceState != null) {
            // 重建续接的那一趟不再重新点火：上一次发出的系统弹框要么还挂着
            // （结果会由平台投给重建实例的 onActivityResult），要么结果已随界面状态存好、
            // 由系统补投。再发一次会在旧弹框上面叠出第二个弹框，用户答的是哪一次说不清。
            return
        }
        // 结果经平台的 pending result 通道回递：配置重建后系统仍会把它投给新实例，
        // 所以注册动作只需在发起这一趟做一次。
        startActivityForResult(pending, REQUEST_CONSENT)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CONSENT) deliver(resultCode, data)
    }

    /** 把系统弹框的结果原样递给采集侧。只认第一次，取消与确认都只回填一次。 */
    private fun deliver(resultCode: Int, data: Intent?) {
        if (delivered) return
        delivered = true
        RelayRuntime.get(applicationContext).screenProjection.onConsentResult(resultCode, data)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 只有这一页真的结束（用户按了返回、系统回收）才替用户回填一次「取消」；
        // 配置重建不算：那一趟由重建实例续接同一次握手，回填取消会把一次转屏记成拒绝。
        if (shouldReportCancelOnDestroy(isFinishing, isChangingConfigurations)) {
            deliver(RESULT_CANCELED, null)
        }
    }

    companion object {
        private const val REQUEST_CONSENT = 1

        /**
         * 销毁这一刻要不要替用户回一个「取消」。
         *
         * 判据只有「这一页真的在结束」：`isFinishing` 为真且不是配置重建。纯函数是为了
         * 在无设备环境下把「转屏被记成拒绝」这类回归钉住。
         */
        internal fun shouldReportCancelOnDestroy(isFinishing: Boolean, changingConfigurations: Boolean): Boolean =
            isFinishing && !changingConfigurations

        fun intent(context: Context): Intent =
            Intent(context, RelayConsentActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
