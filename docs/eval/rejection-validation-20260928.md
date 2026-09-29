# Independent relevance validation freeze, 2026-09-28

The evaluation agent independently authored
`testing/src/main/resources/eval/rejection-validation-20260928.json` before the
coordinator began this batch's policy calibration. Its SHA-256 is
`4f79b2ddcf42dedd6b7f83c10402855f683fcebc3045d9e4668f6da2951759e8`.
The author had read the historical failure evidence and existing policy, but
had not received a new candidate policy or calibration result. The coordinator
receives the hash and inventory first, and must freeze policy and parameters
using development data only before reading the new case content or results.
The author does not participate in calibration. This is independently authored
synthetic validation, not a secret or statistically representative benchmark.

The frozen corpus contains twelve NOTE documents in three real Space aliases.
Its twenty-four queries comprise four direct positives, four paraphrases, four
Space-specific positives, eight related-topic questions whose requested facts
are absent, two wrong-Space absence questions and two unrelated negatives.
Contradictory Space pairs name forbidden source IDs. Negative cases include
requested people, dates, quantities and events; topic overlap cannot answer them.
Each positive has one exact answering sentence. Host integrity checks establish
unique IDs, positive span and answer containment, scope consistency and counts;
they are not retrieval measurements.

The initial validation gate is unchanged from the earlier reserved protocol:
all twelve answer spans must be covered and all twelve absence questions must
be rejected, with zero scope/provenance/anchor/determinism violations. Report
recall/nDCG/MRR and category counts separately, using the unchanged 0.75/0.60
ranking thresholds. A ranking pass alone is not a validation pass. Full hybrid
remains ineligible without a production embedder. Use separate fresh encrypted
vaults for production policy and the explicit ungated control, with a warm-up
and three measured repetitions per query.

Run this fixture only after recording a frozen policy source SHA and its
parameters. Evaluate that frozen candidate once and preserve any failure.
Ordinary local integrity checks may inspect labels and source spans, but must
not execute retrieval against these cases before the policy freeze. Later CI
replays are regression checks; once results have been examined, this dataset
must never again be called fresh validation. Do not change its labels, source
text, thresholds or case inventory to improve a measured outcome. The original
public reserved fixture, report and failures remain separate, unchanged evidence.
