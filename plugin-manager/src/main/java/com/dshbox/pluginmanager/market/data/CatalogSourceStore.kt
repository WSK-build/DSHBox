package com.dshbox.pluginmanager.market.data

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「当前数据源」的落盘。
 *
 * 刻意**单独一个文件**，而不是写进与桌面端共享的 `.dsh-market/state.json`：
 * 那份文件的字段归上游／桌面端所有（`channel` / `region` / `githubProxy` 等），
 * 我们负责的是原样带回；把手机端的目录选择塞进去，等于给共享格式加私货。
 *
 * 内容是**纯文本的源 id**：单值设置不值得上 JSON，也避开 `org.json` 在本地单测
 * 里是桩实现的问题。文件缺失、读失败、内容认不出，一律回落默认源 ——
 * 一个读坏的选择不该让市场打不开。
 */
class CatalogSourceStore(private val file: File) {

    suspend fun read(): CatalogSource = withContext(Dispatchers.IO) {
        val raw = runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull()
        CatalogSource.byId(raw?.trim())
    }

    /**
     * 写入选择；失败返回 false。
     *
     * 调用方据此提示"这次选择没能存住"，但**不阻断**切换本身：内存里已经换了源，
     * 只是下次启动会回到旧选择，比当场拒绝用户的操作更合理。
     */
    suspend fun write(source: CatalogSource): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(source.id + "\n")
        }.isSuccess
    }
}
