"""Real ENOSPC and exact pre-delete process death, scoped to one fresh hosted emulator fixture."""

from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
from pathlib import Path
import queue
import re
import shlex
import subprocess
import time
import zipfile

APP = 'dev.ipf.whitenoise.android.mdkqualification'
NATIVE_SHA256 = 'b83162518d70185c98af5719898609e7e25b105ec0c107b785be8c3643b290b8'


def verify_native(apk):
    """Offsets are valid only for this entire published library, with the observed ELF load mapping."""
    with zipfile.ZipFile(apk) as archive:
        native = archive.read('lib/x86_64/libmarmot_uniffi.so')
    if hashlib.sha256(native).hexdigest() != NATIVE_SHA256:
        raise RuntimeError('Fault hook is not reviewed for this native binary')
    if native[0x2cbe660:0x2cbe672].hex() != '415741564154534881ec1801000048833e00':
        raise RuntimeError('Fault hook instruction file mapping mismatch')


def wait_until(probe, timeout=120):
    """Bound every external barrier; a missing hook is a failure, never a skipped qualification."""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        value = probe()
        if value:
            return value
        time.sleep(0.2)
    raise RuntimeError('Native fault barrier timed out')


def legacy_media_root(paths, root, receipt):
    """Target the baseline sender's legacy upload body; the receiver is already file-backed on master."""
    sender = receipt.get('sender')
    if len(paths) != 2 or any(not path.startswith(root + '/Marmot/') for path in paths):
        raise RuntimeError('Expected only the two synthetic account databases')
    accounts = [Path(path).parent for path in paths if Path(path).parent.name == sender]
    if not isinstance(sender, str) or len(accounts) != 1:
        raise RuntimeError('Could not identify the synthetic sender media directory')
    return str(accounts[0] / 'media')


def run_faults(adb, instrument, stop, ports, reports, runner, test_class):
    """Keep faults before ordinary upgrade so its independent read/cold phases prove native recovery."""
    if os.environ.get('GITHUB_ACTIONS') != 'true' or adb('shell', 'getprop', 'ro.kernel.qemu').strip() != '1':
        raise RuntimeError('Hosted emulator required for native fault injection')
    verify_native(Path('build/mdk-pair/candidate/app.apk'))
    import frida
    if frida.__version__ != '17.5.1':
        raise RuntimeError('Unreviewed fault injector version')
    stop()
    if adb('shell', 'id', '-u').strip() != '0':
        raise RuntimeError('Emulator must be rooted before creating fixture relay/blob transports')
    adb('push', str(Path(os.environ['RUNNER_TEMP']) / 'mdk-frida-server'), '/data/local/tmp/mdk-frida-server')
    adb('shell', 'chmod', '700', '/data/local/tmp/mdk-frida-server')
    # Direct PID attachment needs no Android spawning helpers. Default preload injects zygote/system_server.
    # Detach all three descriptors: daemonization alone can leave adb's captured shell pipe open.
    server_pid = adb('shell', 'nohup /data/local/tmp/mdk-frida-server --disable-preload -l 127.0.0.1:27042 '
                     '</dev/null >/data/local/tmp/mdk-frida-server.log 2>&1 & echo $!').strip()
    if not re.fullmatch(r'[1-9][0-9]*', server_pid):
        raise RuntimeError('Fault injector did not report its owned process')
    try:
        adb('shell', 'kill', '-0', server_pid)
    finally:
        retain_startup_log(adb, reports)
    adb('forward', 'tcp:27042', 'tcp:27042')
    device = frida.get_device_manager().add_remote_device('127.0.0.1:27042')
    root = f'/data/user/0/{APP}/files'
    paths = adb('shell', 'find', root + '/Marmot', '-name', 'session.sqlite').splitlines()
    receipt = json.loads(adb('shell', 'run-as', APP, 'cat', 'files/upgrade-fixture.json'))
    media = legacy_media_root(paths, root, receipt)
    adb('shell', 'run-as', APP, 'mkdir', '-p', media)
    uid = adb('shell', 'stat', '-c', '%u', media).strip()
    label = adb('shell', 'ls', '-Zd', media).split()[0]
    if not uid.isdecimal() or not label.startswith('u:object_r:app_data_file:'):
        raise RuntimeError('Unexpected fixture directory identity')
    facts = {'native_sha256': NATIVE_SHA256, 'injector_version': frida.__version__, 'events': []}
    mounted = False
    try:
        adb('shell', 'mount', '-t', 'tmpfs', '-o', 'size=64m,mode=0700', 'tmpfs', media)
        mounted = True
        adb('shell', 'chown', f'{uid}:{uid}', media)
        adb('shell', 'chcon', label, media)
        if adb('shell', 'stat', '-f', '-c', '%t', media).strip().lower() != '1021994':
            raise RuntimeError('Fault volume is not bounded tmpfs')
        run_one('enospc', device, adb, instrument, stop, ports, reports, runner, test_class, media, facts)
        stop()
        adb('shell', 'rm', media + '/qualification-filler')
        adb('shell', 'umount', media)
        mounted = False
        run_one('crash', device, adb, instrument, stop, ports, reports, runner, test_class, media, facts)
        stop()
        instrument('crash-window-witness', test_class, {'retainedAccountHistoryAndMedia'},
                   (*ports, '-e', 'qualificationPhase', 'fault-witness'))
        facts['ready_file_and_legacy_coexist'] = True
    finally:
        retain_startup_log(adb, reports)
        stop()
        if mounted:
            adb('shell', 'umount', media)
        (reports / 'native-faults.json').write_text(json.dumps(facts, indent=2) + '\n')
        adb('shell', f'if kill -0 {server_pid} 2>/dev/null; then kill {server_pid}; fi')


def retain_startup_log(adb, reports):
    """Keep bounded disposable-emulator startup diagnostics without masking the actual qualification failure."""
    try:
        output = adb('shell', 'tail', '-c', '32768', '/data/local/tmp/mdk-frida-server.log', timeout=10)
    except subprocess.SubprocessError as error:
        output = 'Injector log unavailable: ' + type(error).__name__ + '\n'
    (reports / 'injector-startup.txt').write_text(output)


def run_one(mode, device, adb, instrument, stop, ports, reports, runner, test_class, media, facts):
    """Attach before native start; only the fault trigger releases the waiting worker."""
    stop()
    for name in ('fault-awaiting-hook', 'fault-hook-ready'):
        adb('shell', 'run-as', APP, 'rm', '-f', 'files/' + name)
    phase = 'fault-' + mode
    events = queue.Queue()
    with ThreadPoolExecutor(max_workers=1) as executor:
        if mode == 'enospc':
            future = executor.submit(instrument, phase, test_class, {'retainedAccountHistoryAndMedia'},
                                     (*ports, '-e', 'qualificationPhase', phase))
        else:
            future = executor.submit(adb, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class', test_class,
                                     *ports, '-e', 'qualificationPhase', phase, runner, timeout=180)
        wait_until(lambda: adb('shell', f'run-as {APP} test -f files/fault-awaiting-hook; echo $?').strip() == '0')
        pid = int(adb('shell', 'pidof', APP).strip())
        session = device.attach(pid)
        source = Path('scripts/mdk-native-fault.js').read_text().replace('FAULT_MODE', json.dumps(mode))
        source = source.replace('MEDIA_ROOT', json.dumps(media))
        script = session.create_script(source)
        script.on('message', lambda message, data: events.put(message))
        try:
            script.load()
            event(events, 'armed', facts)
            adb('shell', 'run-as', APP, 'touch', 'files/fault-hook-ready')
            if mode == 'enospc':
                event(events, 'admitted-media-write-blocked', facts)
                # The 64 MiB mount bounds allocation; never fill /data or the physical device volume.
                command = f'dd if=/dev/zero of={shlex.quote(media + "/qualification-filler")} bs=4096 count=17000 2>&1; echo EXIT:$?'
                result = adb('shell', command)
                if 'No space left on device' not in result or 'EXIT:0' in result:
                    raise RuntimeError('Could not establish actual kernel ENOSPC on the fixture volume')
                script.post({'type': 'volume-filled'})
                failed = event(events, 'media-write-result', facts)
                if failed['result'] != -1 or failed['errno'] != 28:
                    raise RuntimeError('Native write did not fail with kernel ENOSPC')
                future.result(timeout=180)
                facts['enospc_paused_legacy_retained'] = True
            else:
                event(events, 'published-before-legacy-delete', facts)
                adb('shell', 'kill', '-KILL', str(pid))
                output = future.result(timeout=30)
                (reports / 'fault-crash.txt').write_text(output)
                if not re.search(r'Process crashed|INSTRUMENTATION_FAILED|shortMsg', output):
                    raise RuntimeError('Instrumentation did not witness the intentional process death')
                facts['pre_delete_sigkill'] = True
        finally:
            stop()
            if not session.is_detached:
                session.detach()


def event(events, expected, facts):
    """Record only fixed hook metadata; never expose paths, SQL, account identities or native arguments."""
    try:
        message = events.get(timeout=120)
    except queue.Empty as error:
        raise RuntimeError(f'Native hook timed out waiting for {expected}') from error
    if message.get('type') != 'send' or message.get('payload', {}).get('event') != expected:
        raise RuntimeError(f'Native hook did not report {expected}: {message.get("type")}')
    payload = message['payload']
    facts['events'].append(payload)
    print('native fault: ' + expected, flush=True)
    return payload
