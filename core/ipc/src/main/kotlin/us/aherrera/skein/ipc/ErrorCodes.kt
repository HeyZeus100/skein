// skein-k7e9 — the executable form of [ErrorCode]'s KDoc mapping table.
//
// ============================================================================
// WHY THIS LIVES IN `:core:ipc`
// ============================================================================
//
// The mapping needs both hierarchies in scope: `ErrorCode` (here) and
// `InferenceException` (`:core:model`). Before this file, no module saw both —
// `:core:inference` depends on `:core:model` but not `:core:ipc`, and
// `:core:ipc` depended on neither.
//
// `:core:ipc` gains `api(project(":core:model"))`. The `build-logic` guards
// permit it:
//
//   * `DependencyGuardTask` bans only the Google Play Services / Firebase /
//     Play Core / ML Kit groups (spec §2.2). `:core:model` is a pure
//     Kotlin/JVM module whose only transitives are `org.jetbrains.kotlinx`
//     coroutines and serialization — nothing on the banned list.
//   * `IsolationGuardTask` enforces two things, neither of which this edge
//     touches. (a) `:core:model` must not APPLY an Android Gradle plugin — it
//     still does not; being consumed BY an Android library is not the same as
//     becoming one, and `:core:model` stays JVM-testable. (b)
//     `:inference-service` / `:embedder-service` may DECLARE project
//     dependencies only on `:core:ipc` and `:core:model` — the allowlist
//     already names both together (`IsolationGuardPlugin`
//     `COMMON_SERVICE_PROJECT_ALLOWLIST`), and the guard inspects declared
//     dependencies, not resolved ones, so a `:core:ipc` → `:core:model`
//     transitive introduces nothing the allowlist did not already permit.
//
// The alternative placement was `:core:inference`, where plan `E4.I4` puts
// `ErrorMapping`. It is not the wrong place — `E4.I4`'s client can and should
// delegate here — but the mapping is contract, not policy: it is the meaning
// of each wire code, which `Parcels.kt` already documents in prose two files
// away. Keeping the prose and the code in one module is what stops them
// drifting, which is the defect `skein-k7e9` was filed for.
//
// This is NOT the `TransportRules` situation that `Parcels.kt`'s judgment call
// J4 kept out of this module. J4 excluded a client POLICY that decides
// inline-vs-fd from measured budgets. A code-to-type mapping decides nothing;
// it has no configuration and no measurement input.
//
// ============================================================================
// MESSAGE HYGIENE
// ============================================================================
//
// [ErrorCodes.toException] takes an `Int` and the SERVICE's diagnostic string —
// the two things `IInferenceCallback.onError(requestId, code, message)`
// carries. It is never handed a `GenerateRequest`, a `Prompt` or a
// `SharedMemRef`, so no prompt text, document body or attachment can reach an
// exception message. `ErrorMappingTest` pins this down by asserting that every
// code's message with no detail is a fixed, documented constant.

package us.aherrera.skein.ipc

import us.aherrera.skein.core.model.InferenceException

/**
 * Translates an [ErrorCode] constant into the
 * [InferenceException] subclass that `Parcels.kt`'s KDoc names for it.
 *
 * `docs/design/POST_REVIEW_RESOLUTIONS.md` §3.4 requires that every `ErrorCode`
 * map to a DISTINCT subclass; `ErrorMappingTest` asserts it by reflecting over
 * [ErrorCode]'s constants, so a thirteenth code cannot be added without either
 * a mapping or a deliberate, documented decision here.
 */
object ErrorCodes {
    /**
     * @param code one of the [ErrorCode] constants. An unrecognised value — a
     *   code from a newer service than this build knows — maps to
     *   [InferenceException.Internal] rather than throwing, so a forward
     *   version skew degrades to "unclassified failure" instead of crashing
     *   the client.
     * @param message the service's free-form diagnostic, appended after the
     *   subclass's fixed prefix where the subclass has room for it. The locked
     *   `E0.I10` subclasses with fixed messages ([InferenceException.HashMismatch],
     *   [InferenceException.OutOfMemory], [InferenceException.ModelNotLoaded],
     *   [InferenceException.Busy]) have no slot for it and must not gain one,
     *   so for those codes the diagnostic is dropped here.
     * @return the exception to raise, or `null` for the two codes that are
     *   documented NOT to be engine faults: [ErrorCode.OK] (success) and
     *   [ErrorCode.CANCELLED] (the caller sees `StopReason.CANCELLED` on
     *   `Token.Done`, and a suspending caller sees
     *   `kotlin.coroutines.cancellation.CancellationException`).
     *
     *   [InferenceException.ServiceDied] is deliberately unreachable from this
     *   function: process death is observed through `linkToDeath`, never
     *   returned over a transaction that by definition can no longer complete.
     */
    fun toException(
        code: Int,
        message: String = "",
    ): InferenceException? =
        when (code) {
            ErrorCode.OK, ErrorCode.CANCELLED -> null
            ErrorCode.HASH_MISMATCH -> InferenceException.HashMismatch(expected = "", actual = "")
            ErrorCode.INVALID_MODEL -> InferenceException.InvalidModel(message)
            ErrorCode.OOM -> InferenceException.OutOfMemory()
            ErrorCode.NOT_LOADED -> InferenceException.ModelNotLoaded()
            ErrorCode.BUSY -> InferenceException.Busy()
            ErrorCode.HASH_MISMATCH_POST_MMAP -> InferenceException.PostMmapHashMismatch(message)
            ErrorCode.TX_TOO_LARGE -> InferenceException.TransactionTooLarge(message)
            ErrorCode.MODEL_IN_USE -> InferenceException.ModelInUse(message)
            ErrorCode.COMPANION_HASH_MISMATCH -> InferenceException.CompanionHashMismatch(message)
            ErrorCode.SESSION_LOCKED -> InferenceException.SessionLocked(message)
            ErrorCode.INTERNAL -> InferenceException.Internal(message)
            // An unrecognised code: see the `code` parameter's KDoc.
            else -> InferenceException.Internal(message)
        }

    // ========================================================================
    // Sync entry points that cannot return a code (judgment call J7, skein-nxk)
    // ========================================================================
    //
    // `load` returns an `int` and `generate` reports through
    // `IInferenceCallback.onError`, so both carry an [ErrorCode] naturally.
    // `IInferenceService.embed` returns `float[]` and `tokenCount` returns an
    // `int` that is a COUNT — neither has a slot for an error code, and
    // widening their signatures would not be additive to a locked contract.
    //
    // The usual Android answer, `android.os.ServiceSpecificException`, carries
    // exactly this (an int plus a message) — but it is not in the public
    // `android.jar` (checked against platform android-37.0: absent), so it
    // cannot be referenced from a module that compiles against the public SDK.
    //
    // Binder DOES transport a fixed set of standard unchecked exceptions
    // faithfully across a transaction, `IllegalStateException` among them. So
    // the convention is: the service throws an `IllegalStateException` whose
    // message begins with [FAILURE_PREFIX] followed by the numeric code, and
    // the client turns it back into the right `InferenceException`. Both halves
    // live here so the two sides cannot drift — that is the whole reason this
    // is in `:core:ipc` and not in either implementation.

    /** Marks an [IllegalStateException] as a coded service failure. */
    const val FAILURE_PREFIX: String = "skein-error:"

    /**
     * The exception a sync entry point with no code slot throws.
     *
     * @param message a fixed diagnostic. **Never** prompt, document or token
     *   text: this crosses a process boundary and lands in a log (spec §9).
     */
    fun asServiceFailure(
        code: Int,
        message: String = "",
    ): IllegalStateException = IllegalStateException("$FAILURE_PREFIX$code $message".trim())

    /**
     * The [ErrorCode] a service encoded in [throwable], or null when it is not
     * one of these — an ordinary `IllegalStateException` from somewhere else
     * must not be silently reinterpreted as a coded refusal.
     */
    fun codeOf(throwable: Throwable): Int? {
        val message = throwable.message ?: return null
        if (!message.startsWith(FAILURE_PREFIX)) return null
        val digits = message.removePrefix(FAILURE_PREFIX).substringBefore(' ')
        return digits.toIntOrNull()
    }

    /** [codeOf] plus [toException]: what a client does with a caught sync failure. */
    fun toExceptionOrNull(throwable: Throwable): InferenceException? {
        val code = codeOf(throwable) ?: return null
        return toException(code, throwable.message?.substringAfter(' ', "").orEmpty())
    }
}
