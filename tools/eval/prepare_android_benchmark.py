#!/usr/bin/env python3
"""Prepare an opt-in synthetic run locally. Does not invoke ADB, install, or generate."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct

MAX_METADATA = 64 * 1024 * 1024
WIDTHS = {0: 1, 1: 1, 2: 2, 3: 2, 4: 4, 5: 4, 6: 4, 7: 1, 10: 8, 11: 8, 12: 8}


def file_sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def gguf_template_metadata(path):
    """Read raw template bytes with bounded metadata traversal, without loading tensors."""
    size = Path(path).stat().st_size
    with Path(path).open("rb") as stream:
        def read(count):
            if count < 0 or stream.tell() + count > min(size, MAX_METADATA):
                raise ValueError("GGUF metadata exceeds bounded header")
            data = stream.read(count)
            if len(data) != count:
                raise ValueError("truncated GGUF metadata")
            return data

        def number(fmt):
            return struct.unpack("<" + fmt, read(struct.calcsize("<" + fmt)))[0]

        def skip(count):
            if count < 0 or stream.tell() + count > min(size, MAX_METADATA):
                raise ValueError("GGUF metadata exceeds bounded header")
            stream.seek(count, 1)

        def string(keep=False):
            count = number("Q")
            if keep:
                if count > 1024 * 1024:
                    raise ValueError("GGUF metadata string exceeds bound")
                return read(count)
            skip(count)
            return None

        def value(kind):
            if kind in WIDTHS:
                skip(WIDTHS[kind])
            elif kind == 8:
                string()
            elif kind == 9:
                element_type, count = number("I"), number("Q")
                if count > 2_000_000:
                    raise ValueError("GGUF array exceeds bound")
                if element_type in WIDTHS:
                    skip(count * WIDTHS[element_type])
                elif element_type == 8:
                    for _ in range(count):
                        string()
                else:
                    raise ValueError("unsupported GGUF array element type")
            else:
                raise ValueError("unsupported GGUF metadata type")

        if read(4) != b"GGUF" or number("I") != 3:
            raise ValueError("expected GGUF v3")
        tensor_count, metadata_count = number("Q"), number("Q")
        if tensor_count > 1_000_000 or metadata_count > 100_000:
            raise ValueError("GGUF entry count exceeds bound")
        template = None
        for _ in range(metadata_count):
            key, kind = string(True), number("I")
            if key == b"tokenizer.chat_template":
                if kind != 8 or template is not None:
                    raise ValueError("invalid or duplicate chat template metadata")
                template = string(True)
            else:
                value(kind)
        if template is None:
            raise ValueError("GGUF has no embedded chat template")
        return {"template_sha256": hashlib.sha256(template).hexdigest(),
                "template_bytes": len(template), "metadata_end_offset": stream.tell(),
                "template_sha256_provenance": "host GGUF raw tokenizer.chat_template bound by model_sha256"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for flag in ("model", "model-sha256", "model-license", "apk", "fixture", "case-set-sha256",
                 "build-sha", "llama-sha", "tokenizer-overlay-sha256", "run-id", "output-dir"):
        parser.add_argument("--" + flag, required=True)
    parser.add_argument("--device-root", default="/data/user/0/app.skein/files/synthetic-benchmark")
    parser.add_argument("--context-length", type=int, required=True)
    parser.add_argument("--threads", type=int, default=4)
    parser.add_argument("--seeds", default="17")
    parser.add_argument("--case-timeout-ms", type=int, default=180000)
    parser.add_argument("--sampling", help="JSON file; default is bounded greedy smoke configuration")
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,100}", args.run_id):
        parser.error("unsafe run id")
    for value in (args.model_sha256, args.case_set_sha256, args.tokenizer_overlay_sha256):
        if not re.fullmatch(r"[a-f0-9]{64}", value):
            parser.error("full lowercase SHA256 required")
    for value in (args.build_sha, args.llama_sha):
        if not re.fullmatch(r"[a-f0-9]{40}", value):
            parser.error("full source commit SHA required")
    if not re.fullmatch(r"/data/(?:user/\d+|data)/app\.skein/files/synthetic-benchmark", args.device_root):
        parser.error("device root must be dedicated app.skein benchmark storage")
    model = Path(args.model)
    if file_sha256(model) != args.model_sha256:
        parser.error("full public model SHA256 differs from expected")
    metadata = gguf_template_metadata(model)
    fixture = Path(args.fixture)
    fixture_bytes = fixture.read_bytes()
    if len(fixture_bytes) > 8 * 1024 * 1024:
        parser.error("fixture exceeds bound")
    cases = [json.loads(line) for line in fixture_bytes.decode("utf-8").splitlines() if line.strip()]
    if not cases or any("gold" in case for case in cases):
        parser.error("expected gold-free exported cases")
    seeds = [int(seed) for seed in args.seeds.split(",")]
    if not 1 <= len(seeds) <= 10 or len(set(seeds)) != len(seeds) or any(seed < 0 for seed in seeds):
        parser.error("invalid fixed seeds")
    sampling = (json.loads(Path(args.sampling).read_text()) if args.sampling else
                dict(temperature=0.0, top_k=1, top_p=1.0, min_p=0.0,
                     repeat_penalty=1.0, max_tokens=256, stop=[]))
    root = args.device_root
    config = dict(schema_version=1, run_id=args.run_id, engine_path="isolated-apk", mode="supplied_evidence",
                  fixture_file=f"{root}/input/{args.run_id}.jsonl",
                  fixture_sha256=hashlib.sha256(fixture_bytes).hexdigest(), case_set_sha256=args.case_set_sha256,
                  model_file=f"{root}/models/{args.model_sha256}/model.gguf", model_sha256=args.model_sha256,
                  model_size=model.stat().st_size, model_license=args.model_license,
                  template_sha256=metadata["template_sha256"], build_sha=args.build_sha,
                  llama_sha=args.llama_sha, tokenizer_overlay_sha256=args.tokenizer_overlay_sha256,
                  expected_apk_sha256=file_sha256(args.apk), seeds=seeds,
                  sampling=sampling, context_length=args.context_length, threads=args.threads,
                  case_timeout_ms=args.case_timeout_ms)
    output = Path(args.output_dir)
    output.mkdir(parents=True, exist_ok=False)
    (output / "config.json").write_text(json.dumps(config, indent=2) + "\n")
    (output / "input.jsonl").write_bytes(fixture_bytes)
    (output / "model_metadata.json").write_text(json.dumps(metadata, indent=2) + "\n")
    print(json.dumps(dict(config=str(output / "config.json"), cases=len(cases), seeds=seeds,
                          fixture_sha256=config["fixture_sha256"], **metadata)))


if __name__ == "__main__":
    main()
