package com.dshbox.app.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dshbox.app.R
import com.dshbox.app.dsh.DshRendererReportStore
import com.dshbox.app.ui.webview.DshRendererReportCard

/** The WebView/report page is the fourth dot; navigation behavior is unchanged. */
private const val WebviewStepIndex = 3

/**
 * Onboarding's read-only DSH environment report.
 *
 * The page never creates a WebView or starts a renderer. It only observes the
 * report populated by a real DSH container, so an unopened DSH page remains
 * explicitly "not collected".
 */
@Composable
internal fun OnboardingWebviewPage(
    onBack: () -> Unit,
    onNext: () -> Unit,
    onContinue: () -> Unit,
    onSkip: () -> Unit,
) {
    val report by DshRendererReportStore.report.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OnboardingStepDots(currentIndex = WebviewStepIndex)
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = onSkip) {
                Text(stringResource(R.string.onboarding_skip))
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = OnboardingContentMaxWidth)
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(
                        R.string.onboarding_step_eyebrow,
                        WebviewStepIndex,
                        OnboardingTotalSteps,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.dsh_renderer_onboarding_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = stringResource(R.string.dsh_renderer_onboarding_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (report.engine == com.dshbox.app.dsh.DshRendererEngine.SYSTEM_WEBVIEW) {
                    Text(stringResource(R.string.onboarding_webview_advice_title),
                        style = MaterialTheme.typography.labelLarge)
                    Text(stringResource(R.string.dsh_renderer_system_advice),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DshRendererReportCard(report = report)
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.onboarding_back))
            }
            OutlinedButton(onClick = onNext, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.onboarding_next))
            }
            Button(onClick = onContinue, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.onboarding_continue))
            }
        }
    }
}
