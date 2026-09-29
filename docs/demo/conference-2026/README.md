# Skein conference demonstration

Three fictional domain packs demonstrate asking about saved notes and opening the source. **Construction / General Contractor is the default 2–3 minute demo.** Aviation records and culinary mycology research are optional examples, not extra steps in that time budget. Each pack contains three short Markdown notes with no frontmatter IDs.

The [demo controls guide](controls.md) covers the split view, collapsible note/chat lists and first-send Knowledge switch in the workspace update.

**Rehearsal status: partial; demo not ready.** Durations are planned targets, not measured performance. The checkpoints below distinguish the original route inspection from actual checks on the installed candidate. This pack supports `skein-830f`. It is not an evaluation corpus, benchmark result or acceptance sign-off. Do not add it to evaluation gold or tune retrieval thresholds against it.

**Physical checkpoint, 29 September at 10:48 UTC:** candidate `5ae91c2` is installed with its APK digest verified. The native public-model replay passed five template/token/control cases and EOG classification; the synthetic answer attempt hit its 900-second watchdog with no valid manifest or answer rows. Normal app launch succeeded, but the device keyguard requires the owner to unlock. **The demo is not ready and post-update UI rehearsal remains NOT RUN.** See the [physical evidence and open gates](runs/2026-09-29-physical/README.md).

**Earlier UI checkpoint:** the owner unlocked the Fold and the three construction notes were imported once. No live question was sent. The [partial UI checkpoint](runs/2026-09-29-ui-checkpoint/README.md) preserves that evidence.

**Installation checkpoint, 16:47 UTC:** the workspace update at `28354dd25` was installed, with its copied APK hash, signer and data-preservation metadata verified. At that checkpoint, normal Android unlock was required and no new UI question or instrumentation had started. See the [workspace installation record](runs/2026-09-29-workspace-update/README.md). The demo remains **not ready** until its live checks pass.

**Later live checkpoint:** the owner unlocked the Fold. New chat, collapsible lists, note/chat, two-note and note/graph layouts, Swap and draft retention were observed on the device, with no teal crease line. One controlled general question completed with Knowledge off; the exact two-sentence format was missed. [Physical UI and general-answer observations](runs/2026-09-29-workspace-update/ui-rehearsal.md) preserve the timing limit and earlier ambiguous interaction.

**Knowledge-on checkpoint, 17:23 UTC:** the construction synthesis turn stayed blank and was stopped; the missing-PO answer acknowledged that no number was provided, but added an unsupported hedge and rendered no citation. The [controlled construction record](runs/2026-09-29-workspace-update/construction-probes.md) preserves the original timings and failures. The complete demo and citation gates remain open.

**Supplied-evidence checkpoint, 17:30 UTC:** the released four-case diagnostic completed with actual answer rows and one raw JUnit pass. Aviation answered correctly with a citation; the other cases retain quality/citation problems. The [v5 evidence](runs/2026-09-29-synthetic-v5/README.md) is separate from live retrieval rehearsal and preserves the previous timeout.

**Aviation UI checkpoint, 17:53 UTC:** the six optional notes were imported once and their bodies verified. The live aviation answer was slow and wrong with eight fictional context rows. Its actual inline citation opened the correct source. The [aviation record](runs/2026-09-29-workspace-update/aviation-probe.md) preserves that distinction and the timing overrun; no model/profile change or retry occurred.

The owner needs the Fold ready by **17:00 on 29 September 2026, America/Los_Angeles (PDT)**. The planned **code freeze is 16:00**, followed by final rehearsal on the same APK and model. The owner released the previous Fold HOLD for a verified update and demo checks with existing app data preserved. One designated runner owns all physical-device access.

## Preload before presenting

1. Let the designated device runner handle the authorized update. The owner unlocks the phone and vault when requested and performs physical folds. Preserve app data, screen lock, biometrics and secure settings; do not disable screenshot protection for the presentation.
2. Use the existing selected Space for import and questions. The current Settings Spaces screen is not a create-Space flow; do not assume a new isolated demo Space can be created. Use an existing selector only if it is actually present. Make a model available first: the New chat composer and its import button require one. Do not import a large model during the presentation.
3. Make the three [construction notes](notes/construction/) available through Android's document picker using the agreed transfer route. Optionally prepare [aviation](notes/aviation/) and [mycology](notes/mycology/) too. Titles and bodies identify the content as DEMO/fictional. Expose only the demo notes during presentation; keep personal notes out of view and do not reset the existing vault.
4. In **New chat**, tap the paperclip labelled **Import file to Knowledge**. Choose one `.md` file, wait for **Added … to Knowledge**, then repeat for the other files in the chosen pack. This is a single-file picker. The current Knowledge screen has Search and New note, not a folder-import action.
5. Clear the `[[filename.md]]` text inserted into the composer. Import adds notes to Knowledge; that text does not explicitly attach a source to the next prompt. Avoid importing a pack twice: ordinary imports create notes rather than updating the earlier copies.
6. Open **Knowledge** and confirm the titles and bodies. Wait for active **Preparing for search** work to finish. **Awaiting search by meaning** means semantic work is still pending. Rehearse the exact questions to establish retrieval on this candidate.
7. Return to **Chat / New chat**. New chats default to **Search Knowledge on**, and the workspace update exposes that switch before the first Send creates the chat. In an existing chat, its **Knowledge on/off** chip opens Context, where **Search Knowledge** affects the next message. Keep it on for these examples.

## Construction: default presenter script

| Planned time | Action and words |
|---|---|
| 0:00–0:20 | On the outer screen, open Knowledge and **DEMO: Cedar Cabinet Order**. “These are fictional general-contractor coordination notes: an approval, an order and a delivery plan.” |
| 0:20–1:10 | Open New chat and ask **What blocks the Cedar cabinet order, and who coordinates delivery?** “I’m asking across the project notes.” Wait for the actual answer. |
| 1:10–1:50 | Tap a rendered citation to open its source. Ask the owner to unfold physically. Show the same chat and source in the wider layout, then return to Chat. “I can check the underlying record.” |
| 1:50–2:30 | Ask **What is the Cedar cabinet purchase order number?** “The notes do not record that number.” Check that the answer acknowledges the gap and does not invent a number. |
| 2:30–3:00 | Allow a question or show the delivery target if rehearsal left enough time. |

If the cross-note prompt fails rehearsal, use the single-source question **What blocks the Cedar cabinet order?** and say that the demonstration is of that narrower question. Keep the failed cross-note result; do not call the substitution a fix.

Use a citation chip only if it appears. If the model omits a marker, open **Knowledge on → Context → Used in this answer** and select the relevant note. Explain that this is the context list, not a rendered answer citation. Do not assume the list contains a source until checked.

## Exact questions and expected source facts

Questions deliberately omit extra style instructions. Source review of the current lexical evidence gate found that appended formatting instructions can add unmatched retrieval terms and reject supported questions. This limitation remains under `skein-gg11.32`; short demo questions do not fix or close it. Every question below still requires actual rehearsal. Citation numbers are assigned at runtime and must not be hard-coded.

### Construction / General Contractor

| Exact question | Expected facts and sources |
|---|---|
| What blocks the Cedar cabinet order, and who coordinates delivery? | **RFI-017 is awaiting finish-selection approval; Nora Elm coordinates delivery.** [Cabinet Order](notes/construction/cedar-cabinet-order.md) + [Delivery Coordination](notes/construction/cedar-delivery.md). [RFI-017](notes/construction/cedar-rfi.md) also establishes that client approval is not recorded. |
| What blocks the Cedar cabinet order? | **RFI-017 / pending finish-selection approval.** [Cabinet Order](notes/construction/cedar-cabinet-order.md). |
| What is the Cedar cabinet delivery target? | **19 October 2026, not a confirmed booking.** [Delivery Coordination](notes/construction/cedar-delivery.md). |
| What is the Cedar cabinet purchase order number? | **Not recorded.** [Cabinet Order](notes/construction/cedar-cabinet-order.md) explicitly records this gap. Do not invent a number or substitute RFI-017, which is the approval request. |

This example connects approval status, purchasing and delivery coordination.

### Aviation: optional records-planning example

| Exact question | Expected facts and sources |
|---|---|
| Which log is missing from DEMO-A? | **The propeller log.** [Records Packet](notes/aviation/demo-a-packet.md). The airframe and engine logs are present. |
| Who owns the DEMO-A records request, and when is the planning meeting? | **Mira Holt; 5 October 2026 at 09:30.** [Records Request](notes/aviation/demo-a-request.md) + [Planning Meeting](notes/aviation/demo-a-meeting.md). The request response date is 2 October, not the meeting date. |
| What is DEMO-A's propeller serial number? | **Not recorded.** [Records Request](notes/aviation/demo-a-request.md) explicitly records this gap. REQ-006 is a request identifier, not a serial number. |

DEMO-A is an invented aircraft label. The example connects a records packet, a document request and a maintenance-planning meeting.

### Mycology: optional culinary-research example

| Exact question | Expected facts and sources |
|---|---|
| Which batch supplied OYS-014? | **Synthetic batch PO-26-C.** [Sample Provenance](notes/mycology/oys-014-provenance.md). The sample is fictional culinary oyster mushroom, labelled *Pleurotus ostreatus*. |
| What mass was recorded for OYS-014 on 22 September? | **21.1 g, explicitly a synthetic reading.** [Observation Log](notes/mycology/oys-014-observations.md). The earlier synthetic reading was 18.4 g on 20 September. |
| What is the open research question for OYS-014? | **Whether storage duration correlates with recorded mass change; no conclusion drawn.** [Open Research Question](notes/mycology/oys-014-research-question.md). Two synthetic observations do not establish an effect. |
| What storage temperature was recorded for OYS-014? | **Not recorded.** [Open Research Question](notes/mycology/oys-014-research-question.md) explicitly records this gap. Do not infer a temperature from the species or other measurements. |

Every measurement is invented and labelled synthetic at the top of the source. This example demonstrates sample provenance, dated observations and an unresolved research question.

## Honest missing-evidence behavior

Each pack has an absent fact above. A valid answer acknowledges that the requested value is not recorded; it must not invent one. A citation to a note explicitly stating the gap is appropriate, but it is not evidence for a fabricated value. Before presenting, confirm that other notes in the selected Space do not supply the requested fact.

Related notes may pass retrieval even when the specific fact is absent, so model abstention still needs rehearsal. If the app instead rejects all supplied evidence, its current response is:

> I couldn't find enough evidence in Knowledge to answer that. Add a relevant note or turn Knowledge off to ask from general knowledge.

That response is application-owned and can skip generation. Describe it as the app declining without supporting evidence, not model reasoning or measured answer quality. Do not turn Knowledge off to manufacture a missing value. A refusal to answer a supported question is a failed positive rehearsal, not a successful missing-evidence demonstration.

## Limits and fallback

- Citation taps open the current whole note. Exact-passage highlighting, revision-aware landing and changed-source badges are not established here. Avoid editing or deleting sources during the presentation.
- **Used in this answer** reflects passages included in the prompt. It does not certify every generated claim; compare the answer with the table above.
- Rehearse the installed candidate's folded/unfolded source layout. Fold after a completed answer; this script does not depend on generation or keyboard survival during the transition.
- Markdown avoids PDF extraction and image-import uncertainty. This is not a demo of OCR, bulk import, guaranteed explicit attachments or search across Spaces. Claim an offline run only if it was actually rehearsed offline and recorded.

If generation is too slow, fails or supplies unsupported facts, use **Stop answer** if it is still running. Open the relevant note through Knowledge and show the source directly. Explain the failed step. A checked, saved chat may be shown as an **earlier rehearsal**, never as fresh generation. If the source/fold route is unreliable, keep the working pane visible and browse the notes without another fold. Leave the failed runtime gate open.

## Rehearsal checklist and results template

The device runner records observations here or in linked evidence. Runtime rehearsal results remain **NOT RUN** until observed; the installation identity below is supporting update evidence. Keep technical logs content-free and preserve secure settings; do not require screenshots when protection prevents them.

| Check / measurement | Observed result |
|---|---|
| Candidate commit; installed package; APK identity | `app.skein`, source `28354dd25`, copied installed APK digest verified; [exact update evidence](runs/2026-09-29-workspace-update/status.json). Version alone is not build identity. |
| Model name/file identity; runtime settings | NOT RUN |
| Date, runner, selected Space; network conditions | NOT RUN |
| Existing vault/app data and secure settings preserved | `install -r`; first-install time, data-directory paths and CE/DE inode metadata unchanged; no data clear or security-setting change. Vault content verification after normal unlock remains pending. |
| Construction: three notes imported once; titles/bodies checked | Three import confirmations observed; body inspection and answer rehearsal pending. [UI checkpoint](runs/2026-09-29-ui-checkpoint/README.md). |
| Aviation/mycology: any optional packs imported and checked | All six imported once through the normal picker; all rendered titles/bodies match their fictional source files. |
| Indexing status, including pending semantic work | Aggregate UI reported one document awaiting search by meaning; all-demo readiness unproven. |
| New-chat Knowledge state and same-Space retrieval | Knowledge on verified before both construction sends; Context listed exactly the three fictional construction notes. This does not prove rendered citations. |
| Construction cross-note question: facts and both supporting sources | FAILED visible-answer rehearsal: no text observed through 182.79s; stopped at 226.87s; no answer saved. |
| Construction single-source fallback, if needed; original failure retained | NOT RUN |
| Construction answer: first visible response / completion time | Synthesis did not complete before Stop. Missing-PO answer first observed complete at 70.963s, after blank at 62.736s; exact TTFT/completion unmeasured. |
| Rendered citation or identified Context fallback opens correct source | Actual aviation inline [1] opened the correct Records Packet; full source body verified. The generated answer itself was wrong. |
| Owner's physical unfold: same chat, correct source, usable layout | NOT RUN |
| Construction absent fact: no invented purchase order number | No number invented in the controlled turn; unsupported extra hedge and missing citation prevent whole-answer acceptance. |
| Optional aviation: correct facts/sources and no invented serial number | Live missing-log answer FAILED; correct source retrieved and citation opened it. Serial-number question not run. |
| Optional mycology: synthetic readings identified, no invented temperature | NOT RUN |
| Generation skipped for any refusal, if established by evidence | NOT RUN |
| Stop during generation, then another successful question | NOT RUN |
| Lock/unlock or relaunch, then another successful question | NOT RUN |
| Three consecutive full rehearsals on the same APK/model; failed attempts retained | NOT RUN |
| Full script elapsed time; truthful fallback exercised | NOT RUN |
| Evidence location and remaining failed gates | NOT RUN |

Track remaining work in Beads through the coordinator. This template records rehearsal evidence and does not close `skein-830f` or broader UX, inference or retrieval gates.
