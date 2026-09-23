// skein-1uw (E4.I4), acceptance criterion 4: "Unit test (JVM, fake
// `IInferenceService` stub) covers error mapping for every `ErrorCode`,
// including `SESSION_LOCKED` → `InferenceException.SessionLocked`."
//
// Two separate claims are proved here, and they are separate on purpose:
//
//   1. EVERY code has a row in this test. Asserted reflectively over
//      `ErrorCode`'s declared constants, so a thirteenth code added to the
//      contract fails this test until somebody decides what the engine does
//      with it. (`:core:ipc`'s own `ErrorMappingTest` asserts the table
//      `ErrorCodes.toException` implements; this asserts that the ENGINE
//      actually routes through it on every path a code can arrive on.)
//   2. Each row produces the right `InferenceException` subclass THROUGH THE
//      ENGINE — over `onError`, over `load`'s return code, and over the coded
//      `IllegalStateException` the sync entry points use. A mapping that is
//      correct in `:core:ipc` and bypassed here would pass that test and fail
//      a user.

package app.skein.core.inference.engine

import app.skein.core.model.ChatMessage
import app.skein.core.model.InferenceException
import app.skein.core.model.Prompt
import app.skein.core.model.Role
import app.skein.core.model.SamplingParams
import app.skein.ipc.ErrorCode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.lang.reflect.Modifier
import kotlin.reflect.KClass

@RunWith(RobolectricTestRunner::class)
class EngineErrorMappingTest {
    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    private lateinit var fixture: StoreFixture
    private lateinit var service: FakeInferenceService
    private lateinit var connector: FakeServiceConnector
    private lateinit var engine: LlamaCppEngine

    @Before
    fun setUp() {
        fixture = StoreFixture(temporaryFolder.newFolder("models"))
        service = FakeInferenceService()
        connector = FakeServiceConnector(service)
        engine =
            LlamaCppEngine(
                connector = connector,
                pins = fixture.pins,
                spillDir = temporaryFolder.newFolder("spill"),
                sessionEpoch = { 1L },
            )
    }

    // ------------------------------------------------------------ coverage

    @Test
    fun everyErrorCodeConstantHasARow() {
        val declared =
            ErrorCode::class.java.declaredFields
                .filter { Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType }
                .onEach { it.isAccessible = true }
                .map { it.getInt(null) }
                .toSet()

        assertThat(EXPECTED.keys).containsExactlyElementsIn(declared)
    }

    // ----------------------------------------------------- the onError path

    @Test
    fun streamMapsEveryErrorCode(): Unit =
        runTest {
            for ((code, expected) in EXPECTED) {
                val fresh = newEngine()
                fresh.load(fixture.model()).getOrThrow()
                service.generateError = code to "diagnostic"

                val failure = runCatching { fresh.stream(PROMPT, PARAMS).toList() }.exceptionOrNull()

                if (expected == null) {
                    // OK and CANCELLED are not engine faults: the flow simply
                    // ends. `ErrorCodes.toException` returns null for both and
                    // the engine closes the channel normally.
                    assertThat(failure).isNull()
                } else {
                    assertThat(failure).isInstanceOf(expected.java)
                }
                service.generateError = null
                service.awaitIdle()
            }
        }

    @Test
    fun sessionLockedIsDistinctFromServiceDied(): Unit =
        runTest {
            engine.load(fixture.model()).getOrThrow()
            service.generateError = ErrorCode.SESSION_LOCKED to "session is locked"

            val failure = runCatching { engine.stream(PROMPT, PARAMS).toList() }.exceptionOrNull()

            // The service is alive and refusing, not dead (plan E4.I4's
            // 2026-09-20 amendment). A caller prompts for unlock; it does not
            // rebind.
            assertThat(failure).isInstanceOf(InferenceException.SessionLocked::class.java)
            assertThat(failure).isNotInstanceOf(InferenceException.ServiceDied::class.java)
        }

    @Test
    fun anUnrecognisedCodeDegradesToInternal(): Unit =
        runTest {
            engine.load(fixture.model()).getOrThrow()
            service.generateError = 12_345 to "from a newer service"

            val failure = runCatching { engine.stream(PROMPT, PARAMS).toList() }.exceptionOrNull()

            assertThat(failure).isInstanceOf(InferenceException.Internal::class.java)
        }

    @Test
    fun theServiceDiagnosticNeverReachesTheExceptionUnsanitized(): Unit =
        runTest {
            engine.load(fixture.model()).getOrThrow()
            // A compromised service's forged log line: newlines plus an ANSI
            // escape (`skein-3yal`). `ErrorCodes.toException` strips both.
            service.generateError = ErrorCode.INTERNAL to "line one\nWARN forged\u001B[31m"

            val failure = runCatching { engine.stream(PROMPT, PARAMS).toList() }.exceptionOrNull()

            assertThat(failure!!.message).doesNotContain("\n")
            assertThat(failure.message).doesNotContain("\u001B")
        }

    // -------------------------------------------------------- the load path

    @Test
    fun loadMapsEveryErrorCode(): Unit =
        runTest {
            for ((code, expected) in EXPECTED) {
                if (expected == null) continue
                val fresh = newEngine()
                service.loadCode = code

                val result = fresh.load(fixture.model())

                assertThat(result.isFailure).isTrue()
                assertThat(result.exceptionOrNull()).isInstanceOf(expected.java)
                service.loadCode = ErrorCode.OK
            }
        }

    @Test
    fun loadNeverThrows(): Unit =
        runTest {
            service.loadCode = ErrorCode.OOM

            // Not `assertThrows`: the contract is that the failure ARRIVES as
            // a value (spec §4.1, "`load` never throws").
            val result = engine.load(fixture.model())

            assertThat(result.isFailure).isTrue()
        }

    @Test
    fun aServiceHashMismatchCarriesTheDigestTheClientAskedFor(): Unit =
        runTest {
            service.loadCode = ErrorCode.HASH_MISMATCH

            val failure = engine.load(fixture.model()).exceptionOrNull()

            val mismatch = failure as InferenceException.HashMismatch
            // Client-built: the engine knows what it told the service to
            // expect. `actual` stays empty because the service refuses by code
            // and never echoes the digest it computed (spec §9).
            assertThat(mismatch.expected).isEqualTo(fixture.mainSha256)
            assertThat(mismatch.actual).isEmpty()
        }

    @Test
    fun aRegistryDigestThatDisagreesWithTheManifestIsAMismatchWithBothSides(): Unit =
        runTest {
            val tampered = fixture.model().copy(sha256 = "0".repeat(64))

            val failure = engine.load(tampered).exceptionOrNull()

            val mismatch = failure as InferenceException.HashMismatch
            assertThat(mismatch.expected).isEqualTo("0".repeat(64))
            assertThat(mismatch.actual).isEqualTo(fixture.mainSha256)
            // The service was never asked: this is caught before a descriptor
            // is handed over.
            assertThat(service.loads).isEmpty()
        }

    // -------------------------------------------- the coded-exception path

    @Test
    fun tokenCountMapsACodedServiceFailure(): Unit =
        runTest {
            engine.load(fixture.model()).getOrThrow()
            service.tokenCountError = ErrorCode.NOT_LOADED

            val failure = runCatching { engine.count("hello") }.exceptionOrNull()

            assertThat(failure).isInstanceOf(InferenceException.ModelNotLoaded::class.java)
        }

    @Test
    fun embedMapsACodedServiceFailure(): Unit =
        runTest {
            engine.load(fixture.embeddingModel()).getOrThrow()
            service.embedError = ErrorCode.SESSION_LOCKED

            val failure = runCatching { engine.embed("hello") }.exceptionOrNull()

            assertThat(failure).isInstanceOf(InferenceException.SessionLocked::class.java)
        }

    private fun newEngine(): LlamaCppEngine =
        LlamaCppEngine(
            connector = FakeServiceConnector(service),
            pins = fixture.pins,
            spillDir = temporaryFolder.newFolder(),
            sessionEpoch = { 1L },
        )

    private companion object {
        val PROMPT = Prompt(messages = listOf(ChatMessage(Role.USER, "hi")))
        val PARAMS = SamplingParams(maxTokens = 4)

        /**
         * Every `ErrorCode` and the `InferenceException` the ENGINE must
         * produce for it. `null` means "not an engine fault" — the two codes
         * `Parcels.kt` documents as mapping to no exception.
         *
         * `ServiceDied` is deliberately absent: process death is observed
         * through the death callback, never returned over a transaction
         * (`ErrorCodes.toException`'s KDoc). `EngineDeathTest` covers it.
         */
        val EXPECTED: Map<Int, KClass<out InferenceException>?> =
            mapOf(
                ErrorCode.OK to null,
                ErrorCode.CANCELLED to null,
                ErrorCode.HASH_MISMATCH to InferenceException.HashMismatch::class,
                ErrorCode.INVALID_MODEL to InferenceException.InvalidModel::class,
                ErrorCode.OOM to InferenceException.OutOfMemory::class,
                ErrorCode.NOT_LOADED to InferenceException.ModelNotLoaded::class,
                ErrorCode.BUSY to InferenceException.Busy::class,
                ErrorCode.HASH_MISMATCH_POST_MMAP to InferenceException.PostMmapHashMismatch::class,
                ErrorCode.TX_TOO_LARGE to InferenceException.TransactionTooLarge::class,
                ErrorCode.MODEL_IN_USE to InferenceException.ModelInUse::class,
                ErrorCode.COMPANION_HASH_MISMATCH to InferenceException.CompanionHashMismatch::class,
                ErrorCode.SESSION_LOCKED to InferenceException.SessionLocked::class,
                ErrorCode.INTERNAL to InferenceException.Internal::class,
            )
    }
}
