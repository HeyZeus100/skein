# Local Models — inventory and reference/substitute mapping (2026-09-29)

Discovery was limited to model caches, developer directories, and the Skein repo and worktrees. No hashing
of large files was done (GGUF headers were read for metadata only).
Caches present: `~/.cache/huggingface/hub` (459 MB), `~/.ollama/models` (40 GB). All other standard locations
(LM Studio, llama.cpp cache, `~/models`, whisper cache, torch, mlx) are absent.

## 1. Pre-existing models

### Vision-language (Ollama store — `registry.ollama.ai/library`, all Q4_K_M, Apache-2.0)

| Name | Blob path | Params / arch | Size | Runtime compatibility | Notes |
|---|---|---|---|---|---|
| `qwen3-vl:30b` | `~/.ollama/models/blobs/sha256-b1da6f96…b190` | 31.1B MoE, `qwen3vlmoe` | 19.6 GB | Ollama. **Not llama-server** (vision baked into one blob, no mmproj) | vision, tools, thinking |
| `qwen3-vl:8b` | `~/.ollama/models/blobs/sha256-ed12a467…8c55` | 8.8B, `qwen3vl` | 6.1 GB | Ollama only (single blob) | vision, tools, thinking |
| `qwen3.8:27b` (tag as listed by Ollama) | model `sha256-f5f1dd89…d57d` (16.8 GB) + projector `sha256-ac3714bf…448e` (931 MB) | 27.3B, GGUF arch `qwen35` | 17.7 GB | Ollama; **[HYP] possibly llama-server** with `-m <blob> --mmproj <blob>` since it has a separate CLIP projector | the only local model with a standalone projector |

### Text LLMs (GGUF)

| Name | Path | Params | Quant | Size | Purpose / source | License |
|---|---|---|---|---|---|---|
| Qwen2.5-1.5B-Instruct | `~/skein-worktrees/conference-streaming-20260929/build/agent-logs/alternate-model/…/qwen2.5-1.5b-instruct-q4_k_m.gguf` | 1.5B | Q4_K_M | 1.12 GB | Skein alternate-model candidate; HF `Qwen/Qwen2.5-1.5B-Instruct-GGUF` | Apache-2.0 |
| Qwen2.5-3B-Instruct-abliterated | `~/skein-worktrees/conference-demo-20260929/build/agent-logs/fold/…/Qwen2.5-3B-Instruct-abliterated-Q3_K_M.gguf` | 3B | Q3_K_M | 1.59 GB | Skein Fold smoke-test model (pulled from device); HF `huihui-ai/…` | Apache-2.0 |
| SmolLM2-135M (tiny.gguf) | `skein/app/src/androidTest/assets/tiny.gguf` (+ copies) | 135M | Q2_K | 84 MB | Android instrumentation fixture only | Apache-2.0 |

### Audio

| Name | Path | Format | Notes |
|---|---|---|---|
| whisper-small (MLX) | `~/.cache/huggingface/hub/models--mlx-community--whisper-small-mlx` | MLX `.npz` fp16, 481 MB, ~244M params | ASR. **No runtime installed** (`mlx-whisper` absent). MIT |

### Vision (non-LLM, unrelated project)

MediaPipe Pose Landmarker lite (`.task`, 5.8 MB), RTMDet person detector (`.pth`, 99 MB) and RTMW3D-L 3D pose
(`.pth`, 231 MB) live in an unrelated project directory under `~/git/`. They are pose models only and play no role in the workshop.

### Not usable

Vocab-only GGUF fixtures (0 tensors) in `skein/third_party/llama.cpp/models/ggml-vocab-*.gguf` (gemma-4, qwen35,
nomic-bert-moe, …) and their worktree copies.

### Absent

No Gemma 3/3n/4 weights, no Qwen2.5-VL / Omni, no SmolVLM / LFM2-VL / Moondream, **no standalone mmproj GGUF**
(outside Ollama's blob store), no whisper.cpp `ggml-*.bin`, no Moonshine, no Kokoro/Piper TTS, and **no embedding model**.

## 2. Reference vs local substitute (Model Reuse Policy)

| Role | REFERENCE MODEL (Kavya) | Installed? | LOCAL SUBSTITUTE | Decision |
|---|---|---|---|---|
| Brain + eyes + ears (default) | `ggml-org/gemma-4-E2B-it-GGUF` @ `b4243c15`: `gemma-4-E2B-it-Q4_0.gguf` (2.841 GB) + `mmproj-gemma-4-E2B-it-Q8_0.gguf` (0.557 GB). Apache-2.0 | No | None equivalent. The nearest are `qwen3-vl:8b` (can't run in llama-server; different family, 2.6× larger, no audio) and `qwen3.8:27b` (8× larger, no audio) | **Download reference** (3.4 GB). It defines the reproduction's behaviour and latency, it is the only option exercising native audio (nb 3 bonus, nb 5), and Skein's own plan lists Gemma 4 E2B/E4B. Substitutes would change every result |
| ASR | `Systran/faster-whisper-base.en` @ `3d3d5dee` (145 MB, CTranslate2, MIT) | No | `whisper-small-mlx` — different size (small, not base), different runtime (MLX, not installed), same weights family | **Download reference** (145 MB). Too small to justify a substitution that also changes the runtime |
| Small-RAM option | `SmolVLM2-2.2B-Instruct` Q4_K_M + mmproj Q8 (~1.7 GB) | No | — | Not needed on 48 GB. Skip unless benchmarking the size ladder |
| Tiny option (`potato`) | `SmolVLM-256M-Instruct` Q8_0 + mmproj (~0.28 GB) | No | — | Optional, cheap. Useful as a phone-class lower bound |
| Big option | `gemma-4-E4B-it` Q4_0 (4.59 GB) + mmproj Q8 (0.56 GB) | No | — | **Recommended later**: E4B Q4_K_M is Skein's planned v1 vision model (E4.I11). Worth benchmarking once E2B works |
| Omni (nb 5) | `Qwen2.5-Omni-3B` Q4_K_M (2.1 GB) + mmproj Q8 (1.54 GB) | No | Gemma 4 E2B also hears (per workshop) | Use Gemma 4 E2B first. Download only if the Qwen comparison is needed |

**Local substitute experiments (optional, clearly labelled):** `qwen3.8:27b` via llama-server with its Ollama
projector blob, run as a **LOCAL SUBSTITUTE RESULT**, never mixed with reference results.
