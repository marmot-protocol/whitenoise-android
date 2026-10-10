"""Own one synthetic PIN on a freshly qualified hosted AVD; never replace a user credential."""

import json
import os
import re
import subprocess
import time

PACKAGE = 'dev.ipf.whitenoise.android.maestrolab'
PROBE = 'dev.ipf.whitenoise.android.maestro.MaestroCredentialProbeTest'
RUNNER = 'dev.ipf.whitenoise.android.maestro.MaestroFixtureRunner'
PIN = '493817'  # Public, disposable fixture value. Never a real user's credential.
ADB = ['adb', '-s', 'emulator-5554']


def command(arguments, timeout=15):
    """Every control/probe command is bounded; callers reconcile an uncertain write once."""
    return subprocess.run(arguments, check=True, capture_output=True, text=True, timeout=timeout).stdout


def probe_record(output, generation, stage):
    """Require one completed read-only native observation for this exact generation/stage."""
    prefix = 'INSTRUMENTATION_STATUS: maestroCredential='
    rows = [line.removeprefix(prefix) for line in output.splitlines() if line.startswith(prefix)]
    if len(rows) != 1 or 'OK (1 test)' not in output:
        raise ValueError('Missing or duplicate completed credential probe')
    row = json.loads(rows[0])
    if (not isinstance(row, dict) or type(row.get('schema')) is not int or row['schema'] != 1
            or row.get('generation') != generation or row.get('stage') != stage
            or row.get('package') != PACKAGE or type(row.get('user')) is not int or row['user'] != 0
            or type(row.get('sdk')) is not int or row['sdk'] != 34 or row.get('qemu') is not True
            or row.get('noBiometricAlternative') is not True
            or any(type(row.get(field)) is not bool for field in ('secure', 'credentialAvailable'))):
        raise ValueError('Unqualified or foreign credential probe')
    return row


def restored_record(row, generation):
    """A result boolean alone cannot certify that this generation restored Android's baseline."""
    expected = {'schema': 1, 'generation': generation, 'package': PACKAGE, 'user': 0, 'sdk': 34,
                'stage': 'restored', 'qemu': True, 'noBiometricAlternative': True,
                'secure': False, 'credentialAvailable': False, 'credentialRestored': True}
    if (not isinstance(row, dict) or any(row.get(key) != value for key, value in expected.items())
            or any(type(row.get(key)) is not bool for key in ('qemu', 'noBiometricAlternative',
                                                             'secure', 'credentialAvailable', 'credentialRestored'))
            or any(type(row.get(key)) is not int for key in ('schema', 'user', 'sdk'))):
        raise ValueError('Credential restoration was not independently certified')
    return row


class DisposableCredential:
    """One generation owns at most one installation and one clear, with authoritative readbacks."""

    def __init__(self, generation, directory):
        if not re.fullmatch('[0-9a-f]{32}', generation):
            raise ValueError('Fresh fixture generation required')
        self.generation, self.directory = generation, directory
        self.attempted = False
        self.admitted = False
        self.owned = False
        self.restore_attempted = False
        self.restore_deadline = None

    def execute(self, arguments):
        timeout = 15
        if self.restore_deadline is not None:
            timeout = min(timeout, self.restore_deadline - time.monotonic())
            if timeout <= 0:
                raise TimeoutError('Credential restoration deadline exceeded')
        return command(arguments, timeout=timeout)

    def observe(self, stage):
        output = self.execute(ADB + ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class', PROBE,
                                '-e', 'fixtureGeneration', self.generation, '-e', 'credentialStage', stage,
                                f'{PACKAGE}.test/{RUNNER}'])
        (self.directory / f'credential-probe-{stage}.txt').write_text(output)
        row = probe_record(output, self.generation, stage)
        (self.directory / f'credential-{stage}.json').write_text(json.dumps(row, indent=2) + '\n')
        return row

    def install(self):
        if self.attempted:
            raise ValueError('Credential installation cannot be replayed')
        if (os.environ.get('GITHUB_ACTIONS') != 'true' or os.environ.get('MAESTRO_ANDROID_API') != '34'
                or not re.fullmatch('[0-9a-f]{40}', os.environ.get('GITHUB_SHA', ''))
                or not re.fullmatch('[1-9][0-9]*', os.environ.get('GITHUB_RUN_ID', ''))):
            raise ValueError('Credential cases require the identified hosted API34 workflow')
        baseline = self.observe('baseline')
        if baseline['secure'] or baseline['credentialAvailable']:
            raise ValueError('Pre-existing credential must never be replaced')
        self.admitted = True
        self.attempted = True  # Retain uncertainty before the single write; no --old replacement.
        try:
            self.execute(ADB + ['shell', 'locksettings', 'set-pin', '--user', '0', PIN])
        except (subprocess.SubprocessError, OSError) as error:
            # A lost response can leave the PIN installed. Reconcile; do not set it again.
            self.reconcile_installation()
            raise ValueError('Uncertain credential installation; restoration required') from error
        self.reconcile_installation()
        if not self.owned:
            raise ValueError('Actual synthetic credential installation was not verified')

    def reconcile_installation(self):
        observed = self.observe('installed')
        if not observed['secure']:
            return
        output = self.execute(ADB + ['shell', 'locksettings', 'verify', '--old', PIN, '--user', '0'])
        if 'Lock credential verified successfully' not in output:
            raise ValueError('Installed credential does not belong to this generation')
        self.owned = True
        (self.directory / 'credential-ownership.txt').write_text(output)
        if not observed['credentialAvailable']:
            raise ValueError('Installed credential is unavailable to real app authentication')

    def restore(self):
        if self.restore_attempted:
            raise ValueError('Credential restoration cannot be replayed')
        self.restore_attempted = True
        self.restore_deadline = time.monotonic() + 60
        if not self.admitted:
            raise ValueError('No admitted baseline for credential restoration')
        observed = self.observe('before-clear')
        if observed['secure'] or observed['credentialAvailable']:
            if not self.owned:
                self.reconcile_installation()
            if not self.owned:
                raise ValueError('Cannot clear an unowned credential')
            try:
                self.execute(ADB + ['shell', 'locksettings', 'clear', '--old', PIN, '--user', '0'])
            except (subprocess.SubprocessError, OSError):
                pass  # Read back once; never blindly replay an uncertain clear.
        row = self.observe('restored')
        row['credentialRestored'] = not row['secure'] and not row['credentialAvailable']
        restored_record(row, self.generation)
        (self.directory / 'credential-restored.json').write_text(json.dumps(row, indent=2) + '\n')
        return row


def accepted_unlock(output, pid, successes=1):
    """Real app callback markers certify cipher/session acceptance, never arbitrary log text."""
    if type(successes) is not int or successes not in (1, 2):
        raise ValueError('Known credential success count required')
    if type(pid) is not int or pid <= 0:
        raise ValueError('Matching native process identity required')
    events = []
    for line in output.splitlines():
        match = re.match(r'^\d\d-\d\d\s+\d\d:\d\d:\d\d\.\d+\s+(\d+)\s+\d+\s+I\s+WNAppUnlock\s*:\s+(.*)$', line)
        if match and int(match[1]) == pid:
            fields = dict(re.findall(r'(activity|session|event)=([A-Za-z0-9_-]+)', match[2]))
            if fields.get('activity') and re.fullmatch('[1-9][0-9]*', fields.get('session', '')):
                events.append(fields)
    cancelled = [row for row in events if row.get('event') == 'prompt-terminated']
    succeeded = [row for row in events if row.get('event') == 'prompt-succeeded']
    launched = [row for row in events if row.get('event') == 'prompt-launched']
    expected = list(range(1, successes + 2))
    if (len(cancelled) != 1 or int(cancelled[0]['session']) != 1
            or len(succeeded) != successes or [int(row['session']) for row in succeeded] != expected[1:]
            or [int(row['session']) for row in launched] != expected
            or len({row['activity'] for row in cancelled + succeeded + launched}) != 1):
        raise ValueError('Missing matching real cancellation and accepted crypto/session retry')
    terminals = cancelled + succeeded
    for index, (launch, terminal) in enumerate(zip(launched, terminals)):
        if events.index(launch) >= events.index(terminal):
            raise ValueError('Accepted callback preceded its actual prompt')
        if index and events.index(launch) <= events.index(terminals[index - 1]):
            raise ValueError('Another prompt launched before the preceding session terminated')
    if events.index(cancelled[0]) >= events.index(succeeded[0]):
        raise ValueError('Retry succeeded before the actual cancellation')
    return {'cancelledSession': 1, 'acceptedSession': expected[-1], 'acceptedSessions': expected[1:],
            'acceptedCryptoSession': True, 'pid': pid}


def credential_state(ready, verified, postcondition):
    """Require typed native cancellation/window evidence, ordered cover rotation and unlocked state."""
    disabled = postcondition == 'app-lock-credential-warm-disabled'
    delay_picker = postcondition == 'app-lock-credential-delay'
    evidence = verified.get('credentialEvidence')
    if (ready.get('appLockFixtureCredential') is not True or verified.get('appLockVerified') is not True
            or type(ready.get('nativePid')) is not int or ready['nativePid'] <= 0
            or not isinstance(evidence, dict) or evidence.get('cancelledSecure') is not True
            or evidence.get('acceptedState') is not True
            or type(evidence.get('rotatedCover')) is not bool
            or evidence['rotatedCover'] != (postcondition == 'app-lock-credential-rotation')
            or any(type(evidence.get(key)) is not int or evidence[key] <= 0
                   for key in ('cancelledSession', 'acceptedSession'))
            or evidence['cancelledSession'] != 1 or evidence['acceptedSession'] != (3 if disabled else 2)
            or type(evidence.get('disabledWarmReturn')) is not bool or evidence['disabledWarmReturn'] != disabled
            or type(evidence.get('delayChoicesVerified')) is not bool or evidence['delayChoicesVerified'] != delay_picker):
        raise ValueError('Missing typed native credential/window/session proof')
    rows = evidence.get('observations')
    if not isinstance(rows, list) or not 2 <= len(rows) <= 128:
        raise ValueError('Missing bounded app-lock observations')
    for row in rows:
        if (not isinstance(row, dict) or any(type(row.get(key)) is not bool
                for key in ('cover', 'secure', 'cancelled', 'evaluating', 'required', 'available'))
                or type(row.get('latestSession')) is not int or row['latestSession'] < 0
                or type(row.get('orientation')) is not int or row['orientation'] not in (1, 2)
                or row.get('delay') not in ('immediately', '1m', '5m', '15m')
                or 'storedDelay' not in row or row['storedDelay'] not in (None, 'immediately', '1m', '5m', '15m')
                or row.get('lifecycle') not in ('INITIALIZED', 'CREATED', 'STARTED', 'RESUMED', 'DESTROYED')
                or 'activeSession' not in row or (row['activeSession'] is not None
                                                 and type(row['activeSession']) is not int)):
            raise ValueError('Malformed native app-lock observation')
    def cancelled(row):
        return (row['cover'] and row['secure'] and row['cancelled'] and not row['evaluating']
                and row['activeSession'] is None and row['required'] and row['available']
                and row['latestSession'] == evidence['cancelledSession'])
    candidates = [index for index, row in enumerate(rows) if cancelled(row)]
    if not candidates:
        raise ValueError('No independently observed secure cancellation')
    if evidence['rotatedCover']:
        landscape = [index for index in candidates if index > candidates[0] and rows[index]['orientation'] == 2]
        if not landscape or not any(index > landscape[0] and rows[index]['orientation'] == 1 for index in candidates):
            raise ValueError('Missing ordered secure cover rotation/return')
    final = rows[-1]
    if (final['cover'] or final['evaluating'] or final['cancelled'] or final['activeSession'] is not None
            or final['required'] is not (not disabled) or not final['available']
            or final['delay'] != ('15m' if delay_picker else 'immediately')
            or final['storedDelay'] != ('15m' if delay_picker else None)
            or final['latestSession'] != evidence['acceptedSession']):
        raise ValueError('Final state did not accept the actual retry')
    if disabled:
        disabled_rows = [row for row in rows if not row['required'] and row['latestSession'] == 3]
        if any(row['cover'] or row['activeSession'] is not None for row in disabled_rows):
            raise ValueError('Disabled app exposed a new lock/session')
        stopped = [index for index, row in enumerate(disabled_rows) if row['lifecycle'] == 'CREATED']
        if not stopped or not any(row['lifecycle'] == 'RESUMED' for row in disabled_rows[stopped[0] + 1:]):
            raise ValueError('Actual disabled warm lifecycle was not observed')
    if delay_picker:
        previous = -1
        for value in ('immediately', '1m', '5m', '15m'):
            matching = [index for index, row in enumerate(rows)
                        if index > previous and row['required'] and row['delay'] == value
                        and row['storedDelay'] == value]
            if not matching:
                raise ValueError('Actual ordered delay choices were not persisted')
            previous = matching[0]
    return evidence


def regular_text(path):
    if path.is_symlink() or not path.is_file():
        raise ValueError('Missing regular credential evidence')
    return path.read_text()


def qualify_credential(leaf, generation, ready, verified, postcondition):
    """Reconcile native OS prerequisites, real accepted callback and verified credential restoration."""
    from_record = credential_state(ready, verified, postcondition)
    if 'Lock credential verified successfully' not in regular_text(leaf / 'credential-ownership.txt'):
        raise ValueError('Actual synthetic PIN ownership was not verified')
    for stage, secure in (('baseline', False), ('installed', True)):
        row = probe_record(regular_text(leaf / f'credential-probe-{stage}.txt'), generation, stage)
        if row['secure'] is not secure or row['credentialAvailable'] is not secure:
            raise ValueError('Wrong actual credential prerequisite')
        if json.loads(regular_text(leaf / f'credential-{stage}.json')) != row:
            raise ValueError('Credential observation differs from native probe')
    restored = restored_record(json.loads(regular_text(leaf / 'credential-restored.json')), generation)
    observed = probe_record(regular_text(leaf / 'credential-probe-restored.txt'), generation, 'restored')
    if {key: value for key, value in restored.items() if key != 'credentialRestored'} != observed:
        raise ValueError('Restoration differs from completed native OS readback')
    trace = accepted_unlock(regular_text(leaf / 'app-unlock-trace.txt'), ready['nativePid'],
                            2 if postcondition == 'app-lock-credential-warm-disabled' else 1)
    if any(trace[key] != from_record[key] for key in ('cancelledSession', 'acceptedSession')):
        raise ValueError('Native observation and actual callback disagree')
    if json.loads(regular_text(leaf / 'credential-accepted.json')) != {'generation': generation, **trace}:
        raise ValueError('Missing matching real crypto/session completion')
