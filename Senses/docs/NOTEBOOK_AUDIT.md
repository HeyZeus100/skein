# Notebook Audit — `offline-agents` (Kavya Sri Chennoju, The AI Conference 2026)

Audit performed **before execution**, by reading every notebook cell and every
Python module. No cell was run to produce this document.

Evidence labels used throughout:

| Label | Meaning |
|---|---|
| **[CODE]** | Verified by reading source at the pinned commit |
| **[DOC]** | Stated in the repo's own README / INSTALL (first-party documentation) |
| **[OBS]** | Our observation (e.g. a discrepancy we noticed) |
| **[HYP]** | Our hypothesis — not yet verified; to be tested in reproduction |

---

## 0. Provenance

| Field | Value |
|---|---|
| Workshop URL | `http://tinyurl.com/offline-agents` |
| Resolves to | `301 → https://github.com/KavyaSriChennoju/offline-agents/archive/refs/heads/main.zip` → `302 → codeload.github.com/.../zip/refs/heads/main` |
| Repository | https://github.com/KavyaSriChennoju/offline-agents |
| Commit | `cb1a677baafd0146140339dc8f222da67b1c2b29` (`refs/heads/main` = `HEAD`, via `git ls-remote`; also embedded as the zip comment) |
| Retrieved | 2026-09-29 |
| Original artifact | `Senses/demo/original/offline-agents-main.zip` (read-only) |
| Zip SHA-256 | `93e6c8804a4f3bb0922141df8aca328fa79170c578b5bbafeec3f0afdca03409` |
| Extracted original | `Senses/demo/original/offline-agents-main/` (read-only, `chmod a-w`) |
| Working copy | `Senses/demo/workshop/` (all changes go here) |
| File dates in zip | 2026-09-28 11:51 (day before retrieval — repo was updated right before/at the workshop) |
| Repo title in README | "Open-Source Multimodal Agents That Run Entirely on Your Laptop (The AI Conference 2026, Day Zero)" — differs from the session title we were given ("How to Build a Multimodal AI System That Runs Entirely on Your Own Hardware") **[OBS]** |
| License | **No LICENSE file in the repository** **[OBS]**. Default copyright applies; treat as "all rights reserved" for redistribution. Studying/running locally is fine; copying code into Skein needs explicit permission or a license. |

### Notebook hashes (SHA-256)

| File | SHA-256 | nbformat | kernelspec | Saved outputs |
|---|---|---|---|---|
| `1_setup.ipynb` | `b6a1312b…4f7688` | 4.5 | python3 | none |
| `2_eyes.ipynb` | `33fdd4b8…3d283` | 4.5 | python3 | none |
| `3_ears.ipynb` | `07dcea4e…f7ac` | 4.5 | python3 | none |
| `4_agent.ipynb` | `a5ff5bf7…b7b5` | 4.5 | python3 | none |
| `5_omni.ipynb` | `0cdcc108…5d0` | 4.5 | python3 | none |
| `6_hands.ipynb` | `4c4f727d…3f3c` | 4.5 | python3 | none |

Full hashes are in `Senses/demo/PROVENANCE.md`. All notebooks were committed **with outputs cleared**, so there
are no presenter reference results (timings, answers) to compare against **[OBS]**.
Hidden "solution" cells carry `{"jupyter": {"source_hidden": true}}` metadata.

**Correction to the brief:** the demo is **not one notebook**. It is **six notebooks**
(4 core + 2 bonus) sharing a small Python package (`agent/`), plus a MuJoCo viewer
script and a test harness **[CODE]**.

---

## 1. Repository inventory

| Path | Role |
|---|---|
| `1_setup.ipynb` … `6_hands.ipynb` | The workshop, in order |
| `config.py` | All knobs + env-var overrides |
| `modes.py` | Shared prompt snippets, `LOOK_WORDS` keyword list |
| `agent/llm.py` | Hand-written OpenAI-compatible client for `llama-server` (no SDK) |
| `agent/server.py` | Downloads GGUF + mmproj from Hugging Face, starts/stops `llama-server` |
| `agent/eyes.py` | OpenCV camera thread, `preprocess()` (resize/crop/grey/JPEG), `how_different()` motion metric |
| `agent/ears.py` | `sounddevice` recording, WAV helpers, `faster-whisper` transcription |
| `agent/mouth.py` | OS TTS (`say` / SAPI / espeak) + `StreamingMouth` sentence-by-sentence speech |
| `agent/brain.py` | `Agent`: modes, when-to-look policy, memory policy, prompt builder |
| `agent/nb.py` | ipywidgets control panels, display helpers |
| `agent/arm.py` | MuJoCo 3-DoF arm, IK, colour detectors, viewer IPC client |
| `hands_viewer.py` | MuJoCo window process exposing a localhost HTTP control API |
| `tests/` | Fake `llama-server`, notebook runner, server/arm/thinking tests |
| `samples/` | `test_card.png` (red square, blue circle, "PIER 48"), `card_{red,green,blue}.png` |
| `pyproject.toml`, `requirements.txt`, `.python-version` (3.12) | Environment |

### Discrepancies found in the repo itself **[OBS]**

1. README says `uv sync` "installs the exact versions pinned in `uv.lock`" — **there is no `uv.lock`** at this commit. Dependencies are floor-pinned only (`>=`). Reproductions will resolve whatever is current; we must record resolved versions ourselves.
2. README says `samples/` includes "one that tries to hijack the model". None of the four PNGs contains injection text (inspected visually). Likely removed or not yet added.
3. `agent/mouth.py` docstring references `CHALLENGES.md` — file does not exist.
4. `pyproject.toml` says `requires-python = ">=3.10,<3.13"`; `.python-version` pins 3.12.

---

## 2. Dependencies declared

From `pyproject.toml` **[CODE]**:

| Package | Floor | Used by | Notes |
|---|---|---|---|
| requests | 2.31 | `llm.py`, `server.py`, `arm.py` | HTTP to localhost servers |
| numpy | 1.24 | everywhere | |
| opencv-python | 4.8 | `eyes.py`, `nb.py`, `arm.py` | camera capture (AVFoundation on macOS via `CAP_ANY`), JPEG encode |
| sounddevice | 0.4.6 | `ears.py` | PortAudio mic capture |
| faster-whisper | 1.0 | `ears.py` | CTranslate2 Whisper; pulls `ctranslate2`, `tokenizers`, `onnxruntime` (Silero VAD), `av` |
| huggingface_hub | 0.23 | `server.py` | model download |
| jupyterlab | 4.0 | notebooks | |
| ipywidgets | 8.0 | `nb.py` panels | |
| mujoco | 3.2 | `arm.py`, `hands_viewer.py` | bonus 6 only |
| dev: nbclient, nbformat, pyflakes | | tests | |

External (non-Python) **[DOC]**: `llama.cpp` (`llama-server`) — required; `uv` — recommended;
Linux-only `libportaudio2`, `espeak-ng`. On macOS TTS uses the built-in `say`.

---

## 3. Environment variables **[CODE]**

| Variable | Default | Effect |
|---|---|---|
| `AGENT_SERVER` | `http://127.0.0.1:8080` | llama-server URL. **Can point at a remote host** (`config.py` docstring shows a LAN IP example) |
| `AGENT_LLAMA_SERVER` | unset | explicit path to `llama-server` binary |
| `AGENT_THINKING` | `0` | `1` lets Gemma 4 / Qwen3 "think" |
| `AGENT_CAMERA` | `0` | webcam index, or image path / folder |
| `AGENT_WHISPER` | `base.en` | faster-whisper model name |
| `AGENT_SPEAK` | `1` | `0` disables TTS |
| `AGENT_ARM_PORT` | `8765` | MuJoCo viewer control port |
| `AGENT_ARM_HEADLESS` | unset | `1` = no MuJoCo window |
| `AGENT_ARM_VIEW` | `window` | `inline` renders arm frames in notebook |
| `MUJOCO_GL` | auto (`egl` on headless Linux) | tests set `osmesa` |

**No API keys, tokens or cloud credentials are read anywhere** **[CODE]**.

---

## 4. Section-by-section audit

Each row: PURPOSE · INPUT · OUTPUT · DEPENDENCIES · MODEL · RUNTIME · NETWORK · FILES · SYSTEM CMDS · HARDWARE · SECURITY · SKEIN RELEVANCE.

### 4.1 `1_setup.ipynb` — "Say hi to the brain"

| Cell(s) | Purpose | Network | Files / processes | Notes |
|---|---|---|---|---|
| 1 | `server.check()` runs `llama-server --version`; `server.download(MODEL)` | **YES** — `HfApi().list_repo_files()` + `hf_hub_download()` from huggingface.co **unless** `models/<name>/` already has a main `.gguf` and an `mmproj*.gguf` | writes `models/<name>/*.gguf` (+ HF `.cache` metadata dir inside it) | Default `MODEL="gemma-4-E2B"` |
| 2 | `server.start()` | none (`-m` local path; binds `--port 8080`, llama-server default host `127.0.0.1` **[HYP: verify]**) | spawns detached `llama-server` (new session / process group); writes `models/llama-server.log`, `models/llama-server.pid` | runs `llama-server --help` to choose `--reasoning off` vs `--reasoning-budget 0`. Server **outlives the kernel**. |
| 4 | Imports, health check | localhost only | | `%autoreload 2` |
| 6, 8, 10–12 | First chat; show the JSON payload; multi-turn "memory = re-send history" | localhost | | Teaching point: the model is stateless |
| 14 | Synthetic test card (red square, blue circle) → vision question | localhost | | First vision call |
| 16 | Camera check | none | opens webcam **(camera permission prompt)** | `Eyes(config.CAMERA)` then `close()` |
| 17 | Mic + Whisper | **YES on first run** — faster-whisper downloads `Systran/faster-whisper-base.en` (~145 MB) into `~/.cache/huggingface/hub` | **microphone permission prompt** | **[HYP]** faster-whisper calls `snapshot_download` on every load; with wifi on it may contact HF even when cached. Verify; mitigate with `HF_HUB_OFFLINE=1`. |
| 18 | TTS | none | spawns `say "..."` | argv list, no shell → no shell injection |

Hardware: ~4 GB RAM headroom for E2B Q4_0 + mmproj + 8k context; Metal on Apple Silicon.
Skein relevance: the "one POST with JSON" client is the whole integration surface; mirrors what an Android
client would send to an on-device inference service.

### 4.2 `2_eyes.ipynb` — "Give it eyes"

| Cell(s) | Purpose | Notes |
|---|---|---|
| 2 | Open camera (or `CAMERA` path) | background thread keeps only newest frame |
| 4 | Show preprocessing pipeline: raw → resize (`max_side`) → JPEG → base64 size | pure OpenCV |
| 6 | Describe the scene with a system prompt constraining to visible content | |
| 8 | **Resolution sweep** 128→1024 px: prints JPEG KB, **prompt tokens**, seconds | Built-in micro-benchmark. Reveals whether the vision encoder is fixed-resolution or variable |
| 10 | `nb.eyes_panel` sliders (max_side, crop, grayscale) | ipywidgets |
| 12–13 | User-written preprocessing (mirror, brightness, 3×3 labelled grid) | Grid overlay = a crude visual grounding technique |
| 15 | `eyes.close()` | only one notebook can hold the camera |

Network: localhost only. Files: `save()` can write `last_seen.jpg` (not called by default here).
Security: camera frames never leave the process except to `127.0.0.1:8080` (unless `AGENT_SERVER` is changed).
Skein relevance: **directly relevant** — image downscaling policy dominates latency/cost on-device.

### 4.3 `3_ears.ipynb` — "Give it ears (and a mouth)"

| Cell(s) | Purpose | Notes |
|---|---|---|
| 2 | Load Whisper (`base.en`), time it | first load downloads |
| 4 | Record 4 s, playback, transcribe, compute **× realtime** | built-in ASR benchmark |
| 6–7 | `talk()`: record → Whisper → LLM (streamed) → `StreamingMouth` speaks per sentence; prints **stop-talking-to-first-word latency** split into whisper vs brain | the key latency decomposition |
| 9 | **Whisper shoot-out** tiny.en / base.en / small.en | **network**: downloads 75 MB / 145 MB / 480 MB on first use |
| 11–13 | Wake-word exercise ("computer") on transcript text | text-level, not acoustic KWS |
| 15 | Per-segment `avg_logprob` → "say that again?" threshold | confidence gating |
| 17 | **Bonus: skip Whisper** — send WAV straight to Gemma 4 via `input_audio` content part; compare with Whisper | requires audio-capable mmproj |

ASR: faster-whisper (CTranslate2, `compute_type="int8"`, `beam_size=1`, `vad_filter=True` → Silero VAD via onnxruntime).
**No VAD for endpointing** — recording is push-to-talk / fixed-duration; VAD only trims silence inside Whisper **[CODE]**.
No speaker diarization, no audio embeddings.
Skein relevance: separate ASR model vs native-audio LLM is exactly the Option A vs B question for Android.

### 4.4 `4_agent.ipynb` — "The agent (and then break it)"

The core notebook. Introduces a **mode** = system prompt + three policies **[CODE]** (`agent/brain.py`):

| Policy | Options |
|---|---|
| **look** (when to attach frames) | `always`, `when_asked` (regex over `LOOK_WORDS`), `never`, `before_after` (snapshot at mode start + now), `burst` (N frames, gap seconds — "a tiny video") |
| **memory** (how history is kept) | `latest_image` (old turns text-only + "[n frames were shown]"), `all_images` (re-send every frame), `captions` (extra non-streamed call writes a one-line caption per frame; stored as `[camera: …]` text) |
| **hide** | regex; matching lines stay in memory but are not shown/spoken (hidden state) |

8 built-in modes: assistant, grumpy art critic, sports commentator (burst), I Spy (hidden `SECRET:`),
what changed? (before/after), charades (burst), fridge chef (captions), memory palace (captions, 20 turns).

| Cell(s) | Purpose | Network / files / risk |
|---|---|---|
| 8 | `peek()` prints exact prompt with image placeholders | — |
| 10 | `nb.agent_panel` — talk (mic) or type | mic + camera |
| 14–16 | **Build 1: LLM router** — replace keyword `wants_to_look` with a YES/NO model call; monkey-patches `Agent.wants_to_look` globally | extra inference per turn |
| 18 | **Build 2: proactive narrator** — polls camera every 0.25 s, `how_different()` threshold + cooldown, 2-frame before/after to the VLM, speaks | continuous camera use for 60 s |
| 20 | **Build 3: "hands" via text protocol** — `ACTION: save_note \| …` / `ACTION: take_photo \| …` lines, parsed by regex, hidden from output, executed by `if/elif` | **writes `notes.txt`** (cwd), **writes `<name>.jpg`** (filename sanitized `[^\w-]→_`, max 40 chars → no path traversal). **Notes are re-injected into the system prompt** each turn. |

**Security implications [CODE + OBS]:**
- **Prompt injection via the camera.** The VLM reads text in frames. A visible sign reading
  `ACTION: save_note | …` could cause an action. Because saved notes are re-inserted into the
  system prompt, an injected note **persists across turns** (stored prompt injection).
- There is **no allowlist beyond the if/elif**, no user confirmation, and no audit log other than `print`.
  Actions are benign here (append a line, save a JPEG), but the pattern does not scale safely.
- `before_after`, `burst` and the narrator loop take camera frames without a per-frame user action.

Skein relevance: very high. The mode/look/memory triad is a clean vocabulary for a perception policy layer;
`captions` memory is a primitive form of the persistent Observation record we want.

### 4.5 `5_omni.ipynb` — "One model, both senses" (bonus)

| Cell(s) | Purpose |
|---|---|
| 4 | 1-s 880 Hz beep → "describe this sound" — capability probe (400/500 = can't hear) |
| 6 | Voice question + current frame in **one request** (`image_url` + `input_audio` + text) |
| 8 | **Race: pipeline vs omni** — Whisper→LLM vs raw audio→LLM, same clip & frame, wall-clock |
| 10 | Paralinguistic experiments: mood, background sounds, language ID/translation, "liar" (speech vs image contradiction) |
| 12–13 | `omni_talk()` — model prefixes `HEARD: <transcript>`; that line becomes text memory; only the newest clip is sent as audio |

Models: Gemma 4 E2B/E4B or Qwen2.5-Omni-3B (SmolVLM cannot hear).
Network: localhost only. Skein relevance: the "model is its own Whisper" pattern (self-transcription into
text memory) is a cheap way to keep an auditable text log without a separate ASR model.

### 4.6 `6_hands.ipynb` — "Give it hands" (bonus)

| Cell(s) | Purpose | Processes / network |
|---|---|---|
| 4 | `arm.open_viewer()` spawns `mjpython hands_viewer.py --port 8765` (macOS) | **second local HTTP server** on `127.0.0.1:8765`, **no auth**: `POST /do {"action":"tap","color":...}`, `GET /state`, `POST /quit` |
| 6–9 | Drive arm directly; inspect closed-form IK | MuJoCo physics, off-screen renderer |
| 13 | **Detector 1:** HSV pixel counting (classic CV) | |
| 15 | **Detector 2:** VLM with `response_format: json_schema` → llama-server **grammar-constrained** output `{"color": red\|green\|blue\|none}` | |
| 17 | `watch()` closed loop: every 1 s decide → require same colour twice (debounce) → press | camera loop |
| 19–21 | Voice → Whisper → keyword → press | mic |
| 23–25 | Sentence → JSON-schema **array** of presses (`maxItems 5`) → execute sequence | structured tool-call plan |

**Security [CODE]:** the viewer validates `action ∈ {tap, home}` and `color ∈ COLORS` server-side (good:
a typed, enumerated action surface), but any local process can POST to it.
Skein relevance: **the most important pattern in the workshop** — constrain the model's action space with a
JSON schema enforced by the sampler, then validate again at the actuator. This is the right shape for
Skein "hands".

---

## 5. Explicit checklist

| Item | Present? | Where |
|---|---|---|
| pip installs inside notebooks | **No** — all deps via `uv sync` / `pip install -r` beforehand | |
| Shell commands in notebooks (`!`/`%%bash`) | **No** — only `%load_ext autoreload` / `%autoreload 2` | |
| Subprocesses from Python | Yes | `llama-server` (server.py), `say` (mouth.py), `mjpython hands_viewer.py` (arm.py) |
| git clones | No (only in README instructions) | |
| Hugging Face downloads | Yes | `server.download()` (GGUF+mmproj), faster-whisper (CTranslate2 Whisper) |
| Other model downloads | No | |
| Remote APIs | **None by default.** `AGENT_SERVER` can be set to a non-local host | config.py |
| API keys | None | |
| Telemetry | None in repo code. **[HYP]** `huggingface_hub` sends a user-agent on download; HF telemetry can be disabled with `HF_HUB_DISABLE_TELEMETRY=1`. JupyterLab: announcements/news fetch may contact jupyter.org unless disabled — verify | |
| Camera access | Yes (OpenCV `VideoCapture`) | 1,2,4,5,6 |
| Microphone access | Yes (`sounddevice`) | 1,3,4,5,6 |
| Image processing | Yes (OpenCV resize/crop/grey/JPEG, HSV masks, frame differencing) | |
| Audio processing | Yes (16 kHz mono float32, WAV encode, RMS, naive resample) | |
| Video processing | **Only pseudo-video**: `burst` = N stills at a fixed gap; no codec/video model | |
| Local servers | `llama-server` :8080, MuJoCo viewer :8765, JupyterLab :8888 | |
| Web servers exposed beyond localhost | **No** — llama-server verified bound to 127.0.0.1 (lsof, entry 010); viewer binds 127.0.0.1 in code | |
| Inference runtimes | llama.cpp (`llama-server`, Metal), CTranslate2 (faster-whisper), onnxruntime (Silero VAD, transitively) | |
| Agent frameworks | **None.** Hand-rolled `Agent` class | |
| MCP | **None** | |
| Function / tool calling | **No OpenAI `tools=` API.** Two home-made patterns: (1) regex `ACTION:` lines; (2) `response_format` JSON-schema constrained decoding | |
| OS commands / app control | Only TTS (`say`) | |
| Device communication | Only the simulated arm over localhost HTTP. **No Arm Device Connect** in this repo | |

---

## 6. What is actually offline? **[CODE] + [HYP]**

| Phase | Needs network? |
|---|---|
| Install (`brew install uv llama.cpp`, `uv sync`) | **Yes** |
| First model download (`server.download`) | **Yes** (~3.4 GB for default) |
| First Whisper load | **Yes** (~145 MB for base.en) |
| Every later run of notebooks 1–6 | **Designed to be No.** All inference is local (llama.cpp + CTranslate2), all servers bind localhost |
| Whisper load with cache present, wifi **on** | **[HYP]** may perform a HEAD request to huggingface.co; test and pin with `HF_HUB_OFFLINE=1` |
| JupyterLab UI | **[HYP]** may fetch news/extension metadata; not required for function |

Verification plan (Phase 5): run the full workshop with `HF_HUB_OFFLINE=1` and with network disabled,
and capture outbound connections (`nettop`/`lsof -i`) during a normal wifi-on run.

---

## 7. Hardware requirements **[DOC]**

| Model option | Target |
|---|---|
| `gemma-4-E2B` (default) | 16 GB RAM / any Apple Silicon |
| `smolvlm2-2.2B` | 8 GB RAM |
| `potato` (SmolVLM-256M) | anything |
| `gemma-4-E4B` | larger machines |
| `qwen2.5-omni-3B` | for notebook 5 |

Plus: webcam, microphone, speakers (all optional — every cell has a file/typed fallback).
