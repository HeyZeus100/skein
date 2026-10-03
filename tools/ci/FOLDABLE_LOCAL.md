# Local macOS ARM fold compatibility

`run-foldable-local.py` is a separate opt-in adapter for a new disposable macOS ARM emulator. It does not invoke the Linux CI runner or set `GITHUB_ACTIONS`. The existing CI entry points keep their original guards and Linux/x86_64 SDK policy. No hosted runner is dispatched.

The coordinator must freeze a clean full commit and grant the serialized build/runtime queue before execution. The runner atomically acquires both coordination leases, creates a UUID-named AVD in its own new evidence directory, and refuses occupied ports. Every device command targets its explicit emulator serial and checks the retained live child, process group, port listeners, and matching leases. Before Gradle discovery, a single read-only `adb devices -l` inventory refuses any other attached target, including a physical phone or an offline emulator. The raw inventory stays private; published evidence should include counts and hashes. `ANDROID_SERIAL` additionally selects the owned emulator.

The installed API35 `google_apis/arm64-v8a` image must match all 30 files from the published revision9/extension13 installation receipt. No SDK acquisition, model download, existing AVD deletion, physical Fold operation, or production model/runtime switch is performed. Builds use offline dependencies and fail if something is missing. A native headful window is required; no `-no-window`, Xvfb, forged Linux environment, `wm size`/density override, or edited profile geometry is used.

The executing adapter modules must belong to the frozen checkout. The existing app test model is checked against the exact lock SHA and byte size before each Gradle invocation and after testing. The custom `:app:fetchTestModel` download task is explicitly excluded; Gradle's `--offline` alone would not constrain that task's URL fetch.

Example, only after the coordinator grants this exact source SHA:

```sh
python3 tools/ci/run-foldable-local.py \
  --repository "$PWD" \
  --sdk /Users/andrewherrera/android-sdk \
  --java-home /opt/homebrew/Cellar/openjdk@17/17.0.20.1/libexec/openjdk.jdk/Contents/Home \
  --coordination /Users/andrewherrera/skein-session-coordination/20260929 \
  --console-port 5580 --profile '7.6in Foldable' \
  --expected-sha FULL_FROZEN_COMMIT \
  --execute-after-coordinator-grant FULL_FROZEN_COMMIT
```

The selected profile must exist in the actual installed catalog and declare a hinge. A generic fold or Pixel Fold run proves only that compatibility profile. It cannot satisfy exact Pixel 9 Pro Fold acceptance. The image containing Pixel 9 data does not itself prove that an exact device profile is available.

The test-only local instrumentation argument is mutually exclusive with the CI argument. It binds the run ID to the exact AVD name and verifies emulator/API35/ARM identity. The same shared protocol requires a nonce-bound readiness request and consumed ACK once before the first geometry action. Readiness never counts toward the six original posture exchanges or the five Activity geometry observations. The original two test identities, 600dp thresholds, 90s ACK deadline, 15s geometry deadline, and Activity identity/privacy assertions are unchanged. Host display diagnostics cannot replace Activity measurements.

Evidence is retained under a fresh `build/agent-logs/fold-local-<run-id>/`: source and SDK receipts, exact commands and output hashes, AVD configuration, process/lease identities, original XML and APK bytes in `snapshot`, console protocol, consumed readiness, and geometry. Prior connected output is moved into that same run's `preexisting-output` directory, never counted as new evidence. `review_local` applies the same case/protocol/geometry validator with a distinct macOS ARM SDK policy, then verifies local source, image, command, and cleanup receipts. Host evidence is not installed-package readback or physical acceptance.

On failure, original output stays in place. An unsuccessful/timed-out Gradle invocation retains the build lease until the coordinator establishes daemon termination; only successful `--no-daemon` completion releases it automatically. The emulator cleanup signals only the retained child, and releases the device lease only after termination and empty owned ports are proven. It never kills a process by a cached PID/name or touches unrelated Java/ADB processes. Acceptance remains open if any cleanup or source check fails.

Successful Gradle completion also requires any newly observed Gradle process to disappear during a bounded 30-second read-only check. A changed parent/process group does not prove termination. Full process inventories, wrapper identities, and candidate daemon logs are private; publication uses bounded counts/hashes. A surviving candidate retains the build lease without being signalled.

Bounded host tests are `python3 -m unittest discover -s tools/ci -p 'test*foldable*.py'`; they use synthetic SDK/process/ADB evidence and do not boot an emulator. Compilation and actual runtime require separate queue grants. Physical Fold, unlocked A–G, exact Pixel9 geometry, real IME/focus, and privacy acceptance remain independent gates.
