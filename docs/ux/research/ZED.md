# Zed: reference study for Skein

**Bead:** `skein-xtov.5` (epic `skein-xtov`)
**Authority:** `docs/research/SKEIN_UI_UX_OVERHAUL_PROMPT.md` §§1–3, 17, 21, 35, 41–42, 52–53
**Companions:** [`CONTINUE.md`](CONTINUE.md), which holds **Proposed Skein command palette & shortcuts**, the design this study feeds; and [`LIBRECHAT.md`](LIBRECHAT.md).
**Status:** Research record, advisory only. No code changes. **No Zed source may be copied into Skein** (see License).

---

## Project

<https://github.com/zed-industries/zed> is a native, GPU-rendered code editor written in Rust on its own UI framework, GPUI. The prompt uses it (§17, §53) as the reference for "high-capability workspace ergonomics": command palette, panes, keyboard UX, and density without chaos.

| Field | Value |
| --- | --- |
| Commit inspected | `933d8d93819c749a607e561883855a9b95c79cea`, the default-branch head, committed 2026-09-25 20:05 UTC (`language_model: Split request methods into LanguageModelClient (#64794)`) |
| Nearest stable release | `v1.21.0` (2026-09-23); pre-release `v1.22.0-pre` (2026-09-23) |
| Inspection date | 2026-09-26 |
| Local clone | `research/clones/zed/`. Shallow, blobless, **sparse**; git-ignored (`.gitignore:121`), never committed. |
| Sparse paths | `crates/{command_palette,command_palette_hooks,picker,fuzzy,workspace,agent_ui,ui,tab_switcher,file_finder,title_bar,recent_projects,keymap_editor,which_key}`, `crates/zed/src/zed`, `crates/settings/src`, `crates/gpui/src/keymap`, `assets/keymaps`, `assets/settings`, `docs/src` |

Reproduce:

```bash
git clone --depth 1 --filter=blob:none --sparse https://github.com/zed-industries/zed.git research/clones/zed
git -C research/clones/zed sparse-checkout set crates/command_palette crates/command_palette_hooks crates/picker \
  crates/fuzzy crates/workspace crates/agent_ui crates/ui crates/tab_switcher crates/file_finder crates/title_bar \
  crates/recent_projects crates/keymap_editor crates/which_key assets/keymaps assets/settings crates/zed/src/zed \
  crates/settings/src crates/gpui/src/keymap docs/src
git -C research/clones/zed log -1 --format='%H %cI'   # 933d8d93819c749a607e561883855a9b95c79cea
```

Citations are permalinks `https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/<path>#L<n>`. Zed's `agent_ui` changes weekly (for example, `thread_view.rs` exceeds 12,000 lines). This document therefore cites **behaviour and defaults**, not internal structure.

## License

**SPDX for everything studied: `GPL-3.0-or-later`.**

- The README says: "*Zed source code is licensed primarily under GPL-3.0-or-later, with Apache-2.0 components where marked.*" ([README.md#L30-L32](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/README.md#L30-L32)).
- The root carries [`LICENSE-GPL`](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/LICENSE-GPL) and [`LICENSE-APACHE`](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/LICENSE-APACHE). GitHub's API reports `NOASSERTION` because the repository is multi-licensed.
- Every crate read for this study declares `license = "GPL-3.0-or-later"`, for example [`crates/command_palette/Cargo.toml#L6`](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/crates/command_palette/Cargo.toml#L6), [`crates/workspace/Cargo.toml#L6`](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/crates/workspace/Cargo.toml#L6) and [`crates/agent_ui/Cargo.toml#L6`](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/crates/agent_ui/Cargo.toml#L6). The per-crate `LICENSE-GPL` files are symlinks to the root.
- Only `gpui` is Apache-2.0 ([`crates/gpui/Cargo.toml#L9`](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/crates/gpui/Cargo.toml#L9)), and it is a desktop GPU UI framework with no Android use.

**Implication.** Skein is Apache-2.0, and its `foss` flavour forbids GPL runtime dependencies (design spec §2 principle 7). Copying or transliterating any Zed source would pull GPL obligations into Skein. **This study takes observable behaviour, defaults and interaction rules only.** Skein's chord choices are Skein's own, Ctrl-based for Android. Overlaps such as Ctrl+W or Ctrl+P are long-standing industry conventions, not Zed's expression.

## Maintenance

**Very active.**
- Stable releases landed on 2026-09-16 (`v1.20.1`), 2026-09-17 (`v1.20.2`) and 2026-09-23 (`v1.21.0`), with weekly pre-releases.
- The head commit is 2026-09-25, and GitHub reports `pushed_at` 2026-09-26.
- The project has 90.9k stars and 3,135 open issues.

The flip side of that activity is churn: agent-panel code is rewritten often. Skein should copy *principles* (focus rules, ranking signals, availability filtering), which have been stable across Zed's history, not the agent panel's current widget layout.

## Relevant Skein Problem

1. **No model of where a command is valid.**
   - Skein's registry has two scopes, `GLOBAL` and a reserved `EDITOR` ([`CommandRegistry.kt`](../../../feature/shell/src/main/kotlin/app/skein/feature/shell/nav/CommandRegistry.kt)), and no notion of state: generating, model loaded, selection present, pane focused.
   - Without it, the palette either lists commands that can't run (breaking prompt §2 rule 4) or stays at four entries.
   - Zed derives the palette from the actions valid in the focused context.
2. **Panes exist on the unfolded Fold, but focus and keys do not.**
   - Skein already has a 30/70 dual pane (≥ 840 dp), a timeline that collapses to an icon rail, split view and preview tabs (`feature/shell/.../layout/{AdaptivePaneHost,IconRail,SplitHost,PaneLayout}.kt`; spec §8.2–8.3).
   - Nothing defines which pane a command acts on, where focus goes when a pane closes, or how the keyboard moves between panes (prompt §41: "close pane", "navigation shortcuts").
3. **Density without chaos on the inner screen.** Prompt §21 proposes `Navigation | Workspace | Optional Inspector` on large displays, and §35 an Inspector pane. Skein needs rules for when side regions show, hide, remember and zoom, so the inner screen can be dense while the default stays calm (prompt §2 rule 15).
4. **Preview-tab policy is under-specified.** Spec §8.3 adopts Cursor-style preview tabs, and §8.2 says command-bar Enter "opens the top hit as a preview tab". Zed has an explicit, per-source policy for when an open is a preview and when it is permanent. Skein needs one before palette and "Go to…" ship.
5. **The assistant surface.** Zed's agent panel is the closest analogue to Skein's chat on the unfolded screen: model selector, context `@` menu, send/stop, thread history and an options menu, all in a narrow panel beside a workspace.
6. **Shortcut discoverability.** Skein will have chords only on the Fold with a keyboard. They must be learnable without a cheat sheet and invisible on the touch-only outer screen.

## Patterns Worth Adopting

Tags Z1–Z16 are referenced from the palette design in [`CONTINUE.md`](CONTINUE.md#proposed-skein-command-palette--shortcuts).

**Z1. The palette lists what is available here, and features that are off vanish as a group.**
- Evidence: the palette is built from `window.available_actions(cx)`, the actions reachable from the focused element up the context tree, minus a global `CommandPaletteFilter` ([command_palette.rs#L120-L136](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/crates/command_palette/src/command_palette.rs#L120-L136); filter at [command_palette_hooks.rs#L19-L93](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/crates/command_palette_hooks/src/command_palette_hooks.rs#L19-L93)). With `disable_ai` on or no edit-prediction provider configured, whole namespaces (`agent`, `assistant`, `edit_prediction`, `copilot`) are hidden ([agent_ui.rs#L819-L860](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/crates/agent_ui/src/agent_ui.rs#L819-L860)).
- Skein translation: `SkeinCommand.isAvailable(CommandContext)`. Two examples:
  - With no model imported, the Model group shows only *Import model…*.
  - Graph commands stay hidden until the graph index has content.

  This is the structural enforcement of "no visible control may be nonfunctional" (prompt §2).

**Z2. Rank recently used commands above fuzzy matches, and count only palette use.**
- Evidence:
  - Commands are pre-sorted by `Reverse(usage)`, where `CommandUsage { last_invoked, invocations }` means recency first, then count ([#L218-L222](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/crates/command_palette/src/command_palette.rs#L218-L222), [#L607-L610](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/crates/command_palette/src/command_palette.rs#L607-L610)).
  - They are then fuzzy-matched with smart case and a length penalty ([#L618-L627](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/crates/command_palette/src/command_palette.rs#L618-L627)).
  - Finally, used commands are hoisted in usage order while unused ones keep fuzzy order ([#L629-L639](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/crates/command_palette/src/command_palette.rs#L629-L639)).
  - Usage counts **palette invocations only**. The rationale is in a comment: "*if a user already knows a keystroke for a command, they are unlikely to use a command palette to look for it*" ([#L390-L414](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/crates/command_palette/src/command_palette.rs#L390-L414)).
  - The invocation log is capped at 1,000 rows ([persistence.rs#L134](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/crates/command_palette/src/persistence.rs#L134)). Each used row offers "Remove from Command History" ([command_palette.rs#L442-L494](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/crates/command_palette/src/command_palette.rs#L442-L494)), and the feature can be turned off (`command_palette.use_command_history`, default `true`; [default.json#L1530-L1533](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/assets/settings/default.json#L1530-L1533)).
- Skein translation: this is ranking step 4 in the palette design. Skein persists **command ids and timestamps only**, never query text (privacy adaptation; see CONTINUE.md §4).

**Z3. ↑ at the top of the palette recalls previous queries, filtered by prefix.**
- Evidence: `QueryHistory.previous/next` walk past queries that start with the current text ([#L231-L307](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/crates/command_palette/src/command_palette.rs#L231-L307), [#L527-L561](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/crates/command_palette/src/command_palette.rs#L527-L561)).
- Skein translation: adopt, with **session-memory-only** storage that is cleared on vault lock. Zed persists `user_query` in SQLite ([persistence.rs#L148-L155](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/crates/command_palette/src/persistence.rs#L148-L155)). Skein must not: queries can contain vault content.

**Z4. Each row shows the chord for that command, resolved in the context the user came from.**
- Evidence:
  - Rows render `HighlightedLabel` (matched characters emphasised) and `KeyBinding::for_action_in(action, previous_focus_handle)` ([#L764-L811](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/crates/command_palette/src/command_palette.rs#L764-L811)).
  - The footer offers "Run ⏎" plus "Add/Change Keybinding…" ([#L813-L864](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/crates/command_palette/src/command_palette.rs#L813-L864)).
  - Icon buttons elsewhere use `Tooltip::for_action`, so every button teaches its chord (e.g. [quick_action_bar.rs#L758-L770](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/crates/zed/src/zed/quick_action_bar.rs#L758-L770)).
  - Empty states include the chord for their primary action ([agent_panel.rs#L5778-L5794](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/crates/agent_ui/src/agent_panel.rs#L5778-L5794)).
- Skein translation: adopt the highlighting, the right-aligned chords, and chords in button labels and tooltips, **only when a hardware keyboard is attached**. Drop "Change Keybinding" (no remapping in v1).

**Z5. Opening the palette remembers focus; confirming restores it, then runs the command.**
- Evidence: `toggle` closes an open palette or captures `window.focused()` ([#L88-L111](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/crates/command_palette/src/command_palette.rs#L88-L111)). `confirm` records usage, calls `window.focus(previous_focus_handle)`, dismisses, and only then dispatches the action ([#L714-L762](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/crates/command_palette/src/command_palette.rs#L714-L762)). The palette is `reopenable(false)` because it is a one-shot action ([#L145-L150](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/crates/command_palette/src/command_palette.rs#L145-L150)).
- Skein translation: "Close tab" from the palette closes the tab that was focused before the palette opened, and "Ask about this note" attaches *that* note. This is non-negotiable on the split inner screen, where two panes compete.

**Z6. Typed input can be interpreted, not just matched.**
- Evidence: a global `CommandPaletteInterceptor` turns special input into synthetic rows. Examples are Vim `:` commands, go-to-line numbers, and `zed://` links, which become an "Open Zed URL" row ([#L593](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/crates/command_palette/src/command_palette.rs#L593), [#L641-L657](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/crates/command_palette/src/command_palette.rs#L641-L657); results may be `exclusive`, [command_palette_hooks.rs#L109-L114](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/crates/command_palette_hooks/src/command_palette_hooks.rs#L109-L114)). Query normalisation lets both "new file" and `workspace::NewFile` match ([#L50-L75](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/crates/command_palette/src/command_palette.rs#L50-L75)).
- Skein translation: the palette's fallback rows are Skein's interceptors:
  - "Ask Skein "q"" and "Search Knowledge for "q"".
  - `/keyword arg` parsing, which preserves `CommandRegistry.match`.
  - A pasted `[[Title]]` becomes an "Open note" row.

**Z7. Keybindings bind to a context tree, and the most specific context wins.**
- Evidence: bindings attach to predicates over a focus tree (`Workspace > Pane > Editor`, `AcpThread > Editor && !use_modifier_to_send`). Lower nodes beat higher ones, later definitions beat earlier ones, and user bindings load last ([docs/src/key-bindings.md#L120-L196](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/docs/src/key-bindings.md#L120-L196)). Contexts carry state, for example `start_of_input` ([default-macos.json#L378](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/assets/keymaps/default-macos.json#L378)) and `not_searching` ([#L817](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/assets/keymaps/default-macos.json#L817)).
- Skein translation: a tiny, fixed version. `resolveChord(chord, CommandContext, isTyping)` resolves in this order:
  1. The focused text field's local keys (Enter, Shift+Enter, ↑, Esc, `[[` popup navigation).
  2. The focused surface (chat, note, list).
  3. Global commands.

  This order is what lets Esc mean "close popup" in the composer, "stop" in a generating chat, and "clear focus" elsewhere (CONTINUE.md §8). There is no user-facing predicate language.

**Z8. Docks, panes and focus follow explicit rules, and the layout is remembered.**
- Evidence:
  - Opening a dock focuses its panel, and closing a dock that held focus returns focus to the centre pane ([workspace.rs#L4518-L4582](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/crates/workspace/src/workspace.rs#L4518-L4582)).
  - `toggle_panel_focus` focuses a panel on the first press and returns to the centre on the second ([#L4689-L4711](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/crates/workspace/src/workspace.rs#L4689-L4711); `close_panel_on_toggle` defaults to `false`, [default.json#L201](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/assets/settings/default.json#L201)).
  - "Toggle All Docks" hides all side regions and later restores exactly the set that was open ([#L4639-L4673](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/crates/workspace/src/workspace.rs#L4639-L4673)).
  - Every change calls `serialize_workspace`.
  - `shift-escape` zooms the focused pane ([default-macos.json#L30](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/assets/keymaps/default-macos.json#L30)).
- Skein translation:
  - **Ctrl+0** "Show/hide list" gets the two-press focus semantics.
  - The Inspector pane (prompt §35) opens *without* stealing focus from the composer when toggled from an answer's "Used 3 notes ▸", and *with* focus when toggled by keyboard.
  - **Remember which side regions were open** per posture, persisted in `rememberSaveable`/ViewModel state, so fold→unfold restores the user's inner layout (prompt §24 Test B). Reopening fresh each time would violate that test.
  - A "Focus mode" (hide list and Inspector, restore later) is Zed's Toggle All Docks and costs one boolean.

**Z9. Preview tabs follow an explicit policy per source.**
- Evidence: `PreviewTabsSettings` has one flag per opening source ([item.rs#L67-L75](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/crates/workspace/src/item.rs#L67-L75)). The defaults ([default.json#L1502-L1521](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/assets/settings/default.json#L1502-L1521)):

  | Source | Opens as |
  | --- | --- |
  | Project-panel single click | **Preview** |
  | File finder | **Permanent** (`enable_preview_from_file_finder: false`) |
  | Code navigation to a file | **Preview** |
  | Navigating away from a preview | Does not keep it (`enable_keep_preview_on_code_navigation: false`) |

- Skein translation (a proposed amendment to spec §8.2/§8.3):

  | Source | Opens as |
  | --- | --- |
  | Timeline or list single tap | Preview |
  | Citation or context-row tap | Preview |
  | Palette "Go to…" / Enter on a search hit | **Pinned** |
  | Editing or double-tap | Pins (existing) |

  Rationale from Zed's defaults: an explicit, typed search signals intent to work with the document, while browsing does not. Today's "Enter opens top hit as a preview tab" (`CommandBarState.onSubmit`) would change accordingly.

**Z10. A recently-used tab switcher that confirms when Ctrl is released.**
- Evidence: `ctrl-tab` / `ctrl-shift-tab` open `tab_switcher` ([default-macos.json#L757](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/assets/keymaps/default-macos.json#L757)). Tabs sort by the pane's activation history ([tab_switcher.rs#L459](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/crates/tab_switcher/src/tab_switcher.rs#L459)), and releasing the held modifier confirms ([#L202-L225](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/crates/tab_switcher/src/tab_switcher.rs#L202-L225)).
- Skein translation: adopt as-is for the inner screen (catalogue #18). Android does not reserve Ctrl+Tab (Alt+Tab is the system app switcher).

**Z11. The agent's send button is one control that changes with state.**
- Evidence: the button cycles through these states ([thread_view.rs#L5358-L5420](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/crates/agent_ui/src/conversation_view/thread_view.rs#L5358-L5420)):
  - A spinner with the tooltip "Loading Added Context…" while attachments resolve.
  - **Stop** (error tint, tooltip "Stop Generation" plus its binding, `editor::Cancel` = Esc) while generating with an empty editor.
  - Queue while generating with typed text.
  - Send, disabled as "Type to Send" when empty.
- Skein translation: `ChatBottomBar` already swaps send and cancel on `isGenerating` ([`ChatBottomBar.kt:205`](../../../feature/chat/src/main/kotlin/app/skein/feature/chat/ChatBottomBar.kt)). Add the "preparing context" state, because retrieval and model load happen before the first token. Put the chord in the content description. Skip Queue for v1.

**Z12. The context `@` menu offers only categories that have something in them.**
- Evidence: `available_context_picker_entries` always offers Files and Symbols. The other categories are conditional ([completion_provider.rs#L1359-L1409](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/crates/agent_ui/src/completion_provider.rs#L1359-L1409)):
  - Threads, when the source supports them.
  - **Selection**, only if a selection exists.
  - **Skills**, only if any exist.
  - **Diagnostics**, only if errors or warnings exist.

  An empty query shows up to 4 recent files and 2 recent threads, excluding anything already mentioned ([#L1290-L1357](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/crates/agent_ui/src/completion_provider.rs#L1290-L1357)). Category labels are plain words: "Files & Directories", "Threads", "Skills" ([#L256-L277](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/crates/agent_ui/src/completion_provider.rs#L256-L277)).
- Skein translation: the `[[` picker gets categories Notes, Chats and Files. **Selection** appears only when the note editor in the other split pane has a selection. **Current note** appears only when a note is open. Recents come first.

**Z13. The model selector groups entries, hides what can't be used, and opens on the active model.**
- Evidence:
  - Entries are grouped as **Favorite**, **Recommended**, then **one section per provider**, with separators that can't be selected ([language_model_selector.rs#L241-L303](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/crates/agent_ui/src/language_model_selector.rs#L241-L303), [#L405-L410](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/crates/agent_ui/src/language_model_selector.rs#L405-L410)).
  - Only authenticated or configured providers are listed ([#L416-L477](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/crates/agent_ui/src/language_model_selector.rs#L416-L477)).
  - Selection starts on the active model, and the placeholder is "Select a model…".
- Skein translation:
  - Sections are **Recommended** (the bundled defaults: Gemma 4 E4B, Qwen 2.5 3B, spec OQ-3) and **Imported**.
  - A disabled row explains why ("Hash check failed", "Needs more memory"), following Continue C11.
  - The picker opens with the current model selected.
  - Keep Favourites out until someone has more than about 5 models.

**Z14. Menus are grouped, entries are conditional, and a trailing … means "opens more UI".**
- Evidence: the agent options menu has headers "Current Thread", "MCP Servers" and "Context" ([agent_panel.rs#L5550-L5776](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/crates/agent_ui/src/agent_panel.rs#L5550-L5776)).
  - "Regenerate Thread Title" appears only after a prompt was submitted and while no title is being generated (#L5573-L5580, #L5624-L5640).
  - Project and global rules entries appear only if the files exist.
  - Items that open further UI end in "…" ("Add Server…", "Install New Servers…").
  - `menu.context(focus)` makes shortcut labels resolve against the right pane.
- Skein translation: the chat overflow "⋯" menu gets:
  - a header **"This chat"**: Rename…, Export…, Delete…
  - a header **"View"**: Show sources, Split with…

  Unavailable items are hidden, and "…" marks any item that asks for more input.

**Z15. Actions on the conversation list are keyboard-reachable once the list has focus.**
- Evidence: in the threads sidebar, `shift-r` renames when not searching, `shift-backspace` archives and `cmd-shift-backspace` removes ([default-macos.json#L801-L823](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/assets/keymaps/default-macos.json#L801-L823)). The archive view uses plain `backspace`.
- Skein translation: with the list pane focused and no text field focused, Enter opens, **Delete/Backspace starts "Delete…" (with confirmation)**, and Shift+R starts rename. These are list-local and never global.

**Z16. Discovering chords: an opt-in, delayed helper.**
- Evidence: which-key is off by default with a 1,000 ms delay ([default.json#L2828-L2835](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/assets/settings/default.json#L2828-L2835); [which_key.rs#L34-L78](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/crates/which_key/src/which_key.rs#L34-L78)).
- Skein translation: Android already ships this surface. The system **Keyboard Shortcuts Helper (Meta+/)** lists whatever the app supplies through `onProvideKeyboardShortcuts()` (API 24+), and apps can open it with `requestShowKeyboardShortcuts()` ([Android docs](https://developer.android.com/develop/ui/compose/touch-input/keyboard-input/keyboard-shortcuts-helper)). Skein supplies the registry's chords there and binds Ctrl+/ to open it. No custom which-key UI is needed.

## Patterns Not Worth Adopting

- **Palette-everything.** Zed exposes nearly every action, including `dev::` actions, under humanised internal names ("workspace: toggle left dock", [`humanize_action_name` #L867-L932](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/crates/command_palette/src/command_palette.rs#L867-L932)). Skein curates about 30 commands with authored product-language titles and descriptions (prompt §2 rule 5, §42 "icons, descriptions").
- **One chord, many meanings.** `cmd-n` is New File in `Workspace && !Terminal` ([default-macos.json#L785-L788](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/assets/keymaps/default-macos.json#L785-L788)), New Thread in `AgentPanel` ([#L255-L258](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/assets/keymaps/default-macos.json#L255-L258)), and New Thread In Group in `ThreadsSidebar`. This is powerful for an editor used daily with the keyboard. For Skein, where the keyboard is occasional and the outer screen has none, a chord should mean one thing: **Ctrl+N is always New chat and Ctrl+Shift+N always New note.**
- **Multi-stroke chords, which-key menus and the pending-keystroke indicator** (`cmd-k cmd-left`, [docs/src/key-bindings.md#L174-L193](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/docs/src/key-bindings.md#L180-L196)), and **Vim/Helix modes**. They add memorisation cost with no mobile benefit.
- **Remapping infrastructure:** keymap editor, user JSON keymaps, command aliases (`command_aliases`, [command_palette.rs#L586-L589](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/crates/command_palette/src/command_palette.rs#L586-L589)), "Add Keybinding…". Out of scope for v1.
- **Three docks, resizable panels and arbitrary pane splits.** The Skein spec already rules out splitting within the right pane (§3.1), and the outer screen must never squeeze panes (prompt §21). Keep Navigate | Work | Inspect, with split view as the only split.
- **Agent power chords**: queued-message management (six chords: `cmd-shift-enter` send immediately, `cmd-ctrl-e` edit queued, …), thinking-effort menus, fast mode, profiles, MCP configuration in the options menu, favourite-model cycling (`alt-tab`, [default-macos.json#L293](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/assets/keymaps/default-macos.json#L293)). Model cycling is also rejected for swap cost (see CONTINUE.md).
- **Chrome toggles as settings sprawl** (`TitleBarSettings` has ten `show_*` flags, [title_bar_settings.rs#L4-L16](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/crates/title_bar/src/title_bar_settings.rs#L4-L16)). Skein should ship minimal chrome by default rather than settings to remove chrome.
- **Telemetry on invocation.** The palette emits `telemetry::event!("Action Invoked", …)` on confirm ([command_palette.rs#L743-L747](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/crates/command_palette/src/command_palette.rs#L743-L747)). Skein has no telemetry (spec §2 principle 3). Usage history stays on-device and serves only ranking.
- **Meta/Cmd-based chords.** Zed's macOS keymap is Cmd-centric. On Android the Meta key is system-owned (see below), so every Skein chord uses Ctrl.
- **GPUI or any Zed code.** GPL (see License), Rust, and a desktop GPU renderer.

## Potential Reusable Code

**None. Code reuse is prohibited by licence and irrelevant by stack.** The studied crates are `GPL-3.0-or-later` and incompatible with Skein's Apache-2.0 `foss` flavour. They are also Rust on GPUI. The only Apache-2.0 crate, `gpui`, is a desktop renderer with nothing to offer an Android Compose app. Migration cost of "reusing" Zed code would be a full rewrite plus a licence conflict. Everything valuable here is behavioural and is restated above in Skein's own terms.

## Android/Fold Relevance

**Verified Android platform facts the design depends on:**

- **The Meta (Search/⌘/Win) key is system-owned.** Android's physical-keyboard shortcuts include:
  - Meta+Tab (recents), Meta+H or Meta+Enter (home)
  - Meta+Backspace, Meta+Left or Meta+~ (back)
  - Meta+/ (shortcut list), Meta+N (notifications), Meta+L (lock), Meta+A (assistant)
  - Meta+Ctrl+arrows (split screen), Meta+I (settings), Meta+letter app launchers

  Also taken: Alt+Tab and Alt+Shift+Tab (recent apps), and Ctrl+Space / Ctrl+Shift+Space (input language). The list is compiled by [Android Police (Android 14)](https://www.androidpolice.com/android-14-physical-keyboard-shortcuts-list/). Android 16 lets users re-customise system chords ([Android Authority](https://www.androidauthority.com/custom-keyboard-shortcuts-android-16-3527394/)), so Skein must never *depend* on a Meta chord reaching it.
- **Esc has no default meaning, but its Alt and Ctrl variants do.** AOSP [`Generic.kcm`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/data/keyboards/Generic.kcm) maps `ESCAPE` to `base: none`, `alt: fallback HOME` and `ctrl: fallback MENU`, and maps `DEL` with `ctrl+alt` to `fallback BACK`. Skein handles Esc explicitly and never binds Alt+Esc, Ctrl+Esc or Ctrl+Alt+Backspace.
- **Compose text fields already own the text-editing chords.** Ctrl+A/C/X/V/Z/Shift+Z, Ctrl/Shift+arrow selection and PageUp/PageDown are handled; `onPreviewKeyEvent` intercepts before them and `onKeyEvent` after ([Android "Keyboard shortcuts"](https://developer.android.com/develop/ui/compose/touch-input/keyboard-input/commands)).
- **The Shortcuts Helper is standard since API 24.** Apps provide groups through `onProvideKeyboardShortcuts()` and can open the helper with `requestShowKeyboardShortcuts()` ([docs](https://developer.android.com/develop/ui/compose/touch-input/keyboard-input/keyboard-shortcuts-helper)). Skein's `minSdk 30` covers it.

**Mapping by posture:**

| Zed pattern | Fold outer (compact, touch) | Unfolded Fold with keyboard | Do not copy |
| --- | --- | --- | --- |
| Z1 availability filter | Same rules; the palette is a full-screen route | Same; centered dialog | Palette-everything |
| Z2/Z3 ranking and history | Recent and suggested rows on the empty query | Plus ↑ query recall | Persisted query text |
| Z4 chords shown | **Hidden** (no keyboard) | Right-aligned in rows, tooltips and drawer | "Change keybinding" |
| Z5 focus restore | Returns to the previous screen or field | Returns to the previously focused pane; the command acts there | — |
| Z7 context resolution | n/a (no chords); touch gestures only | Field → surface → global | Predicate language |
| Z8 docks and focus | The drawer is modal (`NavDrawer` is already modal at every width); the Inspector is a sheet | List pane toggle (Ctrl+0), Inspector pane, focus mode, remembered layout | Three docks, resizing |
| Z9 preview policy | Tabs collapse to "Recent ▾" (spec §8.3); policy still decides what "Recent" keeps | Full policy table | — |
| Z10 tab switcher | n/a | Ctrl+Tab recently-used order | — |
| Z11 send button | Send ↔ Stop with a "preparing" state | Same, plus Esc | Queue |
| Z12 `[[` categories | Bottom sheet above the IME | Popup at the caret | Diagnostics-style dev categories |
| Z13 model picker | Modal bottom sheet | Popover | Favourites cycling |
| Z14 grouped menus | Overflow "⋯" → sheet menu | Dropdown | MCP, profiles |
| Z15 list keys | n/a | List-local Enter / Delete… / Shift+R | Instant delete |

**Fold width caveat.** Skein's dual pane is gated at ≥ 840 dp (`PaneLayout.kt`, `PaneLayoutTest`), and its previews assume 1000 dp unfolded (`NavPreviews.kt`). Measure the inner display's real dp width in both orientations at the default display size (`adb shell wm size` and `adb shell wm density`) before finalising pane thresholds. If portrait lands in the 600–840 dp tier, the Zed-style pane rules above apply only in landscape.

## Mac UX-Lab Relevance

- **Zed as a live reference.** Zed is native on macOS and free to download, so installing it on the MacBook gives an interactive reference for palette ranking, focus return, dock toggling and the Ctrl-Tab switcher while prototyping. Translate every ⌘ to Ctrl, because Android reserves Meta.
- **Emulator keyboard testing.** Test chords with **Control** on the Mac keyboard. Do not design around the host ⌘ key, whose mapping in the emulator is not a stable basis for Android behaviour.
- **Layer A (Compose previews).** Build the palette with a fixture registry and `CommandContext` fixtures (generating, no model, note focused, split) so each availability permutation is one `@Preview` with no inference.
- **Layer B.** Preview the palette at 400 dp with the IME inset and at 1000 dp with and without chords (`hardwareKeyboard = true/false`), plus the argument stages.
- **Layer C.** Not needed. Zed itself is the interactive reference, and a web mock of a palette adds nothing that Compose previews and Robolectric key tests don't cover.
- **Unit tests on the Mac.** `rank()`, `resolveChord()` and the Esc ladder are pure Kotlin and run on the JVM. This is where Zed's focus and precedence rules become regression-proof.

## Recommended Action

**STUDY ONLY.** The licence is GPL-3.0-or-later, so no code may be reused. Patterns Z1–Z5, Z7–Z13 and Z16 feed the native Compose prototype specified in [`CONTINUE.md` → Proposed Skein command palette & shortcuts](CONTINUE.md#proposed-skein-command-palette--shortcuts). Z9 is a proposed spec amendment (preview-tab policy) for the IA and tabs owner to decide.

## Expected Benefit

- A palette that is contextual by construction: it never lists dead commands and it adapts to the focused surface (prompt §2 rule 4, §42 "contextual ranking").
- Ranking that improves with use but stays local-only and private.
- An inner-screen workspace that can be dense (list, work, Inspector, split) and still reads as calm, because side regions are toggled, remembered and zoomable instead of permanently present. This is the principle "High capability does not require permanent clutter".
- Predictable focus on a two-pane screen, which is where keyboard UX most often breaks.
- Discoverable chords through Android's own Shortcuts Helper, with no custom cheat-sheet UI to maintain.

## Expected Cost

- **Availability predicates and `CommandContext` plumbing**: the largest item, about 2 days. The context must be derived from shell state (focused pane, tabs, generating, model state, window class, keyboard presence) that today lives in several places (`NavState`, `TabsState`, `AdaptiveLayoutState`, `ChatTurnState`).
- **Usage history store**: about 0.5 day, as a tiny table or preferences: id, timestamp, count, capped.
- **Focus rules, list toggle and remembered layout**: about 1–2 days in `:feature:shell` layout code, plus fold/unfold tests.
- **Preview-tab policy change**: small in code, but it amends spec §8.2, so it needs the IA owner's decision.
- **Risk**: over-engineering toward an IDE (prompt §17 "Do not turn Skein into an IDE"). Mitigation: the "Not worth adopting" list above, and a hard cap of about 30 curated commands.

---

## Answers to the prompt's specific questions

**Command palette: ranking, fuzzy matching, recent and contextual commands, keybinding display.**
- **Ranking.** Candidates are only the actions available in the focused context (Z1). Recently used commands are hoisted in recency-then-count order, counting palette use only; the rest keep fuzzy order (Z2).
- **Fuzzy matching.** Zed uses nucleo-based fuzzy matching with smart case and a length penalty, and query normalisation so both "new file" and `workspace::NewFile` match (Z6).
- **Recent and contextual commands.** Recency is persisted, capped at 1,000 invocations, removable per row and switchable off. Contextual availability comes from the focus tree.
- **Keybinding display.** Each row shows its chord resolved against the pre-palette focus (Z4). Matched characters are highlighted, and the footer shows the selected command's "Run" and keybinding actions.

For Skein, this becomes the "Ranking" and "Empty-query content" sections of the palette design, with ids-only persistence.

**Panes and docks.** Zed has a centre pane group (splittable) plus left, right and bottom docks holding panels. Docks toggle open or closed, remember which were open ("Toggle All Docks" restores them), and serialise layout on every change. The focused pane can zoom (Z8). For Skein this maps to the prompt's `Navigate | Work | Inspect`: list pane (toggle, Ctrl+0), work area (tabs with one split), and Inspector (toggle). Everything is remembered per posture. Nothing else is added.

**Focus handling.**
- Explicit, symmetric rules: open means focus in, and closing a focused region means focus back to the centre.
- "Toggle focus" alternates between a panel and the centre.
- The palette restores the previous focus before dispatching, so commands hit the right target (Z5, Z8).
- Esc is context-scoped: `editor::Cancel` in the thread view stops generation, while in search bars it dismisses the bar.

Skein's Esc ladder and focus rules (CONTINUE.md §8) follow these rules.

**Keyboard shortcuts and keymap structure.**
- JSON keymaps are organised as binding groups under a `context` predicate over a focus tree, with state attributes. The more specific context wins, later definitions win, and user keymaps load last (Z7).
- Chords are platform files (`default-macos.json`, `default-linux.json`, `default-windows.json`).
- A few global anchors carry most navigation: `cmd-shift-p` palette, `cmd-p` file finder, `ctrl-tab` switcher, `cmd-b`/`cmd-r`/`cmd-j` docks, `cmd-w` close, `cmd-1…9` panes, `escape` → `menu::Cancel` ([default-macos.json#L696-L764](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/assets/keymaps/default-macos.json#L696-L764), [#L27](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/assets/keymaps/default-macos.json#L27)).

Skein adopts the *structure* in miniature (field → surface → global), picks Ctrl-only anchors (Ctrl+K/P/N/W/Tab/0–5), and skips the predicate language, multi-stroke chords and remapping.

**The assistant/agent panel and how it presents context.**
- Context enters through an `@` menu whose categories appear only when non-empty or relevant (Selection, Skills, Diagnostics), with recents first (Z12). Mentions render inline in the message editor.
- The model selector is grouped (Favorite / Recommended / per provider) and lists only usable models (Z13).
- The send button carries loading, stop, queue and send states (Z11).
- Enter sends unless the user opts into modifier-to-send (`use_modifier_to_send: false` by default, [default.json#L1384](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/assets/settings/default.json#L1384); binding at [default-macos.json#L403-L406](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/assets/keymaps/default-macos.json#L403-L406)).
- Thread history supports keyboard rename, archive and remove (Z15).

For Skein: the `[[` categories rule, the grouped model sheet, the send/stop "preparing" state, and an "Enter sends" setting (default on) with Ctrl+Enter always sending.

**Menus.** The native app menus (Zed, File, Edit, Selection, View, Go, Run, Window, Help; [app_menus.rs#L69-L340](https://github.com/zed-industries/zed/blob/933d8d93819c749a607e561883855a9b95c79cea/crates/zed/src/zed/app_menus.rs#L70-L310)) mirror palette actions and show their chords. In-panel menus group items under headers, show entries only when they apply, mark items that open more UI with "…", and resolve chord labels against the right focus context (Z14). Skein has no menu bar on Android, so the chat and note overflow menus take the grouping, conditionality and "…" conventions.

**Density without chaos.** Zed shows a lot of capability at once but keeps the resting state quiet. It does this in six ways:
1. Most capability lives behind the palette and chords rather than visible buttons (Z1, Z4).
2. Side regions are closable, remembered and zoomable (Z8).
3. Features that are off vanish entirely, including from the palette (Z1).
4. Chrome elements are few, and even those can be turned off (title bar flags).
5. Menus show only entries that apply (Z14).
6. Discovery aids are opt-in (which-key off, Z16).

**Principle extracted: "High capability does not require permanent clutter."** In Zed terms: *capability is addressable (palette and chords) rather than displayed (buttons and panels); regions are summoned and remembered rather than pinned; and anything that can't act right now does not appear.* For Skein that means three things:
1. The default screens (outer chat, inner chat plus list) show only the message box, the answer, the list, and one model/persona chip.
2. Everything else is reachable in one step through the palette, a sigil, a disclosure or a toggled pane.
3. The availability predicate guarantees nothing visible is dead.

**Proposed palette and shortcuts.** See [`CONTINUE.md` → Proposed Skein command palette & shortcuts](CONTINUE.md#proposed-skein-command-palette--shortcuts). Zed contributed:
- availability filtering (§2 rule 1)
- recency-hoisted fuzzy ranking with privacy-safe history (§4–5)
- focus restore before dispatch and the dialog sizing (§3)
- field → surface → global chord resolution and the Esc ladder's context scoping (§7–8)
- Ctrl+Tab recently-used switching and Ctrl+W/Ctrl+P/Ctrl+Shift+F conventions (§6–7)
- the preview-tab policy (Z9)

---

**What specific problem in Skein can this project help us solve?** Zed shows how to give Skein's unfolded-Fold workspace (list pane, tabbed and split work area, Inspector) and its command palette a coherent focus-and-availability model. Commands appear only where they can act, recently used ones rank first, focus returns to where the user was, side regions are summoned and remembered instead of permanently shown, and keyboard chords are discoverable. The inner screen can therefore be dense without the default ever looking cluttered.
