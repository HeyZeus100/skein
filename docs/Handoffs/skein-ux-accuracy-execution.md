# UX and answer accuracy: execution evidence

**Resumed by the owner on 2026-09-28.** The preceding paused implementation
checkpoint was `13b7337`. Read the [resume handoff](skein-ux-accuracy-resume-handoff.md)
for preserved artifacts and restart assignments. The coordinator resumed with
isolated retrieval and native/accuracy agents and one CI runner. Fold verification
remains **HOLD**; resumption does not establish physical-device acceptance.

This records execution of the [continuation plan](skein-ux-accuracy-continuation-plan.md)
authorized by the owner's “run it” instruction. Beads remains the task tracker.
The original handoffs describe historical states; they are not proof that the
current build or the physical Fold has passed acceptance.

## Resumed verification

The preserved overlay-provenance commit was integrated once as `07d8ec8`.
The app check, explicit ktlint and both app AndroidTest compilations passed
(807 Gradle tasks). The opt-in benchmark app check, app/service ktlint, both app
AndroidTest compilations and the service Dev AndroidTest compilation also passed
(825 tasks); its app XML contains 226 tests per variant, zero failures or skips.
These checks complete the previously pending app/benchmark integration work.

The reviewed manual retrieval workflow and helper landed as `d696a09`. They
require fresh emulator/test state, real passing instrumentation XML, unchanged
gold and thresholds, and matching prebuilt/installed test APK hashes. Host
timeouts and failures retain their available artifacts. The combined host suite
passed 45 tests at that commit; 27 exercise the retrieval runner. These are host
runner tests, not measured retrieval scores. Source SHA metadata remains
explicitly host-declared, and full-hybrid retrieval remains **INELIGIBLE**.

Independent native review found no reason to change the proven tokenizer design.
The ordinary device inventory contains 36 native/service methods. Fake EOS tests
and the four-token tiny smoke cannot verify real Qwen EOS behavior; its complete
model hash, template identity and isolated-service answer behavior remain open.
Full import-service contracts run on the host; ordinary device XML covers
repository/link primitives, not that complete import workflow.

The released runtime source is `56a46f5eedf217685528072bbf53a04f94e85aa5`.
Its final local opt-in gate passed 871 tasks, including the vault test APK build;
all 51 host evaluation tests passed. Remote
[CI 36502550354](https://github.com/HeyZeus100/skein/actions/runs/36502550354)
passed lint, unit tests and guards (1,388 tasks), then Foss APK assembly.
[Reproducibility 36502550334](https://github.com/HeyZeus100/skein/actions/runs/36502550334)
passed both release builds/comparison, native repeatability and toolchain tests;
its conditional SQLCipher source-verification job was skipped.

[Ordinary emulator 36502596076](https://github.com/HeyZeus100/skein/actions/runs/36502596076)
reported workflow success, but its actual XML contains **264 passes and one
failure entry**, with zero skipped entries: app 37 pass, vault 191 pass plus the
opt-in retrieval assumption, native/service 36 pass. The retrieval class did not
execute; UTP serialized its `AssumptionViolatedException` as `<failure>`.
Do not call this 265 passing tests or a clean XML gate. `skein-u66y` tracks explicit
lane separation/reporting; the dedicated retrieval run supplies its own evidence.

[Synthetic smoke 36504291918](https://github.com/HeyZeus100/skein/actions/runs/36504291918)
completed all 12 unique seed-17 cases: **12 OK, zero timeouts/OOM/errors**, with
`finalized_prompt` source metadata on every row. Eleven generated four tokens;
one used the application-owned missing-evidence response. The four benign native
sequences exactly match their references (16, 10, 30 and 23 IDs). The hostile
literal-control case correctly contains two control tokens instead of the unsafe
reference's three. The corrected Unicode sequence is 30 IDs, preserving the
ordinary merge while retaining control isolation.

Model, template, overlay and fixture identities match the declared source and
verified APK links. The app and app-test digests returned by instrumentation
match the host artifacts; the host independently hashed the installed native
test APK. This is `tiny-structural-v2` (1024 context, two threads, four output
tokens, seed 17, 60-second case deadline). It establishes runtime structure,
not factuality, Qwen EOS or a speed improvement over the older 4096/4/64 run.
Memory/thermal and answer-quality measurements remain unavailable.

All 31 targeted contract checks passed, including migration011/wrong-key,
post-COMMIT acknowledgments, all five draft contracts, altered-excerpt revision
retention, quiesce/kinds, service death/rebind, context capacity and overflow,
cancel/BUSY/lock, and native whitespace/control isolation. This closes the
storage-only AL-10a gate and `skein-i1y1`; the remaining AL-10 lifecycle and
revision-aware citation UI work remain open.

[Screenshot run 36502550337](https://github.com/HeyZeus100/skein/actions/runs/36502550337)
also reported top-level success despite a failed non-blocking job: 14 drawer
goldens drifted with the host date/time zone. `7b2786d` forwards optional clock and
zone parameters with unchanged production defaults, pins the fixtures and adds
date-label assertions. Only 14 inspected drawer baselines changed; comparison
tolerance is unchanged. Its merged app/chat/shell checks, explicit ktlint, both
app AndroidTest compilations and both screenshot suites passed (871 tasks).
Actual XML: app 223 tests per variant with no failures/skips; chat 258 with 12
intentional matrix skips; shell 208 with 14 intentional matrix skips. The scoped
drawer suite also passed under `Pacific/Kiritimati`. Remote verification of this
later fix remains separate from the runtime source above.

[Remote screenshot verification 36505454999](https://github.com/HeyZeus100/skein/actions/runs/36505454999)
passed the actual job on `4a6c3e2`, which contains that fix and evidence notes.
[CI 36505454948](https://github.com/HeyZeus100/skein/actions/runs/36505454948)
and [reproducibility 36505455003](https://github.com/HeyZeus100/skein/actions/runs/36505455003)
also passed that exact head. The conditional SQLCipher source-verification job
was skipped. Later commits contain only retained evidence and documentation;
they do not extend the source scope of any runtime result.

[Retrieval diagnostic 36505374600](https://github.com/HeyZeus100/skein/actions/runs/36505374600)
executed at the original `56a46f5` runtime source: one real passing test, no
failures/skips, matching installed test APK, zero scope/provenance/anchor/
determinism violations. All three **ranking gates failed**. Lexical recall@8 was
0.616667, graph 0, and default lexical+graph 0.600000 against the unchanged 0.75
target; default nDCG@8 was 0.601604 against 0.60. Graph returned nothing for all
76 queries. Lexical/default rejected none of the 16 absence queries. See the
[full baseline and limits](../RETRIEVAL_EVAL.md#first-measured-baseline).
The 508 lexical-index probe warnings are retained without treating their text
as a proven trigger defect. No gold labels, thresholds or failed rows were
changed. Full hybrid remains **INELIGIBLE**; `skein-9744`, `skein-gg11.32` and
the measured graph follow-up `skein-rw52` remain open.

The [durable raw evidence bundle](../eval/runs/2026-09-28-resumed-56a46f5/README.md)
contains unmodified ordinary XML, synthetic rows/manifests/native arrays, and
the complete retrieval report/XML, alongside separate review summaries. All 25
indexed files passed SHA-256 verification after integration. Original trailing
blank lines in two instrumentation logs are preserved as raw evidence; other
source/documentation whitespace checks pass. Earlier failed runs and inactive
pause checkpoints are unchanged. The Fold was not accessed or changed; its
verification hold, Qwen behavior and answer-quality gates remain open.

## Recovered and implemented

The interrupted shell was preserved before integration. The new navigation shell
is now the default, with model import/search and Settings entry points ported.
Reset clears navigation and session entry stores. The original recovery worktree
and the owner's untracked `Untitled/` and `logos/` were preserved.

Vault write quiescence, batched kind lookup, and Obsidian path/title/alias link
resolution landed. Ambiguous links remain unresolved rather than creating a
fabricated destination. The exact `a75964c` emulator run
[36362463113](https://github.com/HeyZeus100/skein/actions/runs/36362463113)
passed 37 app, 180 vault and 30 native tests, including real process death/reload,
quiescence, imports and link lifecycle tests. This evidence predates later work.

The answer pipeline now binds a turn to its chat's Space and selected model,
fits the exact formatted token sequence to the loaded context, and trims complete
history exchanges before generation. Knowledge mode has an explicit evidence
policy and an application-owned missing-evidence response. The inspector uses
post-budget sources. These controls improve the conditions for reliable answers;
they do not establish semantic correctness when retrieved material is weak.

Automatic evidence excludes conversation transcripts and generated AIOUT
artifacts (`1163cf2`, `02d4372`). Explicit conversation retrieval keeps CHAT
provenance and Space filtering. Stored citation decoding validates marker sets,
locators and excerpt hashes (`678ec75`); revision cleanup separately retains
readable references when an excerpt is damaged. See
[evidence provenance](../ANSWER_EVIDENCE_PROVENANCE.md) and
[citation integrity](../CITATION_INTEGRITY.md).

Encrypted drafts and the session-owned turn controller now support atomic first
send, durable queued USER messages, navigation/recreation, bounded lock handling,
and idempotent Stop/completion persistence (`96df909`, `91d51c4`, `25a008a`).
The synchronous post-COMMIT acknowledgment (`d1ed1ad`) prevents cancellation from
turning a successful write into a duplicate retry. See
[the controller contract](../CHAT_TURN_SESSION.md). Delete UI remains a separate
lifecycle integration boundary.

## What execution actually found

The ordinary emulator run
[36383767287](https://github.com/HeyZeus100/skein/actions/runs/36383767287)
at `d33bcb4` reported app **37/0 failures**, vault **190/1**, and native **32/3**,
with no skipped tests. All four post-COMMIT contracts, five draft contracts and
both new native capacity tests passed. The migration fixture reused a driver
whose key had already been consumed; three generation fixtures reserved the
entire context for output, leaving no room for the prompt. Test-only correction
`1435774` supplies fresh key copies and measures valid reservations. It also
adds wrong-key rejection and an explicit overflow-before-streaming assertion.
Compilation is not a substitute for rerunning these native tests.

The synthetic model run
[36384222843](https://github.com/HeyZeus100/skein/actions/runs/36384222843)
at `71cef4d` retained all 12 development rows: **2 OK, 10 timeouts, 0 OOM, 0 errors**.
One OK row was the app's missing-evidence abstention. The profile was 64 output
tokens, 4,096 context, four threads, seed 17 and a 60-second per-case deadline on
an x86 Android emulator. No factuality score was established. An earlier run
failed in SDK setup before any model test and contributes no model evidence.

That same run compared real native token IDs: four of five cases passed.
Unicode whitespace rendered identically but tokenized to 31 rather than 30 IDs.
The split between template newline and message whitespace prevented an ordinary
BPE merge. This is an observed runtime defect, not a model-quality hypothesis.
Literal control-token isolation passed and must remain enforced during repair.

The [raw synthetic rows and manifests](../eval/runs/2026-09-28-tiny-smoke-71cef4d/README.md)
are retained unchanged with this report. In timeout rows, the harness had not
published a final outcome: `used_sources: []` is unavailable finalization
metadata, not proof that the prompt contained no evidence. `memory: null` means
memory was not measured.

The artifacts bind the run to these complete SHA-256 values:

| Artifact | SHA-256 |
|---|---|
| Public tiny model | `741ad12b64088fedc17c33aacb22e48be1972ef36a39f03666dd68bd15614fb9` |
| Installed app APK | `77243235a40ec985cba89e9d54b914bdcde37f49eeb0d566884d7815f32eb82d` |
| GGUF chat template | `872be49dbb638044ad01b60388f48d469ff2980e5f0dccdc22ec907db54d0788` |

At integrated `1435774`, model/vault/RAG/chat/testing checks, explicit ktlint,
and affected AndroidTest compilation passed (540 Gradle tasks). An earlier
integrated app suite exposed concurrent timeline iteration in the in-memory
test repository. `927d071` corrected it; full integrated app/chat/shared testing
checks, explicit ktlint and both app AndroidTest compilations then passed (863
tasks). At `13b7337`, merged retrieval/inference checks and both Android native
ABIs also passed (343 tasks). The native host tokenizer regression now matches
the exact 30-token reference while preserving control isolation. Emulator work
was pending at that checkpoint; the resumed runs above now supply its evidence.
Physical-device work remains on hold. Failed historical runs are unchanged.

## Limits of the evidence

The physical Fold has not received these changes or run an answer-quality test.
Read-only inspection identified a Qwen2.5 3B Instruct Abliterated Q3_K_M artifact
from its GGUF header; a header match is not a full-file hash. The owner vault
has not been used as benchmark input. Installation remains gated on reviewed
emulator evidence, and any authorized update must preserve app data.

The supplied-evidence answer harness exercises the real isolated inference
service, but does not prove indexing, retrieval relevance or Space filtering.
A separate real encrypted-vault retrieval harness measures those paths. Current
production has no embedder; lexical/graph results cannot satisfy a full hybrid
retrieval gate. Development fixtures and public reserved regressions are not a
blind capability benchmark. Human grading of factual claims is still required
before reporting the proposed answer-quality thresholds as achieved.

Revision-aware source landing and an altered-source badge remain in
`skein-gg11.31`; calibrated weak-evidence rejection and conversational retrieval
remain in `skein-gg11.32`. Model comparisons, sustained memory/thermal measurements,
the physical adaptive-layout journey and the remaining UX waves retain their
existing Beads acceptance gates. No broad Astra/ChatGPT parity or elimination of
hallucinations is claimed.
