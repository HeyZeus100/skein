package app.skein.inference.service

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Runs in the isolated `:inference` process (see `AndroidManifest.xml` in `:app`).
 *
 * Stub for E1.I1: declares the process topology from spec §4.1 so it is visible
 * from day one. The AIDL-backed binder, llama.cpp JNI bridge, batching, and
 * templating land in `E1.I4`, `E4.I1`+.
 */
class InferenceService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
