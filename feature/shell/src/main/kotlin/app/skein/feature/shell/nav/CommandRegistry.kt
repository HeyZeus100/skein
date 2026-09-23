package app.skein.feature.shell.nav

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Slash-command scopes (plan `E6.I4`, spec §8.2/§8.5): [GLOBAL] commands are
 * always available (`/new note` here; slice B's `/new chat`/`/persona`/
 * `/model`/`/index now`/`/graph`/`/settings`/`/export` land the same way
 * once their own deps close). [EDITOR] is the scope `E7.I6` (bd `skein-6sd`)
 * registers into only while a note tab is focused, so editor-only commands
 * (`/ai continue`, `/link related`) disappear from the palette the moment
 * focus leaves the editor — this enum exists now purely so that follow-up
 * issue has a scope to register into without changing [CommandRegistry]'s
 * shape.
 */
enum class CommandScope { GLOBAL, EDITOR }

/**
 * One `/` palette entry (spec §8.2). [keyword] is matched against the text
 * typed after the leading `/` — it may be multi-word (`"new note"`), so a
 * user typing `/new` sees both `/new note` and (once slice B lands)
 * `/new chat` in the filtered list; see [CommandRegistry.filter] and
 * [CommandRegistry.match]. [hint] is the palette's trailing description
 * (e.g. `"[title] — create a note and open it pinned"`). [run] receives
 * everything typed after [keyword], trimmed — the note title in
 * `/new note Smoke test`, empty for a bare `/new note`.
 */
data class Command(
    val keyword: String,
    val hint: String,
    val run: suspend (arg: String) -> Unit,
)

/**
 * The `/` palette's command source — the plan `E6.I4` interface shape
 * verbatim (`CommandRegistry.register(scope, commands)`, "consumed by
 * `E7.I6`"). Slice A only ever registers [CommandScope.GLOBAL] (`/new
 * note`, see `BuiltinCommands.kt`); the shape exists now so `E7.I6` can add
 * [CommandScope.EDITOR] commands later without this class changing.
 *
 * Backed by a Compose snapshot state map so a [app.skein.feature.shell.nav.CommandPalette]
 * reading [commands]/[filter] recomposes automatically when a scope's
 * commands change (e.g. `E7.I6` registering/unregistering as the active
 * tab changes) — the same `@Stable` + `mutableStateOf` shape [NavState] and
 * [app.skein.feature.shell.tabs.TabsState] already use.
 */
@Stable
class CommandRegistry {
    private var byScope: Map<CommandScope, List<Command>> by mutableStateOf(emptyMap())

    /** Replaces [scope]'s whole command list. Re-registering the same scope replaces, never appends. */
    fun register(
        scope: CommandScope,
        commands: List<Command>,
    ) {
        byScope = byScope + (scope to commands)
    }

    /** Drops every command registered for [scope] — `E7.I6` calls this when the editor tab loses focus. */
    fun unregister(scope: CommandScope) {
        byScope = byScope - scope
    }

    /** All commands registered across [scopes] (default: [CommandScope.GLOBAL] only). */
    fun commands(scopes: Set<CommandScope> = setOf(CommandScope.GLOBAL)): List<Command> =
        scopes.flatMap { byScope[it].orEmpty() }

    /** Commands whose [Command.keyword] starts with what's typed after `/`, for the palette list. Blank input matches everything. */
    fun filter(
        typed: String,
        scopes: Set<CommandScope> = setOf(CommandScope.GLOBAL),
    ): List<Command> {
        val normalized = typed.trimStart()
        return commands(scopes).filter { normalized.isBlank() || it.keyword.startsWith(normalized, ignoreCase = true) }
    }

    /**
     * The command [typed] names exactly — its [Command.keyword] followed by
     * either nothing or a space and an argument — paired with that
     * (trimmed) argument. `null` if [typed] doesn't complete any registered
     * keyword (e.g. still `"n"`, or an unknown command).
     */
    fun match(
        typed: String,
        scopes: Set<CommandScope> = setOf(CommandScope.GLOBAL),
    ): Pair<Command, String>? {
        val trimmed = typed.trimStart()
        return commands(scopes)
            .firstOrNull { command ->
                trimmed.equals(command.keyword, ignoreCase = true) ||
                    trimmed.startsWith("${command.keyword} ", ignoreCase = true)
            }?.let { command -> command to trimmed.removePrefix(command.keyword).trim() }
    }
}
