package app.skein.feature.shell.auth

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.skein.core.model.AuthorizationToken
import app.skein.core.vault.key.VaultKeyProvider
import app.skein.core.vault.session.UnlockManager
import app.skein.core.vault.session.UnlockOutcome
import app.skein.core.vault.session.UnlockState
import app.skein.feature.shell.testing.ShellTestTags

/**
 * Explicit surviving-factor authentication after a known invalidation.
 * No setup, reset or rewrap is performed. The host keeps this screen mounted
 * during Unlocking while [UnlockManager.recoveryFactor] is non-null.
 */
@Composable
public fun VaultFactorRecoveryScreen(
    unlockManager: UnlockManager,
    onUnlocked: (AuthorizationToken) -> Unit,
    modifier: Modifier = Modifier,
    isDeviceLocked: (() -> Boolean)? = null,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() }
    val failedFactor by unlockManager.recoveryFactor.collectAsState()
    val managerState by unlockManager.state.collectAsState()
    val currentOnUnlocked by rememberUpdatedState(onUnlocked)
    val deviceLocked = remember(context, isDeviceLocked) { isDeviceLocked ?: { context.isDeviceLockedNow() } }
    var attempt by remember(unlockManager, activity) { mutableIntStateOf(0) }
    var phase by remember(unlockManager, activity) { mutableStateOf(FactorRecoveryPhase.READY) }
    var message by remember(unlockManager, activity) { mutableStateOf<String?>(null) }
    val prompt =
        remember {
            BiometricPrompt.PromptInfo
                .Builder()
                .setTitle("Recover access to Skein")
                .setSubtitle("Authenticate with your other device unlock method")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButtonText("Cancel")
                .build()
        }

    LaunchedEffect(unlockManager, activity, attempt) {
        if (attempt == 0 || activity == null) return@LaunchedEffect
        val outcome =
            awaitUnlockPromptOutcome(
                context = context,
                activity = activity,
                isDeviceLocked = deviceLocked,
                onWaiting = { phase = FactorRecoveryPhase.WAITING },
                onPrompting = { phase = FactorRecoveryPhase.PROMPTING },
                authenticate = { unlockManager.unlockSurvivingFactor(activity, prompt) },
            )
        val result = if (outcome is UnlockOutcome.Coalesced) outcome.outcome else outcome
        phase = FactorRecoveryPhase.READY
        when (result) {
            is UnlockOutcome.Success -> currentOnUnlocked(result.token)
            UnlockOutcome.UserCancelled -> message = "Authentication was cancelled. Your vault is unchanged."
            is UnlockOutcome.KeyMaterialGone -> {
                phase = FactorRecoveryPhase.UNAVAILABLE
                message =
                    "The other device key is missing. Keep this vault and your recovery export. Nothing was changed."
            }
            is UnlockOutcome.KeyPermanentlyInvalidated -> {
                phase = FactorRecoveryPhase.UNAVAILABLE
                message = "The other device key is also invalidated. Keep this vault and your recovery export."
            }
            UnlockOutcome.NotInitialised -> {
                phase = FactorRecoveryPhase.UNAVAILABLE
                message = "The existing key envelope is unavailable. Keep this vault and your recovery export."
            }
            else -> message = "Couldn't verify your identity. Your vault is unchanged."
        }
    }

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(24.dp).widthIn(max = 480.dp).verticalScroll(rememberScrollState()),
        ) {
            val guidance =
                when (failedFactor) {
                    VaultKeyProvider.Factor.BIOMETRIC ->
                        "The fingerprint or face key was invalidated. " +
                            "Your device-credential key may still open this vault."
                    VaultKeyProvider.Factor.DEVICE_CREDENTIAL ->
                        "The device-credential key was invalidated. " +
                            "Your fingerprint or face key may still open this vault."
                    null ->
                        "A device key was invalidated. Keep this vault and your recovery export while recovery is assessed."
                }
            Text(
                text = message ?: guidance,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag(ShellTestTags.BIOMETRIC_UNLOCK_MESSAGE),
            )
            when (phase) {
                FactorRecoveryPhase.PROMPTING ->
                    CircularProgressIndicator(Modifier.testTag(ShellTestTags.BIOMETRIC_UNLOCK_PROGRESS))
                FactorRecoveryPhase.WAITING ->
                    Text(
                        "Unlock your device to continue.",
                        Modifier.testTag(ShellTestTags.BIOMETRIC_UNLOCK_WAITING_MESSAGE),
                    )
                FactorRecoveryPhase.READY ->
                    if (failedFactor != null && activity != null && managerState == UnlockState.RecoveryRequired) {
                        Button(
                            onClick = {
                                if (phase == FactorRecoveryPhase.READY &&
                                    unlockManager.state.value == UnlockState.RecoveryRequired
                                ) {
                                    phase = FactorRecoveryPhase.PROMPTING
                                    message = null
                                    attempt++
                                }
                            },
                            modifier = Modifier.testTag(ShellTestTags.BIOMETRIC_UNLOCK_RETRY_BUTTON),
                        ) {
                            Text(
                                if (failedFactor == VaultKeyProvider.Factor.BIOMETRIC) {
                                    "Use PIN, pattern, or password"
                                } else {
                                    "Use fingerprint or face"
                                },
                            )
                        }
                    }
                FactorRecoveryPhase.UNAVAILABLE -> Unit
            }
        }
    }
}

private enum class FactorRecoveryPhase {
    READY,
    WAITING,
    PROMPTING,
    UNAVAILABLE,
}
