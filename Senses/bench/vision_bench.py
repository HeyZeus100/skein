"""
Replays the measurement cells of Kavya's notebooks 1, 2, 4 and 6 against whatever
llama-server is running, using the workshop's own agent package (unmodified).

    cd Senses/demo/workshop
    uv run python ../../bench/vision_bench.py --label "SUBSTITUTE qwen3.8:27b" --out ../../results/<dir>

Images come from samples/ so no camera permission is needed and runs are repeatable.
"""
import argparse
import json
import platform
import time
from pathlib import Path

import cv2

from agent import arm
from agent.eyes import preprocess
from agent.llm import LocalLLM, system, user

p = argparse.ArgumentParser()
p.add_argument("--label", required=True)
p.add_argument("--out", required=True)
args = p.parse_args()

llm = LocalLLM()
assert llm.health(), "llama-server not running"
card = cv2.imread("samples/test_card.png")
results = {"label": args.label, "server_model": llm.model_name(), "host": platform.platform(),
           "started": time.strftime("%Y-%m-%dT%H:%M:%S%z"), "runs": []}


def run(name, msgs, **kw):
    r = llm.chat(msgs, stream=kw.pop("stream", False), **kw)
    row = {"test": name, "seconds": round(r.seconds, 3),
           "first_token_s": r.first_token_s and round(r.first_token_s, 3),
           "prompt_tokens": r.prompt_tokens, "tok_per_s": r.tokens_per_s and round(r.tokens_per_s, 1),
           "prompt_ms": r.timings.get("prompt_ms"), "answer": r.text.strip()}
    results["runs"].append(row)
    print(f"{name:<28} {row['seconds']:>7.2f}s  ptok={row['prompt_tokens']!s:>5}  {row['answer'][:70]!r}")
    return r


# nb1: first text answer (streamed, so time-to-first-token is real)
run("nb1 text", [system("You are a helpful assistant running entirely on a laptop. Keep answers short."),
                 user("In one sentence: what are you, and where are you running right now?")],
    stream=True, max_tokens=200, temperature=0.7)

# first image request includes vision-encoder warm-up; kept as its own row on purpose
run("nb2 cold first image", [user("Describe this image in one sentence.", images=[preprocess(card)])],
    max_tokens=60, temperature=0)

# nb1 test card (Kavya's synthetic card, drawn exactly as in the notebook)
import numpy as np
synth = np.full((300, 400, 3), 255, np.uint8)
cv2.rectangle(synth, (40, 60), (180, 240), (0, 0, 220), -1)
cv2.circle(synth, (290, 150), 70, (220, 80, 0), -1)
run("nb1 shapes", [user("What two shapes are in this image, and what colour is each?", images=[preprocess(synth)])],
    max_tokens=60, temperature=0)

# nb2 sweep: same frame, six sizes
for side in (128, 224, 384, 512, 768, 1024):
    run(f"nb2 sweep {side}px", [user("What is the main object in this image? Five words max.",
                                     images=[preprocess(card, max_side=side)])], max_tokens=30, temperature=0)

# reading text (the "Read this." requirement) at the workshop default and at the smallest size
for side in (512, 224):
    run(f"read text {side}px", [user("Read any text in this image exactly. Output only the text.",
                                     images=[preprocess(card, max_side=side)])], max_tokens=20, temperature=0)

# nb4 honesty prompt: ask about something that is not there
run("nb4 honesty", [system("You are looking through a webcam. If something isn't visible in the image, say you "
                           "can't see it. Never guess text you can't read."),
                    user("What's written on the whiteboard behind me?", images=[preprocess(card)])],
    max_tokens=60, temperature=0)

# nb4 'what changed?' before/after
red, blue = cv2.imread("samples/card_red.png"), cv2.imread("samples/card_blue.png")
run("nb4 before/after", [system("Image 1 is the scene BEFORE. Image 2 is NOW. Work out what changed."),
                         user("What changed?", images=[preprocess(red), preprocess(blue)])],
    max_tokens=60, temperature=0.3)

# nb4 captions memory (the extra call per turn)
run("nb4 caption", [user("Describe this webcam frame in one short line: main objects and where they are.",
                         images=[preprocess(card)])], max_tokens=40, temperature=0)

# nb6: pixels vs constrained VLM on the three cards
for c in arm.COLORS:
    frame = cv2.imread(f"samples/card_{c}.png")
    t0 = time.perf_counter()
    px, _ = arm.color_by_pixels(frame)
    px_s = time.perf_counter() - t0
    got, r = arm.color_by_llm(llm, preprocess(frame))
    row = {"test": f"nb6 colour {c}", "expected": c, "pixels": px, "pixels_s": round(px_s, 4), "llm": got,
           "seconds": round(r.seconds, 3), "prompt_tokens": r.prompt_tokens, "answer": r.text.strip()}
    results["runs"].append(row)
    print(f"{row['test']:<28} {row['seconds']:>7.2f}s  pixels={px} llm={got} raw={r.text.strip()!r}")

# nb6 sequence plan with a JSON-schema array
schema = {"type": "object", "properties": {"sequence": {"type": "array", "maxItems": 5,
          "items": {"type": "string", "enum": list(arm.COLORS)}}}, "required": ["sequence"]}
r = run("nb6 sequence", [user("Turn this into a list of button presses: Press red, then blue, then red again.")],
        max_tokens=60, temperature=0,
        response_format={"type": "json_schema", "json_schema": {"name": "sequence", "schema": schema}})
results["runs"][-1]["valid"] = json.loads(r.text).get("sequence") == ["red", "blue", "red"]

out = Path(args.out)
out.mkdir(parents=True, exist_ok=True)
(out / "vision_bench.json").write_text(json.dumps(results, indent=2))
print("wrote", out / "vision_bench.json")
