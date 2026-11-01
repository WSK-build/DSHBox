package developer.access.core

/**
 * 宿主侧需要向本模块提供的两项能力。
 *
 * 本模块不依赖 `:app`（依赖方向不允许），故解包与 guest 命令执行这两件事以接口由宿主注入。
 * 这与「解包与资产装配由 app 侧完成，结果经接口注入新模块」一致。
 */
interface GuestCommandRunner {

    /**
     * 在运行环境层内执行一条命令。
     *
     * @param command guest 侧 shell 命令串。
     * @param onLine 逐行输出回调。
     * @return 命令是否正常结束（进程退出码为 0 且未被中断）。
     */
    suspend fun run(command: String, onLine: (String) -> Unit = {}): Boolean
}

/**
 * APK 资产取用口。
 *
 * 资产随包分发（与既有小工具包同一资产目录），本模块只持有文件名清单。
 */
interface AssetSource {

    /**
     * 把资产复制到指定宿主文件。
     *
     * @return 复制成功（文件存在且非空）时为 true。
     */
    fun copyTo(assetName: String, target: java.io.File): Boolean
}
