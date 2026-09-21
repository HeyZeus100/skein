# Tokenizer golden fixtures (bd skein-bpt / E5.I2)

`app.skein.core.rag.tokenizers` reimplements WordPiece and Unigram in pure
Kotlin because no permissively-licensed, native-code-free tokenizer library
exists for Android (spec §3 non-negotiables: no third-party tokenizer/NLP
library, FOSS-permissive only). Correctness is therefore proven the only way it
can be: by pinning the reference implementation's output.

`golden/<tokenizer>.golden.json` holds, for each of **300 input strings**:

```json
{"text": "...", "ids": [...], "tokens": [...], "offsets": [[start, end], ...]}
```

plus a `truncation` array with the same shape for `max_length` ∈ {8, 16, 32,
128}. `WordPieceTokenizerTest` and `UnigramTokenizerTest` assert `ids`, `tokens`
**and** `offsets` match exactly — 3 × 300 parity cases and 3 × 40 truncation
cases.

## Corpus

The 300 strings are generated deterministically (`random.Random(20260921)`) and
cover the categories the bead calls for:

| Category | Count | Examples |
|---|---|---|
| Whitespace / degenerate | 12 | `""`, `"   "`, `"\t\n  x  \n"`, `"\u0000� bad"`, zero-width joiners |
| ASCII prose | 60 | ordinary sentences of 1–17 words |
| Accents & diacritics | 45 | every accented sample in both **precomposed and NFD-decomposed** form, plus Greek/Cyrillic/Turkish, ligatures, fractions, full-width forms |
| CJK & other scripts | 45 | Chinese, Japanese, Korean, Thai, Arabic, Hebrew, Devanagari, CJK Extension B |
| Emoji | 30 | single, repeated, ZWJ sequences, flags, skin-tone modifiers |
| Code | 45 | Kotlin, C, SQL, regex, JSON, HTML, shell, URLs, hex/binary literals |
| Very long words / inputs | 30 | 512-char runs, 200-char words, 40–120-word paragraphs |
| Punctuation-heavy / mixed | 33 | smart quotes, em dashes, `[[wikilink\|alias]]`, soft hyphens |

## Offset units

Python `tokenizers` reports offsets as **code point** indices. The generator
converts them to **UTF-16 code-unit** indices (`offset_units: "utf16"` in each
file) so that they can be compared against `Encoding.offsets` with no conversion
on the Kotlin side, and so `text.substring(start, end)` in Kotlin selects
exactly the slice Python's `text[start:end]` selects. The conversion is a pure
bijection over a fixed string — it changes the coordinate system, not the
parity.

## Regenerating

From a scratch directory (not the repo):

```bash
python3 -m venv venv
./venv/bin/pip install 'tokenizers==0.22.1'
./venv/bin/python tools/tokenizers/gen_golden_fixtures.py \
    core/rag/src/test/resources/tokenizers \
    core/rag/src/test/resources/tokenizers/golden
```

**Pinned reference version: `tokenizers==0.22.1`** (the Rust implementation this
module was ported against: `huggingface/tokenizers` tag `v0.22.1`). Regenerating
with a different version may legitimately change the goldens — record the new
version here if you do.

Re-fetching the `tokenizer.json` artifacts themselves (and re-deriving the
hashes in `MANIFEST.md`):

```bash
tools/tokenizers/fetch_tokenizers.sh core/rag/src/test/resources/tokenizers
```

## Known, documented divergences from `tokenizers`

The port is faithful on all 900 fixtures, but two places take a deliberate
shortcut that no fixture exercises and that is worth knowing about:

1. **Added tokens** are matched against the *original* text only. Hugging Face
   keeps a second trie matched against the normalized text for entries whose
   `normalized` flag is true (DeBERTa-v3's `[UNK]` is one). Every added token in
   both of our files is spelled identically before and after its own
   normalizer, so the two are equivalent here.
2. **Unicode normalization alignment** is computed per composition segment (a
   starter plus its combining marks, with Hangul jamo runs kept together for the
   composing forms). Hugging Face uses the `unicode_normalization_alignments`
   crate, which tracks the same thing; segment-wise normalization can only be
   *coarser*, never wrong, and all accent/Hangul/ligature fixtures agree.

A third stage is intentionally unimplemented: **BPE**. It is out of scope for
v1 — the chat LLM tokenizes inside llama.cpp — and `TokenizerFactory` rejects it
loudly rather than half-supporting it.
