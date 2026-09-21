# Architecture

> **Status:** minimal. This file currently holds only the "Logging" section
> written for `E1.I11`/skein-4je. The full module map, process topology,
> dependency rules, coding/test conventions, and startup sequence diagram
> are `E0.I18`/skein-edc's job (open at the time this section was written)
> — expect this file to grow substantially there. Do not treat the absence
> of those sections as evidence they don't apply; see `skein-plan.json`'s
> `E1.I2`/`E0.I18` entries and `docs/BD_TAXONOMY.md` in the meantime.

## Logging

Spec §9: **never log document, prompt, chunk, or embedding content, at any
level, in any build.** This is a hard rule, not a debug-build convenience —
Skein's entire value proposition is that a user's notes and model
conversations never leave their control, and a stray `Log.d` is exactly the
kind of leak that undermines that promise silently.

### `SkeinLog` is the only logging facade

`app.skein.core.model.SkeinLog` (`:core:model`, pure Kotlin/JVM) is the one
place in this codebase allowed to reach a real log sink. It exposes
`d`/`i`/`w`/`e(tag, message)`, each routed through a pluggable
`SkeinLog.Sink`:

- On the JVM (unit tests, tooling, `:core:model` itself) the sink defaults
  to a no-op — nothing here ever calls `android.util.Log`, so plain JUnit
  tests don't need Robolectric just to exercise logging call sites.
- `app.skein.system.AndroidSkeinLogSink` (`:app`) wraps
  `android.util.Log` and is installed by `SkeinApplication.onCreate` in
  every process that `Application` subclass runs in (`:app`, the isolated
  `:inference` and `:embedder` processes alike).

**`NoRawLogging`** (`build-logic/guards`, applied to every Gradle
subproject via the root `build.gradle.kts`, the same way `ktlint` is)
fails `check` if any `src/main/kotlin` file other than `SkeinLog.kt` calls
`android.util.Log.{v,d,i,w,e,wtf}(...)` or `println(...)`. `:app` also
allowlists `AndroidSkeinLogSink.kt`, the one sanctioned bridge from
`SkeinLog` to the real platform logger — `SkeinLog` itself cannot import
`android.util.Log` (it is isolation-guarded pure Kotlin/JVM), so that bridge
has to live somewhere. Test and `androidTest` sources are not scanned: this
repo's tests already use `println` for developer-visible benchmark output
and it never reaches a shipped build.

### Release stripping (AC(a))

`SkeinLog.d`/`SkeinLog.i` are `@JvmStatic` and are compiled out of the
release dex by R8: `app/proguard-rules.pro` declares
`-assumenosideeffects class app.skein.core.model.SkeinLog { public static
void d(...); public static void i(...); }`, and `app/build.gradle.kts`'s
`release` build type now sets `isMinifyEnabled = true` so R8 actually runs
(AGP only invokes R8 for a minified variant). This is scoped narrowly on
purpose — see `proguard-rules.pro`'s header comment — to just enable that
one optimization (`-dontobfuscate` plus a blanket `-keep` disable shrinking
and renaming) without taking on the full R8-full-mode / keep-rule audit
that `E1.I8` (reproducible-build configuration) owns; that issue is
expected to replace this file's blanket keep with an audited rule set.
`w`/`e` are **not** stripped in any build — they carry real diagnostics —
but see the next section for why that's still safe.

Verified once via `tools/logging/check-release-dex.sh`, which disassembles
the release APK's dex with `dexdump` and greps for an `invoke-*` call site
referencing `SkeinLog.d`/`SkeinLog.i` (not just the method's own
definition, which `-keep` intentionally leaves in the dex).

### Defense in depth: content markers, at every level, in every build

Convention (never logging content) is necessary but not sufficient — spec
§9 asks for it to hold even when someone makes a mistake. `SkeinLog`
therefore checks every call, at every level, against a small set of content
markers (`prompt:`, `text:`, `chunk:`, `document:`, `embedding:`); if one is
followed by non-blank, non-already-redacted text, the message that reaches
the sink is replaced with a fixed placeholder instead. This is a safety net
for accidental raw content reaching `SkeinLog`, not the primary control —
callers must still never build such a message in the first place — but it
means a mistake at `w`/`e` (which R8 does not strip) still can't leak
content into logcat.

`SkeinLog.testHook`, set by `:testing`'s `SkeinLogCaptureRule` for the
duration of a test, observes every call's sensitivity flag so
`SkeinLogCaptureRule` can fail a test outright if anything sensitive was
logged — see `app.skein.testing.SkeinLogCapture`'s KDoc.

### llama.cpp: `LlamaLogRedactor`

`app.skein.inference.service.LlamaLogRedactor` (`:inference-service`, pure
Kotlin, no JNI dependency) is the redactor the future `llama_log_set`
native callback will call before anything from llama.cpp itself reaches
`SkeinLog` — llama.cpp logs prompt text directly at points `SkeinLog`'s own
marker check never sees, since that text never goes through `SkeinLog` at
all until this redactor has already run. `LlamaLogRedactor.forward(level,
message)`:

- drops (returns `null` for) anything at `DEBUG`/`INFO` — llama.cpp's own
  verbose/info logging is never forwarded, sensitive or not;
- redacts anything after a `prompt:`/`text:` marker before returning it for
  `WARN`/`ERROR`, e.g. `LlamaLogRedactor.redact("prompt: hello world") ==
  "prompt: <redacted 11 chars>"` — the marker and a single separating space
  are preserved; only the content is replaced, and a message may contain
  more than one marker.

Wiring the real native `llama_log_set` callback to call this is out of
scope for `E1.I11`/skein-4je — it lands with the llama.cpp build itself
(`E1.I4`/`E4.I1`, skein-ca2/skein-3aw) — this issue only ships the pure
Kotlin redactor those issues will call.
