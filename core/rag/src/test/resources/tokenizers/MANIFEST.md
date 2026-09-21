# Tokenizer artifact manifest (bd skein-bpt / E5.I2)

Every file in this directory that came from Hugging Face is recorded here with
its repo, the exact revision it was fetched at, its SHA-256 and its license.

**Scope.** Only `tokenizer.json` files are vendored here. Model weights
(GGUF / ONNX) are explicitly **not** part of this bead — acquiring, hashing and
license-recording those is bd **skein-bxk** (E0.I4). Nothing under this
directory is shipped in the APK; these are JVM unit-test resources only.

Fetched on **2026-09-21** with `tools/tokenizers/fetch_tokenizers.sh`, which
re-derives every hash below.

## Redistribution decision

All three source repositories are **Apache-2.0** (confirmed against the Hugging
Face model API's `cardData.license` at the revisions below, and consistent with
`tools/m0-benchmark/models.yaml`, which records `license: apache-2.0` for
`nomic-embed-text-v1.5`, `cross-encoder/ms-marco-MiniLM-L-6-v2` and
`gliner_small-v2.5`). Apache-2.0 permits redistribution with attribution, so the
files are **checked in** rather than fetched at test time, and the tests need no
network and no `@Ignore`-unless-present guard. Attribution is carried in the
repository-root `NOTICE` under "Test-only tokenizer vocabularies".

## Storage form

The files are stored **gzipped** (`gzip -9 -n`, i.e. no name/timestamp in the
header, so the `.gz` is byte-reproducible): 9.5 MB of JSON becomes 2.1 MB in
git. `TokenizerFactory.fromJson` takes an `InputStream`, so the tests simply
wrap the resource in a `GZIPInputStream` — which doubles as proof that the
loader never assumes a seekable file. Both the raw and the gzipped SHA-256 are
recorded so either form can be verified.

## Artifacts

### `nomic-embed-text-v1.5.tokenizer.json.gz`

| | |
|---|---|
| HF repo | `nomic-ai/nomic-embed-text-v1.5` |
| File | `tokenizer.json` |
| Revision | `e9b6763023c676ca8431644204f50c2b100d9aab` |
| License | Apache-2.0 |
| Model type | WordPiece (BERT-uncased, 30 522 entries) |
| Raw size | 711 396 bytes |
| Raw SHA-256 | `d241a60d5e8f04cc1b2b3e9ef7a4921b27bf526d9f6050ab90f9267a1f9e5c66` |
| Gzip SHA-256 | `b825ebe614d1aa9db667ca1bbbefb125b872b5051ab0bac39b79b10e77b26cbb` |

### `ms-marco-MiniLM-L-6-v2.tokenizer.json.gz`

| | |
|---|---|
| HF repo | `cross-encoder/ms-marco-MiniLM-L6-v2` (the canonical target of the `…-L-6-v2` redirect `models.yaml` names) |
| File | `tokenizer.json` |
| Revision | `233902d25c440f23af6f7d6e94d2946bac0bee0a` |
| License | Apache-2.0 |
| Model type | WordPiece (BERT-uncased, 30 522 entries) |
| Raw size | 711 396 bytes |
| Raw SHA-256 | `d241a60d5e8f04cc1b2b3e9ef7a4921b27bf526d9f6050ab90f9267a1f9e5c66` |
| Gzip SHA-256 | `b825ebe614d1aa9db667ca1bbbefb125b872b5051ab0bac39b79b10e77b26cbb` |

> Note: this file is **byte-identical** to nomic's — both checkpoints ship the
> stock `bert-base-uncased` WordPiece vocabulary, hence the identical hashes.
> Both copies are kept anyway so that each pinned artifact stands on its own and
> an upstream divergence shows up as a hash change rather than a silent
> substitution.

### `gliner-small-v2.5-deberta-v3.tokenizer.json.gz`

| | |
|---|---|
| HF repo | `GG-QandV/gliner_small-v2.5-onnx` (the ONNX distribution `models.yaml` pins for the GLiNER entity extractor; base model `gliner-community/gliner_small-v2.5`, itself DeBERTa-v3-small) |
| File | `tokenizer.json` |
| Revision | `a748820c906f7af707a25bb52411b21b999f8de9` |
| License | Apache-2.0 |
| Model type | Unigram (SentencePiece, 128 000 entries) |
| Raw size | 8 332 739 bytes |
| Raw SHA-256 | `08bb5853718f4a829fa9ce773d7984f7f3f6a7073fdc82a07a382675c5061ba6` |
| Gzip SHA-256 | `3755bc1c64e78114834a56d7884d1986c1c95046356660f878f765dcaad9af01` |

## Not fetched

`special_tokens_map.json` and `tokenizer_config.json` were deliberately **not**
fetched: everything the two algorithms need — the added-token table, the
`[CLS]`/`[SEP]` post-processing template, the `unk_token`/`unk_id`, the
continuation prefix and the decoder — is already inside `tokenizer.json`. The
sibling files only carry `transformers`-level convenience metadata this module
does not read.

## Golden fixtures

`golden/*.golden.json` are generated from the files above with Python
`tokenizers`; see `README.md` in this directory for the pinned version and the
exact command.
