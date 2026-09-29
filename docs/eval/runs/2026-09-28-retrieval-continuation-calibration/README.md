# Development decision before independent validation

The independently authored new fixture was frozen in agent commit `4423d1a`
with SHA-256 `4f79b2ddcf42dedd6b7f83c10402855f683fcebc3045d9e4668f6da2951759e8`
before these experiments. The coordinator has not read or run that fixture.
Original public reserved failures were not replayed or used to choose parameters.

**Production stays `lexical-query-coverage-v1`, minimum coverage 0.5.**
The proposed `lexical-fact-shape-v2-experimental` is explicitly disabled by
production constructors. Its frozen experimental parameters are ordinary
coverage 0.5, typed-value coverage 0.25, two shared terms for the relaxed path,
8,192 query characters and 32,768 source characters. No ranking, source text,
source scores, gold labels or acceptance thresholds are changed.

The prototype requires a money, duration or count-shaped value within the
lexically supporting sentence for recognized English question forms. It passes
all 16 coordinator-authored development controls at 0.25, including three
paraphrases lost at 0.5. Replaying the preserved **ungated development** report
keeps default recall/nDCG at 0.800000/0.717357, rejects 16/16 absence queries and
falsely rejects 12/60 answerable queries. Lexical-only retains the existing lost
incidental semantic answer (36/60 versus ungated 37/60); no recovery is claimed.

**The prototype is rejected for production on development evidence.** A second
agent, without reading either reserved fixture, independently supplied six
counterexamples. All six falsify it at the selected 0.25: it borrows an unrelated
item's price or a length as money, and rejects physical-length questions,
heading-separated evidence, abbreviated duration units and prose quantities.
No regex patches or threshold changes were made to fit these counterexamples.
Sentence proximity does not bind an amount to the requested attribute. The
experiment is retained for explicit diagnostic comparison, not quality acceptance.

The earlier exploratory replay before unitless number assertions lost twelve
budget answers expressed in synthetic credits; it is retained separately as
`rejected-initial-development-replay.json`. It was rejected using development
data, not independent validation. The final replay script requires exact
query/mode inventories, original gold hash, ungated results and no vectors.
The scripts are projections over original results, not new runtime measurements.
Kotlin uses UTF-16 bounds; Python uses codepoint bounds. These bounds are not
exercised by the ASCII development cases, so no general replay parity is claimed.

The production policy and this falsified experimental candidate are frozen for
one fresh validation execution each (with deterministic repetitions in that
execution), alongside the ungated control. Both original and new validation
results must remain separate; future iteration requires a newly frozen set.
The strict production quality gate must not count experimental or control results
as a substitute for production acceptance. Full hybrid remains ineligible.
