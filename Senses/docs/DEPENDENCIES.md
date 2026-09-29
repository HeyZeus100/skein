# Dependency Ledger — Senses lab / Kavya's workshop

Versions are **as resolved in this lab** (`demo/workshop/uv.lock`, 2026-09-29). Upstream floors are from
`pyproject.toml`. "Android" means a realistic path to the Pixel 9 Pro Fold / GrapheneOS build under Skein's
rules (no GMS, no ML Kit, no INTERNET, isolated process).

| NAME | VERSION (floor → resolved) | PURPOSE | SOURCE | LICENSE | PLATFORM | ARM | ANDROID | NETWORK | RUNTIME | SKEIN RELEVANCE |
|---|---|---|---|---|---|---|---|---|---|---|
| **llama.cpp** (`llama-server`, `libmtmd`) | brew HEAD (ref) → **b10964 `b29c606e2`** (lab) | LLM/VLM/audio inference, OpenAI-compatible HTTP, grammar-constrained JSON | github.com/ggml-org/llama.cpp | MIT | macOS/Linux/Windows/Android | Native (NEON, i8mm, SVE; KleidiAI option; Metal/Vulkan) | **Yes**: already Skein's engine (JNI). `tools/mtmd` not yet compiled in Skein | Only for `-hf` downloads (disabled in lab build: `LLAMA_CURL=OFF`) | C++ | **Core.** Same revision as Skein Android |
| faster-whisper | 1.0 → 1.2.1 | ASR (Whisper) | github.com/SYSTRAN/faster-whisper | MIT | desktop | CPU (CTranslate2 NEON) | **No** (Python). Android analogue: whisper.cpp (MIT) via JNI | First model download (HF) | Python + CTranslate2 | Desktop ASR baseline; not portable |
| ctranslate2 | → 4.8.2 | faster-whisper engine | github.com/OpenNMT/CTranslate2 | MIT | desktop | yes | Not practical | no | C++ | indirect |
| onnxruntime | → 1.30.0 | Silero VAD inside faster-whisper | microsoft/onnxruntime | MIT | all | yes | **Yes**: Skein already uses onnxruntime-android 1.27.0 in `:embedder-service` | no | C++ | VAD could run on-device via existing ORT |
| huggingface_hub | 0.23 → 1.33.0 | model downloads | huggingface/huggingface_hub | Apache-2.0 | desktop | n/a | **No**: Skein has no network; models are sideloaded / via Hub APK | **Yes** | Python | lab only |
| opencv-python | 4.8 → **5.0.0.93** | camera capture, resize, JPEG, HSV | opencv/opencv-python | Apache-2.0 (wheel bundles FFmpeg LGPL) | all | yes | OpenCV Android SDK exists, but Skein would use CameraX + native JPEG decode in-process | no | C++ | preprocessing policy transfers, library does not |
| numpy | 1.24 → 2.5.3 | arrays | numpy | BSD-3 | all | yes | n/a | no | Python | lab only |
| sounddevice | 0.4.6 → 0.5.6 (bundles PortAudio on macOS) | mic capture | spatialaudio/python-sounddevice | MIT | desktop | yes | No (Android: AudioRecord) | no | Python + PortAudio | lab only |
| requests | 2.31 → 2.34.2 | HTTP to localhost llama-server | psf/requests | Apache-2.0 | all | n/a | n/a (Skein uses AIDL binder, not HTTP) | localhost only | Python | lab only |
| jupyterlab | 4.0 → 4.6.4 | notebooks UI | jupyterlab | BSD-3 | desktop | n/a | no | **[HYP]** may fetch announcements; not needed to run | Python | lab only |
| ipywidgets | 8.0 → 8.1.9 | control panels | jupyter-widgets | BSD-3 | desktop | n/a | no | no | Python | lab only |
| mujoco | 3.2 → 3.14.0 | simulated arm (nb 6) | google-deepmind/mujoco | Apache-2.0 | desktop | yes | not relevant | no | C + Python | HANDS research only |
| macOS `say` | OS | TTS | Apple | OS | macOS | n/a | Android TextToSpeech (engine-dependent; GrapheneOS ships none by default) | no | OS | MOUTH pattern (sentence streaming) transfers |
| uv | 0.11.14 | env + Python 3.12.13 | astral-sh/uv | MIT/Apache-2.0 | desktop | yes | n/a | install time | Rust | lab only |
| nbclient / nbformat / pyflakes (dev) | → 0.11.0 / 5.11.1 / 4.0.0 | test harness | jupyter | BSD-3 / MIT | desktop | n/a | n/a | no | Python | lab only |
| **device-connect-edge / -agent-tools** (not used by workshop) | 0.2.5 | device discovery/RPC, MCP bridge | github.com/arm/device-connect | Apache-2.0 | Linux/macOS, Python ≥3.11 | yes | **No client** | LAN (Zenoh multicast) or broker | Python | future HANDS reference only |

## Model ledger

| Model | Role | Source @ revision | Files / size | License | Status in lab | Result class |
|---|---|---|---|---|---|---|
| Gemma 4 E2B-it | Kavya default (vision + audio) | `ggml-org/gemma-4-E2B-it-GGUF@b4243c15` | Q4_0 2.841 GB + mmproj Q8_0 0.557 GB | Apache-2.0 | **Paused** (venue wifi) | REFERENCE (pending) |
| faster-whisper base.en | Kavya ASR | `Systran/faster-whisper-base.en@3d3d5dee` | model.bin 0.145 GB | MIT | **Paused** | REFERENCE (pending) |
| Qwen3.8 27B 0814 (Ollama `qwen3.8:27b`) | local vision substitute | Ollama blobs `f5f1dd89…`, `ac3714bf…` | 16.8 GB + 0.93 GB | Apache-2.0 | **Run** | LOCAL SUBSTITUTE (`results/substitute-qwen3.8-27b/`) |
| Gemma 4 E4B-it | Skein's planned v1 vision model | `ggml-org/gemma-4-E4B-it-GGUF@b8093469` | Q4_0 4.591 GB + mmproj Q8_0 0.560 GB | Apache-2.0 | not downloaded | recommended next (most Skein-relevant) |
