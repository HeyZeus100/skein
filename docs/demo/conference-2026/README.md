# Skein conference demonstration

Three fictional domain packs demonstrate asking about saved notes and opening the source. **Construction / General Contractor is the default 2–3 minute demo.** Aviation records and culinary mycology research are optional examples, not extra steps in that time budget. Each pack contains three short Markdown notes with no frontmatter IDs.

**Rehearsal status: NOT RUN.** Durations are planned targets, not measured performance. The UI route was inspected at integration commit `a4ba4f4`; identify and rehearse the installed candidate separately. This pack supports `skein-830f`. It is not an evaluation corpus, benchmark result or acceptance sign-off. Do not add it to evaluation gold or tune retrieval thresholds against it.

The owner needs the Fold ready by **17:00 on 29 September 2026, America/Los_Angeles (PDT)**. The planned **code freeze is 16:00**, followed by final rehearsal on the same APK and model. The owner released the previous Fold HOLD for a verified update and demo checks with existing app data preserved. One designated runner owns all physical-device access.

## Preload before presenting

1. Let the designated device runner handle the authorized update. The owner unlocks the phone and vault when requested and performs physical folds. Preserve app data, screen lock, biometrics and secure settings; do not disable screenshot protection for the presentation.
2. Use the existing selected Space for import and questions. The current Settings Spaces screen is not a create-Space flow; do not assume a new isolated demo Space can be created. Use an existing selector only if it is actually present. Make a model available first: the New chat composer and its import button require one. Do not import a large model during the presentation.
3. Make the three [construction notes](notes/construction/) available through Android's document picker using the agreed transfer route. Optionally prepare [aviation](notes/aviation/) and [mycology](notes/mycology/) too. Titles and bodies identify the content as DEMO/fictional. Expose only the demo notes during presentation; keep personal notes out of view and do not reset the existing vault.
4. In **New chat**, tap the paperclip labelled **Import file to Knowledge**. Choose one `.md` file, wait for **Added … to Knowledge**, then repeat for the other files in the chosen pack. This is a single-file picker. The current Knowledge screen has Search and New note, not a folder-import action.
5. Clear the `[[filename.md]]` text inserted into the composer. Import adds notes to Knowledge; that text does not explicitly attach a source to the next prompt. Avoid importing a pack twice: ordinary imports create notes rather than updating the earlier copies.
6. Open **Knowledge** and confirm the titles and bodies. Wait for active **Preparing for search** work to finish. **Awaiting search by meaning** means semantic work is still pending. Rehearse the exact questions to establish retrieval on this candidate.
7. Return to **Chat / New chat**. New chats default to **Knowledge on**. The first Send creates the chat. In an existing chat, its **Knowledge on/off** chip opens Context, where **Search Knowledge** affects the next message. Keep it on for these examples.

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

The device runner records observations here or in linked evidence. Every result remains **NOT RUN** until observed. Keep technical logs content-free and preserve secure settings; do not require screenshots when protection prevents them.

| Check / measurement | Observed result |
|---|---|
| Candidate commit; installed package/version; APK identity | NOT RUN |
| Model name/file identity; runtime settings | NOT RUN |
| Date, runner, selected Space; network conditions | NOT RUN |
| Existing vault/app data and secure settings preserved | NOT RUN |
| Construction: three notes imported once; titles/bodies checked | NOT RUN |
| Aviation/mycology: any optional packs imported and checked | NOT RUN |
| Indexing status, including pending semantic work | NOT RUN |
| New-chat Knowledge state and same-Space retrieval | NOT RUN |
| Construction cross-note question: facts and both supporting sources | NOT RUN |
| Construction single-source fallback, if needed; original failure retained | NOT RUN |
| Construction answer: first visible response / completion time | NOT RUN |
| Rendered citation or identified Context fallback opens correct source | NOT RUN |
| Owner's physical unfold: same chat, correct source, usable layout | NOT RUN |
| Construction absent fact: no invented purchase order number | NOT RUN |
| Optional aviation: correct facts/sources and no invented serial number | NOT RUN |
| Optional mycology: synthetic readings identified, no invented temperature | NOT RUN |
| Generation skipped for any refusal, if established by evidence | NOT RUN |
| Stop during generation, then another successful question | NOT RUN |
| Lock/unlock or relaunch, then another successful question | NOT RUN |
| Three consecutive full rehearsals on the same APK/model; failed attempts retained | NOT RUN |
| Full script elapsed time; truthful fallback exercised | NOT RUN |
| Evidence location and remaining failed gates | NOT RUN |

Track remaining work in Beads through the coordinator. This template records rehearsal evidence and does not close `skein-830f` or broader UX, inference or retrieval gates.
