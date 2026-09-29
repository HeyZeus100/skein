#!/usr/bin/env python3
"""Disposable CI emulator console transport. Never discovers or addresses physical devices."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import time

PACKAGE = "app.skein"
AVD = "skein_foldable_gate"
REQUEST = "files/foldable-console-request.json"
ACK = "files/foldable-console-ack.json"
LIMIT = 1024
TOKEN = re.compile(r"[0-9a-f]{32}")
KEYS = {"run_id", "sequence", "nonce", "action"}
ACTIONS = {"fold", "unfold"}


class ProtocolError(RuntimeError):
    pass


def decode_request(raw, run_id):
    if len(raw.encode("utf-8")) > LIMIT:
        raise ProtocolError("request exceeds size limit")
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ProtocolError("duplicate JSON key")
            result[key] = value
        return result
    try:
        request = json.loads(raw, object_pairs_hook=unique)
    except (ValueError, TypeError) as error:
        raise ProtocolError("malformed request JSON") from error
    if not isinstance(request, dict) or set(request) != KEYS:
        raise ProtocolError("request keys differ from protocol")
    if request["run_id"] != run_id or not isinstance(request["nonce"], str) or not TOKEN.fullmatch(request["nonce"]):
        raise ProtocolError("stale run or invalid nonce")
    if type(request["sequence"]) is not int or not 1 <= request["sequence"] <= 100:
        raise ProtocolError("invalid sequence")
    if not isinstance(request["action"], str) or request["action"] not in ACTIONS:
        raise ProtocolError("unknown action")
    return request


class Bridge:
    def __init__(self, serial, run_id, evidence, run=subprocess.run, environment=None):
        self.environment = os.environ if environment is None else environment
        if self.environment.get("GITHUB_ACTIONS") != "true":
            raise ProtocolError("requires disposable GitHub Actions host")
        if not re.fullmatch(r"emulator-[0-9]+", serial):
            raise ProtocolError("refusing non-emulator serial")
        if not TOKEN.fullmatch(run_id):
            raise ProtocolError("invalid run ID")
        self.serial, self.run_id, self.evidence, self.run = serial, run_id, evidence, run
        self.last_request = None
        self.last_ack = None
        self.seen_nonces = set()
        self.poisoned = False
        self.timed_out = False
        self.last_attempt = None
        self.last_succeeded = False
        evidence.mkdir(parents=True, exist_ok=True)

    def event(self, kind, **fields):
        with (self.evidence / "console-events.jsonl").open("a") as output:
            output.write(json.dumps({"event": kind, "run_id": self.run_id, **fields}, sort_keys=True) + "\n")

    def adb(self, *args, input=None):
        # All calls are explicitly serial-targeted, bounded, and shell=False.
        try:
            return self.run(["adb", "-s", self.serial, *args], input=input, text=True,
                            capture_output=True, timeout=10, check=False)
        except subprocess.TimeoutExpired:
            self.timed_out = True
            self.poisoned = True
            raise

    def checked(self, *args, input=None):
        result = self.adb(*args, input=input)
        if result.returncode:
            raise ProtocolError(f"adb failed: {args!r}: {result.stderr.strip()}")
        return result.stdout

    def guard(self):
        if self.environment.get("GITHUB_ACTIONS") != "true":
            raise ProtocolError("CI guard changed")
        if self.checked("shell", "getprop", "ro.kernel.qemu").strip() != "1":
            raise ProtocolError("qemu identity mismatch")
        identity = self.checked("emu", "avd", "name").splitlines()
        if identity != [AVD, "OK"]:
            raise ProtocolError(f"AVD identity mismatch: {identity!r}")

    def read_request(self):
        result = self.adb("exec-out", "run-as", PACKAGE, "head", "-c", str(LIMIT + 1), REQUEST)
        if result.returncode:
            message = result.stdout + result.stderr
            # The task builds/installs APKs before instrumentation creates the fixed private file.
            if "is unknown" in message or "unknown package" in message or "No such file or directory" in message:
                return None
            raise ProtocolError(f"request read failed: {message.strip()}")
        return decode_request(result.stdout, self.run_id)

    def write_ack(self, ack):
        self.guard()
        body = json.dumps(ack, sort_keys=True)
        # Only fixed paths enter the remote shell; the untrusted JSON is stdin, never shell code.
        self.checked("shell", "run-as", PACKAGE, "sh", "-c",
                     f"'cat > {ACK}.tmp && mv -f {ACK}.tmp {ACK}'", input=body)
        self.event("ack", **ack)

    def handle(self, request):
        if self.poisoned:
            raise ProtocolError("transport already failed; mutations cannot be retried")
        if request == self.last_request:
            # Cached reply only; an exact replay never re-runs the console command.
            self.write_ack(self.last_ack)
            return
        expected = 1 if self.last_request is None else self.last_request["sequence"] + 1
        if request["sequence"] != expected or request["nonce"] in self.seen_nonces:
            raise ProtocolError("stale, changed, out-of-order or reused-nonce request")
        self.last_request = request
        self.seen_nonces.add(request["nonce"])
        self.event("request", **request)
        try:
            self.guard()
            self.last_attempt, self.last_succeeded = request["action"], False
            result = self.adb("emu", request["action"])
            self.event("console", sequence=request["sequence"], action=request["action"],
                       returncode=result.returncode, stdout=result.stdout, stderr=result.stderr)
            if result.returncode or result.stdout.strip() != "OK" or "KO" in result.stderr:
                raise ProtocolError("console command did not return exact OK")
            self.last_succeeded = True
            self.last_ack = {**request, "status": "ok"}
            self.write_ack(self.last_ack)
        except (ProtocolError, subprocess.TimeoutExpired) as error:
            self.poisoned = True
            self.timed_out = self.timed_out or isinstance(error, subprocess.TimeoutExpired)
            self.event("failure", sequence=request["sequence"], reason=str(error))
            self.last_ack = {**request, "status": "error"}
            # A missing reply still causes a bounded instrumentation failure.
            try:
                self.write_ack(self.last_ack)
            except (ProtocolError, subprocess.TimeoutExpired) as ack_error:
                self.timed_out = self.timed_out or isinstance(ack_error, subprocess.TimeoutExpired)
                self.event("ack_failure", reason=str(ack_error))
            raise

    def cleanup(self):
        # A timed-out command has an ambiguous outcome. Never issue another posture mutation.
        if self.timed_out:
            self.event("cleanup_withheld", reason="transport timeout; final posture is unverified")
            return
        # Restore only a fold that the console positively acknowledged for this controller.
        if self.last_attempt != "fold" or not self.last_succeeded:
            return
        self.guard()
        result = self.adb("emu", "unfold")
        self.event("cleanup_unfold", returncode=result.returncode, stdout=result.stdout, stderr=result.stderr)
        if result.returncode or result.stdout.strip() != "OK" or "KO" in result.stderr:
            raise ProtocolError("cleanup unfold failed")

    def serve(self, stop, lifetime=4800):
        deadline = time.monotonic() + lifetime
        self.guard()
        self.event("started", serial=self.serial, avd=AVD)
        try:
            while not stop.exists():
                if time.monotonic() >= deadline:
                    raise ProtocolError("controller lifetime exceeded")
                request = self.read_request()
                if request is not None and request != self.last_request:
                    self.handle(request)
                time.sleep(0.5)
            self.event("stopped")
        finally:
            self.cleanup()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--stop-file", type=Path, required=True)
    args = parser.parse_args()
    bridge = Bridge(args.serial, args.run_id, args.evidence)
    try:
        bridge.serve(args.stop_file)
    except (ProtocolError, subprocess.TimeoutExpired, OSError) as error:
        bridge.event("fatal", reason=str(error))
        print(str(error), flush=True)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
