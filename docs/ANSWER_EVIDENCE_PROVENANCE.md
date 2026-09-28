# Conversation provenance in automatic retrieval

Automatic Knowledge retrieval excludes CHAT documents before fusion, candidate
capping and final ranking. A prior answer is not reused as evidence merely
because its transcript matches the next question or is connected in the graph.
Notes and imported attachments remain eligible, subject to the existing Space
filter. Drafts never enter the index.

An explicit conversation-history retrieval caller can construct
`RetrievalServiceImpl(includeChatHistory = true)`. This keeps the ordinary Space
boundary and preserves `Retrieved.sourceKind = CHAT`; it does not reclassify a
conversation as a source note. The automatic app pipeline does not enable this
option. No new history-search UI is introduced by this seam.

This is a provenance rule, not a truth or relevance classifier. A note or imported
file can still be wrong, stale or irrelevant. The per-source recall limits still
apply before ranking, so a recalled pool dominated by chats can yield fewer
results after filtering. Measured recall, no-evidence rejection, follow-up
resolution and real embeddings remain separate work under `skein-gg11.32` and
the real retrieval evaluation harness.
