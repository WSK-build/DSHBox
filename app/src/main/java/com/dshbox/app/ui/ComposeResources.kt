package com.dshbox.app.ui

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/**
 * Returns resources that are invalidated with Compose configuration changes.
 *
 * The current Compose BOM predates LocalResources; reading LocalConfiguration keeps
 * locale, density and theme resource lookups synchronized without suppressing lint.
 */
@Composable
internal fun configurationAwareResources(): Resources {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(context, configuration) { context.resources }
}
