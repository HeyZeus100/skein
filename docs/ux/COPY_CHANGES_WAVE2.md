# UX Wave 2 — product copy pass (skein-xtov.23.13)

Every user-visible string changed by the copy pass, old → new, with its file. No
layout, behaviour or architecture changes — see the bead's report for scope,
test updates and goldens.

## Glossary: Timeline → Recent

| File | Old | New |
|---|---|---|
| `feature/shell/.../nav/NavDrawer.kt` | `"Timeline"` (drawer entry) | `"Recent"` |
| `feature/shell/.../SkeinApp.kt` | `DestinationPlaceholder(label = "Timeline")` | `DestinationPlaceholder(label = "Recent")` |
| `feature/shell/.../layout/AdaptivePaneHostPreviews.kt` | `"Timeline"` (mock preview) | `"Recent"` |

## Glossary: vault → Skein / "Skein is locked" / "Unlock Skein" / "Recover Skein"

| File | Old | New |
|---|---|---|
| `feature/settings/.../FlagSecureToggle.kt` | "Hides vault and chat content from recents…" | "Hides Skein and chat content from recents…" |
| `feature/settings/.../RecoveryKeyExportControls.kt` | "Export vault key (passphrase)" (row + 2 dialog titles) | "Export recovery key (passphrase)" |
| " | "Save a passphrase-protected copy of your vault key, so you can recover it on another device." | "Save a passphrase-protected copy of your key, so you can recover Skein on another device." |
| " | "Unlock your vault to export its key." | "Unlock Skein to export its key." |
| " | "Anyone who has this file AND this passphrase can read everything in your vault — on any device, …" | "…everything in Skein — on any device, …" |
| " | "Your vault locked before the export finished. Unlock it and try again." | "Skein locked before the export finished. Unlock Skein and try again." |
| " | "Skein could not confirm it is you, so nothing was exported." | "Couldn't confirm it's you, so nothing was exported." |
| " | "That file could not be written. Nothing was saved. Try a different location." | "Couldn't write that file. Nothing was saved. Try a different location." |
| " | "The export did not finish. Nothing was saved." | "Couldn't finish the export. Nothing was saved." |
| " | 'choose "Restore from a passphrase export" when setting Skein up again.' | 'choose "Recover Skein" when setting Skein up again.' |
| `feature/shell/.../auth/VaultSetupScreen.kt` | `promptTitle` default "Set up your vault" | "Set up Skein" |
| " | Heading "Set up your vault" | "Set up Skein" |
| " | Button "Set up vault" | "Set up Skein" |
| " | Button "Restore from a passphrase export" | "Recover Skein…" |
| " | Heading "Restore from a passphrase export" | "Recover Skein" |
| " | Button "Restore vault key" | "Recover Skein" |
| " | EXPLANATION: "…in an encrypted vault on this device…" | "Skein keeps your notes encrypted on this device…" |
| " | NO_BIOMETRIC_MESSAGE: "…protect your vault key…" | "…protect your key…" |
| " | RESTORE_EXPLANATION: "…exported your vault key to a file, you can restore it here instead." | "…exported a recovery file, you can use it to recover Skein here instead." |
| `feature/shell/.../auth/VaultResetScreen.kt` | "Reset vault" | "Reset Skein" |
| " | "Permanently delete vault" | "Permanently delete Skein" |
| " | TYPE_STEP_EXPLANATION: "Your vault's key file cannot be read…attachments, and vault keys…" | "Skein's key file cannot be read…attachments, and keys…" |
| `feature/shell/.../auth/VaultResetConfirmState.kt` | "The vault is still unlocked. Lock it first, then try resetting again." | "Skein is still unlocked. Lock it first, then try resetting again." |
| " | "The vault could not be reset. Please try again." | "Couldn't reset Skein. Try again." |
| `feature/shell/.../auth/VaultSetupState.kt` | "Setup was cancelled before your vault key was created…" | "Setup was cancelled before your key was created…" |
| " | "Your vault key could not be created. Nothing has been saved. Please try again." | "Couldn't create your key. Nothing has been saved. Try again." |
| `feature/shell/.../auth/VaultRestoreState.kt` | "That passphrase did not unlock the recovery file. Check it and try again…" | "Couldn't unlock the recovery file. Check the passphrase and try again…" |
| " | "That file is not a Skein recovery export. Choose the file you saved when you exported your vault key." | "Couldn't read that file. It's not a Skein recovery export. Choose the file you saved when you exported your key." |
| " | "That recovery file was written by a newer version of Skein. Update the app, then try again." | "Couldn't use that recovery file. It was written by a newer version of Skein. Update the app, then try again." |
| " | "Restore was cancelled before your vault key was saved. Nothing has been changed." | "Recovery was cancelled before your key was saved. Nothing has been changed." |
| " | "Your vault key could not be restored. Nothing has been changed. Please try again." | "Couldn't recover Skein. Nothing has been changed. Try again." |
| `feature/shell/.../auth/EnvelopeUnreadable.kt` | "Your vault's key file could not be read…Unlocking is not possible until the vault is reset." | "Couldn't read Skein's key file, so it can't be unlocked…resetting Skein is the only way to unlock it from here." |
| `feature/shell/.../auth/BiometricUnlockScreen.kt` | `biometricPromptSubtitle` default "Authenticate to open your vault" | "Authenticate to open Skein" |
| " | "Reset vault…" | "Reset Skein…" |
| " | "The vault has not been set up yet." | "Skein hasn't been set up yet." |
| " | "Authentication failed." | "Couldn't verify your identity." |
| " | "Unlock is not available right now." | "Couldn't unlock right now. Try again in a moment." |
| `app/.../MainActivity.kt` | `.setSubtitle("Skein is about to export your vault key")` | "Skein is about to export your recovery key" |
| " | `Text("Opening vault…")` | "Unlocking Skein…" |
| " | `"The vault could not be opened: $reason"` (leaked a raw exception reason) | "Couldn't unlock Skein. Try again." (reason stays log-only) |
| " | "The biometric key was invalidated. Recovery is not available in this build yet." | "Skein can't verify your fingerprint or face anymore, and can't recover this automatically." |

## Glossary: retrieved context / score / recalled by → Sources / "Relevant passage"

| File | Old | New |
|---|---|---|
| `feature/chat/.../ContextPanel.kt` | Header "context" | "Sources" |
| " | "no retrieved context for this turn" | "No sources for this turn" |
| " | Per-row "recalled by: vector" / "recalled by: —" | "Relevant passage" (constant) |
| " | Per-row "score 0.87" | removed — never shown |
| `feature/chat/.../ChatScreen.kt` (+ preview) | Context toggle "⚹ context" | "⚹ Sources" |

## Glossary: ingest / indexing → "Preparing for search…"

| File | Old | New |
|---|---|---|
| `app/.../notify/Channels.kt` | Notification channel name `"Indexing"` | `"Preparing for search"` |
| `app/.../notify/IndexingNotifier.kt` | Notification text `"Indexing…"` | `"Preparing for search…"` |
| `feature/settings/.../SettingsScreen.kt` | Section title "Indexing" | "Search" |
| " | "Indexing progress shown while documents are processed" | "Shows a notification while documents are prepared for search" |

## Glossary: backlinks (Level 1) → "Linked from"

| File | Old | New |
|---|---|---|
| `feature/editor/.../backlinks/BacklinksDrawer.kt` | "Backlinks" | "Linked from" |
| " | "No backlinks yet" | "Nothing links here yet" |

## Glossary: GGUF / model id → friendly name, never raw ids

| File | Old | New |
|---|---|---|
| `app/.../MainActivity.kt` | Command hint "— pick a GGUF file to import and set as default" | "— pick a model file to import and set as default" |
| " | "Use /import model to add one, then come back to this chat." (slash syntax as primary UX) | "Import a model from the search bar above, then come back to this chat." |
| " | `"Registered ${ids.joinToString()} …"` (raw `ModelId`s shown) | Resolves each id to `ModelRecord.model.name` first |
| " | `"Import failed: ${outcome.refusal.describe()}"` (enum names / error codes) | "Couldn't import the model. Choose a different file and try again." |
| `feature/models/.../ModelsScreen.kt` | "No models imported yet — use /import model" | "No models imported yet — import one from the command palette" |

## Glossary: Persona → Space (user-facing only; "Persona" stays internal/storage)

| File | Old | New |
|---|---|---|
| `feature/timeline/.../FilterBar.kt` | Fallback chip label "Persona" | "Space" |
| " | Dropdown item "All personas" | "All Spaces" |

## Canonical term: File (was "Attachment")

| File | Old | New |
|---|---|---|
| `feature/graph/.../GraphLegend.kt` | Legend label "Attachment" | "File" |
| `feature/timeline/.../TimelineFormatting.kt` | `kindLabel` "Attachment" | "File" |

## UX_AUDIT UX-P2-02: terse lower-case copy

| File | Old | New |
|---|---|---|
| `feature/chat/.../ChatScreen.kt` (+ preview) | Header "chat" | "Chat" |
| " | `SERVICE_DIED_BANNER_TEXT`: "model process restarted, retry" | "Couldn't finish the answer. The model had to restart." |
| " | `ENGINE_ERROR_BANNER_TEXT`: "generation failed, retry" | "Couldn't finish the answer." |
| " | Retry button "retry" | "Try again" |
| `feature/chat/.../AssistantBubble.kt` | "interrupted — stopped" | "Stopped" |
| " | "thinking…" | "Working…" (neutral: shown before the first token, while the model may still be reading the prompt; the activity block names the real step in Wave 4) |
| `feature/chat/.../ChatBottomBar.kt` | contentDescription "Attach a file" | "Attach" |
| " | contentDescription "Stop generating" | "Stop answer" |
| " | contentDescription "Send message" | "Send" |

## Destructive / error pattern and punctuation polish

| File | Old | New |
|---|---|---|
| `feature/settings/.../AboutScreen.kt` | `"No licenses match \"$query\"."` (straight quotes) | `"No licenses match “$query”."` (curly quotes) |
| `app/.../MainActivity.kt` | `"Imported \"${name}\" and set as default"` (straight quotes) | `"Imported “${name}” and set as default"` (curly quotes) |
| " | "No model yet." (one-line notice with trailing period) | "No model yet" |

## Tests updated (assertions on the strings above)

- `feature/chat/src/test/.../ChatScreenTest.kt` — "score 0.87" → "Relevant passage"
- `feature/shell/src/test/.../SkeinAppTest.kt` — "Timeline" → "Recent"; `navigateViaDrawer` now keys off `Destination` via a new stable per-item test tag (`ShellTestTags.navDrawerItem`) instead of display text, because the tab-strip's own "Recent" fallback text otherwise collides with the renamed drawer entry; the "tab stays open" assertion now targets a new `ShellTestTags.RECENT_DROPDOWN` tag on `RecentDropdown` instead of matching "Recent" text
- `feature/shell/src/test/.../auth/BiometricUnlockOutcomeTest.kt` — "The vault has not been set up yet." / "Authentication failed." → new strings
- `feature/shell/src/test/.../screenshots/SkeinTypeRenderingTest.kt` — sample fixture string "Set up your vault" → "Set up Skein" (cosmetic, not asserted)
- `feature/settings/src/androidTest/.../RecoveryKeyExportInstrumentedTest.kt` — "Export vault key (passphrase)" → "Export recovery key (passphrase)"
- `app/src/test/.../AskPathComposeTest.kt` — "No model yet." / "/import model" → "No model yet" / "Import a model"
- `app/src/test/.../MainActivityComposeTest.kt` — "Authentication failed." → "Couldn't verify your identity."

## Production (non-test) support changes for the copy fix

- `feature/shell/.../nav/NavDrawer.kt`, `feature/shell/.../tabs/RecentDropdown.kt`, `feature/shell/.../testing/ShellTestTags.kt`: added `ShellTestTags.navDrawerItem(destinationName)` and `ShellTestTags.RECENT_DROPDOWN` test tags — needed once the Timeline→Recent rename made the drawer entry's label collide with the tab-strip's own pre-existing "Recent" fallback text in Compose UI tests. No visual or behavioural change.
