# Demo controls

These instructions describe the contextual workspace controls. Confirm the installed candidate and physical observations in the [demo results](README.md#rehearsal-checklist-and-results-template); earlier builds used text banners or two pane-selector icons.

## One workspace or two

The workspace header has **Show/Hide split view** and opposing arrows. A single **Hide/Show chats** or **Hide/Show notes** sidebar icon appears when the active workspace has room for its list beside the content. These compact icons retain full touch targets.

Tap inside a visible workspace, then open Chat, Knowledge or Graph for that side. A short mark along its top edge indicates the active workspace; there is no center divider. New chat and shared navigation act on that workspace. Keyboard focus also selects the workspace it enters, and TalkBack exposes **Activate left workspace / Activate right workspace** actions. Use **Show split view** to display both workspaces together.

For a construction demo, open **DEMO: Cedar Cabinet Order** on one side and New chat on the other. Other useful combinations are two different notes, or a note beside Graph. When both are visible, the arrows are labelled **Swap panes** and exchange the two workspaces, including their selections and drafts. **Hide split view** returns to one visible workspace. The arrows then become **Switch workspace**, bringing the other retained workspace into view.

A narrow or short window shows only the active workspace and disables the split control with the label **Split view needs more space**. **Switch workspace** still reaches either workspace. An enabled split preference is retained and shows both again when enough space returns.

Opening a note already being edited on the other side activates its existing workspace. Use two different notes to compare them side by side. This avoids two editors writing to the same note.

## Collapsible lists

The single sidebar icon in the workspace header toggles the active Chat or Knowledge list. Its accessible label is **Hide chats / Show chats** or **Hide notes / Show notes**. Collapse the list to give the current conversation or note more room; reopen it to choose another item. Each workspace retains its own list preference. This control is separate from **Show split view**.

Where a workspace is too narrow for an adjacent list, the sidebar icon is omitted. Use the existing navigation menu for chat history or **Back** from a note to its notes list. Graph and Settings have no notes/chat sidebar, so they show no sidebar toggle. Standalone screens retain their own header control.

## Questions with or without Knowledge

In New chat, **Search Knowledge** is available before the first Send. Leave it on for questions about the fictional demo notes. Turn it off for a general question based on the selected model's training, such as:

> Explain the difference between a purchase order and an invoice in two sentences.

This general question is a separate UI probe, not one of the four frozen evaluation questions. It does not use live web search. The local model can be wrong or out of date; verify the actual answer before presenting it.

In an existing chat, tap the **Knowledge on/off** chip to open Context and change **Search Knowledge** for the next message. Switching it off does not erase that conversation's earlier messages. Use a fresh chat with Knowledge off when you want a general question without earlier note-based answers in the conversation.

Do not use Knowledge off to invent a missing project fact. For example, the fictional Cedar purchase-order number is not recorded in the notes.
