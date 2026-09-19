# M0 Benchmark Harness

Purpose: measure Pixel 9 Pro Fold–class hardware performance for the models Skein v1 will ship, so all subsequent architectural decisions cite real numbers rather than estimates.

**Deliverable:** `docs/MEASUREMENTS.md` at repo root, referenced from the design spec.

## What we measure

For each `(model × backend × prompt-length × sampling-config)` cell:

- **Prefill throughput** (tokens/sec) — how fast the model processes the prompt
- **Decode throughput** (tokens/sec) — how fast the model generates output
- **Time-to-first-token** (ms) — cold-load, warm-load, cache-hit variants
- **RSS peak** (MB) — actual RAM used, model + KV cache + runtime
- **Thermal profile** — SoC temperature curve across a 5-min sustained decode; time-to-throttle
- **Battery drain per 10k output tokens**

For embedders:

- Embedding throughput (chunks/sec at 512-token chunks)
- Embedding dimensionality and quality vs baseline (cosine similarity against a reference set)

## Open questions this harness must answer

Every open question from spec §15 must have a data-backed answer at the end of M0:

1. Embedder path — nomic-embed-text-v1.5 via GGUF (llama.cpp) vs ONNX Runtime Mobile
2. Cross-encoder rerank in v1 — fits ≤200 ms budget concurrently with GLiNER + inference?
3. Effective context cap — 16K vs 24K vs 32K on 16 GB Fold with Matryoshka-quantized KV
4. MTP (multi-token prediction) uplift on Qwen 2.5 abliterated — supported? throughput gain?
5. Thermal throttling profile — sustainable inference duration before throttle; adaptive backoff strategy

## Prerequisites

**On the workstation (macOS):**
- Android Platform Tools (`adb`) — `brew install --cask android-platform-tools`
- Python 3.11+ (for result aggregation)
- Git

**On the Pixel 9 Pro Fold (GrapheneOS):**
- USB debugging enabled (Settings → System → Developer options)
- Termux installed (from F-Droid or the GrapheneOS-blessed source)
- Inside Termux: `pkg install clang cmake git ndk-multilib`
- llama.cpp built with `-DGGML_VULKAN=ON -DLLAMA_CURL=OFF` for `arm64-v8a`

**Models to test** (see `models.yaml`):
- Qwen2.5-3B-Instruct-abliterated Q4_K_M GGUF (`huihui-ai`)
- Gemma-4-E4B Q4_K_M GGUF (`unsloth`)
- Gemma-4-E2B Q4_K_M GGUF (`unsloth`)
- nomic-embed-text-v1.5 GGUF
- (optional) Qwen3-Embedding-0.6B ONNX

## Usage

Setup phase (once):

```bash
# 1. Pull models to /sdcard/skein-bench/models on the device
./scripts/pull-models.sh

# 2. Push llama-bench binary + wrapper scripts to the device
./scripts/push-runners.sh
```

Run phase (per test session):

```bash
# Full matrix — ~2 hours on-device
./run.sh --all

# Single cell for iteration
./run.sh --model qwen-2.5-3b-abl --prompt medium-1k --n-gen 256
```

Aggregate:

```bash
# Reads output/*.json → writes docs/MEASUREMENTS.md
python3 collect-results.py
```

## Layout

```
tools/m0-benchmark/
├── README.md                — this file
├── models.yaml              — model manifest with URLs, hashes, sizes
├── prompts/                 — test prompts of varying lengths
│   ├── short-100t.txt
│   ├── medium-1k.txt
│   ├── long-8k.txt
│   └── rag-realistic-4k.txt
├── scripts/                 — device setup helpers
│   ├── pull-models.sh
│   ├── push-runners.sh
│   └── verify-hashes.sh
├── run.sh                   — main orchestration
├── thermal-sampler.sh       — background thermal sampling via adb
├── collect-results.py       — aggregate JSON → MEASUREMENTS.md
└── output/                  — per-run JSON, gitignored
    └── .gitkeep
```

## Ground rules

- Every run captures the full environment: model SHA-256, llama.cpp commit, GrapheneOS version, kernel version, device temperature at start, battery level at start
- Runs must be repeatable — at least 3 runs per cell, report median + IQR
- No numbers reported without a matching JSON in `output/` — every claim in `MEASUREMENTS.md` is auditable
- The Fold must be plugged in and in "performance" thermal mode (screen off, no other apps) during runs; battery-mode runs are a separate labeled column
