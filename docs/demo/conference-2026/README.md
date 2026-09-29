# Skein conference demonstration

Four fictional Markdown notes for a short demonstration of asking about saved notes, opening a source and unfolding the phone. This pack supports `skein-830f`; it is not an evaluation corpus, benchmark result or acceptance sign-off. Do not add it to evaluation gold or tune retrieval thresholds against it.

**Rehearsal status: NOT RUN.** The durations below are planned targets, not measured performance. The UI route was inspected at integration commit `a4ba4f4`; the installed candidate must be identified and rehearsed separately.

The owner needs the Fold ready by **17:00 on 29 September 2026, America/Los_Angeles**. The planning target is a **16:00 code freeze**, followed by a final rehearsal on the same APK and model. The owner released the previous Fold HOLD specifically for a verified update and demo checks with existing app data preserved. One designated runner owns all physical-device access.

## Preload before presenting

1. Let the designated device runner handle the authorized update. The owner unlocks the phone and vault when requested and performs physical folds. Preserve existing app data, screen lock, biometrics and secure settings; do not disable screenshot protection for the presentation.
2. Use the same selected Space for import and questions. Make a model available before starting: the New chat composer and its import button require one. Do not import a large model during the presentation.
3. Make the four files in [notes](notes/) available through Android's document picker using the agreed device-transfer route. These are UTF-8 Markdown notes with no frontmatter IDs. Keep real personal notes out of the visible presentation path; do not reset the existing vault to prepare the demo.
4. In **New chat**, tap the paperclip labelled **Import file to Knowledge**. Choose one `.md` file, wait for the **Added … to Knowledge** message, then repeat for the other three. This is a single-file picker. The current Knowledge screen has Search and New note, not a folder-import action.
5. Clear the `[[filename.md]]` text inserted into the composer after importing. Import adds notes to Knowledge; that text does not explicitly attach a source to the next prompt. Avoid importing the pack twice: ordinary imports create notes rather than updating the earlier copies.
6. Open **Knowledge** and confirm the four titled notes and their contents. Wait for active **Preparing for search** work to finish. **Awaiting search by meaning** means semantic work is still pending; do not describe it as complete. Rehearse the exact questions to establish whether retrieval works on this candidate.
7. Return to **Chat / New chat**. New chats default to **Knowledge on**. The first Send creates the chat. In an existing chat, its **Knowledge on/off** chip opens Context, where **Search Knowledge** affects the next message. Keep it on for this demo.

## Presenter script: planned 2–3 minutes

| Planned time | Action and words |
|---|---|
| 0:00–0:20 | On the outer screen, open Knowledge and **Juniper Schedule**. “These are four invented workshop notes. I’ll ask a question about them and check the source.” |
| 0:20–1:10 | Open New chat and send Question 1 below. “Skein is searching the notes in this Space.” Wait for the actual answer; do not promise a latency that has not been measured. |
| 1:10–1:50 | Tap the answer's citation chip to open the source. Ask the owner to unfold the phone physically. Show the same chat and source in the wider layout, then return to Chat. “Here is the note behind that answer.” |
| 1:50–2:30 | Send Question 2 below. “This pack contains no access code for ORBIT-99.” Show the actual refusal. If the app rejects all evidence before generation, explain that the app declined to call the model without supporting evidence. |
| 2:30–3:00 | Leave room for a question or use the optional synthesis prompt only if rehearsal established enough time. |

Use the citation chip only if it actually appears. If the model omitted a marker, open **Knowledge on → Context → Used in this answer → Juniper Schedule** to inspect the included source, and explain that this is the context list. Do not claim that a missing citation was rendered.

## Exact prompts and expected answers

### Question 1: one source

> When and where does the Juniper workshop start? Answer in one sentence and cite the note.

Expected facts: **10:00 on 14 October 2026, in Cedar Room**. Required supporting note: [Juniper Schedule](notes/juniper-schedule.md). Citation numbers are assigned at runtime; do not require a particular number. A plausible answer with the wrong room, time or date fails the rehearsal.

### Question 2: missing evidence

> What is the access code for ORBIT-99?

None of these four notes names ORBIT-99 or gives its access code. Expected behavior: state that Knowledge does not supply enough evidence, with no invented code or supporting citation. Confirm before presenting that other notes in the selected Space do not supply that fact.

When retrieval rejects all supplied evidence, the current app response is:

> I couldn't find enough evidence in Knowledge to answer that. Add a relevant note or turn Knowledge off to ask from general knowledge.

That response is application-owned and can skip generation. It demonstrates an evidence boundary, not model reasoning or measured answer quality. Do not turn Knowledge off to manufacture an answer to the fictional question.

### Optional question: two sources

> Who coordinates the Juniper workshop, and what equipment is required? Cite the notes.

Expected facts: **Mira Vale; 12 tablets and two spare chargers**. Required supporting notes: [Juniper Team](notes/juniper-team.md) and [Juniper Equipment](notes/juniper-equipment.md). Theo Lark handles equipment; Theo is not the coordinator. Do not use this extra question live unless both retrieval and response time passed rehearsal.

## What this route does and does not show

- A citation tap opens the current whole note. Exact-passage highlighting, revision-aware source landing and changed-source badges are not established by this demo. Avoid editing or deleting sources during the presentation.
- Context's **Used in this answer** list reflects passages included in the prompt. It does not certify that every generated claim is supported. Read the answer against the facts above.
- The folded/unfolded source layout is an intended runtime behavior that still needs rehearsal on the installed candidate. Use a completed answer for the transition; this script does not depend on generation or keyboard survival during a fold.
- Markdown avoids PDF extraction and image-import uncertainty. This pack does not demonstrate OCR, bulk import, guaranteed explicit attachments or search across Spaces.
- The invented note about an offline exercise is not evidence about the app's network behavior. Claim an offline run only if the actual candidate was rehearsed offline and the conditions were recorded.

## Fallback

If generation is too slow, fails or supplies unsupported facts, stop the live answer using **Stop answer** if it is still running. Open the relevant note through Knowledge and show the source directly. Say what failed; do not present a prepared answer as fresh generation.

A previously completed rehearsal chat may be shown as a clearly labelled **earlier rehearsal** if one exists and its sources were checked. If the source/fold route is unreliable, keep the working pane visible and show the notes without another fold. The demo can still show local note browsing while leaving the failed runtime gate open.

## Rehearsal checklist and results template

The device runner records observed results here or in linked evidence. Until that happens every result below is **NOT RUN**. A pass requires an observed result, not code inspection. Keep technical logs content-free and preserve secure settings; do not require screenshots when protection prevents them.

| Check / measurement | Observed result |
|---|---|
| Candidate commit; installed package/version; APK identity | NOT RUN |
| Model name and file identity; runtime settings | NOT RUN |
| Date, runner, selected Space; network conditions | NOT RUN |
| Existing vault/app data and secure settings preserved | NOT RUN |
| Four notes imported once, titles/bodies checked | NOT RUN |
| Indexing status, including pending semantic work | NOT RUN |
| New-chat Knowledge state and same-Space retrieval | NOT RUN |
| Question 1: exact facts and included source | NOT RUN |
| Question 1: first visible response / completion time | NOT RUN |
| Citation chip or honestly identified Context fallback opens Schedule | NOT RUN |
| Owner's physical unfold: same conversation, correct source, usable layout | NOT RUN |
| Question 2: refusal, no fabricated code/citation; generation skipped if known | NOT RUN |
| Optional synthesis: facts, both sources and completion time | NOT RUN |
| Stop during generation, then another successful question | NOT RUN |
| Lock/unlock or relaunch, then another successful question | NOT RUN |
| Three consecutive full rehearsals on the same APK/model; failed attempts retained | NOT RUN |
| Full script elapsed time; fallback exercised | NOT RUN |
| Evidence location and any remaining failed gates | NOT RUN |

Track remaining work in Beads through the coordinator. This template records rehearsal evidence and does not close `skein-830f` or broader UX, inference or retrieval acceptance gates.
