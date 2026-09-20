# Skill Guardrails

> **Stub.** This file was created by `skein-7uwy` to document the
> `SecureTextField`-only rule below. `skein-q3r7` owns fleshing this doc out
> into the full guardrail catalog it's named for; until that lands, this
> file holds just the one rule.

## No raw text-input Composables outside `SecureTextField`

**Rule:** no `BasicTextField(`, `TextField(`, or `OutlinedTextField(` call
outside `feature/shell/src/main/kotlin/app/skein/feature/shell/input/
SecureTextField.kt`.

**Why:** threat model §9 requires `SecureTextField` (`skein-qiu`) to be the
ONLY text-input Composable used for vault-sensitive content — chat prompt,
editor, search, persona system prompts, model import passphrases. It wraps
Compose Material 3's `TextField` (which itself wraps Compose Foundation's
`BasicTextField`) to apply `autoCorrectEnabled = false`,
`InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS`, and
`EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING` — signals to the IME that the
field's content must not be learned from, suggested from, or retained. A
caller that reaches for the raw Compose primitive instead silently loses
all three protections.

**Enforcement:** `RawTextFieldTest`
(`testing/src/test/kotlin/app/skein/testing/RawTextFieldTest.kt`) — a
source-scan JUnit test, following the precedent
`NoShadowOrGradientTest` (`skein-iru`) set for this class of rule. It walks
every `:feature:*` and `:app` module's `src/main/kotlin/` for the three
forbidden call patterns, explicitly allowlisting `SecureTextField.kt`
itself (the one file that legitimately wraps the primitive). Runs as part
of `./gradlew :testing:check` (and therefore `./gradlew check`).

A full Android Lint `Detector` (`RawTextFieldDetector`) was considered —
it would integrate with the IDE and catch violations before compilation —
but was deferred because it needs its own `build-logic/lint` module and a
`lint-checks` dependency wired into every module, infrastructure no issue
has stood up yet. See `bd show skein-7uwy` for the tradeoff notes. If that
infrastructure lands later, this test can be retired in the detector's
favor.

**Known pending exception:** landing this test caught a real, already-
shipped violation — `feature/shell/.../nav/CommandBar.kt`'s `$`-prompt
search/slash-command field uses raw `TextField`. It's tracked separately
in `RawTextFieldTest`'s `pendingMigrations` map (not the same allowlist as
`SecureTextField.kt`) with a link to `bd show skein-yb3m`, the followup
that migrates it and removes the exception.
