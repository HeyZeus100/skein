// `E10.I2` (skein-0j1): a call-recording stand-in for `feature/shell`'s tab
// lifecycle (`TabsState`, spec §8.3, plan `E6.I5`), reduced to plain JVM
// types so shell/tab-driving tests can assert *what actions a surface asked
// for, in what order* without needing `androidx.compose.runtime` — which
// `:testing` cannot depend on and stay pure-JVM (see `docs/TESTING.md`,
// "Why `:testing` has no Android dependencies").
//
// NOT a locked contract: nothing in `core/model` defines a "TabController"
// interface, and `TabsState` itself is a concrete `@Stable` class, not an
// interface a fake implements. [TabController] is a testing-only action
// vocabulary this file invents, mirroring `TabsState`'s public surface
// (`openPreview`/`openPinned`/`pin`/`close`/`closeOthers`/`activate`) so a
// production `TabController`-shaped wrapper (if one is ever introduced
// around `TabsState`) can plug in as [RecordingTabController]'s `delegate`.

package app.skein.testing

/**
 * The three tab content kinds `feature/shell`'s `TabKind` models (spec
 * §8.3). Duplicated here rather than imported — see file header.
 */
public enum class RecordedTabKind { CHAT, NOTE, ATTACHMENT }

/** One action a caller asked a [TabController] to perform, in call order. */
public sealed interface TabAction {
    public data class OpenPreview(
        val docId: String,
        val title: String,
        val kind: RecordedTabKind,
    ) : TabAction

    public data class OpenPinned(
        val docId: String,
        val title: String,
        val kind: RecordedTabKind,
    ) : TabAction

    public data class Pin(
        val tabId: String,
    ) : TabAction

    public data class Close(
        val tabId: String,
    ) : TabAction

    public data class CloseOthers(
        val tabId: String,
    ) : TabAction

    public data class Activate(
        val tabId: String,
    ) : TabAction
}

/**
 * Testing-only action vocabulary mirroring `TabsState`'s public surface.
 * See file header for why this is not a locked production contract.
 */
public interface TabController {
    public fun openPreview(
        docId: String,
        title: String,
        kind: RecordedTabKind = RecordedTabKind.NOTE,
    ): String

    public fun openPinned(
        docId: String,
        title: String,
        kind: RecordedTabKind = RecordedTabKind.NOTE,
    ): String

    public fun pin(tabId: String)

    public fun close(tabId: String)

    public fun closeOthers(tabId: String)

    public fun activate(tabId: String)
}

/**
 * Records every [TabController] call, in order, optionally forwarding to
 * [delegate] afterward. `open*` calls mint a deterministic id (`"tab-<n>"`,
 * `n` starting at 1) when [delegate] is null, so a caller-agnostic test can
 * still assert on the returned id; when [delegate] is non-null its return
 * value is used instead.
 *
 * Approximation — see file header: unlike the real `TabsState`, this
 * recorder enforces none of spec §8.3's invariants (at most one PREVIEW tab
 * at a time, reusing an existing pinned tab for an already-open `docId`,
 * recency ordering, …). It only records call order; a test that needs
 * `TabsState`'s real lifecycle semantics should exercise the real class.
 */
public class RecordingTabController(
    private val delegate: TabController? = null,
) : TabController {
    private val recorded: MutableList<TabAction> = mutableListOf()
    private var nextId: Int = 1

    /** Every action, in call order. */
    public val actions: List<TabAction> get() = recorded.toList()

    override fun openPreview(
        docId: String,
        title: String,
        kind: RecordedTabKind,
    ): String {
        recorded += TabAction.OpenPreview(docId, title, kind)
        return delegate?.openPreview(docId, title, kind) ?: mintId()
    }

    override fun openPinned(
        docId: String,
        title: String,
        kind: RecordedTabKind,
    ): String {
        recorded += TabAction.OpenPinned(docId, title, kind)
        return delegate?.openPinned(docId, title, kind) ?: mintId()
    }

    override fun pin(tabId: String) {
        recorded += TabAction.Pin(tabId)
        delegate?.pin(tabId)
    }

    override fun close(tabId: String) {
        recorded += TabAction.Close(tabId)
        delegate?.close(tabId)
    }

    override fun closeOthers(tabId: String) {
        recorded += TabAction.CloseOthers(tabId)
        delegate?.closeOthers(tabId)
    }

    override fun activate(tabId: String) {
        recorded += TabAction.Activate(tabId)
        delegate?.activate(tabId)
    }

    private fun mintId(): String = "tab-${nextId++}"
}
