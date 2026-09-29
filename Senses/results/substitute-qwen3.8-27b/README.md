# LOCAL SUBSTITUTE RESULT — qwen3.8:27b on llama.cpp b10964 (Metal)

> **Not a reference result.** Kavya's reference model is Gemma 4 E2B (Q4_0, ~2.3B effective, with audio).
> This run uses a model already on this Mac. It is roughly 10× larger and has **no audio projector**.
> Use it to validate the pipeline and the *shape* of the curves, not to predict Gemma 4 or phone numbers.

| | |
|---|---|
| Date | 2026-09-29, MacBook Pro M4 Max 48 GB, macOS 26.6.2 |
| Runtime | `Senses/build/llama.cpp-b10964/bin/llama-server` (Skein's pinned llama.cpp `b29c606e2`), Metal |
| Model | Ollama blob `sha256-f5f1dd89…d57d`: GGUF `qwen35`, "Qwen3.8 27B 0814", 27B, Q4_K_M (file_type 15), Apache-2.0 |
| Projector | Ollama blob `sha256-ac3714bf…448e`: `clip`, `qwen3vl_merger`, 461M, native 768 px, patch 16, merge 2 |
| Command | `llama-server -m <model blob> --mmproj <projector blob> -c 8192 --port 8080 --reasoning off` (same flags as the workshop's `server.start()`) |
| Server bind | **127.0.0.1:8080 only** (verified with `lsof`) |
| Startup | ready in 10 s (weights already in page cache) |
| Raw data | `vision_bench.json` (from `Senses/bench/vision_bench.py`), `llama-server.log` |

## Resolution sweep (nb 2), same frame (`samples/test_card.png`, 1280×720)

| max_side | image+prompt tokens | prefill ms | total s | answer |
|---|---|---|---|---|
| 128 | 35 | 728 | 0.98 | "red square and blue circle" |
| 224 | 55 | 666 | 1.02 | "Pier 48 logo." |
| 384 | 111 | 949 | 1.40 | "Pier 48 logo." |
| 512 (workshop default) | 171 | 1226 | 1.63 | "Red square, blue circle, text." |
| 768 | 363 | 2148 | 2.46 | "Pier 48 logo." |
| 1024 | 603 | 3164 | 3.52 | "Pier 48 logo." |

- **The encoder is variable-resolution:** tokens grow roughly with pixel area (35 → 603). Downscaling is therefore a direct latency lever for this model family.
- There is a ~0.65 s floor at small sizes (encoder + text prompt).
- Decode is flat at **~24 tok/s** (a 27B model on M4 Max), independent of image size.
- llama.cpp warns that Qwen-VL "require[s] at minimum 1024 image tokens … on grounding tasks". Not relevant to these tests, but it matters for pointing/bounding-box use.

## Other workshop behaviours

| Test | Result |
|---|---|
| Text reading ("Read this.") at 512 px / **224 px** | `PIER 48` / `PIER 48`. Correct even at 55 tokens |
| Cold first image | 3.47 s (vs 1.63 s warm at the same size): the encoder warm-up the README warns about |
| nb 1 shapes | correct (red square, blue circle) |
| nb 4 honesty ("whiteboard behind me?") | answered with the visible text "PIER 48". It did not invent a whiteboard, but it did not say "I can't see a whiteboard" either. Partial |
| nb 4 before/after (red card → blue card) | 427 tokens, 5.0 s, answered |
| nb 4 caption call (captions memory) | 2.6 s extra per turn |
| nb 6 colour, JSON-schema constrained | 3/3 correct, all outputs valid JSON, ~1.9 s each; HSV pixels 3/3 correct in <1 ms |
| nb 6 sequence (JSON-schema array) | `["red","blue","red"]`: valid and correct, 1.55 s |

## Kavya's full notebook harness against this real server

`tests/run_notebooks.py` (camera = sample images, mic/whisper faked, speech off, `MUJOCO_GL=cgl`):

| Notebook | Result | Reason |
|---|---|---|
| 1_setup | PASS | |
| 2_eyes | PASS | incl. panel clicks |
| 3_ears | FAIL at bonus cell 17 only | `500: audio input is not supported`. The substitute has no audio projector (expected) |
| 4_agent | PASS | all 8 modes + panel + builds 1–3 |
| 5_omni | FAIL | same: omni requires an audio-capable model (Gemma 4 or Qwen2.5-Omni) |
| 6_hands | PASS | real VLM drives the MuJoCo arm (headless) |

**Conclusion:** EYES + BRAIN + HANDS reproduce on this Mac with Skein's llama.cpp. **EARS (native audio) and real
Whisper are untested**: they need the reference Gemma 4 E2B and the faster-whisper download, both paused on venue wifi.
