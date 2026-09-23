# KV_CACHE_ANALYSIS — incremental conversation, and what survives contact with RAG

Directive §9. Upstream `jegly/OfflineLLM` @ `e81091e86013c0605381d15a1ad7276a4be0b92b` (tag
`5.1.1`), Apache-2.0, reviewed 2026-09-23. The incremental-prompting feature landed in `5.1.0`
(`d54e0bb`) and is unchanged in `5.1.1`.

**Summary: OfflineLLM's `_prevLen` mechanism is the correct minimal solution to a problem Skein
also has — Skein re-decodes the entire prompt every turn — but it is a *prefix* cache keyed on a
templated chat transcript, and Skein's RAG architecture will break that key on most turns. The
transferable part is the invariant and the invalidation rule, not the implementation. Skein should
take the explicit `kvClear` and the invalidation test now, and design the prefix-reuse layer
around a stable-prefix contract later.**

---

## 1. The mechanism

State: a single `size_t`.

```cpp
// Length of the templated conversation prefix already fed to the KV cache.
// Each turn only tokenizes/decodes formatted[_prevLen..new_len) instead of
// re-feeding the whole history (which grew quadratically and duplicated KV).
size_t _prevLen = 0;
```
([`LLMInference.h#L39-L42`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/cpp/LLMInference.h#L39-L42))

Per turn ([`LLMInference.cpp#L294-L337`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/cpp/LLMInference.cpp#L294-L337)):

1. append the user message to `_messages`;
2. render the **whole** conversation with `llama_chat_apply_template(..., add_generation_prompt=true, buf, size)`
   into `_formattedMessages`, growing and re-rendering once if the buffer was short;
3. slice `[_prevLen, new_len)` — the new suffix only;
4. tokenize that slice with `add_special = (_prevLen == 0)` — **BOS on the first chunk only**;
5. decode it; positions continue from `llama_memory_seq_pos_max(...) + 1`.

After the turn completes, `_updatePrevLen()` re-renders with `add_generation_prompt=false` and a
`nullptr` buffer — using `llama_chat_apply_template`'s length-query mode — and stores the result
([`#L251-L255`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/cpp/LLMInference.cpp#L251-L255)),
called from both the EOG path (`#L417`) and the stop path (`#L445`).

Three details that are easy to get wrong and that OfflineLLM gets right:

- **`add_generation_prompt` differs between the two calls.** The *feed* render includes the
  assistant turn opener (it must be decoded); the *checkpoint* render excludes it, because the
  assistant's actual reply will be appended by `addChatMessage` and re-rendered next turn. Getting
  this backwards duplicates or drops the opener.
- **BOS exactly once**, via `add_special = (_prevLen == 0)`.
- **Character offsets, not token offsets.** `_prevLen` indexes the *rendered string*, and the
  suffix is tokenized independently. That is only safe because it is also the invalidation
  trigger — see §2.

### The problem it solves

Without it, each turn re-tokenizes and re-decodes the entire transcript. Cost is quadratic in
turn count, and — worse on a phone — the KV cache is re-filled from scratch every turn, so TTFT
on turn 20 is the cost of prefilling the whole conversation. The README's claim is the plain
statement of the result: *"turn 2 onward only processes your new message — no more re-crunching
the whole conversation each turn"*
([`README.md#L101`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/README.md#L101)).

---

## 2. Invalidation

Exactly one rule, and it is a good one:

```cpp
if (_prevLen > (size_t) new_len) {
    _prevLen = 0;
    llama_memory_clear(llama_get_memory(_ctx), true);
}
```
([`LLMInference.cpp#L329-L332`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/cpp/LLMInference.cpp#L329-L332))

*If the rendered conversation got shorter, the cached prefix cannot be a prefix of it. Drop
everything and start over.* That covers truncation, message deletion and conversation switching in
one comparison.

What it does **not** cover, and this is the mechanism's real limitation: a render that is the
*same length or longer* but differs earlier in the string. Editing a message in place to another
of equal length, or changing the system prompt to one of equal length, leaves `_prevLen` pointing
into a KV cache that no longer corresponds to the text. The guard is a length check standing in
for a content check.

For OfflineLLM this is nearly unreachable — it has no message editing, and the system prompt is
fixed at load time
([`InferenceEngine.kt#L82-L84`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/app/src/main/java/com/jegly/offlineLLM/ai/InferenceEngine.kt#L82-L84)).
For Skein, whose PP-68…PP-71 beads specify inline editing, regeneration and truncation, it is
directly reachable. **Any Skein prefix cache must key on a digest of the prefix, not its length.**

The remaining lifecycle events are handled bluntly:

| Event | OfflineLLM |
|---|---|
| Model switch | `ModelManager.loadModel` calls `inferenceEngine.unloadModel()` first ([`ModelManager.kt#L169`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/app/src/main/java/com/jegly/offlineLLM/ai/ModelManager.kt#L169)) — whole context destroyed. Correct, if expensive. |
| System-prompt change | Requires a reload; not reachable mid-conversation. |
| `storeChats = false` | Every turn clears the buffer, resets `_prevLen`, and clears memory ([`#L279-L284`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/cpp/LLMInference.cpp#L279-L284)) — a clean single-turn mode. |
| Context overflow | Throws before decoding (`MEMORY_ANALYSIS.md` §5). **There is no rollover.** The conversation is simply over; the user must start a new one. |
| Conversation switch | New `loadModel`, i.e. a full model reload — the heaviest possible answer. |

**No context rollover at all** is worth stating plainly, because it is the feature most likely to
be assumed present. OfflineLLM never evicts, never summarises, never shifts. §9's "context
rollover" question has the answer *"not implemented"*.

---

## 3. Skein today

`InferenceService.generateInternal` (`inference-service/.../InferenceService.kt:369-400`):

```kotlin
val rendered = backend.applyChatTemplate(model.model, roles, contents, true)
val promptIds = ChatTemplating.tokenize(backend, model.model, ChatTemplating.segment(rendered, contents))

var nPast = 0
var index = 0
while (index < promptIds.size) {
    ...
    nPast = backend.decodePrompt(model.context, promptIds.copyOfRange(index, end), nPast)
    index = end
}
```

**Every request renders the full message list, tokenizes all of it, and decodes from position
zero.** There is no `_prevLen` equivalent and `kvClear` — which exists at `LlamaBackend.kt:104`
and `skein_jni.cpp:1001` — is never called on this path.

Two consequences:

**Performance.** Skein has the quadratic behaviour OfflineLLM removed in 5.1.0. At 4096 tokens of
history and (say) 8 tok/s prompt processing on the CPU path, turn N pays the full prefill. This is
not an M0 blocker — M0 measures `llama-bench`, not the chat loop — but it is a v1 UX cliff that
lands the moment multi-turn chat exists.

**Correctness, which I could not fully settle.** Decoding at positions `0..n` for `seq_id 0` while
cells from a longer previous turn still occupy positions `n+1..m` relies on two upstream
properties: causal masking (a cell at a higher position is masked for a lower-position query) and
llama.cpp's purge-on-overwrite, which exists explicitly to preserve the `[pos_min, pos_max]`
contiguity invariant:

```cpp
// note: we want to preserve the invariant that all positions between [pos_min, pos_max] for each sequence
//       will be present in the cache. so we have to purge any position which is less than those we would overwrite
```
([`src/llama-kv-cache.cpp#L1160-L1178`](https://github.com/ggml-org/llama.cpp/blob/b29c606e28a01b1bc8c1351026a0fa6e616bf6c4/src/llama-kv-cache.cpp#L1160-L1178))

I read that code and believe Skein is correct today. I could not prove it on device
(no-hardware guardrail), the reasoning is subtle, it is not asserted anywhere in Skein's test
suite, and it is exactly the kind of invariant a llama.cpp bump can change silently.

**Recommendation (escalation E-4's "hardening gap"): call `kvClear(model.context)` at the top of
`generateInternal`, and add R-7.** It costs nothing today — the cache is being fully rewritten
anyway — it makes the "stateless between requests" property explicit rather than emergent, and it
gives the future prefix-reuse work a clean baseline to deviate from deliberately.

---

## 4. What survives contact with RAG — the actual §9 question

Skein will inject retrieved notes, documents, repository context, knowledge-graph context, and
skill/tool results between turns (`ContextBudget` already budgets a `maxRetrievedTokens` block,
`retrievedFraction = 0.40`, cap 3072 — `core/inference/.../InferenceConfig.kt`). The directive is
explicit: *do not simply copy a chat-only caching architecture if it would constrain Skein later.*

**What breaks.** `_prevLen` assumes turn *N+1*'s render extends turn *N*'s as a string prefix.
With RAG that is false whenever retrieved context is (a) placed before the tail of the
conversation, or (b) re-retrieved per turn — which is the normal case, since the query changes.
A prefix cache keyed on the whole render would then invalidate on nearly every turn and the
mechanism would cost more than it saves (a full re-render plus a length comparison plus a full
re-decode).

**What survives, and it is most of the value:**

1. **The invariant.** *The KV cache holds a decoded prefix; a turn decodes only the suffix; any
   change to the prefix invalidates everything after it.* Architecture-independent.
2. **BOS exactly once** (`add_special = (_prevLen == 0)`). Any incremental scheme must carry this.
3. **The checkpoint discipline** — the reuse boundary is recorded only *after* a turn completes,
   from a render that excludes the generation prompt. A cancelled turn must not advance it.
   OfflineLLM handles this by calling `_updatePrevLen` from both the EOG path and `stopCompletion`
   and from nowhere else.
4. **Fail closed on any doubt.** `_prevLen = 0; llama_memory_clear(...)` is the whole recovery
   path. A prefix cache whose miss path is "clear and re-decode" can never be *wrong*, only slow.

**The design that follows (proposal only — no implementation here, §18):**

> Split the prompt into a **stable prefix** and a **volatile suffix**, and cache only the prefix.
>
> Stable prefix := system prompt + persona + the conversation turns up to the last budget
> recomputation. Volatile suffix := retrieved context block + the current user turn + the
> generation prompt.
>
> `PromptAssembler` (`skein-82g`) already builds the prompt in named blocks, so the boundary is
> something it can *declare* rather than something the service must infer. Pass it across AIDL as
> `GenerateRequest.stablePrefixTokens: Int` plus `stablePrefixDigest: ByteArray` (a hash of the
> rendered prefix, never the text — `ContextBudget` already establishes the "key by digest, never
> by content" rule for exactly this reason). The service compares the digest against what it
> decoded last; on a match it decodes only from `stablePrefixTokens`, on a mismatch it clears and
> decodes everything.
>
> Placement then becomes a *design constraint with a measurable payoff*: putting retrieved context
> **after** the stable conversation prefix keeps the prefix cacheable; putting it before destroys
> the cache. That is a fact worth knowing before the prompt layout is frozen, which is the real
> value of reading this mechanism now.

Interaction with `ChatTemplating`: the scaffold/content segmentation
(`inference-service/.../ChatTemplating.kt`) already walks the rendered string forward and
fails closed when content cannot be located verbatim. A prefix boundary must be a **segment**
boundary, never mid-segment, or the `parseSpecial` discipline splits across the cache boundary.
That is an additional constraint OfflineLLM never faced — it has no such discipline
(`SECURITY_ANALYSIS.md` §4) — and it is the reason this cannot be a copy.

Interaction with editing (PP-68…PP-71): an edit truncates the message list, which shortens the
prefix, which is a digest mismatch — handled by the same clear-and-redecode path. Regeneration
walks back to the nearest user message: same. **The digest key makes all of PocketPal's editing
semantics fall out for free, where the length check would not.**

---

## 5. Findings

| Id | Item | Class | Milestone |
|---|---|---|---|
| OL-16 | `_prevLen` incremental templated-prefix reuse | **ADAPT** — take the invariant and the checkpoint discipline; key on a digest, not a length; split stable prefix from volatile suffix | M2 (after `skein-82g`) |
| OL-17 | `_prevLen > new_len` → clear and restart | **ADAPT** — the fail-closed recovery path is right; the *trigger* must be a digest comparison | M2 |
| OL-18 | `storeChats = false` → clear every turn | **REFERENCE** — a clean single-turn mode; Skein gets this for free from the explicit `kvClear` in §3 | — |
| — | No context rollover at all | **REFERENCE** — upstream has not solved it; Skein cannot borrow an answer | — |
| — | Explicit `kvClear` at the top of `generateInternal` + R-7 | **Skein hardening**, prompted by this review | M0/M1 |

Nothing here is M0-blocking. The `kvClear` is a one-line clarity change worth doing whenever the
file is next touched; the prefix-cache design should be recorded in the prompt-assembly design
**before** the prompt layout is frozen, because the layout decision is the expensive one to
reverse.
