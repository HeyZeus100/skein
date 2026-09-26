# Continue: reference study for Skein

**Bead:** `skein-xtov.5` (epic `skein-xtov`)
**Authority:** `docs/research/SKEIN_UI_UX_OVERHAUL_PROMPT.md` §§1–3, 7, 16, 17, 21, 35, 41–42, 52–53
**Companions:** [`ZED.md`](ZED.md), [`LIBRECHAT.md`](LIBRECHAT.md)
**Status:** Research record, advisory only. Nothing here changes code. The command palette and keyboard design proposed at the end is input for Wave 10 ("Command Palette & Power UX", prompt §48). It is not an accepted spec.

---

## Project

<https://github.com/continuedev/continue>. Continue is an open-source coding agent shipped as a VS Code extension, a JetBrains plugin and a CLI (TUI). Its GUI is a React webview (`gui/`) that talks to a shared TypeScript `core/` through a messenger protocol. The CLI (`extensions/cli/`) is an Ink TUI over the same core.

| Field | Value |
| --- | --- |
| Commit inspected | `5522c6f44ca0ac3528b37244818fbfa39b5af470`, the default-branch head, committed 2026-07-21 04:00 UTC (`docs: remove Sign in link (login flow retired) (#13005)`) |
| Final product tags | `v2.0.0-vscode` → `03b05ef60c37…`, `v2.1.0-vscode` → `b238c1f11f26…`, both published 2026-06-19 |
| Inspection date | 2026-09-26 |
| Local clone | `research/clones/continue/`. It is git-ignored (`.gitignore:121` → `research/clones/`) and never committed. |
| Areas read | `gui/src/components/mainInput/**` (composer, toolbar, @-dropdown, context peek, "Lump" status strip), `gui/src/components/{ModeSelect,modelSelection,History,ConversationStarters}`, `gui/src/context/SubmenuContextProviders.tsx`, `gui/src/pages/gui/{Chat.tsx,ToolCallDiv/}`, `core/tools/definitions/`, `core/commands/slash/`, `extensions/vscode/{package.json,src/commands.ts}`, `extensions/cli/src/{commands,ui}/` |

Reproduce:

```bash
git clone --depth 1 --filter=blob:none https://github.com/continuedev/continue.git research/clones/continue
git -C research/clones/continue log -1 --format='%H %cI'   # 5522c6f44ca0ac3528b37244818fbfa39b5af470
git check-ignore -v research/clones/continue              # must print .gitignore:121
```

Every citation below is a SHA-pinned permalink `https://github.com/continuedev/continue/blob/5522c6f44ca0ac3528b37244818fbfa39b5af470/<path>#L<n>`. Skein paths are repo-relative at `009cbb6`.

## License

**SPDX: `Apache-2.0`.** The full text is in [`LICENSE`](https://github.com/continuedev/continue/blob/5522c6f44ca0ac3528b37244818fbfa39b5af470/LICENSE), and the README states "Apache 2.0 © 2023-2026 Continue Dev, Inc." ([README.md#L61-L63](https://github.com/continuedev/continue/blob/5522c6f44ca0ac3528b37244818fbfa39b5af470/README.md#L60-L62)). Apache-2.0 is on Skein's permissive `foss` allowlist, so copying would be legal if the NOTICE/attribution rules in §4 of the licence were followed. **No Continue code is copied into Skein in this bead.** Every candidate below is a TypeScript/React pattern that is cheaper to re-express in Compose than to port.

## Maintenance

**The project is unmaintained and read-only.** The README says: "*The `continuedev/continue` repository is no longer actively maintained and is read-only for all users.*" ([README.md#L19](https://github.com/continuedev/continue/blob/5522c6f44ca0ac3528b37244818fbfa39b5af470/README.md#L19)). A "Final 2.0.0 Release" section says telemetry and authentication were removed ([README.md#L27-L31](https://github.com/continuedev/continue/blob/5522c6f44ca0ac3528b37244818fbfa39b5af470/README.md#L27-L31)).

- The last product release was 2026-06-19 (`v2.0.0-vscode` / `v2.1.0-vscode`). The last `main` commit is a docs change on 2026-07-21. Pushes since then (the GitHub API reports `pushed_at` 2026-09-24) land on Dependabot branches, not on `main`.
- The project has 36k stars and 938 open issues, frozen.
- Consequence for Skein: Continue is a stable, final snapshot to learn from, with nothing upstream to track or depend on. That settles prompt §7's "Do not make Continue a required Skein dependency" by default.

## Relevant Skein Problem

Continue matters to Skein because it solved "simple chat box, deep workspace" inside a narrow side panel. The same constraint applies to the Fold's outer screen. These are the concrete Skein problems it speaks to, as of `009cbb6`:

1. **The `/command` concept is a raw parser, not a palette** (prompt §42).
   - `CommandPalette` renders each row as the literal string `"/${command.keyword} ${command.hint}"`, with no icon, group, description column or shortcut ([`feature/shell/.../nav/CommandPalette.kt:46`](../../../feature/shell/src/main/kotlin/app/skein/feature/shell/nav/CommandPalette.kt)).
   - `CommandRegistry.filter` is prefix-only ([`CommandRegistry.kt:72-77`](../../../feature/shell/src/main/kotlin/app/skein/feature/shell/nav/CommandRegistry.kt)).
   - Tapping a row only fills the field, and nothing runs until IME "Search" (`CommandBarState.onSubmit`).
   - Only four commands exist: `new note`, `chat` ([`BuiltinCommands.kt:28,59`](../../../feature/shell/src/main/kotlin/app/skein/feature/shell/nav/BuiltinCommands.kt)), `import model` and `models` ([`app/.../MainActivity.kt:609,620`](../../../app/src/main/kotlin/app/skein/MainActivity.kt)).
2. **The chat composer's `/` seam is not wired.**
   - `ChatBottomBar` fires `onSlashCommand` when the composer becomes exactly `/` ([`ChatBottomBar.kt:168`](../../../feature/chat/src/main/kotlin/app/skein/feature/chat/ChatBottomBar.kt)), but the parameter defaults to `{}` (line 86).
   - The only `ChatScreen(...)` call site never passes it ([`MainActivity.kt:589-600`](../../../app/src/main/kotlin/app/skein/MainActivity.kt)).
   - A documented affordance (spec §8.4 "slash-commands") therefore does nothing, which breaks prompt §2 rule 4.
3. **There is no keyboard layer.** The only key handling is Enter/Shift+Enter in the composer ([`ChatBottomBar.kt:179-196`](../../../feature/chat/src/main/kotlin/app/skein/feature/chat/ChatBottomBar.kt)) plus editor and autocomplete keys. There is no palette chord, no new-chat chord, no Esc-to-stop (Stop is only the button at line 205), and no way to discover shortcuts (prompt §41).
4. **Context is shown in implementation language.** `ContextPanel` prints `score %.2f` and `recalled by: vector, lexical` ([`ContextPanel.kt:78,89`](../../../feature/chat/src/main/kotlin/app/skein/feature/chat/ContextPanel.kt)), against prompt §2 rule 5 and §35.
5. **The model chip is status, not a control.** The command bar shows `"$modelName · $statusGlyph"` as plain text ([`CommandBar.kt:125-127`](../../../feature/shell/src/main/kotlin/app/skein/feature/shell/nav/CommandBar.kt)). Switching goes through `/models`.
6. **Every new chat is titled `"Chat"`** ([`BuiltinCommands.kt:33`](../../../feature/shell/src/main/kotlin/app/skein/feature/shell/nav/BuiltinCommands.kt)). This produces the "repeated anonymous Chat entries" that prompt §51 forbids.
7. **The key question** (prompt §7): how does Skein keep a PocketPal-simple front door while exposing Continue-like depth (Knowledge, context inspection, personas, models, skills, repositories; prompt §3 "Level 2")?

## Patterns Worth Adopting

Each pattern gives the Continue evidence, then the Skein translation. Numbering (C1–C18) is reused in the palette design below.

**C1. One composer, a few chips, gated by capability and collapsed by width.**
- Evidence: Continue's input toolbar holds Mode, Model, an image button, an `@` button, and Send.
  - The image button renders only if `modelSupportsImages(...)` ([InputToolbar.tsx#L63-L72](https://github.com/continuedev/continue/blob/5522c6f44ca0ac3528b37244818fbfa39b5af470/gui/src/components/mainInput/InputToolbar.tsx#L63-L72), [#L100-L130](https://github.com/continuedev/continue/blob/5522c6f44ca0ac3528b37244818fbfa39b5af470/gui/src/components/mainInput/InputToolbar.tsx#L100-L130)).
  - The reasoning bulb renders only if `modelSupportsReasoning` ([#L138-L167](https://github.com/continuedev/continue/blob/5522c6f44ca0ac3528b37244818fbfa39b5af470/gui/src/components/mainInput/InputToolbar.tsx#L138-L167)).
  - Secondary icons are `hidden` below the `xs` breakpoint (line 99), the mode label collapses to its icon below `sm` ([ModeSelect.tsx#L97](https://github.com/continuedev/continue/blob/5522c6f44ca0ac3528b37244818fbfa39b5af470/gui/src/components/ModeSelect/ModeSelect.tsx#L97)), and "⏎ Enter" collapses to "⏎" below `md` ([InputToolbar.tsx#L241-L244](https://github.com/continuedev/continue/blob/5522c6f44ca0ac3528b37244818fbfa39b5af470/gui/src/components/mainInput/InputToolbar.tsx#L241-L244)).
- Skein translation:
  - The composer row is `[attach] [message] [send/stop]`, with a single model/persona chip above or beside it.
  - "Attach image" appears only when the active model reports vision capability (the `models.capabilities` column exists, spec §5). This enforces "no dead controls" by construction.
  - At outer-screen width, labels collapse to icons with content descriptions.

**C2. The placeholder teaches exactly one power affordance.**
- Evidence: `"Ask anything, '@' to add context"` on an empty chat, `"Ask a follow-up"` after the first turn ([editorConfig.ts#L32-L43](https://github.com/continuedev/continue/blob/5522c6f44ca0ac3528b37244818fbfa39b5af470/gui/src/components/mainInput/TipTapEditor/utils/editorConfig.ts#L32-L43)).
- Skein translation:
  - Empty chat placeholder: "Ask anything · [[ to add a note".
  - After the first turn: "Ask a follow-up".
  - No other onboarding text in the composer.

**C3. The toolbar button types the sigil.** The touch path and the keyboard path are one code path.
- Evidence: the `@` toolbar button calls `insertCharacterWithWhitespace("@")`, which opens the same dropdown as typing `@` ([TipTapEditor.tsx#L281](https://github.com/continuedev/continue/blob/5522c6f44ca0ac3528b37244818fbfa39b5af470/gui/src/components/mainInput/TipTapEditor/TipTapEditor.tsx#L281)).
- Skein translation: an "Add note" affordance (in the 📎 menu, or a chip on the inner screen) inserts `[[` and opens the existing wikilink autocomplete. There is one picker, one test surface, and no parallel attach dialog.

**C4. Context providers come in three shapes, with a cross-provider fallthrough.**
- Evidence: an item either inserts immediately, drills into a `submenu` (→ arrow), or opens an inline `query` field (e.g. search terms) ([AtMentionDropdown/index.tsx#L337-L381](https://github.com/continuedev/continue/blob/5522c6f44ca0ac3528b37244818fbfa39b5af470/gui/src/components/mainInput/AtMentionDropdown/index.tsx#L337-L381)). When the typed text matches no provider name, the dropdown searches every provider's items instead ([getSuggestion.ts#L146-L190](https://github.com/continuedev/continue/blob/5522c6f44ca0ac3528b37244818fbfa39b5af470/gui/src/components/mainInput/TipTapEditor/utils/getSuggestion.ts#L146-L190)).
- Skein translation: the picker behind `[[`/`@` offers categories (Notes · Chats · Files · Tags) as drill-downs, and a query shape "Search Knowledge for …" that attaches retrieval results. Typing a title directly skips the categories. The same three shapes structure the command palette's argument stage (see the design below).

**C5. Deterministic tiered ranking, with open items first when the query is empty.**
- Evidence: file mentions rank in this order ([SubmenuContextProviders.tsx#L224-L278](https://github.com/continuedev/continue/blob/5522c6f44ca0ac3528b37244818fbfa39b5af470/gui/src/context/SubmenuContextProviders.tsx#L224-L278)):
  1. exact name
  2. currently open file
  3. name prefix
  4. exact word
  5. (dev-file heuristics)
  6. camel-case or initials
  7. path prefix

  Ties break on match quality (exact +100, prefix +50, contains +25, shorter names win; [#L280-L314](https://github.com/continuedev/continue/blob/5522c6f44ca0ac3528b37244818fbfa39b5af470/gui/src/context/SubmenuContextProviders.tsx#L280-L314)), then MiniSearch `prefix:true, fuzzy:2` score ([#L25-L28](https://github.com/continuedev/continue/blob/5522c6f44ca0ac3528b37244818fbfa39b5af470/gui/src/context/SubmenuContextProviders.tsx#L25-L28)), then shorter path. With no hits it falls back to open files, or to a "Loading…" row while providers index ([#L406-L443](https://github.com/continuedev/continue/blob/5522c6f44ca0ac3528b37244818fbfa39b5af470/gui/src/context/SubmenuContextProviders.tsx#L406-L443)). Results are capped at 70.
- Skein translation: `[[` autocomplete and the palette's document results rank open tabs first, then exact title, title prefix, word start, initials ("mn" → "Meeting notes") and fuzzy. The empty query lists open tabs, then recently opened documents. Details are under "Ranking" below.

**C6. A pre-attach guard with a reason.**
- Evidence: before inserting a file, Continue asks core whether it exceeds the model's context. If it does, it removes the half-typed mention and says why, including the size ([AtMentionDropdown/index.tsx#L183-L264](https://github.com/continuedev/continue/blob/5522c6f44ca0ac3528b37244818fbfa39b5af470/gui/src/components/mainInput/AtMentionDropdown/index.tsx#L183-L264)).
- Skein translation: with a 16K effective context (spec §6), attaching a long note or PDF should warn inline before send ("Too long to include whole; Skein will use the most relevant parts"). Silent truncation is not acceptable. The warning is an inline row message, not a modal.

**C7. Active context is a collapsed per-message peek.**
- Evidence: `ContextItemsPeek` renders a `ToggleDiv` titled "N context items". While retrieval runs it reads "Gathering context…"; expanded, each item is icon, name and short description, and tapping opens the source ([ContextItemsPeek.tsx#L161-L204](https://github.com/continuedev/continue/blob/5522c6f44ca0ac3528b37244818fbfa39b5af470/gui/src/components/mainInput/belowMainInput/ContextItemsPeek.tsx#L161-L204), [#L30-L52](https://github.com/continuedev/continue/blob/5522c6f44ca0ac3528b37244818fbfa39b5af470/gui/src/components/mainInput/belowMainInput/ContextItemsPeek.tsx#L30-L52)). No scores are shown.
- Skein translation:
  - Replace `score 0.83 · recalled by: vector` with one line under each answer: "Used 3 notes ▸".
  - Expanded, it shows title, kind glyph and matched excerpt, and tapping opens a preview tab (the existing `TabController.openPreview`).
  - Scores and recall sources move to the Context Inspector's "Details" disclosure (prompt §35).

**C8. Thresholded disclosure of the context window.**
- Evidence: `ContextStatus` renders nothing until context is at least 60 % full or history is being pruned. Then it shows a tiny fill bar whose popover offers "Compact conversation" and "Start a new session" ([ContextStatus.tsx#L33-L35](https://github.com/continuedev/continue/blob/5522c6f44ca0ac3528b37244818fbfa39b5af470/gui/src/components/mainInput/ContextStatus.tsx#L33-L35), [#L55-L88](https://github.com/continuedev/continue/blob/5522c6f44ca0ac3528b37244818fbfa39b5af470/gui/src/components/mainInput/ContextStatus.tsx#L55-L88)).
- Skein translation: prompt §3 says Level-1 users should never need to understand "context windows". Skein shows nothing until about 60 % of the 16K budget, then a one-line hint in the status strip (C9): "This chat is getting long · Start fresh with a summary". The raw percentage lives in the Inspector.

**C9. A state-driven strip above the composer shows exactly one next action.**
- Evidence: the "Lump" toolbar renders one of these, in precedence order ([LumpToolbar.tsx#L171-L211](https://github.com/continuedev/continue/blob/5522c6f44ca0ac3528b37244818fbfa39b5af470/gui/src/components/mainInput/Lump/LumpToolbar/LumpToolbar.tsx#L171-L211)):
  1. applying
  2. edit mode
  3. TTS
  4. running terminal
  5. streaming ("Stop ⌘⌫", [StreamingToolbar.tsx#L15-L28](https://github.com/continuedev/continue/blob/5522c6f44ca0ac3528b37244818fbfa39b5af470/gui/src/components/mainInput/Lump/LumpToolbar/StreamingToolbar.tsx#L15-L28))
  6. pending tool calls (Accept ⌘⏎ / Reject ⌘⌫)
  7. pending applies
  8. otherwise, quiet config icons

  Shortcut hints appear only on the first pending row (`index === 0`, [PendingToolCallToolbar.tsx#L61-L83](https://github.com/continuedev/continue/blob/5522c6f44ca0ac3528b37244818fbfa39b5af470/gui/src/components/mainInput/Lump/LumpToolbar/PendingToolCallToolbar.tsx#L61-L83)). Config icons disappear whenever there is active content ([BlockSettingsTopToolbar.tsx#L96-L116](https://github.com/continuedev/continue/blob/5522c6f44ca0ac3528b37244818fbfa39b5af470/gui/src/components/mainInput/Lump/LumpToolbar/BlockSettingsTopToolbar.tsx#L96-L116)).
- Skein translation: a single `ChatStatusStrip` with a sealed state: `Idle`, `LoadingModel`, `Generating` (Stop · Esc), `ReviewInlineEdit` (Keep / Discard), `ContextNearlyFull` and `Error` (Retry). It gives prompt §2 rule 6 ("one clear next action in every major state") one owner, and replaces scattered spinners and banners.

**C10. Modes are tool-permission tiers behind one chip** (defer for Skein v1).
- Evidence: Chat means "All tools disabled", Plan means "Read-only/MCP tools", and Agent means "All tools" ([ModeSelect.tsx#L106-L165](https://github.com/continuedev/continue/blob/5522c6f44ca0ac3528b37244818fbfa39b5af470/gui/src/components/ModeSelect/ModeSelect.tsx#L106-L165); the type is `"chat" | "agent" | "plan" | "background"`, [core/index.d.ts#L495](https://github.com/continuedev/continue/blob/5522c6f44ca0ac3528b37244818fbfa39b5af470/core/index.d.ts#L495)). `⌘.` cycles modes and returns focus to the composer ([#L36-L73](https://github.com/continuedev/continue/blob/5522c6f44ca0ac3528b37244818fbfa39b5af470/gui/src/components/ModeSelect/ModeSelect.tsx#L36-L73)). A ⚠ appears when the model isn't recommended for agent use (`isRecommendedAgentModel`).
- Skein translation: v1 exposes no model-driven tools (spec §9, CaMeL: "model output never derives Intent URIs or tool calls"), so **no mode chip in v1**. If skills or tools land later, reuse exactly this vocabulary: one chip, permission-described modes, a per-model suitability warning, and a keyboard cycle.

**C11. The model picker is a quiet trigger with an honest list.**
- Evidence: the trigger is plain text plus a chevron ([ModelSelect.tsx#L249-L261](https://github.com/continuedev/continue/blob/5522c6f44ca0ac3528b37244818fbfa39b5af470/gui/src/components/modelSelection/ModelSelect.tsx#L249-L261)).
  - Unusable rows stay visible, disabled with their reason "(Missing API key)", and sort last ([#L100-L104](https://github.com/continuedev/continue/blob/5522c6f44ca0ac3528b37244818fbfa39b5af470/gui/src/components/modelSelection/ModelSelect.tsx#L100-L104), [#L146-L155](https://github.com/continuedev/continue/blob/5522c6f44ca0ac3528b37244818fbfa39b5af470/gui/src/components/modelSelection/ModelSelect.tsx#L146-L155)).
  - A header gear opens model settings, the footer offers "Add Chat model" ([#L262-L325](https://github.com/continuedev/continue/blob/5522c6f44ca0ac3528b37244818fbfa39b5af470/gui/src/components/modelSelection/ModelSelect.tsx#L262-L325)), and a footer line teaches the shortcut.
  - Models are chosen **per role** (`chat, autocomplete, embed, rerank, edit, apply, summarize, subagent`, [config-yaml models.ts#L23-L32](https://github.com/continuedev/continue/blob/5522c6f44ca0ac3528b37244818fbfa39b5af470/packages/config-yaml/src/schemas/models.ts#L23-L32)). In edit mode the picker lists the `edit`-role models (ModelSelect.tsx#L135-L144).
- Skein translation:
  - The command-bar model text becomes a tappable chip showing a human name ("Gemma 4 · on device"; prompt §34).
  - The picker lists imported models, with unverified or incompatible ones disabled and a reason ("Needs vision" / "Hash check failed").
  - Footer: "Import model…" and "Manage models".
  - Skein already has roles implicitly (chat model vs the `:embedder` model). Only the chat role belongs in the chip; the embedder stays in Models → Advanced.

**C12. Edit mode re-targets the same composer.**
- Evidence: when editing, the strip reads "← Back to Chat · Editing: *file (lines)*" ([EditToolbar.tsx#L21-L36](https://github.com/continuedev/continue/blob/5522c6f44ca0ac3528b37244818fbfa39b5af470/gui/src/components/mainInput/Lump/LumpToolbar/EditToolbar.tsx#L21-L36)). The outcome reads "N diffs · Accept/Reject" ([EditOutcomeToolbar.tsx#L16-L33](https://github.com/continuedev/continue/blob/5522c6f44ca0ac3528b37244818fbfa39b5af470/gui/src/components/mainInput/Lump/LumpToolbar/EditOutcomeToolbar.tsx#L16-L33)). `Esc` exits edit ([InputToolbar.tsx#L211-L223](https://github.com/continuedev/continue/blob/5522c6f44ca0ac3528b37244818fbfa39b5af470/gui/src/components/mainInput/InputToolbar.tsx#L211-L223)).
- Skein translation: the note editor's inline AI (spec §8.5: Rewrite · Continue · Summarize · Ask) shows as the strip state "Editing: *Note title* (selection) ← Back". The outcome is "Keep / Discard". There is no separate dialog, and Esc discards the pending proposal.

**C13. Activity is written in product language, with three tenses per tool.**
- Evidence: each tool declares `wouldLikeTo` / `isCurrently` / `hasAlready` templates, e.g. "read {{filepath}}" / "reading …" / "read …" ([readFile.ts#L9-L12](https://github.com/continuedev/continue/blob/5522c6f44ca0ac3528b37244818fbfa39b5af470/core/tools/definitions/readFile.ts#L9-L12), [grepSearch.ts#L6-L9](https://github.com/continuedev/continue/blob/5522c6f44ca0ac3528b37244818fbfa39b5af470/core/tools/definitions/grepSearch.ts#L6-L9)). Groups read "Performing / Performed / Attempted N actions" ([ToolCallDiv/utils.tsx#L37-L57](https://github.com/continuedev/continue/blob/5522c6f44ca0ac3528b37244818fbfa39b5af470/gui/src/pages/gui/ToolCallDiv/utils.tsx#L37-L57)), and reasoning collapses to "Thought for 4.2s (812 tokens)" ([ThinkingBlockPeek.tsx#L70-L81](https://github.com/continuedev/continue/blob/5522c6f44ca0ac3528b37244818fbfa39b5af470/gui/src/components/mainInput/belowMainInput/ThinkingBlockPeek.tsx#L70-L81)).
- Skein translation: retrieval and any future `VaultTools` (`core/agent/.../VaultTools.kt`) get tense triples. For example: "Searching your notes for "quarterly plan"", then "Searched your notes · 3 used". This is the activity language prompt §27 asks for, and it costs a data table, not a component.

**C14. Keyboard-first, with context-aware chords.** Each chord below is mapped to its Skein equivalent in the design section.
- **`⌘L`** ([vscode/src/commands.ts#L241-L284](https://github.com/continuedev/continue/blob/5522c6f44ca0ac3528b37244818fbfa39b5af470/extensions/vscode/src/commands.ts#L241-L284)):
  - Unfocused: focus the chat, start a fresh session, and attach the current editor selection.
  - Already focused: start a new session, or hide the panel if the chat is empty.
- **`⌘⇧L`**: the same, but adds to the current session ([#L285-L315](https://github.com/continuedev/continue/blob/5522c6f44ca0ac3528b37244818fbfa39b5af470/extensions/vscode/src/commands.ts#L285-L315)). The bindings are at [package.json#L380-L389](https://github.com/continuedev/continue/blob/5522c6f44ca0ac3528b37244818fbfa39b5af470/extensions/vscode/package.json#L380-L389).
- **Composer keys** ([editorConfig.ts#L233-L344](https://github.com/continuedev/continue/blob/5522c6f44ca0ac3528b37244818fbfa39b5af470/gui/src/components/mainInput/TipTapEditor/utils/editorConfig.ts#L233-L344)):
  - Enter sends, unless a dropdown is open.
  - Shift+Enter inserts a newline, and `Mod+Enter` is an alternate send.
  - `Mod+Backspace` is swallowed while streaming, so "cancel" can never delete text.
  - `↑` at the start of the input recalls the previous prompt.
  - Esc returns focus to the editor.
- **Global cancel** is `⌘⌫` ([pages/gui/Chat.tsx#L136-L152](https://github.com/continuedev/continue/blob/5522c6f44ca0ac3528b37244818fbfa39b5af470/gui/src/pages/gui/Chat.tsx#L136-L152)).
- **CLI Esc ladder** ([UserInput.tsx#L623-L681](https://github.com/continuedev/continue/blob/5522c6f44ca0ac3528b37244818fbfa39b5af470/extensions/cli/src/ui/UserInput.tsx#L623-L681)): interrupt compaction, then interrupt streaming, then close the slash list, then close file search. A double-Esc opens "edit a previous message".
- **CLI mode cycle** is Shift+Tab ([useUserInput.ts#L95-L101](https://github.com/continuedev/continue/blob/5522c6f44ca0ac3528b37244818fbfa39b5af470/extensions/cli/src/ui/hooks/useUserInput.ts#L95-L101)).

**C15. Few commands in the host palette; the rest live where they belong.**
- Evidence: of 37 contributed commands, only 6 are whitelisted into VS Code's command palette ([package.json#L505-L524](https://github.com/continuedev/continue/blob/5522c6f44ca0ac3528b37244818fbfa39b5af470/extensions/vscode/package.json#L505-L524)). Selection-scoped actions live in the editor context menu ("Add to Chat", "Add to Edit", [#L525-L548](https://github.com/continuedev/continue/blob/5522c6f44ca0ac3528b37244818fbfa39b5af470/extensions/vscode/package.json#L525-L548)), and "Select Files as Context" lives in the file-explorer menu ([#L549](https://github.com/continuedev/continue/blob/5522c6f44ca0ac3528b37244818fbfa39b5af470/extensions/vscode/package.json#L549)).
- Skein translation: the palette is not a dumping ground. Selection actions ("Ask about selection", "Add to chat") live in the editor's selection menu *and* the palette, filtered by availability. Long-press on a Knowledge row offers "Add to current chat". Rarely-used maintenance (re-index, re-embed) stays in Settings.

**C16. Slash-command lists: substring match, prefix first, one footer hint.**
- Evidence: the CLI filters with `includes`, sorts prefix matches first and then alphabetically ([SlashCommandUI.tsx#L66-L76](https://github.com/continuedev/continue/blob/5522c6f44ca0ac3528b37244818fbfa39b5af470/extensions/cli/src/ui/SlashCommandUI.tsx#L66-L76)), and ends with "↑/↓ to navigate, Enter to select, Tab to complete" ([#L113-L117](https://github.com/continuedev/continue/blob/5522c6f44ca0ac3528b37244818fbfa39b5af470/extensions/cli/src/ui/SlashCommandUI.tsx#L113-L117)). Commands carry a category, `system` or `assistant` (user prompts, rules, skills; [commands.ts#L14-L18](https://github.com/continuedev/continue/blob/5522c6f44ca0ac3528b37244818fbfa39b5af470/extensions/cli/src/commands/commands.ts#L14-L18), [#L149-L183](https://github.com/continuedev/continue/blob/5522c6f44ca0ac3528b37244818fbfa39b5af470/extensions/cli/src/commands/commands.ts#L149-L183)). The GUI's empty slash list offers "Create a prompt" ([getSuggestion.ts#L227-L241](https://github.com/continuedev/continue/blob/5522c6f44ca0ac3528b37244818fbfa39b5af470/gui/src/components/mainInput/TipTapEditor/utils/getSuggestion.ts#L227-L241)).
- Skein translation: `CommandRegistry.filter` moves from `startsWith` to the ranking described below. The palette footer on the inner screen with a keyboard reads "↑↓ move · ⏎ run · Tab complete · Esc close". A future category separates built-in commands from user-defined ones (persona prompts, skills).

**C17. Conversation history: date groups, tiered search, honest storage note.**
- Evidence: groups are Today / This Week / This Month / Older ([History/util.ts#L16-L50](https://github.com/continuedev/continue/blob/5522c6f44ca0ac3528b37244818fbfa39b5af470/gui/src/components/History/util.ts#L16-L50)). Search runs exact → fuzzy → prefix passes and merges by priority ([History/index.tsx#L69-L116](https://github.com/continuedev/continue/blob/5522c6f44ca0ac3528b37244818fbfa39b5af470/gui/src/components/History/index.tsx#L69-L116)). "Clear chats" asks for confirmation ([#L122-L144](https://github.com/continuedev/continue/blob/5522c6f44ca0ac3528b37244818fbfa39b5af470/gui/src/components/History/index.tsx#L122-L144)). The empty state teaches the new-session shortcut ([#L174-L186](https://github.com/continuedev/continue/blob/5522c6f44ca0ac3528b37244818fbfa39b5af470/gui/src/components/History/index.tsx#L174-L186)), and a footer says where history is stored ([#L216-L226](https://github.com/continuedev/continue/blob/5522c6f44ca0ac3528b37244818fbfa39b5af470/gui/src/components/History/index.tsx#L216-L226)).
- Skein translation: the conversation list groups by date. Its empty state says "No chats yet · Ctrl+N starts one", and the chord appears only when a keyboard is attached. A one-line privacy note, "Chats are stored encrypted on this device", fits Skein's positioning (prompt §2 rule 8).

**C18. Tabs inside the chat surface are opt-in.**
- Evidence: session tabs in Continue's panel default to off (`config.ui?.showSessionTabs ?? false`, [UserSettingsSection.tsx#L50](https://github.com/continuedev/continue/blob/5522c6f44ca0ac3528b37244818fbfa39b5af470/gui/src/pages/config/sections/UserSettingsSection.tsx#L50)).
- Skein translation: this supports the spec's own call (§8.3) that tabs collapse to "Recent ▾" on the folded phone. On the outer screen the conversation list, not a tab strip, is the primary switcher. Tabs are an inner-screen power feature.

## Patterns Not Worth Adopting

- **Hover-only row actions and one-click delete.** History rows reveal Edit / Save-as-Markdown / Delete only on mouse hover, and per-row Delete runs immediately with no confirmation and no undo ([HistoryTableRow.tsx#L150-L186](https://github.com/continuedev/continue/blob/5522c6f44ca0ac3528b37244818fbfa39b5af470/gui/src/components/History/HistoryTableRow.tsx#L150-L186)). Touch has no hover, and prompt §29 requires deliberate delete semantics. See `LIBRECHAT.md` for the touch-correct version.
- **Plan/Agent modes, tool approval UI, apply/diff machinery, autocomplete, Next Edit, codebase indexing commands.** These are coding-agent features. Skein v1 has no model-initiated tools (spec §9). Copying the mode chip now would add a control with nothing behind it (prompt §2 rules 4 and 13).
- **One-key model cycling (`⌘'`,** [ModelSelect.tsx#L171-L204](https://github.com/continuedev/continue/blob/5522c6f44ca0ac3528b37244818fbfa39b5af470/gui/src/components/modelSelection/ModelSelect.tsx#L171-L204)). It is cheap for cloud models. On Skein a swap is unload → hash-verify → mmap → load, costing 2–5 s TTFT (spec §6), with one resident model. An accidental keypress would stall the next answer. Switching model must be an explicit pick.
- **Alphabetical model sort.** With two to five on-device models, "Recommended / Imported" sections (see `ZED.md`) beat A–Z.
- **Code-specific ranking heuristics** (common extensions, `src/` directories; [SubmenuContextProviders.tsx#L60-L112](https://github.com/continuedev/continue/blob/5522c6f44ca0ac3528b37244818fbfa39b5af470/gui/src/context/SubmenuContextProviders.tsx#L60-L112)). Keep the tier *structure*, drop the tier *content*.
- **Modal toasts for recoverable problems** (the "File exceeds model's context length" modal, [AtMentionDropdown/index.tsx#L247-L260](https://github.com/continuedev/continue/blob/5522c6f44ca0ac3528b37244818fbfa39b5af470/gui/src/components/mainInput/AtMentionDropdown/index.tsx#L247-L260)). On a phone a modal over the IME is hostile. Use an inline message on the chip or row.
- **Tooltips as the only explanation** (mode descriptions, shortcut hints, context percentage all live in hover tooltips). Touch needs visible secondary text or a long-press, and screen readers need content descriptions.
- **Two diverging command vocabularies.** The CLI exposes system actions as slash commands (`/rename`, `/fork`, `/compact`, `/resume`; [commands.ts#L25-L126](https://github.com/continuedev/continue/blob/5522c6f44ca0ac3528b37244818fbfa39b5af470/extensions/cli/src/commands/commands.ts#L25-L126)). The GUI exposes the same actions as buttons and uses `/` only for user prompts and "legacy" commands (`core/commands/slash/built-in-legacy/`). Skein should have **one registry** behind every surface.
- **Configuration profiles, Hub, org/assistant switchers, MCP** (`AssistantAndOrgListbox`, BlockSettingsTopToolbar). These are cloud/team concepts with no Skein analogue, and they are exactly the clutter prompt §1 warns about.
- **The stack itself** (TipTap/ProseMirror, a React webview, Redux, Tailwind). Nothing transfers to Compose.

## Potential Reusable Code

**None recommended for copying.** Candidates were considered and rejected as copy targets:

| Candidate | What it is | Why not copy | Cheaper path |
| --- | --- | --- | --- |
| `SubmenuContextProviders.tsx` ranking ([#L224-L314](https://github.com/continuedev/continue/blob/5522c6f44ca0ac3528b37244818fbfa39b5af470/gui/src/context/SubmenuContextProviders.tsx#L224-L314)) | ~90 lines of TS tiers | Half is code-file heuristics; MiniSearch is a JS dependency | Re-express the tier list in a pure Kotlin `rank()` with a unit test (spec below) |
| `History/index.tsx` 3-pass search | exact / fuzzy / prefix merge | Skein already has FTS5 (`VaultRepository.searchTitles/searchBodies`) | Keep FTS5 and add the tier merge in Kotlin |
| Tool phrase templates (`core/tools/definitions/*`) | data, not logic | Coding-tool wording | Write Skein's own tense triples for retrieval steps |
| `ContextStatus` thresholds | two constants (60 %, pruned) | Trivial | Constant in the strip's view-state mapper |

Migration cost if code were ever taken: TS→Kotlin transliteration, plus Apache-2.0 §4(b–d) notice and attribution in `NOTICE`, costs more than writing the Compose version cleanly. It also adds no value, because Continue is frozen and will never ship a fix to re-sync against.

## Android/Fold Relevance

| Continue pattern | Fold outer screen (compact, touch, IME) | Unfolded Fold with physical keyboard | Do not copy |
| --- | --- | --- | --- |
| C1 composer + gated chips | Attach · message · send/stop; chips collapse to icons | Same, plus a visible model chip and shortcut hints in tooltips | Toolbar crowding: at most 3 persistent controls |
| C2 placeholder teaches `[[` | Yes | Yes | Long onboarding copy in the field |
| C3/C4 sigil picker | `[[` or 📎 → "Add note" opens the picker as a **bottom sheet** above the IME | Anchored popup under the caret; ↑↓ ⏎ Tab Esc | Tiny tippy popovers |
| C5 ranking | Same ranking; the sheet shows 5–6 rows | Same; popup shows up to ~10 | Code heuristics |
| C7 "Used 3 notes ▸" | Inline disclosure under the answer; a tap opens the **Inspector sheet** | The Inspector as a supporting pane (prompt §35) | Scores in the default view |
| C8 context threshold | Strip message at ~60 % | Same, plus a percentage in the Inspector | Always-on meters |
| C9 status strip | One row above the composer | Same, with chord labels (Esc, Ctrl+Enter) | Stacked banners |
| C11 model picker | Chip → **modal bottom sheet** with sections | Chip → popover; Ctrl+Shift+M | One-key cycling |
| C12 inline edit strip | "Editing …  ← Back" row; Keep/Discard buttons | Same; Esc = Discard | A separate edit dialog |
| C14 `⌘L` semantics | n/a (no keyboard) | **Ctrl+L**: focus composer, attaching the focused note or selection | Meta-key chords (system-reserved on Android, see `ZED.md`) |
| C17 history | Date-grouped list in the drawer or route | List pane in the Navigate column (prompt §21) | Hover-only row actions |

Fold transition note (prompt §24): the composer text, any open picker query, and the status-strip state must survive fold/unfold. They belong in `rememberSaveable` or ViewModel state, not in popup-local state. Continue's popups are tippy instances that die with layout, which is exactly the failure mode to avoid.

## Mac UX-Lab Relevance

- **Layer A (Compose previews).** C9 gives a finite list of chat states, so it maps directly to the preview matrix prompt §37 already asks for: empty, `LoadingModel`, `Generating`, `ReviewInlineEdit`, `ContextNearlyFull`, `Error`, plus "Used 3 notes ▸" collapsed and expanded. Each is a fixture-driven `@Preview` with no inference pipeline.
- **Layer B (device sizes).** C1's width-collapse rules should be previewed at the widths Skein's previews already use (400 dp folded, 1000 dp unfolded; `NavPreviews.kt`), plus 600 and 840 dp, with long model names ("Qwen 2.5 3B Instruct Abliterated Q4_K_M").
- **Layer C (localhost prototype): not warranted for Continue.** Its GUI cannot run standalone. `MockIdeMessenger` exists only for tests (`gui/src/context/MockIdeMessenger.ts`), and `npm run dev` expects an IDE host. For a live reference on the MacBook, install the final VS Code extension 2.x, which ships without telemetry per the README. Keep it off the device, because it is a reference and not a dependency.
- **Keyboard behaviour** is best checked with JVM unit tests of pure decision functions (see the palette design) plus Robolectric key-event tests, which run on the Mac without an emulator.

## Recommended Action

**STUDY ONLY.** The repository is frozen, the stack is web/TS, and nothing is worth copying. Its patterns (C1–C9, C11–C17) feed a native Compose **prototype** in Wave 10, specified below under "Proposed Skein command palette & shortcuts". C10 (modes) is parked until Skein has model-initiated tools.

## Expected Benefit

- The raw `/` parser is replaced by a palette users can discover, search, and drive by touch or keyboard (prompt §42). The dead `/` seam in the chat composer is fixed.
- A single status strip gives every chat state one next action (prompt §2 rule 6), replacing ad-hoc spinners and banners.
- Context becomes legible to Level-1 users ("Used 3 notes ▸") while scores and recall sources stay reachable for Level-2 users (prompt §35).
- The Fold with a keyboard gains a coherent, discoverable chord set built from the same registry, so shortcuts cannot drift from the buttons (prompt §41).
- Continue patterns proved in a narrow side panel transfer to the outer screen with little adaptation.

## Expected Cost

- **Registry evolution** (id, title, description, icon, group, shortcut, availability; see below): about 1–2 days, including migrating the four existing commands and their tests (`CommandBarStateTest`, `CommandBarLayoutTest`).
- **Palette UI** (full-screen route on compact, dialog on expanded, argument stage): about 3–4 days, plus Roborazzi baselines for outer, inner, keyboard on and keyboard off.
- **Keymap resolver, root key dispatch and `onProvideKeyboardShortcuts`**: about 2 days with unit tests.
- **Status strip, context peek and product-language labels**: about 2–3 days in `:feature:chat`, mostly presentation mapping. No RAG or inference changes are needed (prompt preamble: don't refactor RAG internals).
- **Risk:** the palette accumulates entries that don't work yet. Mitigation: the availability predicate plus the rule that a command is registered only when its action works end to end.

---

## Answers to the prompt's specific questions

**Chat vs agent modes.** Continue distinguishes modes by *tool permission*, not by UI surface. Chat means no tools, Plan means read-only tools, Agent means all tools. One chip carries a description per mode, a warning when the chosen model isn't suited, and a `⌘.` cycle that keeps focus in the composer (C10). Skein v1 should ship **without** a mode control because it has no model-initiated tools (spec §9). The vocabulary and chip are the template if Skills or tools arrive (prompt §3 "Use Skill").

**Context selection (@-mentions and providers), and how active context is shown.**
- Selection happens inside the composer via `@`. The toolbar `@` button merely types `@` (C3).
- Providers are "insert", "drill-down submenu" or "inline query" (C4). The list is ranked in tiers with open items first (C5), and oversized items are refused with a reason before send (C6).
- Active context appears three ways:
  1. **Inline pills** in the composer.
  2. **"N context items"** collapsed per message, reading "Gathering context…" while live (C7).
  3. A **context meter** that stays hidden until 60 % full and then offers "Compact" or "New session" (C8).

For Skein this becomes: `[[` pills, "Used 3 notes ▸" per answer, and a strip hint when the chat grows long. Scores and recall sources move into the Inspector.

**Repository and project awareness.** Continue gets project awareness from the IDE: workspace directories, open files and the codebase index, all fed to `@` providers. The UI never shows a "project" object. Recent open files lead the mention list (C5), and history rows show the workspace folder name (`HistoryTableRow.tsx#L132-L135`). For Skein, "Open Project / Repository" (prompt §3) should follow suit: a repository is a **context source inside Knowledge** that appears as a category in the `[[`/`@` picker and as a filter in Knowledge, not as a new top-level destination. That matches prompt §20's "Repositories → Knowledge".

**Model selection UI.** A quiet text trigger in the composer toolbar leads to a list where unusable models stay visible, disabled with their reason, plus a settings gear, "Add model", and a footer that teaches the shortcut. Models are chosen per role, and the picker switches role in edit mode (C11). Skein keeps the chip and the honest disabled rows, but drops one-key cycling because of the swap cost.

**Slash and command workflows.** In the GUI, `/` inserts user prompts and rules as a prompt block, and bookmarked slash commands become empty-state starter cards (up to 5, with "Show N more…"; [ConversationStarterCards.tsx#L8-L63](https://github.com/continuedev/continue/blob/5522c6f44ca0ac3528b37244818fbfa39b5af470/gui/src/components/ConversationStarters/ConversationStarterCards.tsx#L8-L63)). In the CLI, `/` covers everything, including system actions (C16). Skein should keep one registry and expose it three ways:
1. The palette (Ctrl+K or the top bar).
2. `/` typed first in the composer, which opens the same list filtered to chat-scoped commands.
3. Raw `/keyword arg` typing for power users, preserving `CommandRegistry.match`'s argument parsing.

Persona starter prompts can use Continue's starter-card idea on the empty chat.

**Keyboard-first interaction.** The chords are context-aware (C14):
- `⌘L` focuses the chat, attaches the selection and starts fresh; a second press toggles.
- `⌘⇧L` adds to the current chat.
- Enter sends and Shift+Enter inserts a newline.
- `⌘⌫` cancels, guarded so it can't delete text.
- `↑` recalls the previous prompt.
- Esc follows a precedence ladder.
- Shortcut hints sit in the controls themselves ("Stop ⌘⌫", "⌘. for next mode", "⌘' to toggle model").

The Skein mapping (Ctrl-based, no Meta) is in the design below.

**CLI and GUI coexistence.** Both are front-ends over one `core` (config, providers, tools), but their *command vocabularies diverged*: slash commands in the CLI, buttons in the GUI. Skein's analogue is the raw `/command` syntax versus the touch palette. The lesson is to make them two syntaxes over **one** `CommandRegistry`, so `/new note Foo` typed in the bar and "New note" tapped in the palette are the same object, with the same availability and tests.

**Contextual commands.** Continue whitelists only 6 of 37 commands into the host palette. Selection actions live in the editor context menu, file actions in the explorer menu, and state-specific actions (Accept/Reject/Stop) appear in the status strip only while relevant (C9, C15). Skein does the same: availability predicates hide what doesn't apply, and the palette's empty state shows "Suggested here" for the focused surface.

**Edit workflows.** The same composer is re-targeted ("Editing: file (range)", "← Back to Chat") and ends in an "N diffs · Accept/Reject" strip, with Esc to exit (C12). Skein's inline AI in the note editor should use this re-targeting pattern with Keep/Discard, not a separate dialog.

**How advanced capability avoids clutter.** Continue uses six mechanisms, all transferable:
1. Capability gating (a control exists only if it works for this model; C1).
2. Thresholds (the context meter appears at 60 %; C8).
3. Collapsed peeks ("N context items", "Thought for 4.2s"; C7, C13).
4. One state strip owning the next action (C9).
5. Sigils instead of buttons (C3).
6. Opt-in density (session tabs off by default; C18).

**Key question: how can Skein have a PocketPal-simple front door with Continue-like workspace depth?** Make depth *enter through the same three things the simple user already sees*: the message box, the answer, and one top bar. Add nothing else to the default screen.

- **The message box.** Level 1 is type, send, stop. Level 2 is typing `[[` or `/` in the same box (taught by the placeholder, C2), or tapping 📎 → "Add note" (C3). There is no mode picker, no persona picker, and no knowledge panel in the default view.
- **The answer.** Level 1 is text. Level 2 is "Used 3 notes ▸" and "Thought for …" disclosures that expand in place (C7, C13), leading on to the Inspector (sheet on the outer screen, pane on the inner).
- **The top bar.** Level 1 is ≡ and a model/persona chip with a human name. Level 2 is the command palette behind the same bar or Ctrl+K, which reaches every destination and action with ranking and shortcuts (design below).
- **The status strip** (C9) surfaces power only when a state demands it: Stop, Keep/Discard, "chat is getting long".
- **Capability gating** (C1) plus availability predicates keep every visible control working.

Prompt §3's progression "Ask → Attach Knowledge → Inspect Context → Use Skill → Explore Graph → Open Project" then corresponds to "type → `[[` → tap 'Used 3 notes' → `/skill` → palette 'Open graph around this note' → Knowledge › Repositories". Every step is discovered from the previous one, and none is dumped onto the default screen.

**Continue must not become a dependency.** Confirmed. It is unmaintained and read-only, and this study copies no code.

---

## Proposed Skein command palette & shortcuts

This design is referenced from [`ZED.md`](ZED.md) and [`LIBRECHAT.md`](LIBRECHAT.md). Sources are tagged **[C]** Continue, **[Z]** Zed, **[L]** LibreChat. It is a proposal for Wave 10 and changes no code in this bead.

### 1. What changes relative to today

| Today (`009cbb6`) | Proposed |
| --- | --- |
| `Command(keyword, hint, run)` with prefix filter ([`CommandRegistry.kt`](../../../feature/shell/src/main/kotlin/app/skein/feature/shell/nav/CommandRegistry.kt)) | `SkeinCommand` with id, title, description, icon, group, slash alias, default chord, availability predicate, optional argument stage (§2) |
| Rows print `/new note [title] — create a note and open it pinned` | Rows show icon · **title** (matched characters highlighted [Z]) · one-line description · chord (keyboard only) · `…` if it asks for more input [Z] |
| Tap fills the field; Enter runs | Tap or Enter **runs**; argument commands open a second stage in the same surface [C4] |
| 4 commands, 1 scope in use (`GLOBAL`; `EDITOR` reserved for E7.I6) | ~30 commands in 6 groups with availability predicates; `CommandScope.EDITOR` kept as-is and used for the note-focused boost |
| Composer `/` fires an unwired callback | Composer `/` (first character only [L]) opens the same palette filtered to chat-scoped commands, anchored above the composer |
| No global keys | Root-level key dispatch through a pure `resolveChord()` plus `onProvideKeyboardShortcuts` for the system Shortcuts Helper |

### 2. Command model (registry evolution, not a rewrite)

```kotlin
// Proposal only. Extends today's Command(keyword, hint, run); CommandScope stays.
data class SkeinCommand(
    val id: String,                    // "chat.new": stable id, used for usage history and keymap
    val title: String,                 // "New chat" (may be contextual, see "Ask Skein")
    val description: String?,          // "Start a blank conversation": product language only
    val group: CommandGroup,           // CHAT, KNOWLEDGE, NAVIGATE, MODEL, VIEW, APP
    val icon: ImageVector,
    val slash: String?,                // "chat": keeps `/chat` and `/new note Title` working
    val keywords: List<String> = emptyList(), // extra match terms: "conversation", "thread"
    val chord: KeyChord? = null,       // default binding; null = palette-only
    val firesWhileTyping: Boolean = false,    // [L] EDITING_ALLOWED_SHORTCUTS
    val argument: ArgumentStage? = null,      // Text(prefill) | Pick(source) | Confirm(copy) [C4]
    val isAvailable: (CommandContext) -> Boolean = { true }, // [Z] available_actions + filter
    val run: suspend (CommandContext, arg: String) -> Unit,
)

data class CommandContext(
    val surface: Surface,              // CHAT, NOTE, KNOWLEDGE_LIST, GRAPH, MODELS, SETTINGS
    val focusedDocId: DocId?, val hasSelection: Boolean,
    val isGenerating: Boolean, val modelState: ModelState,  // NONE | LOADING | READY | ERROR
    val hasRetrievedContext: Boolean, val openTabCount: Int,
    val windowClass: WindowClass,      // COMPACT | MEDIUM | EXPANDED (PaneLayout's tiers)
    val hardwareKeyboard: Boolean,     // Configuration.hardKeyboardHidden == HARDKEYBOARDHIDDEN_NO
)
```

Rules:

1. **Unavailable means hidden in the palette** [Z]. Its chord does nothing and **does not consume the key event** [L] (`run` returning `false` → event not prevented; [useKeyboardShortcuts.ts#L306-L307](https://github.com/LibreChat-AI/LibreChat/blob/7b2362d7a7c6148b84850924dc7fa5fc43307923/client/src/hooks/useKeyboardShortcuts.ts#L306-L307)).
2. **A command is registered only once its action works end to end** (prompt §2 rules 4 and 13). Rows marked *later* in §6 stay unregistered until then.
3. **Buttons, palette rows and chords dispatch the same `SkeinCommand`.** LibreChat gets this by having shortcuts click the visible control "so the shortcut can never diverge from the button's semantics" ([#L616-L619](https://github.com/LibreChat-AI/LibreChat/blob/7b2362d7a7c6148b84850924dc7fa5fc43307923/client/src/hooks/useKeyboardShortcuts.ts#L616-L619)). Skein gets it structurally.

### 3. Palette anatomy by posture

| | Fold outer screen / any `COMPACT` window | Unfolded Fold (`MEDIUM`/`EXPANDED`) |
| --- | --- | --- |
| Trigger | Tap the top bar's search field; `/` as first character in the composer | Same, plus **Ctrl+K** (alias Ctrl+Shift+P) |
| Container | **Full-screen route** with the field at the top and the IME up. A sheet would leave about 3 visible rows under the IME. Back or swipe-down closes it. | **Centered modal** about 560–640 dp wide (Zed's palette is 38 rem, [command_palette.rs#L147-L150](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/crates/command_palette/src/command_palette.rs#L147-L150)), top-anchored, with a scrim; outside tap or Esc closes it |
| Row | ≥ 56 dp; icon · title · description (commands) or kind + "edited 2 h ago" (documents); **no chords** | 40–48 dp; icon · title · description · **chord right-aligned** [Z] · `…` for argument commands |
| Footer | none | "↑↓ move · ⏎ run · Tab complete · Esc close" [C16] |
| Focus | IME stays up; the selected row is not highlighted until arrow keys are used | Focus returns to the previously focused element on close [Z `previous_focus_handle`]; the command runs *after* focus is restored, so it acts on the right pane [Z] |
| Fold/unfold while open | Query, stage and selection survive (`rememberSaveable`); the container swaps route↔dialog (prompt §24 Test D) | |

**Argument stage** [C4]. Commands whose title ends in `…` push a second stage into the same container, with a breadcrumb ("Switch model ›") and Back/Esc to stage 1:

- **`Pick`**:
  - *Switch model…* lists "Recommended" / "Imported" sections, with non-selectable headers and selection starting on the active model [Z, see `ZED.md`].
  - *Switch persona…*, *Go to…*.
- **`Text`**: *Rename chat…* shows a prefilled field. Enter commits, Esc cancels, with the 100-character cap [L].
- **`Confirm`**: *Delete chat…* reads "Delete chat? This will delete **Weekly plan**" with a destructive button [L]. It is never instant from the palette.

### 4. Empty-query content (the palette never opens blank)

| Section | Contents | Source |
| --- | --- | --- |
| **Suggested here** (≤ 4) | Available commands whose scope matches the focused surface. In a chat while generating: *Stop generating*. In a chat with sources: *Show sources*. In a note: *Ask about this note*, *Show backlinks*. | [C9] state-driven, [Z] context-scoped actions |
| **Recent** (≤ 5) | Commands previously run **from the palette**, most recent first, frequency as tiebreak. Each row has "Remove from recent". | [Z] `CommandUsage{last_invoked, invocations}`, palette-only counting ([command_palette.rs#L390-L414](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/crates/command_palette/src/command_palette.rs#L390-L414)) |
| **Jump to** (≤ 6) | Open tabs, then the last 4 opened notes and 2 chats, excluding the current one | [Z] agent `@` recents (4 files + 2 threads, [completion_provider.rs#L1300-L1354](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/crates/agent_ui/src/completion_provider.rs#L1300-L1354)); [C5] open items first |

On the outer screen, show *Suggested here* and *Jump to* (3 each) so the empty state fits above the IME.

**Privacy adaptation.** Zed also stores the *typed query* for ↑-recall ([persistence.rs#L148-L155](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/crates/command_palette/src/persistence.rs#L148-L155)). Skein persists **command ids and timestamps only**, capped at about 1,000 rows as Zed does ([persistence.rs#L134](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/crates/command_palette/src/persistence.rs#L134)). Query text (which can contain note content or search terms) stays in memory for the session and is wiped on vault lock, in line with spec §9's "no plaintext leakage" stance.

### 5. Ranking

Candidates are the available commands plus documents from `VaultRepository.searchTitles` and `searchBodies`, both already debounced at 150 ms in `CommandBarState`. They are ranked by a pure function `rank(query, candidates, usage, context): List<Row>`:

1. **Hard filter**: `isAvailable(context)` [Z].
2. **`/`-prefixed query** means commands only, matched against `slash` and title. `CommandRegistry.match` argument parsing is preserved (`/new note Weekly plan`).
3. **Tier score for commands, highest first:**
   1. exact title, slash or keyword [C5]
   2. title prefix
   3. word-start ("sw mod" → Switch model)
   4. initials ("nn" → New note [C5])
   5. substring [C16]
   6. fuzzy subsequence with length penalty (Zed's matcher runs `LengthPenalty::On`, [command_palette.rs#L618-L627](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/crates/command_palette/src/command_palette.rs#L618-L627))
4. **Boosts inside a tier:**
   - previously used (recency, then count) goes above unused, keeping fuzzy order among the unused (Zed hoists used commands, [#L629-L639](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/crates/command_palette/src/command_palette.rs#L629-L639))
   - scope matches the focused surface (`CommandScope.EDITOR` when a note is focused)
5. **Documents:** open tab, then recently opened, then exact title, then title prefix, then word-start, then fuzzy title, then body hit. The body-after-title order is today's `CommandBarState.performSearch`.
6. **Interleaving:** a "Commands" section (≤ 5) above "Notes & chats". A document outranks commands only on an exact title hit.
7. **Fallback rows**, always last so the palette never dead-ends:
   - "Search Knowledge for "q"" opens the full results route [L `/search`].
   - "Ask Skein "q"" starts a new chat with *q* as the first message. This is the PocketPal-simple path: type a question in the top bar, then Enter.
8. **Stable tiebreak:** alphabetical by title.

The **Enter** default is the first row. **↑ at the top row** recalls earlier queries from this session only [Z `QueryHistory`, [command_palette.rs#L527-L561](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/crates/command_palette/src/command_palette.rs#L527-L561)].

### 6. Command catalogue

Status key:
- **exists**: backed by a code path present at `009cbb6`; it only needs registering or presenting.
- **lifecycle**: depends on the chat and note lifecycle work in prompt §§28–31.
- **later**: new feature; not registered until it works.

| # | Group | Title (palette row) | Description | Slash | Default chord | Available when | Status | Source |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | Chat | **New chat** | Start a blank conversation | `/chat` (existing), `/new chat` | **Ctrl+N** ✎ | vault unlocked | exists (`chatCommand`) | [L] newChat, [Z] cmd-n |
| 2 | Chat | **Ask Skein** / *Ask about "‹note›"* / *Ask about selection* (contextual title) | Put the cursor in the message box; attaches the focused note or selection | `/ask` | **Ctrl+L** ✎ | always (attachment only when a note or selection is focused) | exists (focus); attaching needs `[[` insertion | [C] ⌘L, [Z] `cmd->` AddSelectionToThread |
| 3 | Chat | **Stop generating** | Stop the current answer | `/stop` | **Esc** (focus in that chat) | generating | exists (`onCancel`) | [C] ⌘⌫, [Z] Esc→Cancel, [L] stopGenerating |
| 4 | Chat | **Attach file…** | Add a file to this message | `/attach` | **Ctrl+Shift+A** ✎ | chat focused | exists (SAF `attachLauncher`) | [L] uploadFile |
| 5 | Chat | **Add note to chat…** | Pick notes to use as context | `/add` | none (type `[[`) | chat focused | exists (`[[` autocomplete) | [C3], [Z] ctrl-; add context |
| 6 | Chat | **Show sources** | See what this answer was based on | `/sources` | **Ctrl+Shift+I** | chat has an answer with retrieved context | exists (`ContextPanel` toggle) | [C7], prompt §35 |
| 7 | Chat | **Rename chat…** | Give this chat a name | `/rename` | none | a saved chat is open | lifecycle | [C] CLI `/rename`, [L] Rename |
| 8 | Chat | **Delete chat…** | Asks before deleting | `/delete` | **Ctrl+Shift+Backspace** (not while typing) | a saved chat is open | lifecycle | [L] deleteConversation → confirm dialog |
| 9 | Chat | **Copy last answer** | Copy the latest answer as Markdown | `/copy` | none | chat has an answer | later | [L] copyLastResponse |
| 10 | Chat | **Regenerate answer** | Ask again with the same message | `/retry` | none | last turn is an answer and the model is ready | later | [L] regenerate + sibling `2/3` |
| 11 | Knowledge | **New note** | Create a note (optional title) | `/new note [title]` (existing) | **Ctrl+Shift+N** ✎ | vault unlocked | exists (`newNoteCommand`) | [Z] cmd-n family |
| 12 | Knowledge | **Go to…** | Open a note or chat by title | `/go` | **Ctrl+P** ✎ | always | exists (title search) | [Z] cmd-p file finder |
| 13 | Knowledge | **Search Knowledge** | Find text across notes and chats | `/search [text]` | **Ctrl+Shift+F** ✎ | always | exists (`searchBodies`); needs a results route | [Z] cmd-shift-f, [L] `/search` |
| 14 | Knowledge | **Import file…** | Add a PDF, text or image file to Knowledge | `/import` | none | vault unlocked | exists (`ImportService`) | [L] attach menu |
| 15 | Knowledge | **Show backlinks** | Notes that link here | `/backlinks` | none | note focused | verify (spec §8.5 drawer) | [C15] contextual |
| 16 | Knowledge | **Open graph around this note** | See how this note connects | `/graph` | none | note focused (otherwise the title is "Open Graph") | exists (graph destination) | spec §8.6 |
| 17 | Navigate | **Chat · Knowledge · Graph · Models · Settings** | Go to a section | — | **Ctrl+1 … Ctrl+5** ✎ (drawer order) | always | exists (drawer destinations; labels follow the final IA) | [Z] cmd-1…9 |
| 18 | Navigate | **Switch tab** | Recently used tabs first | — | **Ctrl+Tab / Ctrl+Shift+Tab** ✎ (release Ctrl to open) | ≥ 2 tabs, `MEDIUM`+ | tabs exist (`TabsState.activate`); recently-used order needs an activation history, which `TabsState` doesn't keep today | [Z] tab_switcher MRU |
| 19 | Navigate | **Close tab** | Close the current tab | `/close` | **Ctrl+W** ✎ | a tab is open | exists (tabs; editor flush via `FlushRegistry`) | [Z] cmd-w |
| 20 | View | **Show/hide list** | Show or hide the conversations and notes list | — | **Ctrl+0** ✎ (1st press shows and focuses the list; 2nd returns focus) | `MEDIUM`+ (compact: opens the drawer) | exists (timeline collapse / `NavDrawer`) | [Z] `toggle_panel_focus`, VS Code Ctrl+0 |
| 21 | View | **Split view** | Show two tabs side by side | `/split` | **Ctrl+\\** | `EXPANDED`, ≥ 2 tabs | exists (`SplitHost`) | spec §8.3 |
| 22 | Model | **Switch model…** | Choose which on-device model answers | `/model` (keeps `/models`) | **Ctrl+Shift+M** | ≥ 1 model imported | exists (models overlay) | [L] openModelSelector, [Z] model selector |
| 23 | Model | **Import model…** | Add a GGUF model file from storage | `/import model` (existing) | none | always; replaces #22 when no model exists | exists | — |
| 24 | Model | **Switch persona…** | Change who Skein answers as | `/persona` | none | ≥ 1 persona | exists (personas) | [L] model specs ≈ personas |
| 25 | Model | **Unload model** | Free memory now | `/unload` | none | model loaded and idle | later | spec §9 idle-unload |
| 26 | App | **Command palette** | Search or run a command | — | **Ctrl+K** ✎ (alias Ctrl+Shift+P) | always | new | [Z] cmd-shift-p |
| 27 | App | **Settings** | — | `/settings` | **Ctrl+,** ✎ | always | exists | [Z] cmd-, |
| 28 | App | **Keyboard shortcuts** | Show all shortcuts | `/keys` | **Ctrl+/** ✎ | hardware keyboard | new (`requestShowKeyboardShortcuts()`) | [L] showShortcuts, Android helper |
| 29 | App | **Lock Skein** | Lock the vault now | `/lock` | none | unlocked | verify | spec §9 lock policy |

✎ = fires while a text field has focus [L] ([useKeyboardShortcuts.ts#L295-L302](https://github.com/LibreChat-AI/LibreChat/blob/7b2362d7a7c6148b84850924dc7fa5fc43307923/client/src/hooks/useKeyboardShortcuts.ts#L295-L302)). Chords without ✎ are ignored while typing, so text editing keeps its keys. Destructive #8 is deliberately *not* ✎.

### 7. Keyboard map for the unfolded Fold with a physical keyboard

The brief's required actions:

| Action (prompt §41) | Chord | Notes |
| --- | --- | --- |
| Command palette | **Ctrl+K** (alias **Ctrl+Shift+P**) | Toggle: pressing it again closes [Z `CommandPalette::toggle`, [#L88-L111](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/crates/command_palette/src/command_palette.rs#L88-L111)] |
| New chat | **Ctrl+N** | New note is **Ctrl+Shift+N**. Fixed meanings, not focus-dependent (see `ZED.md` for why not Zed's context-dependent `cmd-n`) |
| Search | **Ctrl+Shift+F** (Knowledge full-text), **Ctrl+P** (go to by title) | **Ctrl+F** is reserved for find-in-current-chat/note and bound only when that exists |
| Close pane / tab | **Ctrl+W** | Closes the focused tab. In split view with no tabs left, collapses the split. Flushes the editor first (`FlushRegistry`) |
| Send message | **Enter** (when "Enter sends", as today), **Ctrl+Enter** always sends | Shift+Enter inserts a newline. Enter never sends while an autocomplete, `[[` or `/` popup is open [C], or while the IME is composing [L `isComposing`] |
| Cancel generation | **Esc** with focus in the generating chat | Esc ladder in §8. The palette row "Stop generating" works from anywhere |
| Focus composer | **Ctrl+L** | Context-aware "Ask Skein" [C ⌘L]. A second press returns focus to where it came from [Z] |
| Open Knowledge | **Ctrl+2** | Ctrl+1…5 follow drawer order (Chat, Knowledge, Graph, Models, Settings under the IA proposed in prompt §20). The drawer shows the chord beside each entry when a keyboard is attached |

The rest of the default set: Ctrl+Shift+N new note · Ctrl+Shift+A attach · Ctrl+Shift+I sources · Ctrl+Shift+M model · Ctrl+Tab/Ctrl+Shift+Tab tabs · Ctrl+0 list · Ctrl+\\ split · Ctrl+, settings · Ctrl+/ shortcuts · Ctrl+Shift+Backspace delete chat (with confirmation) · ↑ in an empty composer recalls the last sent prompt [C].

**Android constraints.** These decide the chord choices; evidence is in `ZED.md` §Android/Fold.

- **Never bind Meta (Search/⌘/Win).** Android reserves Meta chords system-wide: Meta+Tab recents, Meta+H/Enter home, Meta+Backspace/Left back, Meta+/ shortcuts helper, Meta+N notifications, Meta+L lock, Meta+Ctrl+arrows split-screen, Meta+letter app launches. All Skein chords use **Ctrl**. This is the Android counterpart of Continue's JetBrains fallback when the host owns `⌘⌫` ([StreamingToolbar.tsx#L23-L26](https://github.com/continuedev/continue/blob/5522c6f44ca0ac3528b37244818fbfa39b5af470/gui/src/components/mainInput/Lump/LumpToolbar/StreamingToolbar.tsx#L23-L26)).
- **Avoid these system-taken chords:**
  - **Alt+Tab** / Alt+Shift+Tab (app switch)
  - **Ctrl+Space** / Ctrl+Shift+Space (input language)
  - **Alt+Esc** and **Ctrl+Esc**, which fall back to HOME and MENU, and **Ctrl+Alt+Del** (BACK), per AOSP `Generic.kcm`
- **Do not override text-field chords inside text fields.** Compose already handles Ctrl+A/C/X/V/Z/Shift+Z, Ctrl/Shift+arrows and PageUp/PageDown (Android "Keyboard shortcuts" docs).
- **Reserve Ctrl+B, Ctrl+I, Ctrl+U** for future note-editor formatting. They are never global. This is why the list toggle is Ctrl+0, not Zed/VS Code's Ctrl+B.
- **Every global chord includes Ctrl** [L `isValidBinding` requires a modifier, [shortcuts.ts#L194-L212](https://github.com/LibreChat-AI/LibreChat/blob/7b2362d7a7c6148b84850924dc7fa5fc43307923/client/src/utils/shortcuts.ts#L194-L212)]. Esc, Enter and ↑ are surface-local only.
- **Ignore auto-repeat** for commands (`KeyEvent.repeatCount > 0`) [L `e.repeat`].
- **Suppress global chords** while a modal dialog or menu is open, except the palette toggle inside the palette [L [#L1072-L1078](https://github.com/LibreChat-AI/LibreChat/blob/7b2362d7a7c6148b84850924dc7fa5fc43307923/client/src/hooks/useKeyboardShortcuts.ts#L1072-L1078)].

### 8. Esc and focus rules

Esc resolves to the **first** applicable rung and consumes the event. It combines Continue CLI's ladder [C14] with Zed's context-scoped `editor::Cancel` [Z].

1. Close the topmost transient: palette stage 2 → stage 1 → close; `[[`/`/` popup; menu; bottom sheet.
2. Stop generation, **only if focus is inside the generating chat's pane.** In split view, Esc in the note editor never stops the other pane's chat. Use the palette row instead.
3. Discard a pending inline-edit proposal (strip state `ReviewInlineEdit`) [C12].
4. Leave the text field (clear focus). Never navigate or close anything.

Esc is **never** mapped to Back navigation. `Generic.kcm` gives ESCAPE no base fallback, and Back remains the system gesture/key.

Focus rules [Z `toggle_dock` / `toggle_panel_focus`, [workspace.rs#L4518-L4582](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/crates/workspace/src/workspace.rs#L4518-L4582), [#L4689-L4711](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/crates/workspace/src/workspace.rs#L4689-L4711)]:
- Opening a pane or sheet moves focus into it.
- Closing it returns focus to the pane that had it.
- A second press of a "focus X" chord returns focus to where it came from.
- Palette confirm restores focus *before* running the command.

### 9. Composer sigils

| Typed as the first character | Opens | Notes |
| --- | --- | --- |
| `/` | The palette in "chat" scope, anchored above the composer: a sheet on compact, a popup on expanded | Wires today's `onSlashCommand` seam. Once a slash is followed by a space or text, it is plain text again [L `shouldTriggerCommand` ≤ 5 chars, [useHandleKeyUp.ts#L34-L49](https://github.com/LibreChat-AI/LibreChat/blob/7b2362d7a7c6148b84850924dc7fa5fc43307923/client/src/hooks/Input/useHandleKeyUp.ts#L34-L49)] |
| `[[` (anywhere) | Wikilink/Knowledge picker (existing) | Gains categories and a "Search Knowledge for…" query row [C4] |

Do **not** add LibreChat's `@` `+` `$` sigil zoo (see `LIBRECHAT.md`). Two sigils are enough, and both already exist in Skein's spec.

### 10. Discoverability without clutter

- **Show chords only when a keyboard is attached** (`hardKeyboardHidden == NO`). The outer screen, touch-only, never shows them.
- **Chords appear in:**
  - palette rows [Z `KeyBinding::for_action_in`]
  - tooltips and long-press labels of the matching buttons [L `useShortcutHint` → "Select a model (Ctrl+Shift+M)"]
  - drawer entries (Ctrl+1…5)
  - the status strip ("Stop · Esc") [C9]
- **Accessibility:** Compose `contentDescription`s mention the chord, the ARIA `aria-keyshortcuts` analogue [L].
- **Implement `onProvideKeyboardShortcuts()`** from the registry, grouped like the palette, so **Meta+/** (Android's system Keyboard Shortcuts Helper, API 24+) lists Skein's chords. Ctrl+/ calls `requestShowKeyboardShortcuts()`. There is no custom shortcuts screen to maintain.
- Empty states teach one chord ("No chats yet · Ctrl+N") [C17].

### 11. Verification (for the Wave 10 bead)

- **Pure JVM unit tests**, no Android framework:
  - `rank()`: "nn" → New note; "/new" → New chat and New note; recency hoist; availability filter; body-after-title.
  - `resolveChord(chord, context, isTyping)`: ✎ vs non-✎; unavailable → not consumed; repeat ignored.
  - `resolveComposerKey(...)`: the Enter/Shift/Ctrl/IME-composing/generating decision table. This pattern is taken from LibreChat's `resolveComposerKeyDown` ([shortcuts.ts#L256-L313](https://github.com/LibreChat-AI/LibreChat/blob/7b2362d7a7c6148b84850924dc7fa5fc43307923/client/src/utils/shortcuts.ts#L256-L313)); reimplement it, don't port.
- **Robolectric Compose tests:**
  - open/close restores focus
  - Esc ladder order
  - composer `/` opens the palette
  - fold↔unfold keeps the query (width change 400 → 1000 dp)
- **Roborazzi baselines:** palette empty and with results at 400 dp (IME up) and 1000 dp (keyboard attached with chords, and without); argument stages (model pick, rename, delete confirm).

### 12. Deliberately out of scope for v1

- User-remappable shortcuts (LibreChat's resolver and recorder are about 1,100 lines).
- Which-key chord menus and multi-stroke chords [Z].
- Command aliases [Z `command_aliases`].
- A mode chip [C10].
- Model cycling.
- Side-by-side multi-model compare [L].

Each can be revisited once v1's palette usage data (local only) shows demand.

---

**What specific problem in Skein can this project help us solve?** Continue shows how to turn Skein's raw `/command` parser, its unwired composer `/`, and its score-and-recall-source context panel into a PocketPal-simple chat that reveals depth only on demand. Depth arrives through the same composer (sigils), the same answer (collapsed "Used 3 notes" peeks), one state-driven status strip, and a ranked, keyboard-and-touch command palette backed by a single command registry.
