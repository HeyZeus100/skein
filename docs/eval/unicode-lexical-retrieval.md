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
the original `GetStringUTFChars` path; this is not a global encoding migration.
[AOSP's ART JNI implementation](https://android.googlesource.com/platform/art/+/ef21fb5f2520c604a6d5659452488a93433ee85a/runtime/jni/jni_internal.cc)
emits four-byte supplementary characters while retaining encoded NUL (`C0 80`).
This differs from desktop JNI's six-byte CESU-8 supplementary encoding, as the
[AOSP encoding table](https://android.googlesource.com/platform/libnativehelper/+/refs/tags/android-17.0.0_r1/header_only_include/nativehelper/scoped_utf_chars.h)
explicitly distinguishes. Preserving that original binding protects existing
repository key comparisons without guessing that all Android rows use CESU-8.

Ordinary run `36527953704` at `bcd2b32` retained 276 passing cases and one failed
case: the initial compatibility test forced a CESU-8 title and incorrectly
expected ART's generic query binding to match it. That failure established the
bad test assumption; the XML did not retain the bound bytes. The corrected
control writes a title through the unchanged original JNI path, captures its
actual hex, and checks ART's expected four-byte bytes, exact/prefix lookup and
unchanged stored bytes. Assertion messages include measured hex. A separate
supplementary-plus-NUL key checks the real old/new distinction (`C0 80` versus
`00`): original binding must resolve its own stored key, while explicit UTF-8
rebinding must not. The next ordinary run must establish these new assertions;
local compilation is not runtime evidence.

The native negative control demonstrates that SQLite's UTF-16 binding strips a
leading BOM; the explicit UTF-8 path preserves derived bytes. The revision
control retains generic binding and verifies unchanged logical source/hash.
Reads accept standard UTF-8, ART's encoded NUL, and CESU-8 compatibility inputs.
The separate forced-CESU-8 source revision/JSON control checks decoded values,
retained revision metadata and unchanged stored bytes; it makes no claim that
those bytes came from the Android writer. No stored revisions are rewritten.
Historical posting investigation remains open (`skein-5uu2`), conditional on
actual stored encoding and posting evidence. No evidence currently establishes
that existing Android supplementary postings universally contain CESU-8, and
this implementation performs no automatic historical repair.

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
