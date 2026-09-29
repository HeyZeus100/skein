# Demo controls

These instructions describe the workspace update at source `28354dd25`. Source and screenshot checks have passed; confirm the installed build and physical observations in the [demo results](README.md#rehearsal-checklist-and-results-template).

## One workspace or two

On the unfolded display, use **Show split view** in the workspace header. Select **Left** or **Right**, then open Chat, Knowledge or Graph for that side. The selected side has a filled header button. New chat and the shared navigation act on that selected side.

For a construction demo, open **DEMO: Cedar Cabinet Order** on one side and New chat on the other. Other useful combinations are two different notes, or a note beside Graph. **Swap panes** exchanges the two workspaces, including their selections and drafts. **Hide split view** returns to one visible workspace. A narrow window may show only the active workspace even while the split preference is retained.

Opening a note already being edited on the other side activates its existing workspace. Use two different notes to compare them side by side. This avoids two editors writing to the same note.

## Collapsible lists

The sidebar icon beside a Chat or Knowledge header toggles its list. Its accessible label is **Hide chats / Show chats** or **Hide notes / Show notes**. Collapse the list to give the current conversation or note more room; reopen it to choose another item. This control is separate from **Show split view**.

## Questions with or without Knowledge

In New chat, **Search Knowledge** is available before the first Send. Leave it on for questions about the fictional demo notes. Turn it off for a general question based on the selected model's training, such as:

> Explain the difference between a purchase order and an invoice in two sentences.

This general question is a separate UI probe, not one of the four frozen evaluation questions. It does not use live web search. The local model can be wrong or out of date; verify the actual answer before presenting it.

In an existing chat, tap the **Knowledge on/off** chip to open Context and change **Search Knowledge** for the next message. Switching it off does not erase that conversation's earlier messages. Use a fresh chat with Knowledge off when you want a general question without earlier note-based answers in the conversation.

Do not use Knowledge off to invent a missing project fact. For example, the fictional Cedar purchase-order number is not recorded in the notes.
