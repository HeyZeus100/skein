// `E3.I10` (`skein-xhi`), part 3 of the guard: the tap boundary.
//
// Design spec §9: "model output never derives Intent URIs or tool calls;
// every out-of-app action requires explicit user tap", and
// `docs/design/VAULT_TOOL_PRIMITIVES.md`'s rule that a write happens only
// under an `AuthorizationToken` minted from a user gesture. Plan `E3.I10`
// asks for this to exist as a named object with a single always-true
// function, "so tests can assert no code path bypasses it": it is a seam,
// not a decision point. There is deliberately no `false` branch, no policy
// table and no configuration — if a caller ever needs one, that is a spec
// change, and the test that pins this file to `true` is what forces the
// conversation.

package us.aherrera.skein.security.prompt

/**
 * Something the app would do on the user's behalf that leaves the safety of
 * rendered text — the only kinds of action [ActionPolicy] is consulted for.
 *
 * Carries the target so a confirmation sheet can show the user what they are
 * about to do ("tap shows the URL and asks", plan `E3.I10`). It is never
 * logged: spec §9 forbids document, prompt and chunk content in logs at any
 * level, and a URI lifted from model output may contain either.
 */
public sealed interface OutboundAction {
    /** Following a link rendered from model output or from retrieved text. */
    public data class FollowLink(
        val uri: String,
    ) : OutboundAction

    /** Opening the source behind a `[N]` citation chip as a preview tab (spec §7.3). */
    public data class OpenCitation(
        val marker: Int,
    ) : OutboundAction

    /** Anything that hands data to another app — share sheet, external viewer, export. */
    public data class LeaveApp(
        val label: String,
    ) : OutboundAction
}

/**
 * The one answer the app gives about acting on model output: **the user
 * taps, always** (design spec §9).
 *
 * [requiresTap] returns `true` for every action, unconditionally. Nothing
 * derived from a model response or a retrieved chunk is ever auto-executed,
 * auto-resolved or pre-authorized, and no caller may special-case a "safe"
 * scheme: `PromptGuard.neutralizeActionable` has already turned the
 * dangerous ones into inert text, and this gate covers the rest.
 */
public object ActionPolicy {
    /** Always `true`. See the class KDoc — the constant is the contract. */
    public fun requiresTap(action: OutboundAction): Boolean = true
}
