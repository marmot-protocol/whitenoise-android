"""Hosted-only populated native upgrade and recovery qualification on one explicitly selected emulator."""

import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import threading
import time
import xml.etree.ElementTree as ET

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools/attachment-fixture'))
from fixture_relay import FixtureRelay
from fixture_server import FixtureServer

APP = 'dev.ipf.whitenoise.android.mdkqualification'
RUNNER = APP + '.test/dev.ipf.whitenoise.android.qualification.QualificationRunner'
UPGRADE = 'dev.ipf.whitenoise.android.qualification.MarmotUpgradeFixtureTest'
RECOVERY = 'dev.ipf.whitenoise.android.state.MarmotKitRecoveryQualificationTest'
RECOVERY_CASES = {
    'offlineReplayBeyondTheRelayClampRecoversEveryMessage',
    'epochGapIsRecoveredFromTheNewestEventsWhenComparisonFails',
    'catchingUpRefusalIsAcknowledgedForOneChange',
    'retryAfterAnIncompleteLookupCreatesExactlyOneGroup',
}
IGNORED = {'oneSenderBeyondTheReorderToleranceRecoversEveryMessage',
           'offlineReplayAfterTheLookbackRecoversEveryMessage'}
REPORTS = Path('build/mdk-qualification')


def adb(*arguments, timeout=120, binary=False):
    """Never target an implicit device or any serial outside the CI emulator."""
    result = subprocess.run(['adb', '-s', 'emulator-5554', *arguments], check=True,
                            capture_output=True, text=not binary, timeout=timeout)
    return result.stdout


def instrument(name, test_class, expected, extra=()):
    """Require each named case's terminal PASS; a zero adb exit or ignored test cannot qualify it."""
    print(f'{name}: starting native qualification', flush=True)
    try:
        output = adb('shell', 'am', 'instrument', '-w', '-r', '-e', 'class', test_class,
                     *extra, RUNNER, timeout=1200)
    except subprocess.TimeoutExpired as error:
        partial = error.stdout or b''
        if isinstance(partial, bytes):
            partial = partial.decode('utf-8', errors='replace')
        (REPORTS / f'{name}.txt').write_text(partial + '\nHOST_TIMEOUT: qualification incomplete\n')
        raise
    (REPORTS / f'{name}.txt').write_text(output)
    statuses = {}
    current = {}
    for line in output.splitlines():
        if line.startswith('INSTRUMENTATION_STATUS: '):
            key, _, value = line.removeprefix('INSTRUMENTATION_STATUS: ').partition('=')
            current[key] = value
        elif line.startswith('INSTRUMENTATION_STATUS_CODE: '):
            code = int(line.split(': ', 1)[1])
            if 'test' in current and code != 1:
                statuses[current['test']] = code
            current = {}
    passed = {case for case, code in statuses.items() if code == 0}
    skipped = {case for case, code in statuses.items() if code == -3}
    unexpected = {case: code for case, code in statuses.items() if code not in (0, -3)}
    fact = {'passed': sorted(passed), 'skipped': sorted(skipped), 'failed': unexpected}
    (REPORTS / f'{name}.json').write_text(json.dumps(fact, indent=2) + '\n')
    expected_skipped = IGNORED if test_class == RECOVERY else set()
    if passed != expected or unexpected or skipped != expected_skipped:
        raise RuntimeError(f'{name}: required cases did not all pass; see retained transcript')
    if 'INSTRUMENTATION_CODE: -1' not in output:
        raise RuntimeError(f'{name}: runner did not finish successfully')
    print(f'{name}: passed {len(passed)} cases; explicitly ignored {len(skipped)} cases', flush=True)


def stop():
    """Terminate only this disposable package; never clear storage or reinstall to hide a failure."""
    adb('shell', 'am', 'force-stop', APP)
    if adb('shell', f'pidof {APP} || true').strip():
        raise RuntimeError('Fixture process survived force-stop')
    disable_background_entries()


def install(role):
    """Both app generations use the same package and signer, and every update is an ordinary -r install."""
    for name in ('app', 'test'):
        adb('install', '-r', '-t', str(Path('build/mdk-pair') / role / f'{name}.apk'), timeout=180)
    disable_background_entries()


def disable_background_entries():
    """Reassert fixture ownership after production WorkManager initialization and before native instrumentation."""
    for component in ('androidx.work.impl.background.systemjob.SystemJobService',
                      'dev.ipf.whitenoise.android.notifications.BackgroundConnectionBootReceiver'):
        # disable-user is a package state; PackageManager rejects it for individual components.
        output = adb('shell', 'pm', 'disable', '--user', '0', APP + '/' + component)
        if not output.strip().endswith('new state: disabled'):
            raise RuntimeError(f'Fixture background component {component} was not disabled')


def usable_chat(name):
    """Observe production MainActivity after real bootstrap, then open retained synthetic conversation history."""
    print(f'{name}: opening retained conversation through MainActivity', flush=True)
    adb('shell', 'pm', 'grant', APP, 'android.permission.POST_NOTIFICATIONS')
    adb('shell', 'am', 'start', '-W', '-n', APP + '/dev.ipf.whitenoise.android.MainActivity')
    deadline = time.monotonic() + 120
    opened = False
    while time.monotonic() < deadline:
        adb('shell', 'uiautomator', 'dump', '/sdcard/qualification-window.xml')
        xml = adb('shell', 'cat', '/sdcard/qualification-window.xml')
        nodes = list(ET.fromstring(xml).iter('node'))
        conversation = any(n.get('resource-id', '').endswith('composer-pill-surface') for n in nodes)
        chat_list = any(n.get('resource-id', '').endswith('chats.scope.chats') for n in nodes)
        consent = any(n.get('text') == 'Help Improve White Noise' for n in nodes)
        for node in nodes:
            text = node.get('text', '')
            if 'Retained qualification history' in text and conversation and not chat_list:
                (REPORTS / f'{name}-window.xml').write_text(xml)
                (REPORTS / f'{name}.png').write_bytes(adb('exec-out', 'screencap', '-p', binary=True))
                print(f'{name}: retained conversation ready', flush=True)
                return
            close_consent = (consent and node.get('content-desc') == 'Close' and node.get('enabled') == 'true')
            open_chat = (chat_list and not conversation and not consent
                         and text == 'Nightly upgrade qualification' and not opened)
            if close_consent or open_chat:
                bounds = list(map(int, re.findall(r'\d+', node.get('bounds', ''))))
                if len(bounds) != 4:
                    raise RuntimeError('Conversation bounds unavailable')
                adb('shell', 'input', 'tap', str((bounds[0] + bounds[2]) // 2), str((bounds[1] + bounds[3]) // 2))
                if not consent:
                    opened = True
                break
        time.sleep(2)
    (REPORTS / f'{name}-window.xml').write_text(xml)
    (REPORTS / f'{name}.png').write_bytes(adb('exec-out', 'screencap', '-p', binary=True))
    raise RuntimeError(f'{name}: retained conversation did not become usable')


def main():
    """Keep upgrade phases dependent, but finish both independent recovery modes even if migration fails."""
    if os.environ.get('GITHUB_ACTIONS') != 'true' or adb('shell', 'getprop', 'ro.kernel.qemu').strip() != '1':
        raise RuntimeError('Hosted disposable emulator required')
    if adb('shell', 'am', 'get-current-user').strip() != '0':
        raise RuntimeError('The disposable emulator must use its primary user')
    if APP in adb('shell', 'pm', 'list', 'packages', APP):
        raise RuntimeError('A fresh isolated fixture package is required')
    REPORTS.mkdir(parents=True, exist_ok=True)
    private = Path(os.environ['RUNNER_TEMP']) / 'mdk-qualification-private'
    private.mkdir(mode=0o700)
    relay = FixtureRelay()
    blob = FixtureServer(private / 'blob')
    facts = {'source': os.environ['GITHUB_SHA'], 'device': 'emulator-5554',
             'api': adb('shell', 'getprop', 'ro.build.version.sdk').strip(),
             'abi': adb('shell', 'getprop', 'ro.product.cpu.abi').strip(),
             'package': APP, 'exact_signed_preview_qualified': False, 'phases': {}}
    for server in (relay, blob):
        threading.Thread(target=server.serve_forever, daemon=True).start()
        port = server.server_address[1]
        adb('reverse', '--no-rebind', f'tcp:{port}', f'tcp:{port}')
    ports = ('-e', 'relayPort', str(relay.port), '-e', 'blobPort', str(blob.server_port))
    try:
        try:
            install('baseline')
            instrument('baseline-seed', UPGRADE, {'retainedAccountHistoryAndMedia'},
                       (*ports, '-e', 'qualificationPhase', 'seed'))
            stop()
            # Preserve the original encrypted synthetic root on the runner, never rewrite or restore migration rows.
            backup = adb('exec-out', 'run-as', APP, 'tar', '-cf', '-', 'files', binary=True)
            (private / 'baseline-root.tar').write_bytes(backup)
            facts['baseline_root_sha256'] = hashlib.sha256(backup).hexdigest()
            usable_chat('baseline-ready')
            stop()
            facts['phases']['baseline'] = 'passed'
            blob.acquisition_unavailable.set()
            install('candidate')
            from run_mdk_faults import run_faults
            run_faults(adb, instrument, stop, ports, REPORTS, RUNNER, UPGRADE)
            facts['phases']['kernel_enospc'] = 'passed'
            facts['phases']['pre_delete_crash'] = 'passed'
            instrument('upgrade-read', UPGRADE, {'retainedAccountHistoryAndMedia'},
                       (*ports, '-e', 'qualificationPhase', 'upgrade'))
            stop()
            usable_chat('upgrade-ready')
            facts['phases']['upgrade'] = 'passed'
            stop()
            usable_chat('cold-ready')
            stop()
            instrument('cold-read', UPGRADE, {'retainedAccountHistoryAndMedia'},
                       (*ports, '-e', 'qualificationPhase', 'cold'))
            facts['phases']['cold_restart'] = 'passed'
        except (RuntimeError, subprocess.SubprocessError) as error:
            facts['phases']['upgrade_campaign'] = type(error).__name__ + ': ' + str(error)
        # Keep core upgrade/cold evidence even if the separate pending upload qualification fails.
        if facts['phases'].get('cold_restart') == 'passed':
            try:
                stop()
                instrument('pending-promotion', UPGRADE, {'retainedAccountHistoryAndMedia'},
                           (*ports, '-e', 'qualificationPhase', 'promote'))
                facts['phases']['pending_promotion'] = 'passed'
                stop()
                instrument('promoted-cold-read', UPGRADE, {'retainedAccountHistoryAndMedia'},
                           (*ports, '-e', 'qualificationPhase', 'promoted-cold'))
                facts['phases']['promoted_cold_restart'] = 'passed'
                # Only the known synthetic blob service may satisfy explicit recovery of deliberately damaged files.
                blob.acquisition_unavailable.clear()
                for damage in ('corrupt', 'lost'):
                    stop()
                    instrument('media-' + damage, UPGRADE, {'retainedAccountHistoryAndMedia'},
                               (*ports, '-e', 'qualificationPhase', damage))
                    facts['phases']['media_' + damage + '_recovery'] = 'passed'
            except (RuntimeError, subprocess.SubprocessError) as error:
                facts['phases']['pending_promotion_campaign'] = type(error).__name__ + ': ' + str(error)
        # Separate generated roots in the maintained recovery fixture do not depend on upgrade success.
        install('candidate')
        for comparison in ('refuse', 'ignore'):
            try:
                stop()
                instrument('recovery-' + comparison, RECOVERY, RECOVERY_CASES,
                           ('-e', 'comparison', comparison))
                facts['phases']['recovery_' + comparison] = 'passed'
            except (RuntimeError, subprocess.SubprocessError) as error:
                facts['phases']['recovery_' + comparison] = type(error).__name__ + ': ' + str(error)
    finally:
        (REPORTS / 'qualification.json').write_text(json.dumps(facts, indent=2) + '\n')
        for server in (relay, blob):
            server.shutdown()
            server.server_close()
    required = {'baseline', 'upgrade', 'cold_restart', 'pending_promotion', 'promoted_cold_restart',
                'recovery_refuse', 'recovery_ignore', 'kernel_enospc', 'pre_delete_crash',
                'media_corrupt_recovery', 'media_lost_recovery'}
    if set(facts['phases']) != required or any(value != 'passed' for value in facts['phases'].values()):
        raise SystemExit('Qualification failed; retained reports distinguish failed and unexecuted phases')


if __name__ == '__main__':
    main()
