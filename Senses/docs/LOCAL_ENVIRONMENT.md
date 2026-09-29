# Local Environment — MacBook inventory (2026-09-29)

Read-only inventory. Nothing was installed during inventory. Private folders (Documents/Desktop/Downloads)
were only filename-searched for model extensions (no hits). Nothing was uploaded.

## Hardware / OS

| Item | Value |
|---|---|
| Model | MacBook Pro, `Mac16,5` |
| Chip | **Apple M4 Max** |
| CPU | 16 cores (12 performance + 4 efficiency) |
| GPU | 40-core, Metal 4 |
| RAM | **48 GB** unified |
| macOS | 26.6.2 (build 25G83), Darwin 25.6.0 |
| Disk | 926 GiB; **~60 GiB free (94% used)** — budget downloads carefully |
| Cameras | MacBook Pro Camera; iPhone Continuity Camera |
| Audio | MacBook Pro Microphone (default in), iPhone Microphone, MacBook Pro Speakers (default out) |

## Tooling found at inventory time

| Tool | Status |
|---|---|
| python3 (default) | 3.14.4 (python.org framework). Also Homebrew 3.14.7, **uv-managed 3.12.13**, system 3.9.6 |
| uv | 0.11.14 (`~/.local/bin/uv`) |
| pip | pip3 26.0.1 (framework 3.14) |
| conda / mamba | absent |
| Homebrew | 7.0.6 — cmake 4.4.3, ffmpeg 8.1.2, mlx 0.32.0 (+ mlx-c), ollama 0.32.14, openjdk@17, android tools |
| Jupyter | absent globally |
| llama.cpp binaries | **absent** (source only: Skein submodule `third_party/llama.cpp` b10964 / `b29c606e2`) |
| Ollama | 0.32.14, server running, 3 VLMs pulled (see LOCAL_MODELS.md) |
| MLX | core 0.32.0 only; **mlx-lm / mlx-vlm / mlx-whisper absent** |
| PyTorch | 2.13.0 only inside an unrelated project venv |
| ExecuTorch | absent |
| ONNX Runtime | 1.27.0 only inside an unrelated project venv |
| whisper.cpp | absent |
| huggingface CLI | absent (library present in some venvs) |
| LM Studio | absent |
| portaudio (brew) | absent (not needed on macOS: the `sounddevice` wheel bundles PortAudio) |
| git-lfs | absent |
| Xcode | Command Line Tools 26.6 only (Apple clang 21); **no Xcode.app** (no `xcodebuild`, no Core ML compile) |
| Java / Android | Temurin 27 default, openjdk@17; adb 1.0.41; SDK at `~/android-sdk` (NDK 27.3, 28.2) |

## What the Senses lab added (all inside `Senses/`, all git-ignored)

| Item | How | Location |
|---|---|---|
| `llama-server`, `llama-mtmd-cli`, `llama-bench` | **Built out-of-tree from Skein's own pinned llama.cpp** (`b29c606e2`, build 10964), `-DGGML_METAL=ON -DLLAMA_CURL=OFF`, AppleClang 21. Submodule source tree verified clean afterwards | `Senses/build/llama.cpp-b10964/bin/` |
| Workshop Python env | `uv sync --group dev` → Python 3.12.13, 120 packages | `Senses/demo/workshop/.venv/` (lock: `Senses/demo/workshop/uv.lock`, list: `Senses/demo/workshop-resolved-packages.txt`) |
| Reference models | workshop's own `server.download()` / `ears.load_whisper()` | `Senses/demo/workshop/models/gemma-4-E2B/`, `~/.cache/huggingface/hub/models--Systran--faster-whisper-base.en` |

Why build rather than `brew install llama.cpp` (the workshop's documented path): it installs nothing
system-wide, it avoids a second llama.cpp runtime, and it makes every benchmark in this lab run on **the exact
llama.cpp revision Skein ships on Android**. That is more useful for Skein than Homebrew's HEAD. `-DLLAMA_CURL=OFF`
makes the binary **incapable of downloading models** (`-hf` will not work), so it is offline by construction.
Deviation from reference: Homebrew would be a newer llama.cpp. This is recorded in IMPLEMENTATION_LOG.

Point the workshop at it without editing code:
```bash
export AGENT_LLAMA_SERVER=/Users/andrewherrera/skein/Senses/build/llama.cpp-b10964/bin/llama-server
```
