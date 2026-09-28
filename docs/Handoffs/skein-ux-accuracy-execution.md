# UX and answer accuracy: execution evidence

This records execution of the [continuation plan](skein-ux-accuracy-continuation-plan.md)
authorized by the owner's “run it” instruction. Beads remains the task tracker.
The original handoffs describe historical states; they are not proof that the
current build or the physical Fold has passed acceptance.

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
test repository; `skein-f17n` owns that correction. The controller's own suite
passed, but that does not make the failed integrated app suite green.

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
