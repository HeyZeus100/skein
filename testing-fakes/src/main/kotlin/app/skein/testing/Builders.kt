// `E10.I2` (skein-0j1): consistent builder DSL over the consolidated fakes —
// `fakeVault { note("Title", "body"); chat(...) }`, `scriptedEngine("q" to
// listOf("a", "b"))`, `fakeEmbedder()` — so tests stop hand-assembling
// `NewDocument`/`NewMessage`/script maps and get one obvious way to seed a
// fake vault or a scripted engine.
//
// Every builder here is a thin convenience wrapper: it adds no behaviour of
// its own beyond composing the underlying fake's public API (see each
// fake's own KDoc — `InMemoryVaultRepository`, `FakeInferenceEngine`,
// `FakeEmbedderService` — for what is faithful and what is approximate).

package app.skein.testing

import app.skein.core.model.Document
import app.skein.core.model.DocumentKind
import app.skein.core.model.NewDocument
import app.skein.core.model.NewMessage
import app.skein.core.model.PersonaId
import app.skein.core.model.Role
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO

/** Scopes `fakeVault { ... }`'s builder lambda so it can't be nested inside another builder DSL. */
@DslMarker
public annotation class FakeVaultDsl

/**
 * Builder body for [fakeVault]. Every call runs synchronously (blocks the
 * calling thread via `runBlocking`) against the underlying
 * [InMemoryVaultRepository] — convenient for test setup, never appropriate
 * outside tests.
 */
@FakeVaultDsl
public class FakeVaultBuilder internal constructor(
    private val repository: InMemoryVaultRepository,
) {
    /** Creates a [DocumentKind.NOTE] document with [title]/[body]. Returns the created [Document]. */
    public fun note(
        title: String,
        body: String,
        personaId: PersonaId? = null,
        frontmatter: JsonObject = JsonObject(emptyMap()),
    ): Document =
        runBlocking {
            repository.createDocument(
                NewDocument(
                    kind = DocumentKind.NOTE,
                    title = title,
                    bodyMd = body,
                    personaId = personaId,
                    frontmatter = frontmatter,
                ),
            )
        }

    /**
     * Creates a [DocumentKind.CHAT] document and appends [messages] (role/content
     * pairs, oldest first) to it via [InMemoryVaultRepository.appendMessage].
     * Returns the chat [Document] after every message has been appended — its
     * `bodyMd` is the re-materialized Markdown transcript, matching what a
     * caller would see from [InMemoryVaultRepository.getDocument].
     */
    public fun chat(
        title: String,
        vararg messages: Pair<Role, String>,
        personaId: PersonaId? = null,
    ): Document =
        runBlocking {
            val created =
                repository.createDocument(
                    NewDocument(kind = DocumentKind.CHAT, title = title, bodyMd = "", personaId = personaId),
                )
            for ((role, content) in messages) {
                repository.appendMessage(created.id, NewMessage(role = role, contentMd = content))
            }
            repository.getDocument(created.id) ?: created
        }

    /** Creates a [DocumentKind.ATTACHMENT] document holding [bytes] under [mimeType]. */
    public fun attachment(
        title: String,
        mimeType: String,
        bytes: ByteArray,
    ): Document =
        runBlocking {
            repository.createAttachment(title, mimeType) { out -> out.write(bytes) }
        }
}

/**
 * Builds an [InMemoryVaultRepository] pre-populated by [block]:
 * ```
 * val vault = fakeVault {
 *     note("Title", "body")
 *     chat("Chat", Role.USER to "hi", Role.ASSISTANT to "hello")
 * }
 * ```
 *
 * @param clock forwarded to [InMemoryVaultRepository] for deterministic
 *   `created_at`/`updated_at` — pass a `FakeClock` for a controlled seed.
 * @param attachments forwarded to [InMemoryVaultRepository]; defaults to a
 *   fresh [InMemoryAttachmentStore].
 */
public fun fakeVault(
    clock: () -> Long = System::currentTimeMillis,
    attachments: AttachmentStore = InMemoryAttachmentStore(),
    block: FakeVaultBuilder.() -> Unit,
): InMemoryVaultRepository {
    val repository = InMemoryVaultRepository(clock = clock, attachments = attachments)
    FakeVaultBuilder(repository).block()
    return repository
}

/**
 * Builds a [FakeInferenceEngine] scripted by (last-user-message -> pieces)
 * pairs: `scriptedEngine("q" to listOf("a", "b"))`. See
 * [FakeInferenceEngine]'s own KDoc for exact `stream`/`load` semantics
 * (single-collector `Busy`, cooperative cancellation, hash-mismatch
 * rejection, …) — this builder only composes its constructor.
 */
public fun scriptedEngine(
    vararg script: Pair<String, List<String>>,
    tokenDelay: Duration = ZERO,
): FakeInferenceEngine = FakeInferenceEngine(script = script.toMap(), tokenDelay = tokenDelay)

/**
 * Builds a [FakeEmbedderService] — deterministic, hash-seeded embeddings
 * (equal text always embeds to the identical vector; see
 * [FakeEmbedderService]'s own KDoc for exactly what that does and doesn't
 * guarantee). A convenience alias so builder call sites read consistently
 * (`fakeVault { ... }`, `scriptedEngine(...)`, `fakeEmbedder()`); it adds no
 * behaviour beyond the constructor call.
 */
public fun fakeEmbedder(
    embedderId: String = "fake-embedder",
    embedderVersion: Int = 1,
): FakeEmbedderService = FakeEmbedderService(embedderId = embedderId, embedderVersion = embedderVersion)
