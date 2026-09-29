# Physical workspace checks and first general answer

The owner unlocked and unfolded the device after the installation checkpoint. The sole runner observed Skein on the inner display at 2152 × 2076, rotation 3, using the verified `28354dd25` APK.

## Observed workspace behavior

- Repeated New chat presses opened an empty landing/composer. Search Knowledge was available before the first Send and enabled by default.
- Chat and Knowledge lists collapsed successfully; Chat also reopened and collapsed again.
- The teal crease debug line was absent.
- A note and chat appeared side by side. Swap exchanged them while preserving the selected fictional RFI note and the exact unsent synthetic draft.
- Two distinct fictional notes appeared side by side: Cedar RFI and Delivery Coordination.
- The RFI stayed visible beside Graph, which showed its synthetic node.
- Hiding split and returning to Chat preserved the synthetic draft and its Knowledge setting.

The three previously imported construction notes remained present. No duplicate import or owner-draft edit was made. Root visually reviewed actual captures of the single RFI and two-note split; only fictional content was visible in those captures. Other screenshots containing owner previews remain private.

## Controlled general question

The first attempt has uncertain interaction attribution: the helper stopped before toggling Knowledge, then the next inspection found a sent conversation and an evidence refusal without a recorded runner Send. Its submitted Knowledge state and transition cause are unestablished. That attempt is preserved separately and is not counted as a model failure or successful Knowledge-off test.

The owner then explicitly provided a hands-off window. In a fresh chat, the runner verified Search Knowledge unchecked both before typing and immediately before one controlled Send at **17:03:00.063 UTC**:

> Explain the difference between a purchase order and an invoice in two sentences.

The initial capture showed Working, Knowledge off and Stop, with no answer text. By **17:04:18.801899 UTC**, the captured screen showed the completed answer and an idle composer:

> A purchase order details the items and quantities to be delivered, while an invoice shows the amounts due for each item.

This demonstrates one live general-mode answer with Knowledge off. The requested two-sentence format was not followed. Completion was observed within **78.74 seconds**; this is an observation upper bound, not an exact latency or first-token measurement. No Stop was used for this answer.

## Evidence and limits

| Local observation | SHA256 |
|---|---|
| Actual Fold: single fictional RFI, list collapsed | `7e2fbe02d0b4793084c03b0c2c16b2658b4cf95e61aeeff3edb57e3ed4d96fa3` |
| Actual Fold: two distinct fictional notes | `ea815832257bc4e6c3715610e62958d55cca261803a049c8b935eb92f617c1eb` |
| Actual Fold: controlled general answer | `6bab9ffd8063ff799d6e0182cf6323123d32bb9cd811dc20c980f1a7160bd7ec` |
| Independent general-answer review | `41eab21e4c01d0592b3875f980043d0273deb460db779b2fc98065b773d4d1c6` |

Images and raw execution records remain in the ignored local run directory. This report does not establish Knowledge-on answer quality, citation correctness, generated EOG stopping, offline operation, repeated rehearsal, physical fold/IME transitions or formal Fold acceptance. Those gates remain open.
