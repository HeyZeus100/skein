// skein-xtov.9 — fixture vault for the timeline captures (a copy of
// :feature:shell's screenshot fixture, so both modules render the same
// entries), at fixed timestamps so relative times never drift between runs.
package app.skein.feature.timeline.screenshots

import app.skein.core.model.FrontmatterKeys
import app.skein.core.model.Role
import app.skein.testing.FakeClock
import app.skein.testing.InMemoryVaultRepository
import app.skein.testing.fakeVault
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** "Now" for every capture: 2026-09-26 16:00 UTC. */
val UX_NOW: Long = Instant.parse("2026-09-26T16:00:00Z").toEpochMilli()
val UX_ZONE: ZoneId = ZoneOffset.UTC

/** A real GGUF filename long enough to stress the command bar's model chip. */
const val UX_LONG_MODEL_FILE = "qwen2.5-3b-instruct-abliterated-q3_k_m.gguf"

/** What `:app`'s `MainActivity` shows in the chip for a default model that is not loaded yet (`CHIP_NAME_MAX` = 20). */
val UX_NOT_LOADED_CHIP = "${UX_LONG_MODEL_FILE.take(20)} · not loaded"

private fun tags(vararg values: String): JsonObject =
    JsonObject(mapOf(FrontmatterKeys.TAGS to JsonArray(values.map { JsonPrimitive(it) })))

private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE
private const val DAY = 24 * HOUR

fun uxFixtureVault(): InMemoryVaultRepository {
    val clock = FakeClock()
    return fakeVault(clock = clock::now) {
        clock.set(UX_NOW - 9 * DAY)
        note(
            "Reading list — local-first software",
            "Ink & Switch essay, CRDT papers, and the Automerge talk. Pull quotes into [[Sync design]].",
            frontmatter = tags("reading"),
        )
        clock.set(UX_NOW - 3 * DAY - 2 * HOUR)
        note(
            "Sync design",
            "No network permission, so sync is export/import only. Folder export stays plain Markdown.",
            frontmatter = tags("design", "vault"),
        )
        clock.set(UX_NOW - DAY - 5 * HOUR)
        chat(
            "Chat about quantisation",
            Role.USER to "Is Q3_K_M good enough for a 3B model on the Fold?",
            Role.ASSISTANT to "For chat, yes: Q3_K_M keeps most of the quality at about 1.6 GB.",
        )
        clock.set(UX_NOW - DAY - 2 * HOUR)
        note(
            "Weekly review",
            "Shipped: citations, backlinks drawer. Next: fold transitions and the model picker.",
            frontmatter = tags("review"),
        )
        clock.set(UX_NOW - 3 * HOUR)
        note(
            "Fold launch plan",
            "Targets M2 for the ask path. Owner smoke test on the Pixel 9 Pro Fold before tagging.",
            frontmatter = tags("launch", "fold"),
        )
        clock.set(UX_NOW - 40 * MINUTE)
        chat(
            "Chat",
            Role.USER to "What did my notes say about the Fold launch plan?",
            Role.ASSISTANT to "The launch plan targets M2 for the ask path [1].",
        )
        clock.set(UX_NOW - 12 * MINUTE)
        note(
            "Meeting notes 26 Sep",
            "Decided: Roborazzi baselines before any redesign. Action: capture every posture.",
            frontmatter = tags("meeting"),
        )
    }
}
