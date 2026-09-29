# Senses — local multimodal R&D lab

Experimental lab for giving Skein **eyes, ears, memory, reasoning and hands** offline.
**Not production code:** nothing here is referenced by Gradle, and nothing in Skein's production modules was changed.

Primary reference: Kavya Sri Chennoju's workshop repo `offline-agents@cb1a677` (The AI Conference 2026).

| Path | What |
|---|---|
| `docs/NOTEBOOK_AUDIT.md` | Pre-execution audit of all 6 notebooks (network, processes, security, offline) |
| `docs/KAVYA_ARCHITECTURE.md` | What she actually built + senses decomposition + our abstraction |
| `docs/SKEIN_CONTEXT.md` | Where perception could plug into Skein (read-only survey) |
| `docs/LOCAL_ENVIRONMENT.md`, `docs/LOCAL_MODELS.md` | Mac inventory, reference vs substitute models |
| `docs/DEPENDENCIES.md` | Dependency + model ledger |
| `docs/IMPLEMENTATION_LOG.md` | Every reproduction step, error, and fix |
| `research/kavya/INDEX.md` | Her related work (Device Connect, PyTorch Conf, …) |
| `demo/original/`, `demo/workshop/` | **Local only, git-ignored** (upstream has no license, this repo is public). Recreate via `demo/PROVENANCE.md` |
| `bench/vision_bench.py` | Replays the notebooks' measurement cells against any llama-server |
| `results/` | Benchmark results, labelled REFERENCE or LOCAL SUBSTITUTE |
| `build/` (git-ignored) | llama.cpp built from Skein's pinned submodule, Metal |

## Run the workshop

```bash
cd Senses/demo/workshop
export AGENT_LLAMA_SERVER="$PWD/../../build/llama.cpp-b10964/bin/llama-server"
uv run jupyter lab
```

Rebuild the runtime if `build/` is missing:
```bash
cmake -S third_party/llama.cpp -B Senses/build/llama.cpp-b10964 -DCMAKE_BUILD_TYPE=Release \
  -DGGML_METAL=ON -DLLAMA_CURL=OFF -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF
cmake --build Senses/build/llama.cpp-b10964 -j --target llama-server llama-mtmd-cli llama-bench
```
