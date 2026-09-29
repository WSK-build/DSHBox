package interlock.relay.core.exec.a11y

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import java.lang.ref.WeakReference

/**
 * 无障碍服务载体。界面文案与手势注入均由该服务承担，
 * 服务实例以弱引用暴露给后端使用，销毁时立即置空，避免持有已失效的上下文。
 */
class RelayAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        bound = WeakReference(this)
        onBoundChanged?.invoke()
    }

    /** 不消费事件流：内容采集一律按需读取节点树，避免依赖事件类型带来的时序问题。 */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        bound = null
        onBoundChanged?.invoke()
        super.onDestroy()
    }

    companion object {
        @Volatile
        private var bound: WeakReference<RelayAccessibilityService>? = null

        fun current(): RelayAccessibilityService? = bound?.get()

        /**
         * 绑定关系变化时通知装配根。开关这一服务是用户在系统页面里做的，本应用收不到
         * 任何系统回调，而依赖它的能力状态要写进沙盒侧那份能力清单——不留这个口子，
         * 能力清单就会长期停在「未授予」。回调可能在主线程上触发，切线程由接收方负责。
         */
        @Volatile
        var onBoundChanged: (() -> Unit)? = null
    }
}
