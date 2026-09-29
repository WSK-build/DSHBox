package interlock.relay.core.protocol

/**
 * 启动落地核验里已知的**中介包**：它们停在最前面不代表目标没起来，而是有一个系统级的选择框
 * 在等人挑一个图标（应用分身的双图标、"用哪个应用打开"）。这类框既不进控件树也不报错，
 * 只有截帧看得见 —— 所以它必须与"目标真没起来"分开回，否则调用方会去重建虚拟屏，
 * 而重建收不掉一个选择框。
 */
internal object LaunchMediators {

    private val known = setOf(
        "com.vivo.doubleinstance",
        "com.android.intentresolver",
    )

    fun isMediator(pkg: String?): Boolean = pkg != null && pkg in known
}
