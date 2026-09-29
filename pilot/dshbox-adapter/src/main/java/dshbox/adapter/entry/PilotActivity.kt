package dshbox.adapter.entry

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import dshbox.adapter.dshboxRelayConfig
import dshbox.adapter.ui.PilotScreen
import dshbox.adapter.ui.PilotTheme
import interlock.relay.core.runtime.RelayContainer
import interlock.relay.core.runtime.RelayRuntime

/**
 * 手机助手界面的唯一入口。宿主只需要在它的入口动作里发这一条 Intent，
 * 不需要引入本模块的装配、页面或状态类型。
 *
 * 本界面**不拥有通道生命周期**：启动走 [RelayRuntime.start] 的进程级作用域，
 * 关闭时也不停通道——助手会话期间信箱要一直有人消费。
 * 本界面只负责「用户在场」这一件事：它可见时确认框画在这里，不可见时同一个问题
 * 由通知栏去问，不靠把这一页推到前台来要答案。
 */
class PilotActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 带 dshbox 装配启动：若沙盒服务已先启动过通道，容器已存在，这里沿用首次装配的参数。
        RelayRuntime.start(applicationContext, dshboxRelayConfig(applicationContext))
        val container = RelayRuntime.get(applicationContext)
        setContent { PilotRoot(container = container) }
    }

    override fun onResume() {
        super.onResume()
        val container = RelayRuntime.get(applicationContext)
        container.foreground.visible = true
        // 语言是宿主那边改的，pilot 收不到任何回调；回到前台就是最近的一次对表时机，
        // 把常驻通知与渠道名换成当前手选语言（运行期还有通道那条服务的周期核对兜着）。
        PilotNotificationChannels.syncAll(applicationContext)
        // 前台状态变化是审批呈现路由的输入：回到页内后页内卡接管，通知那条路收起。
        container.onForegroundChanged()
    }

    override fun onStop() {
        super.onStop()
        val container = RelayRuntime.get(applicationContext)
        container.foreground.visible = false
        // 离开后页内那张框问不到人了：还没有结论的待决项由通知补挂，同一个问题换条路接着问。
        container.onForegroundChanged()
    }

    @Composable
    private fun PilotRoot(container: RelayContainer) {
        // 主题由 PilotScreen 内部施加；此处再套一层会让同一棵树过两遍主题。
        PilotScreen(container = container, onBack = { finish() })
    }

    companion object {
        /**
         * 供宿主入口动作使用的构造器。用类引用而不是 action 字符串，避免拼错后静默无响应。
         *
         * [Intent.FLAG_ACTIVITY_NEW_TASK]：宿主可能从应用级上下文（非 Activity）调用，
         * 缺该标志时 `startActivity` 直接抛 `AndroidRuntimeException`；Activity 上下文带它也无害。
         */
        fun intent(context: Context): Intent =
            Intent(context, PilotActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
