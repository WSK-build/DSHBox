package com.dshbox.app.ui.webview

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dshbox.app.R
import com.dshbox.app.dsh.DshRendererEngine
import com.dshbox.app.dsh.DshRendererPhase
import com.dshbox.app.dsh.DshRendererReport
import com.dshbox.app.dsh.DshDiagnosticLogs
import com.dshbox.app.ui.files.PrimaryGreen
import com.dshbox.app.ui.configurationAwareResources
import com.dshbox.dsh.renderer.api.DshWebCapability
import java.text.DateFormat
import java.util.Date

/**
 * Shared DSH report card.
 *
 * This card reports observations and stages only. It deliberately has no
 * "passed" state: API/CSS presence and page-stop callbacks are not business
 * readiness evidence.
 */
@Composable
fun DshRendererReportCard(
    report: DshRendererReport,
    onViewLogs: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val resources = configurationAwareResources()
    val unknown = stringResource(R.string.dsh_renderer_result_unknown)
    val updatedAt = remember(report.updatedAtMillis) {
        report.updatedAtMillis?.let {
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM)
                .format(Date(it))
        }
    }
    val accent = when (report.phase) {
        DshRendererPhase.FAILED -> MaterialTheme.colorScheme.error
        DshRendererPhase.RELEASED -> MaterialTheme.colorScheme.outline
        DshRendererPhase.NOT_COLLECTED -> MaterialTheme.colorScheme.outline
        else -> MaterialTheme.colorScheme.primary
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.dsh_renderer_report_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                PhaseBadge(phase = report.phase, accent = accent)
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FingerprintRow(
                    label = stringResource(R.string.dsh_renderer_engine),
                    value = stringResource(engineLabel(report.engine)),
                )
                report.engineBuild?.let {
                    FingerprintRow(
                        label = stringResource(R.string.dsh_renderer_engine_build),
                        value = it,
                    )
                }
                if (report.engine == DshRendererEngine.SYSTEM_WEBVIEW) {
                    FingerprintRow(
                        label = stringResource(R.string.dsh_renderer_provider),
                        value = report.provider?.packageName ?: unknown,
                    )
                    FingerprintRow(
                        label = stringResource(R.string.dsh_renderer_provider_version),
                        value = report.provider?.versionName ?: unknown,
                    )
                }
                FingerprintRow(
                    label = stringResource(R.string.dsh_renderer_user_agent),
                    value = report.userAgent ?: unknown,
                )
                if (updatedAt != null) {
                    FingerprintRow(
                        label = stringResource(R.string.dsh_renderer_updated_at),
                        value = updatedAt,
                    )
                }
            }

            if (report.phase == DshRendererPhase.NOT_COLLECTED) {
                NoteText(stringResource(R.string.dsh_renderer_not_collected_note))
            }
            if (report.engine == DshRendererEngine.GECKO) {
                NoteText(stringResource(R.string.dsh_renderer_gecko_note))
            }

            Text(
                text = stringResource(R.string.dsh_renderer_capabilities),
                style = MaterialTheme.typography.labelLarge,
            )
            if (!report.capabilitiesObserved) {
                NoteText(stringResource(R.string.dsh_renderer_capabilities_not_collected))
            } else {
                CapabilityGroup(
                    title = stringResource(R.string.dsh_renderer_javascript),
                    capabilities = JAVASCRIPT_CAPABILITIES,
                    values = report.effectiveCapabilities,
                    nativeValues = report.nativeCapabilities,
                )
                CapabilityGroup(
                    title = stringResource(R.string.dsh_renderer_worker_capabilities),
                    capabilities = WORKER_CAPABILITIES,
                    values = report.workerCapabilities,
                    nativeValues = null,
                )
                val missingWorkerApis = report.workerDetails
                    .filterValues { !it }
                    .keys
                    .sorted()
                if (report.workerCapabilities != null && missingWorkerApis.isNotEmpty()) {
                    NoteText(
                        stringResource(
                            R.string.dsh_renderer_worker_missing_apis,
                            missingWorkerApis.joinToString(),
                        ),
                    )
                }
                CapabilityGroup(
                    title = stringResource(R.string.dsh_renderer_css),
                    capabilities = CSS_CAPABILITIES,
                    values = report.effectiveCapabilities,
                    nativeValues = report.nativeCapabilities,
                )
                NoteText(stringResource(R.string.dsh_renderer_css_observation_note))
            }

            NoteText(
                text = stringResource(R.string.dsh_renderer_disclaimer),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ReportActionButton(
                    label = stringResource(R.string.dsh_renderer_copy_report),
                    onClick = {
                        val copied = runCatching {
                            val clipboard = context.getSystemService(ClipboardManager::class.java)
                                ?: return@runCatching false
                            clipboard.setPrimaryClip(ClipData.newPlainText(
                                resources.getString(R.string.dsh_renderer_report_title),
                                DshDiagnosticLogs.exportReport(report),
                            ))
                            true
                        }.getOrDefault(false)
                        Toast.makeText(context,
                            if (copied) R.string.onboarding_webview_copied else R.string.dsh_renderer_copy_failed,
                            Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f),
                )
                onViewLogs?.let { openLogs ->
                    ReportActionButton(
                        label = stringResource(R.string.dsh_renderer_view_logs),
                        onClick = openLogs,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ReportActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.extraSmall,
        color = Color.Transparent,
        border = BorderStroke(1.dp, PrimaryGreen),
        modifier = modifier,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = PrimaryGreen,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun PhaseBadge(phase: DshRendererPhase, accent: Color) {
    Surface(
        shape = CircleShape,
        color = accent.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.55f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(accent, CircleShape),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringResource(phaseLabel(phase)),
                style = MaterialTheme.typography.labelSmall,
                color = accent,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun CapabilityGroup(
    title: String,
    capabilities: List<DshWebCapability>,
    values: Map<DshWebCapability, Boolean>?,
    nativeValues: Map<DshWebCapability, Boolean>?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
        for (capability in capabilities) {
            val native = nativeValues?.get(capability)
            val effective = values?.get(capability)
            val resultRes = capabilityResult(native = native, effective = effective)
            val resultColor = when (effective) {
                true -> MaterialTheme.colorScheme.primary
                false -> MaterialTheme.colorScheme.error
                null -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = stringResource(capabilityLabel(capability)),
                        style = MaterialTheme.typography.bodySmall
                            .copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(resultRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = resultColor,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    text = stringResource(
                        R.string.dsh_renderer_result_fmt,
                        capabilityState(native),
                        capabilityState(effective),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun capabilityState(value: Boolean?): String = stringResource(
    when (value) {
        true -> R.string.dsh_renderer_result_available
        false -> R.string.dsh_renderer_result_missing
        null -> R.string.dsh_renderer_result_unknown
    },
)

@StringRes
private fun capabilityResult(native: Boolean?, effective: Boolean?): Int = when {
    native == false && effective == true -> R.string.dsh_renderer_result_compat
    native == true && effective == false -> R.string.dsh_renderer_result_native_only
    native == null && effective == true -> R.string.dsh_renderer_result_effective_only
    native == null && effective == false -> R.string.dsh_renderer_result_effective_missing
    native == true && effective == true -> R.string.dsh_renderer_result_available
    native == false && effective == false -> R.string.dsh_renderer_result_missing
    else -> R.string.dsh_renderer_result_unknown
}

@Composable
private fun FingerprintRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall
                .copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.weight(1.2f),
        )
    }
}

@Composable
private fun NoteText(
    text: String,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
    )
}

@StringRes
private fun engineLabel(engine: DshRendererEngine): Int = when (engine) {
    DshRendererEngine.GECKO -> R.string.dsh_renderer_engine_gecko
    DshRendererEngine.SYSTEM_WEBVIEW -> R.string.dsh_renderer_engine_system
    DshRendererEngine.UNKNOWN -> R.string.dsh_renderer_engine_unknown
}

@StringRes
private fun phaseLabel(phase: DshRendererPhase): Int = when (phase) {
    DshRendererPhase.NOT_COLLECTED -> R.string.dsh_renderer_phase_not_collected
    DshRendererPhase.STARTING -> R.string.dsh_renderer_phase_starting
    DshRendererPhase.PAGE_STARTED -> R.string.dsh_renderer_phase_page_started
    DshRendererPhase.PAGE_STOPPED -> R.string.dsh_renderer_phase_page_stopped
    DshRendererPhase.FAILED -> R.string.dsh_renderer_phase_failed
    DshRendererPhase.RELEASED -> R.string.dsh_renderer_phase_released
}

@StringRes
private fun capabilityLabel(capability: DshWebCapability): Int = when (capability) {
    DshWebCapability.PROMISE_WITH_RESOLVERS ->
        R.string.dsh_renderer_capability_promise_with_resolvers
    DshWebCapability.PROMISE_TRY ->
        R.string.dsh_renderer_capability_promise_try
    DshWebCapability.ABORT_SIGNAL_ANY ->
        R.string.dsh_renderer_capability_abort_signal_any
    DshWebCapability.ARRAY_TO_SORTED ->
        R.string.dsh_renderer_capability_array_to_sorted
    DshWebCapability.ARRAY_FIND_LAST ->
        R.string.dsh_renderer_capability_array_find_last
    DshWebCapability.ARRAY_TO_REVERSED ->
        R.string.dsh_renderer_capability_array_to_reversed
    DshWebCapability.STRUCTURED_CLONE ->
        R.string.dsh_renderer_capability_structured_clone
    DshWebCapability.URL_PARSE ->
        R.string.dsh_renderer_capability_url_parse
    DshWebCapability.URL_CAN_PARSE ->
        R.string.dsh_renderer_capability_url_can_parse
    DshWebCapability.REGEXP_ESCAPE ->
        R.string.dsh_renderer_capability_regexp_escape
    DshWebCapability.MAP_GET_OR_INSERT ->
        R.string.dsh_renderer_capability_map_get_or_insert
    DshWebCapability.MAP_GET_OR_INSERT_COMPUTED ->
        R.string.dsh_renderer_capability_map_get_or_insert_computed
    DshWebCapability.RESIZE_OBSERVER ->
        R.string.dsh_renderer_capability_resize_observer
    DshWebCapability.WORKER ->
        R.string.dsh_renderer_capability_worker
    DshWebCapability.WORKER_CLASSIC ->
        R.string.dsh_renderer_capability_worker_classic
    DshWebCapability.WORKER_MODULE ->
        R.string.dsh_renderer_capability_worker_module
    DshWebCapability.WORKER_TRANSFERABLE_STREAM ->
        R.string.dsh_renderer_capability_worker_transferable_stream
    DshWebCapability.WORKER_FETCH_STREAM_BODY ->
        R.string.dsh_renderer_capability_worker_fetch_stream_body
    DshWebCapability.PDF_WORKER_API ->
        R.string.dsh_renderer_capability_pdf_worker_api
    DshWebCapability.READABLE_STREAM ->
        R.string.dsh_renderer_capability_readable_stream
    DshWebCapability.WEBSOCKET ->
        R.string.dsh_renderer_capability_websocket
    DshWebCapability.CSS_HAS ->
        R.string.dsh_renderer_capability_css_has
    DshWebCapability.CSS_COLOR_MIX ->
        R.string.dsh_renderer_capability_css_color_mix
    DshWebCapability.CSS_DVH ->
        R.string.dsh_renderer_capability_css_dvh
    DshWebCapability.CSS_SUBGRID ->
        R.string.dsh_renderer_capability_css_subgrid
    DshWebCapability.CSS_LIGHT_DARK ->
        R.string.dsh_renderer_capability_css_light_dark
}

private val JAVASCRIPT_CAPABILITIES = listOf(
    DshWebCapability.PROMISE_WITH_RESOLVERS,
    DshWebCapability.PROMISE_TRY,
    DshWebCapability.ABORT_SIGNAL_ANY,
    DshWebCapability.ARRAY_TO_SORTED,
    DshWebCapability.ARRAY_FIND_LAST,
    DshWebCapability.ARRAY_TO_REVERSED,
    DshWebCapability.STRUCTURED_CLONE,
    DshWebCapability.URL_PARSE,
    DshWebCapability.URL_CAN_PARSE,
    DshWebCapability.REGEXP_ESCAPE,
    DshWebCapability.MAP_GET_OR_INSERT,
    DshWebCapability.MAP_GET_OR_INSERT_COMPUTED,
    DshWebCapability.RESIZE_OBSERVER,
    DshWebCapability.WORKER,
    DshWebCapability.READABLE_STREAM,
    DshWebCapability.WEBSOCKET,
)

private val WORKER_CAPABILITIES = listOf(
    DshWebCapability.WORKER_CLASSIC,
    DshWebCapability.WORKER_MODULE,
    DshWebCapability.WORKER_TRANSFERABLE_STREAM,
    DshWebCapability.WORKER_FETCH_STREAM_BODY,
    DshWebCapability.PDF_WORKER_API,
)

private val CSS_CAPABILITIES = listOf(
    DshWebCapability.CSS_HAS,
    DshWebCapability.CSS_COLOR_MIX,
    DshWebCapability.CSS_DVH,
    DshWebCapability.CSS_SUBGRID,
    DshWebCapability.CSS_LIGHT_DARK,
)
