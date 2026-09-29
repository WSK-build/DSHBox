package interlock.relay.core.spi

import android.content.Context

/**
 * 宿主侧文案与语言的取用口。core 内不直接绑定任何界面层语言机制：
 * 错误码、能力描述、通知标题等默认词条由 core 资源提供，宿主可整体替换成自己的
 * 多语实现（含运行期语言切换）。
 *
 * 默认实现用 applicationContext 与系统语言。
 */
interface RelayText {

    /**
     * 取词条。宿主实现负责语言选择；core 不假设任何语言策略。
     *
     * `@StringRes` 标在**参数**上（标在方法上会让 lint 报 SupportAnnotationUsage，且那也不是它要表达的意思）。
     */
    fun string(@androidx.annotation.StringRes resId: Int): String

    /** 当前生效的语言标签（BCP-47）；宿主没有语言概念时回 null。 */
    fun languageTags(): String?

    /** 默认实现：applicationContext 资源，语言跟随系统。 */
    class Default(context: Context) : RelayText {
        private val appContext = context.applicationContext

        override fun string(resId: Int): String = appContext.getString(resId)

        override fun languageTags(): String? =
            appContext.resources.configuration.locales.toLanguageTags()
    }
}
