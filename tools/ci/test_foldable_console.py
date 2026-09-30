"""Host-only protocol regressions: every adb process is replaced by a deterministic fake."""
import importlib.util
import json
import os
import sys
from pathlib import Path
import subprocess
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("foldable_console", Path(__file__).with_name("foldable-console-bridge.py"))
bridge = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bridge)
RUN = "a" * 32


class FakeAdb:
    def __init__(self):
        self.calls = []
        self.qemu = "1\n"
        self.avd = "skein_foldable_gate\nOK\n"
        self.timeout = False
        self.read_timeout = False
        self.console_output = "OK\n"
        self.raw_request = ""
        self.read_exit = 0
        self.read_stderr = ""
        self.ack = None
        self.diagnostic_timeout = False

    def __call__(self, argv, **kwargs):
        self.calls.append((argv, kwargs))
        assert argv[:3] == ["adb", "-s", "emulator-5554"]
        assert kwargs["timeout"] in (5, 10) and kwargs["check"] is False
        args = argv[3:]
        if args == ["shell", "getprop", "ro.kernel.qemu"]:
            output = self.qemu
        elif args == ["emu", "avd", "name"]:
            output = self.avd
        elif args[:1] == ["emu"]:
            if self.timeout:
                raise subprocess.TimeoutExpired(argv, 10)
            output = self.console_output
        elif args[:3] == ["shell", "run-as", "app.skein"]:
            assert args[3:] == ["sh", "-c", "'cat > files/foldable-console-ack.json.tmp && mv -f files/foldable-console-ack.json.tmp files/foldable-console-ack.json'"]
            self.ack = json.loads(kwargs["input"])
            output = ""
        elif tuple(args) in bridge.DISPLAY_READS:
            assert kwargs["timeout"] == 5
            if self.diagnostic_timeout:
                raise subprocess.TimeoutExpired(argv, 5)
            output = {bridge.DISPLAY_READS[0]: "Folded area: 0,0,884,2208\n",
                      bridge.DISPLAY_READS[1]: "1\n",
                      bridge.DISPLAY_READS[2]: "DISPLAY MANAGER (dumpsys display)\n"}[tuple(args)]
        elif args in (["exec-out", "run-as", "app.skein", "head", "-c", "1025", bridge.REQUEST],
                       ["shell", "-T", "run-as", "app.skein", "head", "-c", "1025", bridge.REQUEST]):
            if self.read_timeout:
                raise subprocess.TimeoutExpired(argv, 10)
            if args[0] == "exec-out":
                # ADB's legacy exec stream drops the remote status and merges stderr into stdout.
                return subprocess.CompletedProcess(argv, 0, self.raw_request + self.read_stderr, "")
            return subprocess.CompletedProcess(argv, self.read_exit, self.raw_request, self.read_stderr)
        else:
            raise AssertionError(args)
        return subprocess.CompletedProcess(argv, 0, output, "")

    def mutations(self):
        return [argv[3:] for argv, _ in self.calls if argv[3:] in (["emu", "fold"], ["emu", "unfold"])]


class ConsoleProtocolTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.path = Path(temp.name)
        self.adb = FakeAdb()
        self.host = bridge.Bridge("emulator-5554", RUN, self.path, run=self.adb,
                                  environment={"GITHUB_ACTIONS": "true"})

    def request(self, sequence=1, action="fold", nonce="b" * 32):
        return {"run_id": RUN, "sequence": sequence, "nonce": nonce, "action": action}

    def test_guards_reject_physical_serial_ci_and_invalid_run_before_any_command(self):
        for serial, run, env in (("R5physical", RUN, {"GITHUB_ACTIONS": "true"}),
                                 ("emulator-5554;echo", RUN, {"GITHUB_ACTIONS": "true"}),
                                 ("emulator-5554", RUN, {}), ("emulator-5554", "bad", {"GITHUB_ACTIONS": "true"})):
            with self.assertRaises(bridge.ProtocolError):
                bridge.Bridge(serial, run, self.path, run=self.adb, environment=env)
        self.assertEqual([], self.adb.calls)

    def test_wrong_qemu_or_avd_cannot_mutate_or_write_ack(self):
        for property_name, value in (("qemu", "0\n"), ("avd", "another_avd\nOK\n")):
            with self.subTest(property_name=property_name):
                self.setUp()
                setattr(self.adb, property_name, value)
                with self.assertRaises(bridge.ProtocolError):
                    self.host.handle(self.request())
                self.assertEqual([], self.adb.mutations())
                self.assertIsNone(self.adb.ack)

    def test_json_rejects_stale_unknown_duplicate_oversized_and_wrong_shapes(self):
        good = self.request()
        bad = [{**good, "run_id": "c" * 32}, {**good, "action": "rotate"}, {**good, "action": []},
               {**good, "sequence": True}, {**good, "sequence": 101}, {**good, "nonce": "bad"},
               {**good, "path": "arbitrary"}, []]
        raw = [json.dumps(value) for value in bad] + ['{"run_id":"x","run_id":"y"}', " " * 1025, "{"]
        for value in raw:
            with self.subTest(value=value), self.assertRaises(bridge.ProtocolError):
                bridge.decode_request(value, RUN)
        self.assertEqual(good, bridge.decode_request(json.dumps(good), RUN))
        self.assertEqual([], self.adb.calls)

    def test_exact_replay_only_replies_with_matched_cached_ack(self):
        request = self.request()
        self.host.handle(request)
        self.host.handle(dict(request))
        self.assertEqual([["emu", "fold"]], self.adb.mutations())
        self.assertEqual({**request, "status": "ok"}, self.adb.ack)
        events = [json.loads(line) for line in (self.path / "console-events.jsonl").read_text().splitlines()]
        self.assertEqual(2, sum(event["event"] == "ack" for event in events))

    def test_changed_old_out_of_order_or_reused_nonce_cannot_mutate(self):
        self.host.handle(self.request())
        for request in (self.request(action="unfold"), self.request(3, nonce="c" * 32), self.request(2)):
            with self.assertRaises(bridge.ProtocolError):
                self.host.handle(request)
        self.host.handle(self.request(2, "unfold", "c" * 32))
        with self.assertRaises(bridge.ProtocolError):
            self.host.handle(self.request())
        self.assertEqual([["emu", "fold"], ["emu", "unfold"]], self.adb.mutations())

    def test_display_reads_are_bound_to_one_exchange_and_replay_does_not_recapture(self):
        self.host.display_diagnostics = True
        request = self.request()
        self.host.handle(request)
        self.host.handle(request)
        rows = [json.loads(line) for line in (self.path / "display-diagnostics.jsonl").read_text().splitlines()]
        self.assertEqual(1, len(rows))
        self.assertEqual(request, {key: rows[0][key] for key in request})
        self.assertEqual("console_ok_before_ack", rows[0]["phase"])
        self.assertEqual([list(command) for command in bridge.DISPLAY_READS], [r["command"] for r in rows[0]["reads"]])
        self.assertEqual([["emu", "fold"]], self.adb.mutations())
        self.assertEqual("ok", self.adb.ack["status"])
        self.assertLessEqual(rows[0]["started_monotonic_ns"], rows[0]["finished_monotonic_ns"])

    def test_display_timeout_is_preserved_and_never_allows_another_posture_mutation(self):
        self.host.display_diagnostics = True
        self.adb.diagnostic_timeout = True
        with self.assertRaises(subprocess.TimeoutExpired):
            self.host.handle(self.request())
        self.host.cleanup()
        self.assertEqual([["emu", "fold"]], self.adb.mutations())
        row = json.loads((self.path / "display-diagnostics.jsonl").read_text())
        self.assertIn("error", row)
        self.assertEqual([], row["reads"])
        self.assertEqual("error", self.adb.ack["status"])

    def test_display_collection_refuses_prior_evidence(self):
        (self.path / "display-diagnostics.jsonl").write_text("preserve original\n")
        with self.assertRaisesRegex(bridge.ProtocolError, "earlier controller"):
            bridge.Bridge("emulator-5554", RUN, self.path, run=self.adb,
                          environment={"GITHUB_ACTIONS": "true"}, display_diagnostics=True)
        self.assertEqual("preserve original\n", (self.path / "display-diagnostics.jsonl").read_text())
        self.assertEqual([], self.adb.calls)

    def test_timeout_is_poisoned_error_ack_without_retry(self):
        self.adb.timeout = True
        with self.assertRaises(subprocess.TimeoutExpired):
            self.host.handle(self.request(action="unfold"))
        self.assertEqual("error", self.adb.ack["status"])
        with self.assertRaises(bridge.ProtocolError):
            self.host.handle(self.request(action="unfold"))
        self.host.cleanup()  # An ambiguous unfold is never issued a second time.
        self.assertEqual([["emu", "unfold"]], self.adb.mutations())

    def test_fold_timeout_withholds_all_further_posture_mutations(self):
        self.adb.timeout = True
        with self.assertRaises(subprocess.TimeoutExpired):
            self.host.handle(self.request())
        self.adb.timeout = False
        self.host.cleanup()
        with self.assertRaises(bridge.ProtocolError):
            self.host.handle(self.request(2, "unfold", "c" * 32))
        self.assertEqual([["emu", "fold"]], self.adb.mutations())
        self.assertIn("cleanup_withheld", (self.path / "console-events.jsonl").read_text())

    def test_request_read_timeout_after_fold_also_withholds_cleanup(self):
        self.host.handle(self.request())
        self.adb.read_timeout = True
        with self.assertRaises(subprocess.TimeoutExpired):
            self.host.serve(self.path / "absent-stop")
        self.assertEqual([["emu", "fold"]], self.adb.mutations())
        self.assertIn("cleanup_withheld", (self.path / "console-events.jsonl").read_text())

    def test_console_ko_is_not_success(self):
        self.adb.console_output = "KO: Device is not foldable\n"
        with self.assertRaises(bridge.ProtocolError):
            self.host.handle(self.request())
        self.assertEqual("error", self.adb.ack["status"])

    def test_cleanup_unfold_is_only_after_owned_fold_and_is_guarded(self):
        self.host.cleanup()
        self.assertEqual([], self.adb.calls)
        self.host.handle(self.request())
        self.adb.qemu = "0\n"
        with self.assertRaises(bridge.ProtocolError):
            self.host.cleanup()
        self.assertEqual([["emu", "fold"]], self.adb.mutations())
        self.adb.qemu = "1\n"
        self.host.cleanup()
        self.assertEqual([["emu", "fold"], ["emu", "unfold"]], self.adb.mutations())

    def test_stop_and_lifetime_are_bounded_and_recorded(self):
        stop = self.path / "stop"
        stop.touch()
        self.host.serve(stop)
        self.assertEqual([], self.adb.mutations())
        stop.unlink()
        with self.assertRaisesRegex(bridge.ProtocolError, "lifetime exceeded"):
            self.host.serve(stop, lifetime=0)

    def test_shell_stop_escalates_and_reaps_a_stalled_owned_controller(self):
        # Run the real stop function, with shorter clock ticks; no adb/SDK process exists.
        source = Path(__file__).with_name("run-foldable-emulator.sh").read_text()
        stop_function = source.split("stop_controller() {", 1)[1].split("\n}\n", 1)[0]
        stop_function = "stop_controller() {" + stop_function + "\n}\n"
        child = self.path / "child.py"
        child.write_text("import signal,time\nsignal.signal(signal.SIGTERM, signal.SIG_IGN)\nprint('ready',flush=True)\ntime.sleep(3)\n")
        evidence = self.path / "build/foldable-evidence"
        evidence.mkdir(parents=True)
        script = stop_function + r"""
controller_result=0
stop_file=stop
sleep() { command sleep 0.001; }
"$PYTHON" child.py > ready &
controller_pid=$!
while ! test -s ready; do command sleep 0.01; done
owned_pid=$controller_pid
stop_controller
! kill -0 "$owned_pid" 2>/dev/null || exit 10
test -z "$controller_pid" || exit 11
test "$controller_result" -ne 0 || exit 12
test -f "$stop_file" || exit 13
"""
        completed = subprocess.run(["bash", "-c", script], cwd=self.path,
                                   env={**os.environ, "PYTHON": sys.executable}, capture_output=True, timeout=5)
        self.assertEqual(0, completed.returncode, completed.stderr)
        self.assertIn("stop deadline exceeded", (evidence / "console-controller.log").read_text())

    def test_uninstalled_package_and_missing_private_file_wait_without_parsing_diagnostics(self):
        for diagnostic in ("run-as: unknown package: app.skein\n",
                           "head: files/foldable-console-request.json: No such file or directory\n"):
            with self.subTest(diagnostic=diagnostic):
                self.adb.read_exit = 1
                self.adb.read_stderr = diagnostic
                legacy = self.adb(["adb", "-s", "emulator-5554", "exec-out", "run-as", "app.skein",
                                   "head", "-c", "1025", bridge.REQUEST], timeout=10, check=False)
                self.assertEqual(0, legacy.returncode)
                self.assertEqual(diagnostic, legacy.stdout)
                self.assertEqual("", legacy.stderr)
                self.assertIsNone(self.host.read_request())
        self.assertEqual([], self.adb.mutations())

    def test_exit_aware_read_keeps_real_errors_and_malformed_data_fatal(self):
        for code, stdout, stderr in ((1, "", "run-as: package not debuggable: app.skein\n"),
                                    (1, "", "head: files/another.json: No such file or directory\n"),
                                    (1, "", "error: device offline\n"),
                                    (2, "", "run-as: unknown package: app.skein\n"),
                                    (1, "", "run-as: unknown package: app.skein\nadditional error\n"),
                                    (1, '{"partial":', "head: files/foldable-console-request.json: No such file or directory\n"),
                                    (0, json.dumps(self.request()), "unexpected diagnostic\n"),
                                    (0, "not-json", ""), (0, "", "")):
            with self.subTest(code=code, stdout=stdout, stderr=stderr):
                self.adb.read_exit, self.adb.raw_request, self.adb.read_stderr = code, stdout, stderr
                with self.assertRaises(bridge.ProtocolError):
                    self.host.read_request()
        self.assertEqual([], self.adb.mutations())

    def test_private_request_read_has_fixed_path_and_byte_limit(self):
        self.adb.raw_request = json.dumps(self.request())
        self.assertEqual(self.request(), self.host.read_request())


if __name__ == "__main__":
    unittest.main()
