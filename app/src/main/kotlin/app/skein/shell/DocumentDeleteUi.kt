package app.skein.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import app.skein.core.designsystem.components.LocalSkeinWindowActive
import app.skein.core.designsystem.components.SkeinDestructiveDialog
import app.skein.core.designsystem.components.SkeinSnackbarHost

/** Hosted inside the active shell so dialogs inherit its safe Fold partition. */
@Composable
internal fun DocumentDeleteUi(coordinator: DocumentDeleteCoordinator) {
    if (!LocalSkeinWindowActive.current) return
    val prompt by coordinator.prompt.collectAsState()
    prompt?.let {
        SkeinDestructiveDialog(
            title = it.heading,
            consequence = it.consequence,
            onConfirm = coordinator::confirm,
            onDismiss = coordinator::cancel,
        )
    }
    val message by coordinator.message.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it.text)
            coordinator.dismissMessage(it)
        }
    }
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
        SkeinSnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}
