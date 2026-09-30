package app.skein.feature.shell.auth

import android.content.Context
import androidx.fragment.app.FragmentActivity
import app.skein.core.vault.session.UnlockOutcome
import kotlinx.coroutines.flow.first

/** One composition-owned prompt lifetime, shared by normal and surviving-factor unlock. */
internal suspend fun awaitUnlockPromptOutcome(
    context: Context,
    activity: FragmentActivity,
    isDeviceLocked: () -> Boolean,
    onWaiting: () -> Unit,
    onPrompting: () -> Unit,
    authenticate: suspend () -> UnlockOutcome,
): UnlockOutcome {
    while (true) {
        if (!isReadyToPresent(activity, isDeviceLocked)) {
            onWaiting()
            deviceUnlockWakeSignals(context, activity).first { isReadyToPresent(activity, isDeviceLocked) }
        }
        onPrompting()
        val result = authenticate()
        if (result != UnlockOutcome.DeviceLocked) return result
        onWaiting()
        deviceUnlockWakeSignals(context, activity).first { isReadyToPresent(activity, isDeviceLocked) }
    }
}
