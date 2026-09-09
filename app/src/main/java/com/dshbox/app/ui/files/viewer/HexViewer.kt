package com.dshbox.app.ui.files.viewer

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dshbox.app.R
import com.dshbox.app.util.viewer.HexDumper
import com.dshbox.app.util.viewer.HexDumper.HexRow
import com.dshbox.app.util.formatFileSize
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 十六进制查看（1.2.0 §6.9）：三栏（偏移 | hex | ASCII），按 64KB 块随机读取，
 * LazyColumn 只渲染可视块，内存恒定。只读；「按文本打开」等出口由外壳顶栏提供。
 */
@Composable
internal fun HexViewer(
    file: File,
    magicLabel: String?,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val rowCount = HexDumper.rowCount(file.length())
    val rowsPerBlock = HexDumper.BLOCK_SIZE / HexDumper.ROW_BYTES
    val blocks = remember(file) { mutableStateMapOf<Int, List<HexRow>>() }
    val entropy = remember(file) { mutableStateOf<Double?>(null) }
    // 返工修正 #9：偏移列宽按文件大小自适应（>4GB 文件 %08X 溢出 8 位导致列漂移）
    val offsetWidth = maxOf(8, file.length().toString(16).length)

    // 头部信息：熵粗估（IO 异步，辅助判断压缩/加密）
    LaunchedEffect(file) {
        entropy.value = withContext(Dispatchers.IO) { HexDumper.entropyOf(file) }
    }

    // 可视范围 → 需要的块（前后各预留一块），缺失则后台加载
    val firstVisible = listState.firstVisibleItemIndex
    val lastVisible = (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: firstVisible)
    val firstBlock = HexDumper.blockOf(HexDumper.rowOffset(firstVisible))
    val lastBlock = HexDumper.blockOf(HexDumper.rowOffset(lastVisible))
    LaunchedEffect(file, firstBlock, lastBlock) {
        withContext(Dispatchers.IO) {
            for (b in (firstBlock - 1)..(lastBlock + 1)) {
                if (b >= 0 && !blocks.containsKey(b)) {
                    blocks[b] = HexDumper.formatBlock(
                        HexDumper.readBlock(file, b),
                        b.toLong() * HexDumper.BLOCK_SIZE,
                    )
                }
            }
            // 缓存上限：仅保留可视块附近 8 块
            if (blocks.size > 8) {
                blocks.keys.filter { it < firstBlock - 2 || it > lastBlock + 2 }
                    .forEach { blocks.remove(it) }
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // ---- 顶部信息（§6.9：大小 / 魔数 / 熵） ----
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                text = stringResource(R.string.files_hex_size, formatFileSize(file.length())),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            magicLabel?.let {
                Text(text = it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            entropy.value?.let { h ->
                Text(
                    text = stringResource(R.string.files_hex_entropy, h),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // ---- 三栏内容 ----
        // 十六进制区恒为 LTR（偏移/字节/ASCII 为方向敏感内容，
        // RTL 下避免整体右对齐与栏序镜像造成误读）。
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(rowCount, key = { it }) { row ->
                    val block = row / rowsPerBlock
                    val loaded = blocks[block]
                    val hexRow = loaded?.getOrNull(row % rowsPerBlock)
                    HexRowView(hexRow, offsetWidth)
                }
            }
        }
    }
}

@Composable
private fun HexRowView(row: HexRow?, offsetWidth: Int) {
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Start,
    ) {
        Text(
            text = row?.offset?.let { String.format(java.util.Locale.US, "%0${offsetWidth}X", it) } ?: " ".repeat(offsetWidth),
            style = mono,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = row?.hex ?: " ",
            style = mono,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = row?.ascii ?: " ",
            style = mono,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
