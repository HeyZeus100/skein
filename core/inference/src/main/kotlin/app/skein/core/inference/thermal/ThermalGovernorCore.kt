// skein-7p0 (E4.I9) — Android-free thermal-governor state machine.
//
// Split from the Android-facing `ThermalGovernor` adapter (same package)
// the same way `VaultKeyProviderImpl`/`UnlockManager` split their
// state-machine logic from Android-specific plumbing (`core/vault/.../session/UnlockManager.kt`):
// this class takes only [ThermalBackoffTable], a `java.time.Clock`, and two
// tiny functional seams ([HeadroomSource], [ThermalStatusSource]), so the
// whole state machine — cadence gating, hysteresis, Paused expiry — is
// JVM-unit-testable with a fake clock and a counting headroom source, no
// Robolectric or emulator required.
//
// Polling model: this class does NOT own a timer/coroutine scope. A driver
// (the Android `ThermalGovernor` adapter, or a test) calls [tick]
// repeatedly; [tick] itself enforces the <=1 Hz cadence by comparing
// `clock.millis()` against the last actual poll, so calling it more often
// than once a second is harmless and cheap (it's a no-op until the cadence
// is due). This keeps the *scheduling policy* (how often to call tick, on
// what dispatcher) entirely on the Android side, while the *cadence limit
// itself* — a correctness property this class is responsible for — lives
// here where it's tested.
//
// Hysteresis: see `ThermalBackoffTable.nextIndex`'s KDoc for the exact
// escalate-immediately / de-escalate-after-margin rule.
//
// Paused expiry: while the published state is `ThermalState.Paused` and
// `now < untilMs`, [tick]/status events are no-ops — the pause is a hard
// floor that only gets re-examined at its own deadline (this also means we
// skip polling `headroomSource` for the whole pause window, not just the
// action). Once `now >= untilMs`, the next tick/event re-evaluates from the
// Paused entry's own index: if headroom still lands in (or above) that
// entry, the pause is **re-armed** with a fresh `untilMs` (still Paused,
// new deadline); otherwise the table's ordinary hysteresis rule decides
// where to land (which may skip straight down to Nominal if headroom
// dropped a lot while paused).

package app.skein.core.inference.thermal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Clock
import java.util.concurrent.atomic.AtomicInteger

/**
 * @param table the headroom -> action table. Defaults to
 *   [ThermalBackoffTable.DEFAULT] (see its KDoc — a marked placeholder).
 * @param clock source of "now" for cadence gating and [ThermalState.Paused.untilMs].
 * @param headroomSource polled at most once/sec while active.
 * @param statusSource subscribed only while active; a status-change event
 *   short-circuits the cadence and forces an immediate re-evaluation.
 * @param onUnsupportedHeadroom fired exactly once (per instance) the first
 *   time [headroomSource] reports `NaN` — the AC's "one-time SkeinLog.w".
 *   Left as an injectable no-op-by-default callback rather than a direct
 *   `SkeinLog.w` call because `SkeinLog` (spec §9) is not implemented yet
 *   in this codebase (only its test capture point,
 *   `app.skein.testing.SkeinLogCapture`, exists) — the `ThermalGovernor`
 *   Android adapter wires this to `SkeinLog.w` once it lands.
 */
public class ThermalGovernorCore
    @JvmOverloads
    constructor(
        private val table: ThermalBackoffTable = ThermalBackoffTable.DEFAULT,
        private val clock: Clock = Clock.systemUTC(),
        private val headroomSource: HeadroomSource,
        private val statusSource: ThermalStatusSource = ThermalStatusSource.NONE,
        private val onUnsupportedHeadroom: () -> Unit = {},
    ) {
        private val _state = MutableStateFlow<ThermalState>(ThermalState.Nominal)
        public val state: StateFlow<ThermalState> = _state.asStateFlow()

        private val activeCount = AtomicInteger(0)
        private val transitionLock = Any()

        private var currentIndex = 0

        // `null` means "never polled (or just (re)activated) — the next
        // tick()/event is always due". Deliberately NOT a Long.MIN_VALUE
        // sentinel: `now - Long.MIN_VALUE` overflows a 64-bit Long (wraps to
        // a large negative number), which would make the very first poll
        // after activate() look "too recent" and get skipped.
        private var lastPollMillis: Long? = null
        private var loggedUnsupportedOnce = false
        private var statusSubscription: AutoCloseable? = null

        /** `true` while at least one `activate()` has not yet been matched by a `deactivate()`. */
        public val isActive: Boolean get() = activeCount.get() > 0

        /**
         * Registers interest (inference or ingest running). Reference-counted:
         * polling/listener subscription only start on the 0->1 transition.
         * [reason] is caller-supplied context, used only for logging/debugging.
         */
        public fun activate(
            @Suppress("UNUSED_PARAMETER") reason: String,
        ) {
            synchronized(transitionLock) {
                if (activeCount.getAndIncrement() == 0) {
                    // Force the very next tick() to actually poll, regardless
                    // of how recently (in a prior activation) we last polled.
                    // Seed conservatively at Nominal (index 0) — the first
                    // tick() polls headroomSource for real and re-derives it.
                    lastPollMillis = null
                    currentIndex = 0
                    statusSubscription = statusSource.addListener { onStatusChanged() }
                }
            }
        }

        /**
         * Releases interest. On the last matching release (ref count -> 0),
         * unsubscribes from [statusSource] immediately so polling/listening
         * stops well within the AC's 2 s budget — there is no lingering timer
         * here at all, since this class never owns one.
         */
        public fun deactivate(
            @Suppress("UNUSED_PARAMETER") reason: String,
        ) {
            synchronized(transitionLock) {
                val was = activeCount.getAndUpdate { (it - 1).coerceAtLeast(0) }
                if (was == 1) {
                    statusSubscription?.close()
                    statusSubscription = null
                }
            }
        }

        /**
         * Driver entry point — call this as often as your desired *latency*
         * (e.g. every 250 ms), not your desired poll rate: this method
         * itself enforces the <=1 Hz cap on actual [headroomSource] polls.
         * No-op when [isActive] is false.
         */
        public fun tick() {
            evaluateIfDue(forced = false)
        }

        private fun onStatusChanged() {
            // Short-circuits the cadence: a platform status-change event is
            // always worth an immediate re-evaluation.
            evaluateIfDue(forced = true)
        }

        private fun evaluateIfDue(forced: Boolean) {
            if (!isActive) return
            val now = clock.millis()
            val last = lastPollMillis
            if (!forced && last != null && now - last < MIN_POLL_INTERVAL_MILLIS) return
            lastPollMillis = now

            val cur = _state.value
            if (cur is ThermalState.Paused && now < cur.untilMs) {
                // Hard floor — hold until expiry, see class KDoc.
                return
            }

            val headroom = headroomSource.headroomNow()
            if (headroom.isNaN()) {
                if (!loggedUnsupportedOnce) {
                    loggedUnsupportedOnce = true
                    onUnsupportedHeadroom()
                }
                currentIndex = 0
                _state.value = ThermalState.Nominal
                return
            }

            currentIndex = table.nextIndex(headroom, currentIndex)
            _state.value = table.entries[currentIndex].action.toThermalState(now)
        }

        public companion object {
            /** The AC's "at most once per second" poll cadence. */
            public const val MIN_POLL_INTERVAL_MILLIS: Long = 1_000L
        }
    }
