package com.dshbox.app.ui.webview

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dshbox.app.R
import com.dshbox.app.ui.files.ControlBg
import com.dshbox.app.ui.files.PageBg
import com.dshbox.app.ui.files.TextPrimary
import com.dshbox.app.ui.files.filesUseDarkTheme
import com.dshbox.app.common.R as CommonR

/**
 * 附件上传的「来源」面板 —— 七枚方块，两行（上四、下三左对齐）。
 *
 * ## 它解决什么
 *
 * 网页端 `<input type="file">` 走系统选择器只能取到手机侧内容来源：PRoot 沙箱不是
 * Android 的 DocumentsProvider，工作区与共享区永远不出现在系统选择器里；相机、相册、
 * 音频、录音更是上游完全没有的能力。因此**由宿主自绘来源面板**，用户先选来源，
 * 再走该来源对应的通道取文件。
 *
 * ## 交付方式不变（关键）
 *
 * 本面板只替换「选来源」这一层：选中的文件仍由 [DshWebContainer.submitFileChooserResult]
 * 回填给**同一个**隐藏 `<input type="file">`，对网页而言依旧是一次普通上传，
 * 附件照旧落在 DSH 的 composer 里。
 *
 * ## 与插件的关系
 *
 * 与 `dsh-mobile-adapt` 插件**无关**：来源面板由原生在 `onShowFileChooser` 处无条件接管，
 * 插件被拔掉（或「移动端适配」开关关闭）时本面板照常工作。插件里原有的来源菜单已删除。
 *
 * ## 关闭即结清
 *
 * 面板被右上角 X、点遮罩或系统返回键关闭时，[onDismiss] 必须让调用方以 `null` 结清
 * WebView 回调 —— 回调必须恰好被调用一次，否则那个 `<input>` 会永久锁死。
 *
 * @param onDismiss 用户放弃选择（调用方须以 `null` 结清回调）。
 * @param onPick 用户选定来源；调用方据此进入该来源的取文件通道。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UploadSourcePanel(
    onDismiss: () -> Unit,
    onPick: (UploadSource) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // 面板无标题：不画拖拽把手，关闭只留右上角 X（与文件页加号面板同款）
        dragHandle = null,
        containerColor = PageBg(),
        // 遮罩浓度与文件页加号面板同值，保证整机观感一致。
        scrimColor = Color.Black.copy(alpha = if (filesUseDarkTheme()) 0.52f else 0.32f),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // 封顶半屏：超高时由面板内部滚动，不占满整屏
                    .heightIn(max = maxHeight * 0.5f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 20.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                        Icon(
                            imageVector = ImageVector.vectorResource(CommonR.drawable.ic_x),
                            contentDescription = stringResource(R.string.files_close),
                            tint = TextPrimary(),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                // 上排四项。下排三项 + 一个等宽空位：第二行「左移一个位置」后仍与
                // 第一行同列宽，不因少一项而把三枚磁贴拉宽。
                SourceRow(
                    tiles = listOf(
                        UploadSource.PHOTO to (R.drawable.ic_camera to R.string.webview_upload_src_photo),
                        UploadSource.VIDEO to (R.drawable.ic_video to R.string.webview_upload_src_video),
                        UploadSource.GALLERY to (R.drawable.ic_photo to R.string.webview_upload_src_gallery),
                        UploadSource.PHONE to (R.drawable.ic_paperclip to R.string.webview_upload_src_phone),
                    ),
                    onPick = onPick,
                )
                Spacer(Modifier.height(10.dp))
                SourceRow(
                    tiles = listOf(
                        UploadSource.AUDIO to (R.drawable.ic_music to R.string.webview_upload_src_audio),
                        UploadSource.WORKSPACE to
                            (CommonR.drawable.ic_folder to R.string.webview_upload_src_workspace),
                        UploadSource.SHARE to
                            (R.drawable.ic_folder_share to R.string.webview_upload_src_share),
                    ),
                    trailingBlankSlots = 1,
                    onPick = onPick,
                )
            }
        }
    }
}

/** 一行来源磁贴；[trailingBlankSlots] 用于补齐列宽（保持与上一行同列）。 */
@Composable
private fun SourceRow(
    tiles: List<Pair<UploadSource, Pair<Int, Int>>>,
    onPick: (UploadSource) -> Unit,
    trailingBlankSlots: Int = 0,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        tiles.forEach { (source, spec) ->
            SourceTile(
                iconRes = spec.first,
                labelRes = spec.second,
                onClick = { onPick(source) },
                modifier = Modifier.weight(1f),
            )
        }
        repeat(trailingBlankSlots) { Spacer(Modifier.weight(1f)) }
    }
}

/** 单枚来源磁贴：图标在上、文案在下，整块可点。 */
@Composable
private fun SourceTile(
    iconRes: Int,
    labelRes: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .height(92.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(ControlBg())
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = ImageVector.vectorResource(iconRes),
            contentDescription = null,
            // 面板图标统一取导航键灰色，不用品牌绿
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(26.dp),
        )
        Spacer(Modifier.height(9.dp))
        Text(
            text = stringResource(labelRes),
            fontSize = 12.sp,
            color = TextPrimary(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
