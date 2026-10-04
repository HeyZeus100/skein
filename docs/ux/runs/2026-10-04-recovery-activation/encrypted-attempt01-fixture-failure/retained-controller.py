#!/usr/bin/env python3
"""Private scoped encrypted activation gate; synthetic factor authentication. Requires explicit root queue grant; never auto-run."""
import argparse
import collections
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import uuid
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path('/Users/andrewherrera/skein-worktrees/ux-integration-20260929')
COORD = Path('/Users/andrewherrera/skein-session-coordination/20260929')
SDK = Path('/Users/andrewherrera/android-sdk')
EXPECTED = '52acc3114720e2ae136a80f4f74cb1bd5ec33c2e'
RUN = ROOT / 'build/agent-logs/local-recovery-activation-20261004-01'
AVD_ID = uuid.uuid4().hex
NAME = 'skein_recovery_' + AVD_ID
SERIAL = 'emulator-5580'
PORTS = (5580, 5581)
OWNER = '/root/fold_transport'
CLASS_PREFIX = 'app.skein.core.vault.key.recovery.'
EXPECTED_METHODS = {
    CLASS_PREFIX + 'AuthenticatedRecoveryActivationInstrumentedTest': {
        'nativeProofAndBothFreshReadbacksActivateMetadataOnlyThenBothNormalFactorsRead',
        'wrongCandidateFailsFreshNativeProofBeforeAuthenticationOrTransaction',
        'substitutingEitherAuthenticatedCipherRefusesWithoutChangingEncryptedSources',
        'recomputedChecksumCannotAuthenticateChangedStagedHeader',
        'liveStageTamperDuringSecondAuthenticationCannotReachRename',
        'eitherAuthenticatedWrapOfAnotherKeyCannotBorrowTheProvedDatabaseCandidate',
        'cancellationDuringSecondAuthenticationZerosInputAndReleasesClosedVaultLease',
        'revocationDuringSecondAuthenticationRefusesAndPreservesBothOriginalWraps',
        'injectedBeforeRenameIoRetainsExactEvidenceAndFreshInspectionObservesOldOnly',
        'injectedPostRenameDirectorySyncIoReportsVisibleNewWithUncertainDurability',
        'postRenameRevocationIsUnknownUntilFreshLeaseObservationWithoutUnlockAuthority',
    },
    CLASS_PREFIX + 'ExistingVaultKeyProofInstrumentedTest': {
        'rawKeyProvesEncryptedPagesAndWrongKeyCannotChangeSources',
        'directReadOnlyProofCreatesOnlyEmptyPrivateWalAndPreservesEncryptedSources',
        'plaintextEmptyMissingCorruptAndUnknownSchemaNeverProveCandidate',
        'committedWalSchemaIsReadWithoutImmutableModeOrSourceCheckpoint',
        'proofNeverMigratesOlderSupportedSchema',
    },
}
JAVA = '/opt/homebrew/Cellar/openjdk@17/17.0.20.1/libexec/openjdk.jdk/Contents/Home'
MODULES = ('core/vault',)
COMMANDS = []
LEASES = []
EMU = None
ENV = dict(os.environ)
ENV.update(JAVA_HOME=JAVA, ANDROID_HOME=str(SDK), ANDROID_SDK_ROOT=str(SDK), ANDROID_SERIAL=SERIAL,
           ANDROID_AVD_HOME=str(RUN / 'avd-home'), ANDROID_USER_HOME=str(RUN / 'android-user'))
ENV['PATH'] = JAVA + '/bin:' + str(SDK / 'platform-tools') + ':' + str(SDK / 'emulator') + ':' + ENV['PATH']
ENV.pop('GITHUB_ACTIONS', None)
TOKEN = str(uuid.uuid4())
RESULT = {'state': 'not_started', 'source_sha': EXPECTED, 'serial': SERIAL, 'avd': NAME,
          'platform': 'macOS/API35/google_apis/arm64-v8a', 'application_source_sha': EXPECTED, 'commands': COMMANDS,
          'physical_device_actions': 0, 'acceptance_scope': 'Real native SQLCipher proof and filesystem activation; synthetic in-memory AES/auth callbacks; no Android per-use authentication, StrongBox, public wiring, process-death/restart, power-loss or physical acceptance',
          'installed_apk_readback': 'NOT_MEASURED', 'token': TOKEN}

def now(): return datetime.datetime.now(datetime.timezone.utc).isoformat()
def sha(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as f:
        while b := f.read(1024 * 1024): h.update(b)
    return h.hexdigest()
def save():
    (RUN / 'result.json').write_text(json.dumps(RESULT, indent=2) + '\n')
def cmd(args, timeout=30, check=True, input=None, label='command'):
    idx = len(COMMANDS)
    rec = {'argv': args, 'started': now(), 'timeout_seconds': timeout, 'label': label}
    COMMANDS.append(rec)
    stem = RUN / f'commands/{idx:04d}-{label}'
    try:
        save()
        io = {'input': input} if input is not None else {'stdin': subprocess.DEVNULL}
        with stem.with_suffix('.stdout').open('wb') as out, stem.with_suffix('.stderr').open('wb') as err:
            if label == 'scoped-vault-gradle':
                process = subprocess.Popen(args, cwd=ROOT, env=ENV, stdout=out, stderr=err,
                    stdin=subprocess.DEVNULL, start_new_session=True)
                rec['retained_child'] = {'pid': process.pid, 'pgid': os.getpgid(process.pid), 'cwd': str(ROOT)}
                save()
                try: code = process.wait(timeout=timeout)
                except subprocess.TimeoutExpired:
                    process.kill(); process.wait(timeout=10)
                    rec['retained_child']['exit_after_timeout'] = process.returncode
                    raise
                rec['retained_child']['terminal_exit'] = code
                r = subprocess.CompletedProcess(args, code)
            else:
                r = subprocess.run(args, cwd=ROOT, env=ENV, stdout=out, stderr=err, timeout=timeout, **io)
        r.stdout = stem.with_suffix('.stdout').read_bytes()
        r.stderr = stem.with_suffix('.stderr').read_bytes()
        rec['exit'] = r.returncode
        rec['stdout_sha256'] = hashlib.sha256(r.stdout).hexdigest()
        rec['stderr_sha256'] = hashlib.sha256(r.stderr).hexdigest()
        if check and r.returncode: raise RuntimeError(f'{label} exit {r.returncode}: {args}')
        return r
    except subprocess.TimeoutExpired as e:
        rec['timeout'] = True
        raise
    finally:
        rec['ended'] = now()
        save()
def git(*args): return cmd(['git', *args], label='source').stdout.decode().strip()
def lease(kind):
    p = COORD / (kind + '.lock')
    p.mkdir()  # Atomic, never waits on or removes somebody else's lease.
    LEASES.append(p)
    record = {'owner': OWNER, 'pid': os.getpid(), 'worktree': str(ROOT), 'token': TOKEN,
              'purpose': 'scoped encrypted activation API35 arm64 owned disposable emulator only', 'created': now()}
    (p / 'owner.json').write_text(json.dumps(record, indent=2) + '\n')
    (RUN / (kind + '-lease.json')).write_text(json.dumps(record, indent=2) + '\n')
def check_leases():
    for p in LEASES:
        v = json.loads((p / 'owner.json').read_text())
        if v['owner'] != OWNER or v['token'] != TOKEN: raise RuntimeError('lease ownership changed')
def listeners(port):
    r = subprocess.run(['/usr/sbin/lsof', '-t', '-nP', f'-iTCP:{port}', '-sTCP:LISTEN'],
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=5)
    if r.returncode not in (0, 1): raise RuntimeError('lsof failed')
    return {int(x) for x in r.stdout.split()}
def owned(require_ports=True):
    check_leases()
    if EMU is None or EMU.poll() is not None: raise RuntimeError('owned emulator exited')
    if os.getpgid(EMU.pid) != EMU.pid: raise RuntimeError('owned process group changed')
    if require_ports:
        for port in PORTS:
            pids = listeners(port)
            if not pids: raise RuntimeError(f'owned emulator listener absent:{port}')
            if any(os.getpgid(pid) != EMU.pid for pid in pids):
                raise RuntimeError('listener is not in owned emulator process group')
def adb_raw(args, timeout=20, check=True):
    owned()
    assert re.fullmatch(r'emulator-\d+', SERIAL)
    return cmd([str(SDK / 'platform-tools/adb'), '-s', SERIAL, *args], timeout=timeout, check=check, label='emulator-adb')
def identity():
    q = adb_raw(['shell', 'getprop', 'ro.kernel.qemu']).stdout.decode().strip()
    n = adb_raw(['emu', 'avd', 'name']).stdout.decode().replace('\r', '').strip().splitlines()
    api = adb_raw(['shell', 'getprop', 'ro.build.version.sdk']).stdout.decode().strip()
    abi = adb_raw(['shell', 'getprop', 'ro.product.cpu.abi']).stdout.decode().strip()
    if (q, n, api, abi) != ('1', [NAME, 'OK'], '35', 'arm64-v8a'): raise RuntimeError('owned emulator AVD/API/ABI mismatch')
    RESULT['emulator_identity'] = {'qemu': q, 'avd': NAME, 'api': api, 'abi': abi}
def mutate(args, check=True):
    identity()
    return adb_raw(args, check=check)
def gradle_processes(label):
    result = cmd(['/bin/ps', '-axo', 'pid=,ppid=,pgid=,lstart=,command='], label=label)
    return {line.split()[0]: line.strip() for line in result.stdout.decode().splitlines()
            if any(marker in line for marker in ('GradleDaemon', 'GradleWrapperMain', 'org.gradle.launcher'))}

def raw_xml_inventory():
    out = []; counts = collections.Counter(); ids = collections.Counter()
    for module in MODULES:
        for p in sorted((ROOT / module / 'build/outputs/androidTest-results/connected').rglob('*.xml')):
            r = ET.parse(p).getroot(); cases = list(r.iter('testcase'))
            for case in cases:
                ids[(module, case.get('classname'), case.get('name'))] += 1
                counts['tests'] += 1
                for k in ('failure', 'error', 'skipped'): counts[k] += len(case.findall(k))
            out.append({'path': str(p.relative_to(ROOT)), 'bytes': p.stat().st_size, 'sha256': sha(p), 'testcases': len(cases)})
    return out, counts, ids

def execute():
    global EMU
    RUN.mkdir(parents=True, exist_ok=False)
    (RUN / 'commands').mkdir()
    RESULT.update(state='preflight', started=now(), controller_sha256=sha(__file__))
    save()
    lease('build')
    lease('device')
    if git('rev-parse', 'HEAD') != EXPECTED: raise RuntimeError('source HEAD differs from root freeze')
    if git('status', '--porcelain', '--untracked-files=all'): raise RuntimeError('tracked worktree dirty')
    install = ROOT / 'docs/ux/runs/2026-09-30-context-recovery-transport/local-sdk-google-apis-arm64/installation-result.json'
    installed = json.loads(install.read_text())
    if installed.get('passed') is not True or installed.get('installed_metadata') != {'path': 'system-images;android-35;google_apis;arm64-v8a', 'revision': 9, 'extension': 13}:
        raise RuntimeError('exact authorized compatible image install not verified')
    archive = installed.get('archive', {})
    if archive.get('bytes') != 1778933980 or archive.get('sha1') != '16f5bceca236b2737008977c4aaf826e46a8de7d' or not re.fullmatch('[0-9a-f]{64}', archive.get('sha256', '')):
        raise RuntimeError('full compatible-image archive pin not verified')
    if installed.get('license_before_sha256') != installed.get('license_after_sha256'):
        raise RuntimeError('license file changed')
    RESULT['source_files'] = {}
    source_paths = [
        'core/vault/src/main/kotlin/app/skein/core/vault/key/recovery/' + name for name in (
            'AuthenticatedRecoveryActivation.kt', 'AuthenticatedRecoveryPreparation.kt', 'RecoveryEnvelopeTransaction.kt',
            'RecoveryEnvelopeV2.kt', 'ClosedVaultRecoverySnapshot.kt', 'ExistingVaultKeyProof.kt')]
    source_paths += ['core/vault/src/androidTest/kotlin/app/skein/core/vault/key/recovery/' + name for name in (
        'AuthenticatedRecoveryActivationInstrumentedTest.kt', 'ExistingVaultKeyProofInstrumentedTest.kt')]
    for relative in source_paths:
        p = ROOT / relative
        RESULT['source_files'][relative] = {'bytes': p.stat().st_size, 'sha256': sha(p)}
    RESULT['compatible_image_install'] = {'path': str(install), 'sha256': sha(install)}
    for p in PORTS:
        if listeners(p): raise RuntimeError('requested emulator port occupied')
    RESULT['model_acquisition'] = 'NONE; scoped vault task has no model assets and both fetch tasks are explicitly excluded'
    owner_paths = [Path('/Users/andrewherrera/.android/avd/skein_spike.ini'), Path('/Users/andrewherrera/.android/avd/skein_spike.avd/config.ini')]
    RESULT['owner_avd_before'] = {str(p): sha(p) for p in owner_paths if p.is_file()}
    RESULT['sdk_runtime_binaries'] = {str(p): sha(p) for p in [SDK / 'emulator/emulator', SDK / 'emulator/qemu/darwin-aarch64/qemu-system-aarch64', SDK / 'platform-tools/adb']}
    image = SDK / 'system-images/android-35/google_apis/arm64-v8a'
    RESULT['image_payloads'] = []
    if len(installed['files']) != 30: raise RuntimeError('installed image pin incomplete')
    for original in installed['files']:
        p = image / original['path']
        if Path(original['path']).is_absolute() or '..' in Path(original['path']).parts:
            raise RuntimeError('invalid published image path')
        actual = {'path': original['path'], 'bytes': p.stat().st_size, 'sha256': sha(p)}
        RESULT['image_payloads'].append(actual)
        if actual != original: raise RuntimeError('installed image bytes changed: ' + original['path'])
    for command in [['/usr/sbin/sysctl', 'hw.memsize'], ['/usr/bin/memory_pressure', '-Q'], ['/bin/df', '-k', str(ROOT)]]:
        cmd(command, label='host-capacity')
    (RUN / 'avd-home').mkdir(); (RUN / 'android-user').mkdir()
    avd_path = RUN / 'avd-home' / (NAME + '.avd')
    cmd([str(SDK / 'cmdline-tools/latest/bin/avdmanager'), 'create', 'avd', '--name', NAME,
         '--package', 'system-images;android-35;google_apis;arm64-v8a', '--device', 'pixel_6', '--path', str(avd_path)],
        input=b'no\n', timeout=120, label='create-owned-avd')
    RESULT['avd_config_before_boot_sha256'] = sha(avd_path / 'config.ini')
    for p in PORTS:
        if listeners(p): raise RuntimeError('requested port occupied before launch')
    argv = [str(SDK / 'emulator/emulator'), '-avd', NAME, '-ports', '5580,5581', '-memory', '3072', '-cores', '2',
            '-gpu', 'swiftshader', '-no-window', '-noaudio', '-no-boot-anim', '-no-snapshot-load', '-no-snapshot-save']
    with (RUN / 'emulator.log').open('wb') as log:
        EMU = subprocess.Popen(argv, cwd=ROOT, env=ENV, stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    RESULT.update(state='booting', emulator={'pid': EMU.pid, 'command': argv, 'started': now(), 'token': TOKEN}); save()
    deadline = time.monotonic() + 300
    ready = False
    while time.monotonic() < deadline:
        owned(require_ports=False)
        if all(listeners(p) for p in PORTS):
            try:
                identity()
                props = {p: adb_raw(['shell', 'getprop', p], timeout=10).stdout.decode().strip() for p in ('sys.boot_completed', 'dev.bootcomplete', 'init.svc.bootanim')}
                if props == {'sys.boot_completed': '1', 'dev.bootcomplete': '1', 'init.svc.bootanim': 'stopped'}:
                    RESULT['boot_properties'] = props; ready = True; break
            except (RuntimeError, subprocess.TimeoutExpired) as e:
                RESULT['boot_last_observation'] = str(e)
        time.sleep(5)
    if not ready: raise RuntimeError('bounded300s boot conditions unmet')
    time.sleep(20)
    for args in [ ['input', 'keyevent', 'KEYCODE_WAKEUP'], ['wm', 'dismiss-keyguard'], ['locksettings', 'set-pin', '1234'],
                  ['svc', 'power', 'stayon', 'true'], ['settings', 'put', 'system', 'screen_off_timeout', '2147483647'],
                  ['settings', 'put', 'global', 'window_animation_scale', '0'], ['settings', 'put', 'global', 'transition_animation_scale', '0'],
                  ['settings', 'put', 'global', 'animator_duration_scale', '0'] ]:
        mutate(['shell', *args], check=args[:2] != ['wm', 'dismiss-keyguard'])
    # Observe late keyguard transitions for the approved bounded120s after synthetic PIN setup.
    unlock_deadline = time.monotonic() + 120
    while time.monotonic() < unlock_deadline:
        s = adb_raw(['shell', 'dumpsys', 'activity', 'activities']).stdout.decode()
        if 'mKeyguardShowing=true' in s:
            mutate(['shell', 'input', 'keyevent', 'KEYCODE_WAKEUP'])
            mutate(['shell', 'wm', 'dismiss-keyguard'], check=False); time.sleep(1)
            mutate(['shell', 'input', 'text', '1234']); mutate(['shell', 'input', 'keyevent', 'KEYCODE_ENTER']); time.sleep(2)
        time.sleep(min(5, max(0, unlock_deadline-time.monotonic())))
    state = adb_raw(['shell', 'dumpsys', 'activity', 'activities']).stdout.decode()
    if 'mKeyguardShowing=true' in state or 'mKeyguardShowing=false' not in state: raise RuntimeError('emulator unlocked state not proven')
    RESULT['normal_synthetic_unlock_verified'] = True
    mutate(['logcat', '-G', '64M']); mutate(['logcat', '-c'])
    # Preserve all original reports instead of allowing stale XML to count as this run.
    prior = RUN / 'preexisting-output'
    for module in MODULES:
        for suffix in ('outputs/androidTest-results/connected', 'reports/androidTests/connected'):
            p = ROOT / module / 'build' / suffix
            if p.exists():
                dst = prior / module / suffix; dst.parent.mkdir(parents=True, exist_ok=True); shutil.move(str(p), str(dst))
    if git('rev-parse', 'HEAD') != EXPECTED or git('status', '--porcelain', '--untracked-files=all'): raise RuntimeError('source changed before instrumentation')
    identity()
    inventory = cmd([str(SDK / 'platform-tools/adb'), 'devices', '-l'], timeout=10, label='private-target-inventory').stdout.decode()
    inventory_lines = [line.strip() for line in inventory.splitlines() if line.strip()]
    if not inventory_lines or inventory_lines[0] != 'List of devices attached': raise RuntimeError('unknown target inventory')
    targets = [line.split() for line in inventory_lines[1:]]
    if len(targets) != 1 or targets[0][:2] != [SERIAL, 'device']:
        raise RuntimeError('unexpected attached target; Gradle discovery refused')
    RESULT['exclusive_inventory'] = {'target_count': 1, 'owned_online_count': 1, 'unexpected_count': 0}
    RESULT.update(state='instrumentation', gradle_started=now()); save()
    # ANDROID_SERIAL is consumed by AGP9.4.1 exact device filter; fail if target missing/offline.
    before = gradle_processes('private-gradle-processes-before')
    RESULT['gradle_terminal_proven'] = False
    try:
        gradle = cmd(['./gradlew', '--offline', '--no-daemon', '--max-workers=2', ':core:vault:connectedDevDebugAndroidTest', '--stacktrace',
                      '-x', ':app:fetchTestModel', '-x', ':inference-service:fetchTestModel',
                      '-Pandroid.testInstrumentationRunnerArguments.class=' + ','.join(EXPECTED_METHODS)],
                     timeout=2700, check=False, label='scoped-vault-gradle')
        RESULT['gradle_exit'] = gradle.returncode
    finally:
        after = gradle_processes('private-gradle-processes-after')
        candidates = set(after) - set(before)
        RESULT['new_gradle_candidates'] = sorted(candidates)
        deadline = time.monotonic() + 30
        while candidates:
            current = gradle_processes('private-gradle-processes-terminal')
            candidates.intersection_update(current)
            if not candidates or time.monotonic() >= deadline: break
            time.sleep(1)
        RESULT['surviving_new_gradle_candidates'] = sorted(candidates)
        RESULT['gradle_terminal_proven'] = RESULT.get('gradle_exit') == 0 and not candidates
    RESULT['state'] = 'collecting'
    save()


def finish_evidence():
    if EMU is not None and EMU.poll() is None:
        try: adb_raw(['logcat', '-d', '-v', 'threadtime'], timeout=30, check=False)
        except Exception as e: RESULT['logcat_collection_error'] = repr(e)
    xml, counts, ids = raw_xml_inventory()
    RESULT['raw_xml'] = xml; RESULT['counts'] = dict(counts)
    RESULT['duplicate_identities'] = [list(k) + [v] for k,v in ids.items() if v != 1]
    expected_ids = collections.Counter({('core/vault', cls, method): 1 for cls, methods in EXPECTED_METHODS.items() for method in methods})
    RESULT['expected_methods'] = {cls: sorted(methods) for cls, methods in EXPECTED_METHODS.items()}
    RESULT['missing_identities'] = [list(x) for x in (expected_ids - ids).elements()]
    RESULT['unexpected_identities'] = [list(x) for x in (ids - expected_ids).elements()]
    RESULT['exact_scoped_identity_gate'] = (ids == expected_ids and len(xml) == 1 and sum(ids.values()) == 16
        and not any(counts.get(key, 0) for key in ('failure', 'error', 'skipped')))
    RESULT['final_source_sha'] = git('rev-parse', 'HEAD')
    RESULT['final_tracked_status'] = git('status', '--porcelain', '--untracked-files=all')
    RESULT['final_source_files'] = {p: {'bytes': (ROOT / p).stat().st_size, 'sha256': sha(ROOT / p)} for p in RESULT['source_files']}
    if RESULT['source_files'] != RESULT['final_source_files']: raise RuntimeError('executed source bytes changed')
    RESULT['owner_avd_after'] = {p: sha(p) for p in RESULT.get('owner_avd_before', {})}
    RESULT['owner_avd_unchanged'] = RESULT.get('owner_avd_before') == RESULT['owner_avd_after']
    with zipfile.ZipFile(RUN / 'raw-instrumentation.zip', 'x', zipfile.ZIP_DEFLATED) as z:
        for module in MODULES:
            for suffix in ('outputs/androidTest-results/connected', 'reports/androidTests/connected'):
                for p in sorted((ROOT / module / 'build' / suffix).rglob('*')):
                    if p.is_file(): z.write(p, p.relative_to(ROOT))
    RESULT['raw_archive_sha256'] = sha(RUN / 'raw-instrumentation.zip')
    RESULT['retained_apks'] = []
    for module in MODULES:
        for apk in sorted((ROOT / module / 'build/outputs/apk/androidTest/dev/debug').rglob('*.apk')):
            relative = apk.relative_to(ROOT)
            target = RUN / 'retained-apks' / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(apk, target)
            RESULT['retained_apks'].append({'path': str(relative), 'bytes': target.stat().st_size, 'sha256': sha(target),
                'attribution': 'host DEV vault instrumentation APK output; no installed APK readback'})
    RESULT['native_entries'] = []
    for apk in RESULT['retained_apks']:
        with zipfile.ZipFile(RUN / 'retained-apks' / apk['path']) as z:
            for entry in z.infolist():
                if entry.filename.startswith('lib/') and entry.filename.endswith('.so'):
                    data = z.read(entry)
                    RESULT['native_entries'].append({'apk': apk['path'], 'path': entry.filename, 'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()})
    RESULT['passed'] = (RESULT.get('gradle_exit') == 0 and RESULT.get('gradle_terminal_proven') is True
        and RESULT.get('final_source_sha') == EXPECTED and RESULT.get('final_tracked_status') == ''
        and RESULT.get('exact_scoped_identity_gate') is True and RESULT.get('owner_avd_unchanged') is True
        and len(RESULT['retained_apks']) == 1 and not RESULT['duplicate_identities'])


def cleanup():
    if 'gradle_started' in RESULT and RESULT.get('gradle_exit') != 0:
        cmd(['/bin/ps', '-axo', 'pid=,ppid=,pgid=,lstart=,command='], label='private-gradle-processes-failure')
    if EMU is not None:
        if EMU.poll() is None:
            # Signal only the retained unreaped child, never a cached PID or process name.
            EMU.terminate()
            try: EMU.wait(timeout=20)
            except subprocess.TimeoutExpired: EMU.kill(); EMU.wait(timeout=10)
        RESULT['emulator_terminal_exit'] = EMU.returncode
    remaining_listeners = {str(port): sorted(listeners(port)) for port in PORTS} if EMU is not None else {}
    RESULT['emulator_listeners_after_cleanup'] = remaining_listeners
    released = []
    for p in reversed(LEASES):
        if p.name == 'build.lock' and 'gradle_started' in RESULT and RESULT.get('gradle_terminal_proven') is not True:
            RESULT.setdefault('retained_leases', []).append({'path': str(p), 'reason': 'Gradle successful terminal completion not proven; coordinator must establish daemon termination before release.'})
            continue
        if p.name == 'device.lock' and any(remaining_listeners.values()):
            RESULT.setdefault('retained_leases', []).append({'path': str(p), 'reason': 'Emulator listener cleanup not proven; never kill unrelated listeners.'})
            continue
        v = json.loads((p / 'owner.json').read_text())
        if v.get('owner') == OWNER and v.get('token') == TOKEN:
            (p / 'owner.json').unlink(); p.rmdir(); released.append(p.name)
        else: RESULT.setdefault('lease_release_refusals', []).append(str(p))
    RESULT['released_matching_leases'] = released
    if RESULT.get('retained_leases'): RESULT['passed'] = False
    RESULT['ended'] = now()
    save()

if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--execute-after-root-grant', required=True, choices=[EXPECTED])
    a = p.parse_args()
    if not re.fullmatch('[0-9a-f]{40}', EXPECTED): raise SystemExit('Root source freeze has not been substituted')
    if sys.platform != 'darwin' or os.uname().machine != 'arm64': raise SystemExit('Only qualified macOS ARM64 host')
    if RUN.exists():
        raise SystemExit('Refusing existing run directory; original evidence untouched')
    try:
        execute()
    except BaseException as e:
        RESULT['error'] = repr(e); RESULT['passed'] = False
    finally:
        if RUN.exists():
            try:
                # Do not scan pre-existing XML if execution never reached the cleared-output phase.
                if 'gradle_started' in RESULT: finish_evidence()
            except BaseException as e: RESULT['evidence_error'] = repr(e); RESULT['passed'] = False
            try: cleanup()
            except BaseException as e: RESULT['cleanup_error'] = repr(e); RESULT['passed'] = False; save()
    print(json.dumps({k: RESULT.get(k) for k in ('passed','error','evidence_error','cleanup_error','counts','gradle_exit','source_sha','released_matching_leases')}, indent=2))
    raise SystemExit(0 if RESULT.get('passed') is True and not any(k in RESULT for k in ('error','evidence_error','cleanup_error')) else 1)
