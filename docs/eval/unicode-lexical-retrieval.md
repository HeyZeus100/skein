# Unicode lexical queries and ingest probes

`skein-3q32` uses the linked SQLite FTS5 `unicode61` tokenizer, with the same
zero-option configuration as `chunks_fts`. `IndexStore.lexicalTerms` exposes
its ordered tokens to ingest; both BM25 and repository body search obtain terms
from their owned SQLite connection before quoting them as literal OR queries.
The final retained term keeps the existing prefix behavior. FTS operators,
quotes and punctuation in source text cannot become query syntax.

The native bridge uses FTS5's documented `xFindTokenizer` / `xTokenize` API.
It creates no scratch table, persists no query/source text, and emits no content
in diagnostic messages. Tokenization and MATCH run under existing connection
ownership. Each ingested row still receives at most one row-constrained MATCH,
independent of its global rank. The longest usable token among the bounded
returned terms is sampled. This is not a complete posting-integrity test.

`LexicalQueryLimits` and the native bridge cap input at 65,536 UTF-8 bytes, output
at 128 terms, and individual terms at 512 UTF-8 bytes. Overlong input returns no
terms; overlong tokens are skipped whole. No truncation invents an index token.
Rows consisting only of separators, unsupported/overlong terms, or overlong input
remain unprobed. The fake implementation explicitly approximates Unicode and
cannot establish SQLite tokenizer correctness.

The actual default tokenizer folds simple Latin accents, supports non-Latin
and private-use tokens, and keeps adjacent mixed-script runs together. It does
not perform language-specific segmentation or universal canonical
normalization: the default `remove_diacritics=1` treats precomposed Vietnamese
`ộ` differently from `o` followed by combining marks. Tests preserve this actual
behavior. The synchronous evidence gate uses its own text normalization; this
change does not make that policy equivalent to the SQLite tokenizer or validate
semantic relevance.

New derived chunk text and quoted FTS MATCH parameters use explicit UTF-8
binding with exact Kotlin bytes and byte length, preserving leading U+FEFF,
supplementary Unicode and embedded NUL. Generic repository bindings retain
their historical modified UTF-8 encoding so existing titles, paths, tags and
dangling-link keys still compare equal. This is not a global encoding migration.
The native negative control demonstrates that SQLite's UTF-16 binding strips a
leading BOM; the explicit UTF-8 path preserves derived bytes. The revision
control retains generic binding and verifies unchanged logical source/hash.
An actual old-title equality/prefix control passes with generic binding and
deliberately fails with UTF-8 rebinding, guarding the compatibility boundary. Reads accept standard UTF-8
and the old JNI writer's modified UTF-8 (CESU-8 surrogate pairs and encoded NUL).
The native old-storage control writes those exact bytes into a source revision
and JSON metadata, then checks decoded values, retained revision metadata and
unchanged stored bytes. No stored revisions are rewritten. Historical chunks
whose supplementary postings were built from modified UTF-8 still require normal
re-ingest through the corrected writer; this implementation performs no automatic
historical repair (`skein-5uu2`).

`IndexStoreImplAcceptanceTest` adds seven real-driver contracts: actual tokens
and queries, supplementary/legacy text roundtrips, literal adversarial queries,
healthy Unicode-only ingest, missing insert trigger, missing middle row between
healthy rows, and whole-token/input bounds. The no-ASCII negative controls check
that base rows remain while MATCH fails and a content-free warning is emitted.
The prior crowded-rank, missing-later-row and ASCII/Unicode-boundary regressions
remain. Android execution must be established from the sole runner's actual XML;
compilation alone does not close that gate.

The host helper can be checked without Android or a model:

```sh
mkdir -p build/fts-tokenizer
cc -O1 -g -fsanitize=address,undefined -DSQLITE_ENABLE_FTS5 \
  -Inative/sqlite/amalgamation native/sqlite/amalgamation/sqlite3.c \
  native/sqlite/fts_terms_test.c -lm -o build/fts-tokenizer/test
build/fts-tokenizer/test
```

This compiles the exact vendored tokenizer and checks its tokens against real
FTS postings, bounds and standard/legacy text decoding. It does not establish
Android JNI loading, encrypted-vault behavior or physical-device acceptance.
