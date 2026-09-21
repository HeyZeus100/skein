// skein-ank2 — shared by `BiometricUnlockScreen` (skein-ugo) and
// `VaultSetupScreen`: both drive `VaultKeyProvider` / `UnlockManager` entry
// points that present a `BiometricPrompt` against a `FragmentActivity`, so
// both need the same "which activity is hosting this composition?" lookup.

package app.skein.feature.shell.auth

import android.content.Context
import android.content.ContextWrapper
import androidx.fragment.app.FragmentActivity

/** Unwraps a possibly-decorated [Context] down to its hosting [FragmentActivity], if any. */
internal tailrec fun Context.findFragmentActivity(): FragmentActivity? =
    when (this) {
        is FragmentActivity -> this
        is ContextWrapper -> baseContext.findFragmentActivity()
        else -> null
    }
