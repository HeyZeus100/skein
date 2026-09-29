#!/usr/bin/env python3
"""Review exact foldable testcases and bind immutable report/APK hashes to the checkout."""
import argparse
import hashlib
import json
import re
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

CLASS = "app.skein.foldable.MainActivityFoldableGateTest"
EXPECTED = {
    (CLASS, "gateSurvivesClosedFlatClosedWithoutActivityReplacement"),
    (CLASS, "gateSurvivesOuterLandscapeWithoutExposingNavDisplay"),
}
PROFILES = {
    "pixel_9_pro_fold": "exact Pixel 9 Pro Fold profile",
    "pixel_fold": "Pixel Fold compatibility",
    "7.6in Foldable": "generic deprecated foldable compatibility",
}


def sha256(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def review(repository, expected_sha, profile="pixel_9_pro_fold"):
    source_sha = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=repository, text=True).strip()
    result = {"schema_version": 1, "lane": "foldable-gate", "source_sha": source_sha,
              "control_transport": "guarded host emulator-console fold/unfold and UiAutomation rotation; no physical sensor claim",
              "source_attestation": "host-declared checkout", "apk_attestation": "build outputs; not installed-package attestation",
              "expected_source_sha": expected_sha, "profile": profile, "cases": [], "artifacts": {}, "errors": []}
    result["profile_scope"] = PROFILES.get(profile, "unsupported profile")
    if profile not in PROFILES:
        result["errors"].append("unsupported profile; exact catalog ID required")
    if source_sha != expected_sha:
        result["errors"].append("checkout source SHA differs from dispatched SHA")
    config_path = repository / "build/foldable-evidence/avd-config.ini"
    try:
        config = dict(line.split("=", 1) for line in config_path.read_text().splitlines()
                      if "=" in line and not line.lstrip().startswith("#"))
        config = {key.strip(): value.strip() for key, value in config.items()}
        result["avd_hardware"] = {key: value for key, value in config.items() if key.startswith("hw.")}
        result["artifacts"][str(config_path.relative_to(repository))] = sha256(config_path)
        if config.get("hw.device.name") != profile:
            result["errors"].append("actual AVD hardware ID differs from selected profile")
        if config.get("hw.sensor.hinge") != "yes" or int(config.get("hw.sensor.hinge.count", "0")) < 1:
            result["errors"].append("actual AVD does not declare a hinge sensor")
        if profile == "7.6in Foldable" and config.get("hw.device.manufacturer") != "Generic":
            result["errors"].append("generic compatibility profile has unexpected manufacturer")
    except (OSError, ValueError):
        result["errors"].append("missing or malformed actual AVD config")
    seen = set()
    for path in sorted((repository / "app/build/outputs/androidTest-results/connected").rglob("*.xml")):
        result["artifacts"][str(path.relative_to(repository))] = sha256(path)
        try:
            root = ET.parse(path).getroot()
        except (ET.ParseError, OSError) as error:
            result["errors"].append(f"unreadable XML {path.name}: {type(error).__name__}")
            continue
        for case in root.iter("testcase"):
            identity = (case.get("classname"), case.get("name"))
            tags = [tag for tag in ("failure", "error", "skipped") if case.find(tag) is not None]
            result["cases"].append({"class": identity[0], "name": identity[1], "nonpassing": tags})
            if identity not in EXPECTED or identity in seen or tags:
                result["errors"].append(f"unexpected, duplicate, failed or skipped testcase: {identity}; {tags}")
            seen.add(identity)
    for identity in sorted(EXPECTED - seen):
        result["errors"].append(f"required testcase absent: {identity}")
    for directory in ("app/build/outputs/apk/dev/debug", "app/build/outputs/apk/androidTest/dev/debug"):
        apks = sorted((repository / directory).glob("*.apk"))
        if len(apks) != 1:
            result["errors"].append(f"expected exactly one APK under {directory}, found {len(apks)}")
        for path in apks:
            result["artifacts"][str(path.relative_to(repository))] = sha256(path)
    geometry_path = repository / "build/foldable-evidence/window-geometry.jsonl"
    try:
        rows = [json.loads(line) for line in geometry_path.read_text().splitlines() if line.strip()]
        steps = {row["step"]: row for row in rows}
        expected_steps = {"closed_before", "flat", "closed_after", "outer_portrait", "outer_landscape"}
        if len(rows) != len(expected_steps) or set(steps) != expected_steps:
            result["errors"].append("missing or duplicate runtime geometry steps")
        valid_values = all(type(row.get(key)) is int and row[key] > 0
                           for row in rows for key in ("width_dp", "height_dp", "density_dpi", "activity_identity"))
        if not valid_values:
            result["errors"].append("invalid runtime geometry values")
        valid_windows = all(
            row.get("geometry_observer") == "activity" and type(row.get("orientation")) is int and
            row["orientation"] in (1, 2) and isinstance(row.get("window_bounds_px"), list) and
            len(row["window_bounds_px"]) == 4 and all(type(value) is int for value in row["window_bounds_px"]) and
            row["window_bounds_px"][2] > row["window_bounds_px"][0] and
            row["window_bounds_px"][3] > row["window_bounds_px"][1] for row in rows)
        if not valid_windows:
            result["errors"].append("missing or invalid Activity window metrics/observer")
        if set(steps) == expected_steps and valid_values:
            if (steps["closed_before"]["width_dp"] >= 600 or steps["closed_after"]["width_dp"] >= 600 or
                    steps["flat"]["width_dp"] < 600 or
                    steps["flat"]["width_dp"] <= steps["closed_before"]["width_dp"] or
                    steps["outer_landscape"]["height_dp"] >= 600):
                result["errors"].append("runtime geometry does not meet cover/inner/short-window thresholds")
            for journey in (("closed_before", "flat", "closed_after"), ("outer_portrait", "outer_landscape")):
                if len({steps[step]["activity_identity"] for step in journey}) != 1:
                    result["errors"].append("runtime geometry records Activity replacement within a journey")
            if valid_windows:
                widths = {name: (row["window_bounds_px"][2] - row["window_bounds_px"][0]) * 160 / row["density_dpi"]
                          for name, row in steps.items()}
                heights = {name: (row["window_bounds_px"][3] - row["window_bounds_px"][1]) * 160 / row["density_dpi"]
                           for name, row in steps.items()}
                if (widths["closed_before"] >= 600 or widths["closed_after"] >= 600 or widths["flat"] < 600 or
                        widths["flat"] <= widths["closed_before"] or heights["outer_landscape"] >= 600):
                    result["errors"].append("Activity window metrics do not meet cover/inner/short-window thresholds")
                if (steps["outer_portrait"]["orientation"] != 1 or widths["outer_portrait"] >= heights["outer_portrait"] or
                        steps["outer_landscape"]["orientation"] != 2 or widths["outer_landscape"] <= heights["outer_landscape"]):
                    result["errors"].append("Activity window metrics disagree with requested rotation")
        result["geometry"] = rows
        result["artifacts"][str(geometry_path.relative_to(repository))] = sha256(geometry_path)
    except (OSError, ValueError, KeyError, TypeError):
        result["errors"].append("missing or malformed runtime geometry JSONL")
    # The private-file bridge must complete; XML alone cannot mask a failed host controller.
    run_path = repository / "build/foldable-evidence/console-run-id.txt"
    events_path = run_path.with_name("console-events.jsonl")
    try:
        run_id = run_path.read_text().strip()
        events = [json.loads(line) for line in events_path.read_text().splitlines() if line.strip()]
        result["artifacts"][str(run_path.relative_to(repository))] = sha256(run_path)
        result["artifacts"][str(events_path.relative_to(repository))] = sha256(events_path)
        if not re.fullmatch(r"[0-9a-f]{32}", run_id) or not events:
            raise ValueError("invalid controller run ID/events")
        if any(event["run_id"] != run_id for event in events):
            raise ValueError("controller event run mismatch")
        if events[0]["event"] != "started" or events[-1]["event"] != "stopped":
            raise ValueError("controller did not start and stop cleanly")
        if events[0].get("avd") != "skein_foldable_gate" or not re.fullmatch(r"emulator-[0-9]+", events[0].get("serial", "")):
            raise ValueError("controller target identity mismatch")
        if any(event["event"] not in {"started", "request", "console", "ack", "stopped"} for event in events):
            raise ValueError("controller recorded failure or fallback cleanup")
        # Accept only chronological request -> one command -> one/more identical ACKs.
        cursor = 1
        for sequence in range(1, 7):
            action = "fold" if sequence % 2 else "unfold"
            for kind in ("request", "console", "ack"):
                event = events[cursor]
                if event["event"] != kind or event["sequence"] != sequence or event["action"] != action:
                    raise ValueError("out-of-order console transcript or posture actions")
                cursor += 1
            while cursor < len(events) and events[cursor]["event"] == "ack":
                if events[cursor] != events[cursor - 1]:
                    raise ValueError("changed cached acknowledgment")
                cursor += 1
        if cursor != len(events) - 1:
            raise ValueError("unexpected extra console events")
        requests = [event for event in events if event["event"] == "request"]
        # Both journeys and their @After flat cleanup produce six absolute posture requests.
        if len(requests) != 6 or [request["sequence"] for request in requests] != list(range(1, 7)):
            raise ValueError("missing, duplicate or out-of-order posture requests")
        if len({request["nonce"] for request in requests}) != 6:
            raise ValueError("reused request nonce")
        for request in requests:
            if request["action"] not in {"fold", "unfold"} or not re.fullmatch(r"[0-9a-f]{32}", request["nonce"]):
                raise ValueError("invalid posture request")
            outputs = [event for event in events if event["event"] == "console" and event["sequence"] == request["sequence"]]
            acks = [event for event in events if event["event"] == "ack" and event["sequence"] == request["sequence"]]
            if len(outputs) != 1 or not acks:
                raise ValueError("missing console output/ack or repeated mutation")
            output = outputs[0]
            if output["action"] != request["action"] or output["returncode"] != 0 or output["stdout"].strip() != "OK" or "KO" in output["stderr"]:
                raise ValueError("console command failed")
            for ack in acks:
                if any(ack[key] != request[key] for key in ("run_id", "sequence", "nonce", "action")) or ack["status"] != "ok":
                    raise ValueError("mismatched or failed acknowledgment")
        if sum(event["event"] == "console" for event in events) != 6:
            raise ValueError("unexpected console mutation")
        if any(event["sequence"] not in range(1, 7) for event in events if event["event"] == "ack"):
            raise ValueError("unexpected acknowledgment sequence")
        if requests[-1]["action"] != "unfold":
            raise ValueError("last posture request was not flat cleanup")
        result["console_requests"] = requests
    except (OSError, ValueError, KeyError, TypeError, IndexError) as error:
        result["errors"].append(f"missing or invalid console protocol evidence: {error}")
    result["passed"] = not result["errors"]
    result["remaining_gates"] = (["Pixel 9 Pro Fold profile acceptance"] if profile != "pixel_9_pro_fold" else []) + ["hinge-angle/sensor assertions", "unlocked A-G", "real IME and focus", "system_server heap privacy", "physical Fold A-G acceptance outside this lane"]
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", type=Path, default=Path.cwd())
    parser.add_argument("--expected-sha", required=True)
    parser.add_argument("--profile", choices=tuple(PROFILES), required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = review(args.repository, args.expected_sha, args.profile)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2))
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
