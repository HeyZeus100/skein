// skein-xtov.24.4 (AL-05, throwaway): the Activity the recreation and
// process-death gate tests drive. `ProtoHost` is set by the test before launch
// (it stands in for `MainActivity`'s graph); the last saved Bundle is kept so
// a test can scan it and restore a fresh Activity from it.
package app.skein.prototype.nav3

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

object ProtoHost {
    lateinit var deps: ProtoDeps
    lateinit var gate: ProtoGate
    var initialNav: () -> ProtoNavigationState = { ProtoNavigationState() }
    var lastSaved: Bundle? = null
    var creates: Int = 0
    var nav: ProtoNavigationState? = null
}

class ProtoActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ProtoHost.creates++
        setContent {
            ProtoRoot(ProtoHost.deps, ProtoHost.gate, ProtoHost.initialNav, onNavigationState = {
                ProtoHost.nav =
                    it
            })
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        ProtoHost.lastSaved = Bundle(outState)
    }
}
