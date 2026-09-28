# Stored citation validation

`CitationRecordJson` applies the same marker, cardinality, revision-hash,
locator and excerpt validation when reading and writing v1 records. It also
checks JSON field types and required arrays on read. Invalid records decode to
the existing `Empty` payload so a damaged citation cannot prevent opening the
conversation or become a clickable source. The message text is preserved.

Every newly encoded excerpt receives a BLAKE3 hash. A supplied hash must agree
with the excerpt; decoding a mismatched hash fails closed. `excerptIntegrity`
distinguishes `VERIFIED`, `HASH_MISSING` and `ALTERED`. The wire schema's optional
hash remains readable for older records, but absence is never called verified.
This checks stored excerpt integrity, not factuality or whether a citation
supports the model's claim.

Revision cleanup uses a separate conservative pin parser: a damaged excerpt does
not release any readable document/revision references. An ambiguous or truncated
payload stops that sweep entirely. This may retain extra revisions until the
damaged row is repaired or deleted; it cannot turn invalid citations into evidence.

Pre-003 chunk-ID arrays retain their legacy behavior. Existing imported document
IDs are checked for nonblankness rather than newly requiring canonical UUIDs;
import reminting is tracked separately and must not make old citations unreadable.

The current Message projection maps rejected records to no citation payload.
A distinct altered-source badge and revision-aware source landing still need
the presentation work in `skein-gg11.31`; this codec change does not claim that
UI is complete. No raw record, excerpt or document ID is added to diagnostics.
