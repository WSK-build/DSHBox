package com.dshbox.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dshbox.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Offline reader restricted to the license bundle generated at build time. */
@Composable
fun LicensesScreen(onBack: () -> Unit, backEnabled: Boolean, modifier: Modifier = Modifier) {
    val assets = LocalContext.current.assets
    var selected by remember { mutableStateOf<String?>(null) }
    val index by produceState<Result<List<String>>?>(null, assets) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                assets.open("licenses/INDEX.txt").bufferedReader().use { reader ->
                    reader.readLines().filter { path ->
                        path.isNotBlank() && !path.startsWith('/') &&
                            path.split('/').none { it == ".." } && '\\' !in path
                    }
                }
            }
        }
    }
    val document by produceState<Result<String>?>(null, selected, index) {
        value = null
        val path = selected ?: return@produceState
        if (path !in index?.getOrNull().orEmpty()) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching { assets.open("licenses/$path").bufferedReader().use { it.readText() } }
        }
    }
    val goBack = { if (selected != null) selected = null else onBack() }
    BackHandler(enabled = backEnabled, onBack = goBack)
    Column(modifier.fillMaxSize().padding(24.dp)) {
        TextButton(onClick = goBack) { Text(stringResource(R.string.diagnostics_back)) }
        Text(stringResource(R.string.settings_open_source_licenses), style = MaterialTheme.typography.titleLarge)
        if (selected != null) {
            Text(selected.orEmpty(), style = MaterialTheme.typography.labelSmall)
            SelectionContainer(Modifier.weight(1f)) {
                Text(
                    document?.getOrNull() ?: stringResource(
                        if (document?.isFailure == true) R.string.licenses_read_error else R.string.licenses_loading,
                    ),
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        } else {
            when {
                index == null -> Text(stringResource(R.string.licenses_loading))
                index?.isFailure == true -> Text(stringResource(R.string.licenses_read_error))
                else -> LazyColumn(Modifier.weight(1f)) {
                    items(index?.getOrNull().orEmpty(), key = { it }) { path ->
                        Text(path, Modifier.fillMaxWidth().clickable { selected = path }.padding(vertical = 12.dp))
                    }
                }
            }
        }
    }
}
