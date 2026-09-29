# Reserved relevance-rejection validation

`testing/src/main/resources/eval/rejection-validation.json` is a separate,
independently authored synthetic source/query fixture for `skein-gg11.32`.
The graph-repair agent authored and froze it after graph implementation and
before the coordinator selected a relevance threshold from the development
corpus. The author had not seen raw-score calibration results or a candidate
rejection threshold. No generation holdout was read or reused.

The fixture SHA-256 is
`bf162254094103310102e28ad90b4c945bf24dd7f8f9a706039b2fe3a826b57c`.
Retain these bytes and labels when reporting validation. If a later policy is
tuned using its results, describe subsequent runs as development checks rather
than fresh validation.

Six short NOTE sources cover ceramic firing, bicycle repair, seed lending,
piano maintenance, telescope packing and bread fermentation. There are six
answerable queries (three direct and three paraphrased), three related-topic
queries requesting absent numeric facts, and three no-match queries. Each
positive label identifies an exact answering sentence in one source. The
absent requested facts are electricity cost, bolt torque and membership fee;
topic overlap alone supplies none of them. All documents and queries use the
`default` Space alias, which the runtime harness must resolve to its actual ID.

This is **public reserved validation, not a blind benchmark**. The small corpus
cannot establish general rejection accuracy, ranking quality, model factuality
or full-hybrid eligibility. It complements the unchanged 1,000-document
development evaluation; it does not replace its corpus, gold labels or ranking
thresholds. Report the runtime corpus used with it because raw BM25 scores
depend on the indexed collection.

At fixture freeze, host validation checked unique IDs, six positive exact
source spans, answer containment, Space aliases and the 6/3/3 case counts.
No retrieval policy or device result had been evaluated on this fixture.
