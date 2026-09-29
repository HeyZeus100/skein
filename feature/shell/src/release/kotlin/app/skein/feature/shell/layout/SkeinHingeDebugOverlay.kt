package app.skein.feature.shell.layout

import androidx.compose.material3.adaptive.WindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Release counterpart: no drawing, logging, observer, state or input surface, even when enabled. */
@Suppress("UNUSED_PARAMETER")
@Composable
fun SkeinHingeDebugOverlay(
    info: WindowAdaptiveInfo,
    enabled: Boolean = false,
    modifier: Modifier = Modifier,
) = Unit
