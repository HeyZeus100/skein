# Kavya Sri Chennoju — Related Work Index

Research date: 2026-09-29. Labels: **[CODE]** verified from code · **[DOC]** verified from first-party
documentation · **[PRES]** presenter statement · **[OBS]** our observation · **[HYP]** our hypothesis.
SHAs were obtained with `git ls-remote` on 2026-09-29 and re-checked by the main session where marked ✓.

## Identity note

Two GitHub accounts exist **[DOC, GitHub API]**:
- `KavyaSriChennoju`: hosts `offline-agents`. Otherwise mostly 2019–2022 coursework and forks.
- `kavya-chennoju` ✓: created 2026-02-09, bio "Staff AI Engineer at Arm". Linked from the Arm Learning Paths. All Arm work lives here.

## Key findings

1. **The workshop demo does not use any of her Arm product work.** `offline-agents` contains no Device Connect, PyTorch, ExecuTorch or vLLM **[CODE]**. The "hands" in the workshop are a local MuJoCo arm over localhost HTTP.
2. **The three Device Connect packages are one monorepo** (`arm/device-connect`, `packages/*`), not three repos. The "SDK" ships as **`device-connect-edge`** **[CODE]**. Repo names like `arm/device-connect-sdk` do not exist **[OBS]**.
3. **"Point. Ask. Answer. Building Vision into AI": NOT FOUND** in any public source reachable (web search, aiconference.com agenda/speakers/dz pages, repo grep). Not attributable **[OBS]**. Ask the presenter directly if it matters.
4. **ExecuTorch / vLLM**: the only public link is two sponsored PyTorch Conference NA 2026 sessions (Oct 20, 2026, i.e. in the future). No code, slides or video yet **[DOC]**.

---

## S0. `offline-agents` — the workshop (PRIMARY)

| Field | Value |
|---|---|
| TITLE | offline-agent ("Open-Source Multimodal Agents That Run Entirely on Your Laptop") |
| AUTHOR | Kavya Sri Chennoju (both commits) **[CODE]** |
| DATE | 2026-09-27 (initial), 2026-09-28 (HEAD) |
| URL | https://github.com/KavyaSriChennoju/offline-agents (via tinyurl.com/offline-agents) |
| TYPE | Workshop repo: 6 Jupyter notebooks + Python package |
| COMMIT | `cb1a677baafd0146140339dc8f222da67b1c2b29` ✓ |
| LICENSE | **None** (no LICENSE file; GitHub API reports none). Treat as all-rights-reserved for reuse |
| PURPOSE | Teach a local camera + voice + VLM assistant, then agents/hands |
| ARCHITECTURE | See `docs/KAVYA_ARCHITECTURE.md` |
| MODELS | Gemma 4 E2B/E4B, SmolVLM2-2.2B, SmolVLM-256M, Qwen2.5-Omni-3B (GGUF), faster-whisper tiny/base/small.en |
| DEPENDENCIES | llama.cpp `llama-server`, requests, numpy, opencv, sounddevice, faster-whisper, huggingface_hub, jupyterlab, ipywidgets, mujoco |
| PLATFORM | macOS / Windows / Linux laptops |
| OFFLINE | Yes after first download **[DOC + CODE]** (verification in progress: IMPLEMENTATION_LOG) |
| DESKTOP | **High**. Runs as-is on Apple Silicon |
| ANDROID | Indirect. Same llama.cpp mtmd path Skein plans (E4.I11); Python/Jupyter layer does not port |
| SKEIN | **High**: input policy, memory policy, constrained-output actions, benchmarks |

## S1. Device-to-Device communication with Device Connect (Arm Learning Path)

| Field | Value |
|---|---|
| AUTHOR | Kavya Sri Chennoju & Annie Tallund (Arm) **[DOC]** |
| DATE | Last updated 2026-07-08; source PR ArmDeveloperEcosystem/arm-learning-paths#3180 (merged 2026-04-22, by kavya-chennoju) |
| URL | https://learn.arm.com/learning-paths/embedded-and-microcontrollers/device-connect-d2d/ |
| TYPE | Arm Learning Path (25 min) |
| REPOSITORY | ArmDeveloperEcosystem/arm-learning-paths `content/learning-paths/embedded-and-microcontrollers/device-connect-d2d/` |
| LICENSE | GitHub reports NOASSERTION for the learning-paths repo |
| PURPOSE | Peer-to-peer device discovery + RPC on one LAN with no infrastructure |
| ARCHITECTURE | Four capabilities: **discovery** (by id/type/capability), **RPC**, **status**, **events** (pub/sub). D2D = Zenoh multicast scouting, no registry, "~50–100 devices". Driver model: subclass `DeviceDriver`; `identity` (`DeviceIdentity`: type, manufacturer, model, description), `status` (`DeviceStatus`: availability, location, health); decorators `@rpc`, `@emit`, `@periodic(interval)`, `@on(event_name, device_type)`; `DeviceRuntime(driver, device_id, allow_insecure)`; client `connect()`, `discover_devices()`, `invoke_device(id, method, params)` **[DOC]** |
| STRUCTURED INTERFACES | `@rpc` derives **JSON Schema from Python type hints** (`device_connect_edge/drivers/decorators.py`: `_python_type_to_json_schema`). Commands are JSON-RPC on subject `device-connect.{tenant}.{device_id}.cmd`; events `…event.{name}`; heartbeats; `…registry` **[CODE]** |
| MODELS | none |
| DEPENDENCIES | `device-connect-edge`, `device-connect-agent-tools`, Python ≥3.11, eclipse-zenoh |
| PLATFORM | Raspberry Pi 5 example; macOS/Linux/Windows dev machines are valid peers **[DOC]** |
| OFFLINE | **Yes**, LAN-only, zero infrastructure **[DOC + CODE]**. But the learning path uses `allow_insecure=True`, meaning **no authentication** **[CODE]** |
| DESKTOP | Direct (pure Python + zenoh wheels) |
| ANDROID | **No support** **[OBS]**. Would need a Kotlin/Rust Zenoh reimplementation of the wire protocol **[HYP]** |
| SKEIN | **Medium (future "hands" / multi-device)**: typed, schema-described capabilities is the right shape for tools. The unauthenticated D2D mode is unacceptable for Skein without its own pairing/trust layer **[OBS]** |

## S2. Deploy multi-network device meshes using Device Connect server and NATS (Arm Learning Path)

| Field | Value |
|---|---|
| AUTHOR | Kavya Sri Chennoju & Annie Tallund (Arm) **[DOC]** |
| DATE | Last updated 2026-08-13; PRs #3268 (merged 2026-05-13), #3299 (merged 2026-05-15) |
| URL | https://learn.arm.com/learning-paths/embedded-and-microcontrollers/device-connect-server/ |
| TYPE | Arm Learning Path |
| PURPOSE | Move from LAN D2D to a registry-backed, secured, multi-network mesh |
| ARCHITECTURE | Persistent registry; distributed state with lease/watch (etcd); multi-network (NAT/cloud); commissioning ("trusted identity before it joins the mesh"); **per-device identity, JWT credentials (NATS) or client TLS certs (Zenoh), role-based ACLs, audit logs** **[DOC]**. Server code: `registry`, `security` (DeviceACL/FunctionACL/EventACL, ACLManager), `state` (etcd3gw), `logging` (MongoDB audit), `portal`, CLIs **[CODE]** |
| SETUP AS WRITTEN | **Hosted portal** `portal.deviceconnect.dev`, `NATS_URL=nats://portal.deviceconnect.dev:4222`. Agent example `StrandsDeviceConnectAgent(model_id="claude-sonnet-4-20250514")` with `ANTHROPIC_API_KEY` **[DOC]** |
| SELF-HOST | `packages/device-connect-server/infra/docker-compose*.yml` (Zenoh/NATS/MQTT variants), `security_infra/` cred/TLS/JWT scripts **[CODE]** |
| OFFLINE | **Only if self-hosted** on the LAN. The tutorial path needs internet (hosted broker + cloud LLM) **[DOC/OBS]** |
| DESKTOP | Docker-based. Heavy for a personal app |
| ANDROID | No |
| SKEIN | **Low now / reference later**: commissioning + per-device ACL + audit is a good *model* for pairing phone ↔ desktop. The infrastructure itself (etcd, MongoDB, NATS) contradicts "don't introduce distributed infrastructure merely because it exists" **[OBS]** |

## S3. `arm/device-connect` monorepo (edge SDK, server, agent-tools)

| Field | Value |
|---|---|
| AUTHOR | Arm Limited. Kavya is a founding committer (initial commit `c7689d0`, 2026-03-18) and release author; top committers are others **[CODE]** |
| URL | https://github.com/arm/device-connect (homepage deviceconnect.dev) |
| COMMIT/TAG | HEAD `84d2447986d4bf10ff0eef3ddca11757af309a21` ✓ (2026-09-15, "query device state with CEL discovery predicates #64"); latest tag **v0.2.5** → `fdaa3861e5b137514d1036f3575bdc685d380508` ✓ (2026-06-13) |
| LICENSE | **Apache-2.0** **[CODE]**; PyPI `device-connect-{edge,server,agent-tools}` 0.2.5, Python ≥3.11 ✓ |
| device-connect-edge | deps: eclipse-zenoh ≥1.0, nats-py, pydantic, nkeys, pyyaml; transports `zenoh_adapter.py`, `nats_adapter.py`, `mqtt_adapter.py`; TLS/mTLS, JWT/NKey, PIN commissioning, per-device ACLs ("active development"); insecure mode logs "Do NOT use this in production!" **[CODE/DOC]** |
| device-connect-agent-tools | **Framework adapters (optional extras):** Strands (`adapters/strands.py`, `strands_agent.py` hard-wired to `AnthropicModel`), LangChain (`StructuredTool`), claude-agent-sdk in-process MCP (`adapters/claude.py`), **FastMCP bridge** `mcp/bridge.py` (`python -m device_connect_agent_tools.mcp`) exposing 4 meta-tools `describe_fleet`, `list_devices`, `get_device_functions`, `invoke_device` to avoid tool explosion; per-device MCP tool names `{device_id}::{function}`; `DeviceToolsServer` for capability generation. **Preferred API:** selector-based `discover("device(category:camera, location:zone-A/*)")`, `invoke("device(x).function(y)", params)`, `invoke_many`, `broadcast`, `subscribe`. D2D auto when `DEVICE_CONNECT_DISCOVERY_MODE=d2d` or Zenoh with no URLs. **No Ollama / llama.cpp / vLLM / ExecuTorch / OpenAI adapter** **[CODE]** |
| OFFLINE | Edge + agent-tools: yes in D2D. The shipped agent adapters default to cloud Claude **[CODE]** |
| DESKTOP | Direct (Python) |
| ANDROID | No |
| SKEIN | **Medium**: (1) the **MCP bridge + selector discovery** keeps tool lists small, which matters for 2–4B local models; (2) schema-from-type-hints matches the JSON-schema-constrained action pattern; (3) framework-agnostic, so a local llama-server tool loop could call `discover`/`invoke` directly **[OBS/HYP]** |

## S4. "Point. Ask. Answer. Building Vision into AI"

**NOT FOUND.** Searched: exact-phrase web search (with Arm / Chennoju / sessionize / YouTube / LinkedIn / The AI Conference),
aiconference.com home, /speakers/, /agenda/, /dz/, agenda.aiconference.com raw HTML, and the offline-agents repo. No
attributable source. **[OBS]** The closest attributable vision prior art is `offline-agents` notebook 2 plus the
presenter bio ("five years at Amazon shipping production vision and multimodal AI systems") **[PRES]**.

## S5. PyTorch Conference North America 2026 (PyTorch / ExecuTorch / vLLM / Device Connect)

| Field | Value |
|---|---|
| SOURCE | pytorch.org/blog "vLLM Sessions at PyTorch Conference North America 2026" (updated 2026-08-28) **[DOC]** |
| SESSIONS | "Sponsored: Hardware-Aware AI: Building Agentic Systems from Cloud to Edge with PyTorch, ExecuTorch" (Oct 20, 2026, 12:20–12:45); "Sponsored: From Prompt to Physical Action: A Live Hardware-Aware AI Demo with PyTorch, ExecuTorch" (Oct 20, 3:55–4:05, Expo Demo Theater). San Jose **[DOC]** |
| ABSTRACT | "PyTorch for model development, ExecuTorch for on-device inference, vLLM for scalable LLM serving, and Arm Device Connect… foundation models reasoning about tasks, invoking edge models, retrieving live sensor data, and coordinating physical devices" **[PRES]** |
| CODE | **None public yet** **[OBS]** |
| ARCHITECTURE | PROMPT → cloud/vLLM reasoning → Device Connect discovery → ExecuTorch edge model → sensor → result → action **[PRES]** |
| SKEIN | Conceptual only. Skein's stack is llama.cpp (not ExecuTorch) and has **no cloud reasoner** by principle. The "reasoner discovers and invokes edge models as tools" idea maps to Skein Desktop ↔ Android capability sharing, not to cloud ↔ edge **[HYP]** |

## S6. The AI Conference 2026 session

| Field | Value |
|---|---|
| TITLE | "How to Build a Multimodal AI System That Runs Entirely on Your Own Hardware" **[DOC]** |
| WHEN/WHERE | 2026-09-29 16:00, Theater 3, Technical track, Day ZERØ (90-minute workshops) **[DOC]** |
| ABSTRACT | Local assistant with live camera + voice, local multimodal LLM, "no internet connection required". Arc: setup/architecture → vision layer → voice layer → response layer (grounding, multi-turn) → stress testing **[PRES]** |
| BIO | "Staff AI Engineer at Arm, building AI infrastructure and tools that connect agents to edge devices. Previously spent five years at Amazon shipping production vision and multimodal AI systems at scale." **[PRES]** |
| NOTE | Repo README uses a different title ("Open-Source Multimodal Agents That Run Entirely on Your Laptop") **[OBS]** |

## S7. Other public work (lower relevance)

| Item | Detail | SKEIN |
|---|---|---|
| strands-labs/robots PR #370 "feat: add Device Connect integration" (merged 2026-06-14), #449 docs | Adds `strands_robots/device_connect/` driver adapters, **layered under existing safety gates (rate limit, human approval, audit)** **[DOC/CODE]**. Repo Apache-2.0, HEAD `8099ff7698e63c73e73c0d959883fc768ace02fb` ✓ (moved since the agent's read) | Medium: a worked example of **permissioned device actions**, the pattern Skein's HANDS needs |
| arm/agent-resources PR #1 (merged 2026-07-09) | YAML registry of Arm resources for agents | Low |
| kavya-chennoju/nandasettle | "Signed, hash-chained escrow primitive for autonomous agents" (MIT NandaHack 2026) | Low (hash-chained audit idea only) |
| ericvh/go9p PR #1 | Device Connect over 9P experiment (open) | Low |
| Arm community blogs | none found authored by her | — |
| ACL 2025 claim (search snippet) | arXiv 2502.18439 author list does **not** include her. **Unverified, not attributed** | — |

## Skein takeaways (ours)

- **Primary prior art for EYES/EARS/BRAIN:** `offline-agents` (S0).
- **Prior art for HANDS (future):** Device Connect's typed, schema-described RPC and its MCP 4-meta-tool bridge (S3), plus strands-robots' safety-gate layering (S7). **Not** the hosted server/portal (S2).
- **Not applicable now:** ExecuTorch/vLLM (no code; different runtime from Skein's llama.cpp).
