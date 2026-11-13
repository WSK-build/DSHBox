package com.dshbox.app.ui.home

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dshbox.app.R
import com.dshbox.app.common.R as CommonR
import kotlinx.coroutines.delay

@Composable
internal fun EmergencyRepairPrompt(compact: Boolean = false) {
    val context = LocalContext.current
    val prompt = stringResource(R.string.emergency_repair_prompt_body)
    var expanded by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val scrollIsolation = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource) = available
        }
    }
    LaunchedEffect(copied) {
        if (copied) { delay(1500); copied = false }
    }
    val copyAction = {
        copied = runCatching {
            val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return@runCatching false
            clipboard.setPrimaryClip(ClipData.newPlainText("dsh-repair-prompt", prompt))
            true
        }.getOrDefault(false)
        if (!copied) Toast.makeText(context, R.string.emergency_repair_prompt_copy_failed, Toast.LENGTH_SHORT).show()
    }
    val copyLabel = stringResource(if (copied) R.string.dsh_run_log_copied else R.string.dsh_run_log_copy)

    if (compact) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.emergency_repair_prompt_compact_title),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(vertical = 12.dp),
            )
            TextButton(onClick = copyAction) { Text(copyLabel) }
        }
    } else {
        Column {
            InstanceRow(
                iconRes = CommonR.drawable.ic_file,
                title = stringResource(R.string.emergency_repair_prompt_title),
                description = stringResource(R.string.emergency_repair_prompt_desc),
                trailing = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CompactButton(text = copyLabel, onClick = copyAction)
                        CompactButton(
                            text = stringResource(if (expanded) R.string.emergency_repair_prompt_collapse else R.string.emergency_repair_prompt_expand),
                            onClick = { expanded = !expanded },
                        )
                    }
                },
            )
            if (expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = prompt,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp)
                            .nestedScroll(scrollIsolation).verticalScroll(rememberScrollState()).padding(12.dp),
                    )
                }
            }
        }
    }
}
