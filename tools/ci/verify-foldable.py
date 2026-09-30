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
    sdk_path = repository / "build/foldable-evidence/sdk-runtime-receipts.json"
    try:
        sdk = json.loads(sdk_path.read_text())
        image_dir = "system-images/android-35/google_apis/x86_64"
        if (type(sdk["schema_version"]) is not int or sdk["schema_version"] != 1 or
                sdk["attribution"] != "host SDK files, not emulator process attestation or original failed image equality" or
                sdk["avd_config_sha256"] != sha256(config_path) or
                sdk["image_sysdir"] != config["image.sysdir.1"] or
                sdk["image_sysdir"].rstrip("/") != image_dir):
            raise ValueError("SDK/config attribution mismatch")
        files = sdk["files"]
        paths = {row["path"] for row in files}
        payloads = sdk["system_image_payloads"]
        required = {f"{image_dir}/{name}" for name in ("system.img", "vendor.img", "ramdisk.img")}
        metadata = {"emulator/source.properties", "emulator/emulator",
                    "emulator/qemu/linux-x86_64/qemu-system-x86_64", f"{image_dir}/source.properties"}
        if (len(paths) != len(files) or len(set(payloads)) != len(payloads) or
                not required <= set(payloads) or not metadata <= paths or not set(payloads) <= paths or
                not any(path.startswith(f"{image_dir}/kernel-ranchu") for path in payloads)):
            raise ValueError("missing or duplicate raw system-image payload receipts")
        if any(not path.startswith(image_dir + "/") or "/" in path[len(image_dir) + 1:] or
               not (path.endswith(".img") or path.startswith(image_dir + "/kernel-ranchu")) for path in payloads):
            raise ValueError("unexpected system-image payload path")
        if any(type(row["bytes"]) is not int or row["bytes"] <= 0 or
               not re.fullmatch(r"[0-9a-f]{64}", row["sha256"]) for row in files):
            raise ValueError("invalid SDK file hash/size")
        result["artifacts"][str(sdk_path.relative_to(repository))] = sha256(sdk_path)
        result["sdk_attribution"] = sdk["attribution"]
    except (OSError, ValueError, KeyError, TypeError):
        result["errors"].append("missing or malformed SDK/image payload receipts")
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
        ready_request, ready_ack = events[1:3]
        ready_fields = {key: ready_request[key] for key in ("run_id", "sequence", "nonce", "action")}
        if (ready_request["event"] != "request" or ready_fields["sequence"] != 0 or
                ready_fields["action"] != "ready" or not re.fullmatch(r"[0-9a-f]{32}", ready_fields["nonce"])):
            raise ValueError("missing nonce-bound readiness request before posture")
        if ready_ack != {"event": "ack", **ready_fields, "status": "ok"}:
            raise ValueError("missing matching readiness ACK")
        ready_path = run_path.with_name("console-ready.json")
        if json.loads(ready_path.read_text()) != {**ready_fields, "status": "ok"}:
            raise ValueError("instrumentation did not consume the matching readiness ACK")
        result["artifacts"][str(ready_path.relative_to(repository))] = sha256(ready_path)
        result["transport_readiness"] = {**ready_fields, "instrumentation_consumed_ack": True}
        cursor = 3
        while cursor < len(events) and events[cursor]["event"] == "ack":
            if events[cursor] != ready_ack:
                raise ValueError("changed cached readiness acknowledgment")
            cursor += 1
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
        requests = [event for event in events if event["event"] == "request" and event["action"] != "ready"]
        # Both journeys and their @After flat cleanup produce six absolute posture requests.
        if len(requests) != 6 or [request["sequence"] for request in requests] != list(range(1, 7)):
            raise ValueError("missing, duplicate or out-of-order posture requests")
        if len({request["nonce"] for request in [ready_request, *requests]}) != 7:
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
        if any(event["sequence"] not in range(0, 7) for event in events if event["event"] == "ack"):
            raise ValueError("unexpected acknowledgment sequence")
        if requests[-1]["action"] != "unfold":
            raise ValueError("last posture request was not flat cleanup")
        result["console_requests"] = requests
        command_path = run_path.with_name("console-commands.jsonl")
        commands = [json.loads(line) for line in command_path.read_text().splitlines() if line.strip()]
        result["artifacts"][str(command_path.relative_to(repository))] = sha256(command_path)
        if not commands or len(commands) % 2:
            raise ValueError("missing or partial bounded command transcript")
        last_time = -1
        console_actions = []
        readiness_read = False
        readiness_write = False
        posture_phase = False
        request_read = ["shell", "-n", "-T", "run-as", "app.skein", "head", "-c", "1025",
                        "files/foldable-console-request.json"]
        display_reads = [["shell", "wm", "folded-area"], ["shell", "cmd", "device_state", "print-state"],
                         ["shell", "dumpsys", "display"]]
        for index in range(0, len(commands), 2):
            started, finished = commands[index:index + 2]
            fields = ("command_sequence", "run_id", "serial", "args", "phase", "timeout_seconds", "stdin_mode")
            if (started["event"] != "command_started" or finished["event"] != "command_finished" or
                    any(started[key] != finished[key] for key in fields)):
                raise ValueError("unmatched, failed or timed-out command receipt")
            if (started["command_sequence"] != index // 2 + 1 or started["run_id"] != run_id or
                    started["serial"] != events[0]["serial"] or started["phase"] not in {"readiness", "posture"}):
                raise ValueError("command receipt identity or phase mismatch")
            times = (started["monotonic_ns"], finished["monotonic_ns"])
            if any(type(value) is not int for value in times) or not last_time <= times[0] <= times[1]:
                raise ValueError("command chronology invalid")
            last_time = times[1]
            args = started["args"]
            if not isinstance(args, list) or not args or not all(isinstance(arg, str) for arg in args):
                raise ValueError("command arguments malformed")
            writer = args == ["shell", "run-as", "app.skein", "sh", "-c",
                              "'cat > files/foldable-console-ack.json.tmp && mv -f files/foldable-console-ack.json.tmp files/foldable-console-ack.json'"]
            allowed = writer or args == request_read or args in display_reads or args in (
                ["shell", "getprop", "ro.kernel.qemu"], ["emu", "avd", "name"], ["emu", "fold"], ["emu", "unfold"])
            if not allowed:
                raise ValueError("unreviewed command in console transcript")
            if started["phase"] == "posture":
                if not readiness_read or not readiness_write:
                    raise ValueError("posture phase lacks a completed readiness read and ACK write")
                posture_phase = True
            elif posture_phase:
                raise ValueError("command phase regressed after readiness")
            elif args == request_read and finished["returncode"] == 0:
                readiness_read = True
            elif writer and finished["returncode"] == 0:
                if not readiness_read:
                    raise ValueError("readiness ACK preceded its request read")
                readiness_write = True
            if started["stdin_mode"] != ("payload" if writer else "devnull"):
                raise ValueError("unexpected inherited stdin or input payload")
            if started["timeout_seconds"] != (5 if args in display_reads else 10):
                raise ValueError("changed command timeout")
            if any(not re.fullmatch(r"[0-9a-f]{64}", finished[key]) for key in ("stdout_sha256", "stderr_sha256")):
                raise ValueError("command output hashes invalid")
            if finished["returncode"] != 0:
                absent_hashes = {hashlib.sha256(message.encode()).hexdigest() for message in (
                    "run-as: unknown package: app.skein\n",
                    "head: files/foldable-console-request.json: No such file or directory\n")}
                if (finished["returncode"] != 1 or args != request_read or
                        finished["stdout_sha256"] != hashlib.sha256(b"").hexdigest() or
                        finished["stderr_sha256"] not in absent_hashes):
                    raise ValueError("failed command beyond the permitted not-yet-published request")
            if args in (["emu", "fold"], ["emu", "unfold"]):
                if started["phase"] != "posture" or finished["returncode"] != 0:
                    raise ValueError("posture command before readiness or unsuccessful")
                console_actions.append(args[1])
        if console_actions != [request["action"] for request in requests]:
            raise ValueError("command transcript differs from original six posture exchanges")
        result["bounded_adb_commands"] = len(commands) // 2
    except (OSError, ValueError, KeyError, TypeError, IndexError) as error:
        result["errors"].append(f"missing or invalid console protocol evidence: {error}")
    diagnostics = run_path.with_name("display-diagnostics.jsonl")
    try:
        samples = [json.loads(line) for line in diagnostics.read_text().splitlines() if line.strip()]
        result["artifacts"][str(diagnostics.relative_to(repository))] = sha256(diagnostics)
        requests = result.get("console_requests", [])
        if len(samples) != 6 or len(requests) != 6:
            raise ValueError("six exchange-bound display samples required")
        commands = [["shell", "wm", "folded-area"], ["shell", "cmd", "device_state", "print-state"],
                    ["shell", "dumpsys", "display"]]
        last_end = 0
        for sample, request in zip(samples, requests):
            if any(sample[key] != request[key] for key in ("run_id", "sequence", "nonce", "action")):
                raise ValueError("display sample differs from console exchange")
            if sample.get("schema_version") != 1 or sample.get("phase") != "console_ok_before_ack" or "error" in sample:
                raise ValueError("failed or unknown display sample")
            start, end = sample["started_monotonic_ns"], sample["finished_monotonic_ns"]
            if type(start) is not int or type(end) is not int or not 0 < start <= end or start < last_end:
                raise ValueError("invalid display sample interval")
            last_end = end
            reads = sample["reads"]
            if [read["command"] for read in reads] != commands:
                raise ValueError("missing, changed or extra display reads")
            if any(type(read["returncode"]) is not int or read["returncode"] != 0 or read["stderr"] != "" or
                   not isinstance(read["stdout"], str) or not read["stdout"].strip() for read in reads):
                raise ValueError("display read did not complete successfully")
        result["display_diagnostics"] = dict(samples=len(samples), phase="console_ok_before_ack",
                                             scope="instantaneous emulator configuration; never substitutes Activity geometry")
    except (OSError, ValueError, KeyError, TypeError) as error:
        result["errors"].append(f"missing or invalid display diagnostic evidence: {error}")
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
