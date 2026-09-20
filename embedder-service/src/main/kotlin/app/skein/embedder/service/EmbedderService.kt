package app.skein.embedder.service

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Runs in the isolated `:embedder` process (see `AndroidManifest.xml` in `:app`).
 *
 * Stub for E1.I1: declares the process topology from spec §4.1 so it is visible
 * from day one. The AIDL-backed binder and ONNX Runtime embedding/NER pipelines
 * land in `E5.I1`+.
 */
class EmbedderService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
