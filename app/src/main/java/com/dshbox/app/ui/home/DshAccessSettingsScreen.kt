package com.dshbox.app.ui.home

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dshbox.app.R
import androidx.compose.runtime.collectAsState
import com.dshbox.app.DshApp
import com.dshbox.app.dsh.DshDiagnosticLogs
import com.dshbox.app.sandbox.DshState
import com.dshbox.pluginmanager.ui.bootLogAnnotated
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import com.dshbox.app.ui.configurationAwareResources
import com.dshbox.app.ui.webview.dshUrlWithToken
import com.dshbox.app.ui.webview.openDefaultAppsSettings
import kotlinx.coroutines.delay
import com.dshbox.app.ui.theme.AppDshAddressState
import com.dshbox.app.common.R as CommonR

/**
 * Local DSH access occupies the same full-screen subpage layer as Pilot/Cordis detail pages.
 * Its rows describe the APP's external-access boundary; DSH itself is not modified.
 */
@Composable
internal fun DshAccessSettingsScreen(
    launchToken: String?,
    uptime: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val resources = configurationAwareResources()
    val fullUrl = remember(launchToken, AppDshAddressState.baseUrl) {
        launchToken
            ?.takeIf { it.isNotEmpty() }
            ?.let { dshUrlWithToken(AppDshAddressState.baseUrl, it) }
    }
    var copied by remember(fullUrl) { mutableStateOf(false) }
    var settingsFailed by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1_500)
            copied = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(start = 4.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = ImageVector.vectorResource(CommonR.drawable.ic_arrow_left),
                    contentDescription = stringResource(R.string.home_browser_more_back),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clickable(onClick = onBack)
                        .padding(12.dp)
                        .size(22.dp),
                )
                Text(
                    text = stringResource(R.string.home_browser_more_title),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(26.dp),
        ) {
            Text(
                text = stringResource(R.string.home_browser_more_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SettingsSection(stringResource(R.string.home_browser_more_section_connection)) {
                AddressRow(
                    fullUrl = fullUrl,
                    copied = copied,
                    onCopy = {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        if (clipboard != null && fullUrl != null) {
                            clipboard.setPrimaryClip(
                                ClipData.newPlainText(
                                    resources.getString(R.string.home_browser_more_title),
                                    fullUrl,
                                ),
                            )
                            copied = true
                        }
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsRow(
                    iconRes = CommonR.drawable.ic_browser_history,
                    title = stringResource(R.string.home_browser_more_service_title),
                    description = stringResource(R.string.home_browser_more_service_help),
                    trailing = {
                        Text(
                            text = uptime,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
                DshRunLogSection()
            }

            SettingsSection(stringResource(R.string.home_browser_more_section_external)) {
                SettingsRow(
                    iconRes = CommonR.drawable.ic_external_link,
                    title = stringResource(R.string.home_browser_more_default_apps),
                    description = stringResource(R.string.home_browser_more_default_browser_help),
                    trailing = {
                        CompactActionButton(
                            text = stringResource(R.string.home_browser_more_change),
                            onClick = {
                                settingsFailed = !openDefaultAppsSettings(context)
                            },
                        )
                    },
                )
                if (settingsFailed) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(
                        text = stringResource(R.string.home_browser_more_settings_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 36.dp, vertical = 12.dp),
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsRow(
                    iconRes = CommonR.drawable.ic_layout_grid,
                    title = stringResource(R.string.home_browser_more_in_app_title),
                    description = stringResource(R.string.home_browser_more_in_app_help),
                    trailing = {
                        Text(
                            text = stringResource(R.string.home_browser_more_unchanged),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }

            SettingsSection(stringResource(R.string.home_browser_more_section_compat)) {
                SettingsRow(
                    iconRes = CommonR.drawable.ic_help_circle,
                    title = stringResource(R.string.home_browser_more_compat_title),
                    description = stringResource(R.string.home_browser_more_chromium_help),
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsRow(
                    iconRes = CommonR.drawable.ic_lock,
                    title = stringResource(R.string.home_browser_more_credential_title),
                    description = stringResource(R.string.home_browser_more_credential_help),
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    text = stringResource(R.string.home_browser_more_scope_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 14.dp),
                )
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface),
        ) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            content()
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun AddressRow(
    fullUrl: String?,
    copied: Boolean,
    onCopy: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            SettingsIcon(CommonR.drawable.ic_world)
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.home_browser_more_address),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(8.dp))
            CompactActionButton(
                text = stringResource(
                    if (copied) R.string.home_browser_more_copied else R.string.home_browser_more_copy,
                ),
                enabled = fullUrl != null,
                onClick = onCopy,
            )
        }
        if (fullUrl != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 32.dp, top = 10.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(56.dp)
                        .background(MaterialTheme.colorScheme.primary),
                )
                SelectionContainer(modifier = Modifier.weight(1f)) {
                    Text(
                        text = fullUrl,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
            }
            Text(
                text = stringResource(R.string.home_browser_more_address_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 32.dp, top = 8.dp),
            )
        } else {
            Text(
                text = stringResource(R.string.home_browser_more_token_missing),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 32.dp, top = 8.dp),
            )
        }
    }
}

/**
 * DSH 运行日志折叠面板：标题行在 DSH 运行失败（ERROR）时变红；点击展开定高面板，
 * 滚动查看最近启动日志（经 [DshDiagnosticLogs.readTail] 脱敏），右上复制。
 * 日志源与插件面板「插件加载记录」同一份 process-dsh.log，着色复用 [bootLogAnnotated]。
 */
@Composable
private fun DshRunLogSection() {
    val context = LocalContext.current
    val dshState by (context.applicationContext as DshApp)
        .container.sandboxManager.dshState.collectAsState()
    val failed = dshState == DshState.ERROR
    var expanded by remember { mutableStateOf(false) }
    var logText by remember { mutableStateOf("") }
    var copied by remember { mutableStateOf(false) }
    val markerColor = MaterialTheme.colorScheme.error
    val bodyColor = MaterialTheme.colorScheme.onSurfaceVariant

    LaunchedEffect(expanded) {
        if (expanded) {
            logText = withContext(Dispatchers.IO) {
                val file = File(context.filesDir, "logs/process-dsh.log")
                DshDiagnosticLogs.readTail(file, lineLimit = 4000, byteLimit = 200 * 1024)
                    .lines.joinToString("\n")
            }
        } else {
            logText = ""
        }
    }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    val annotated = remember(logText, markerColor, bodyColor) {
        bootLogAnnotated(logText, markerColor, bodyColor)
    }

    // 嵌套滚动隔离：日志框内滚动剩余量由此消费，不再外溢带动整页滚动。
    val scrollIsolation = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset = Offset.Zero
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset = available
        }
    }

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(horizontal = 4.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = ImageVector.vectorResource(CommonR.drawable.ic_browser_history),
            contentDescription = null,
            tint = if (failed) markerColor else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.dsh_run_log_title),
                style = MaterialTheme.typography.bodyLarge,
                color = if (failed) markerColor else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(
                    if (failed) R.string.dsh_run_log_failed else R.string.dsh_run_log_hint,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = if (failed) markerColor else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = if (expanded) "▴" else "▾",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (expanded) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            Text(
                text = stringResource(
                    if (copied) R.string.dsh_run_log_copied else R.string.dsh_run_log_copy,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable(enabled = logText.isNotBlank()) {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        if (clipboard != null) {
                            clipboard.setPrimaryClip(ClipData.newPlainText("dsh-run-log", logText))
                            copied = true
                        }
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp)
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .nestedScroll(scrollIsolation),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            if (logText.isBlank()) {
                Text(
                    text = stringResource(R.string.dsh_run_log_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp),
                )
            } else {
                Text(
                    text = annotated,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                )
            }
        }
    }
}

@Composable
private fun SettingsRow(
    iconRes: Int,
    title: String,
    description: String,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        SettingsIcon(iconRes)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (trailing != null) {
            Spacer(modifier = Modifier.width(8.dp))
            trailing()
        }
    }
}

@Composable
private fun SettingsIcon(iconRes: Int) {
    Icon(
        imageVector = ImageVector.vectorResource(iconRes),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(top = 2.dp)
            .size(20.dp),
    )
}

@Composable
private fun CompactActionButton(
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
        modifier = Modifier
            .widthIn(min = 64.dp)
            .height(28.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            softWrap = false,
        )
    }
}
