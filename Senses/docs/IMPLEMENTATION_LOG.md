# Implementation Log — reproducing Kavya's `offline-agents` on this MacBook

Goal of this phase: **Kavya's demo runs successfully on this MacBook.** No optimisation, no rewrite, no
porting, no Skein integration. Smallest reasonable changes only, all in `Senses/demo/workshop/`.

Format per entry: TIME · ACTION · CELL/SECTION · EXPECTED · ACTUAL · ERROR · DIAGNOSIS · FIX · FINAL RESULT.
Times are local (America/…, from the Mac clock), date 2026-09-29 unless noted.

---

### 001 — Protect existing work
- **Action:** `git status` in `/Users/andrewherrera/skein`
- **Expected:** clean tree, or identify uncommitted work
- **Actual:** only untracked `Untitled/` and `logos/` (pre-existing, unrelated). `Senses/` existed and was empty. The directory is spelled `Senses/` (capital S); on case-insensitive APFS this is the same as `senses/`, so we use the existing directory.
- **Final:** no unrelated files touched.

### 002 — Resolve the workshop URL
- **Action:** `curl -I http://tinyurl.com/offline-agents`
- **Actual:** `301 → github.com/KavyaSriChennoju/offline-agents/archive/refs/heads/main.zip → 302 codeload`. `git ls-remote` HEAD = `cb1a677baafd0146140339dc8f222da67b1c2b29`.
- **Final:** zip saved to `Senses/demo/original/` (SHA-256 `93e6c880…3409`), extracted, made read-only. Working copy is at `Senses/demo/workshop/`. See `Senses/demo/PROVENANCE.md`.

### 003 — Audit before execution
- **Action:** read all 6 notebooks + `agent/*.py` + tests (no execution)
- **Actual:** it is 6 notebooks rather than 1; there is no `uv.lock` despite the README; there is no LICENSE; notebooks have no saved outputs.
- **Final:** `docs/NOTEBOOK_AUDIT.md`, `docs/KAVYA_ARCHITECTURE.md`.

### 004 — Reference model metadata (Hugging Face API, read-only)
- **Actual (default model):** `ggml-org/gemma-4-E2B-it-GGUF@b4243c15`: `gemma-4-E2B-it-Q4_0.gguf` 2.841 GB + `mmproj-gemma-4-E2B-it-Q8_0.gguf` 0.557 GB (the `_pick()` ranking prefers Q8 mmproj and skips `mtp-*` draft heads). Apache-2.0, not gated.
- **Whisper:** `Systran/faster-whisper-base.en@3d3d5dee` `model.bin` 0.145 GB, MIT.

<!-- Reproduction entries continue below -->

### 005 — 16:29 · Build llama-server (runtime)
- **Action:** out-of-tree CMake build of Skein's pinned `third_party/llama.cpp` (`b29c606e2`, build 10964), `-DGGML_METAL=ON -DLLAMA_CURL=OFF`, targets `llama-server llama-mtmd-cli llama-bench` → `Senses/build/llama.cpp-b10964/bin/`
- **Expected:** working binary; submodule untouched
- **Actual:** `version: 0.4.1-dev (build 10964, commit b29c606e2)`, AppleClang 21. The binary supports `--reasoning [on|off|auto]`, so the workshop's `_thinking_flags()` will pick `--reasoning off`. `git -C third_party/llama.cpp status` is clean.
- **Deviation from reference:** Kavya's reference path is `brew install llama.cpp` (newer HEAD). We use Skein's pinned revision instead, and select it with the workshop's own `AGENT_LLAMA_SERVER` env var (no code change).

### 006 — 16:27 · Python environment
- **Action:** `uv sync --group dev` in `Senses/demo/workshop/`
- **Actual:** Python 3.12.13, 120 packages. No upstream `uv.lock` exists, so uv generated one (`workshop/uv.lock`, SHA-256 `0a72ef28…a5d0`). Key resolved versions: faster-whisper 1.2.1, ctranslate2 4.8.2, onnxruntime 1.30.0, **opencv-python 5.0.0.93** (a major version newer than the workshop's floor; watch for API changes), numpy 2.5.3, mujoco 3.14.0, jupyterlab 4.6.4, huggingface-hub 1.33.0. Full list: `demo/workshop-resolved-packages.txt`.

### 007 — 16:32–16:40 · Reference model download
- **Action:** the workshop's `server.download("gemma-4-E2B")` (equivalent to nb 1 cell 1), with `HF_HUB_DISABLE_TELEMETRY=1`
- **Expected:** ~3.4 GB in a few minutes
- **Actual:** **~74 KB/s** measured with curl against huggingface.co (the Mac is on the conference venue network, 10.12.x). The Xet client made almost no progress (~1 MB in 6 min).
- **Diagnosis:** network, not code. At 74 KB/s, 3.4 GB ≈ 13 h.
- **Fix/next:** reordered to fetch whisper base.en (145 MB) first, with Gemma resuming after. Options for the model: better network, the facilitators' USB stick (README: "copy the model folder … into `models/`"), or a labelled local substitute.
- **Final:** PENDING.

### 008 — 16:35–16:45 · Facilitator test suite against the fake llama-server (no model needed)
- **Action:** in an isolated scratch copy (because `run_notebooks.py` writes fake GGUFs into `models/gemma-4-E2B/`, which would fool the real download), ran `tests/fake_llama_server.py` + `tests/run_notebooks.py`, `test_server.py`, `test_thinking.py`, `test_arm.py`
- **First result:** notebooks 1–5 PASS; **6_hands FAIL**: `RuntimeError: invalid value for environment variable MUJOCO_GL: osmesa`
  - **Diagnosis:** the harness does `os.environ.setdefault("MUJOCO_GL", "osmesa")`, but osmesa is Linux-only. MuJoCo on macOS accepts `cgl`/`glfw`.
  - **Fix (env only, no code change):** `export MUJOCO_GL=cgl` → **6/6 PASS**
- **test_server.py FAIL:** "llama-server isn't installed yet"
  - **Diagnosis:** `tests/fake_bin/llama-server` is mode `100644` **in upstream git** (checked via GitHub tree API), so `shutil.which` ignores it. This is an upstream bug that affects every checkout.
  - **Fix:** `chmod +x workshop/tests/fake_bin/llama-server` (mode change only) → **server tests ok**
- **Final:** all facilitator checks pass on macOS 26.6 / M4 Max: 6/6 notebooks with panel clicks, server, thinking, arm (headless viewer API).

### 009 — ~16:50 · Downloads paused (user decision)
- Paused the Gemma 4 E2B and whisper base.en downloads at the user's request (venue wifi ~74 KB/s; whisper had stalled at 2.4 MB). Resume later with: `cd Senses/demo/workshop && uv run python -c "from agent import ears, server; ears.load_whisper('base.en'); server.download('gemma-4-E2B')"`

### 010 — ~17:00 · LOCAL SUBSTITUTE run: qwen3.8:27b (Ollama blobs) on our llama-server
- **Action:** `llama-server -m <ollama model blob> --mmproj <ollama projector blob> -c 8192 --port 8080 --reasoning off`
- **Expected:** loads if Ollama's blobs are standard llama.cpp GGUF
- **Actual:** loaded in 10 s. The GGUF headers are standard (`qwen35` + `clip`/`qwen3vl_merger`). The server binds **127.0.0.1 only** (closes audit [HYP]).
- **Benchmark:** `bench/vision_bench.py` (replays nb 1/2/4/6 measurement cells using the workshop's unmodified `agent` package). Results in `results/substitute-qwen3.8-27b/`.
- **Notebook harness vs real server:** 1, 2, 4, 6 PASS; 3 and 5 FAIL only on audio input (`audio input is not supported`), as expected for a vision-only projector.
- **Final:** EYES/BRAIN/HANDS reproduced (substitute). EARS pending the reference model.
