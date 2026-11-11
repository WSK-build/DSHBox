package com.dshbox.remote

import android.content.Context
import com.dshbox.dsh.renderer.api.DshRendererAdapter
import com.dshbox.dsh.renderer.api.DshSelectedFile
import java.io.File

/**
 * 远程连接模块的宿主注入口：宿主 app 在组装期把渲染引擎适配器交给模块。
 *
 * 引擎选择权在宿主（适配器实现位于 app 的 flavor sourceSet，模块不可引用），
 * 模块只面向 [DshRendererAdapter] 协议，不感知引擎与 flavor 差异。
 */
interface RemoteConnectionHost {
    /** 当前 flavor 的 DSH 渲染引擎适配器：远程会话的显示槽经它创建渲染器。 */
    fun rendererAdapter(): DshRendererAdapter

    /**
     * 宿主上下文：会话内核用于模块私有存档（设备档案、槽位分配与凭据加密
     * 存储）。返回 null 表示宿主未提供存档环境，会话能力保持骨架行为。
     */
    fun appContext(): Context? = null

    /**
     * 宿主附件来源面板选择：远程页面的 `<input type="file">` 请求经它改走
     * 宿主自绘的来源面板（与本地 DSH 页同一套七来源通道）。
     *
     * 三态约定（渲染宿主据此分派）：
     *  - 返回 null = 宿主未提供来源面板 → 渲染宿主退回既有系统选择器路径；
     *  - 返回空列表 = 用户在面板中取消 → 渲染宿主以「取消」结清该请求；
     *  - 返回非空列表 = 用户选定的文件，直接回填给页面。
     *
     * 在主线程挂起调用；面板与各来源通道的 UI 由宿主自行承载。
     *
     * [mimeTypes] 当前与本地页同口径忽略（面板不按 accept 过滤来源），保留
     * 参数以备后续按 MIME 收紧来源口径；实现方不得据此抛错。
     */
    suspend fun pickUploadFiles(multiple: Boolean, mimeTypes: List<String>): List<DshSelectedFile>? = null

    /**
     * 宿主下载目录：主框架响应判定为下载时，代理把响应体落盘到该目录并向
     * 渲染器回结果页。返回 null（默认）表示宿主未提供下载能力，响应照旧
     * 透传给渲染器。
     */
    fun downloadDirectory(): File? = null

    /** 是否已取得下载落点所需的存储权限（默认 true = 宿主不区分）。 */
    fun hasDownloadPermission(): Boolean = true

    /** 打开系统的存储权限授予页（宿主实现；默认无动作）。 */
    fun requestDownloadPermission() {}

    /**
     * 打开下载目录（共享区/DSHBox/download-remote）的宿主入口：下载器底部
     * 的「打开文件夹」动作经它跳转。宿主自行决定落点（如切到文件页并定位
     * 该目录）；无可用入口或无处定位时返回 false，调用方保持现状提示即可。
     * 默认 false = 宿主未实现该能力。
     */
    fun openDownloadsFolder(): Boolean = false
}
