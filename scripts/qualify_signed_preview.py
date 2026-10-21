"""Qualify unchanged trusted ARM64 preview APKs through ordinary UI on a hosted emulator."""

import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

if __package__:
    from .verify_mdk_qualification_pair import fixture_certificate
else:
    from verify_mdk_qualification_pair import fixture_certificate

APP = 'dev.ipf.whitenoise.android.preview.pr3371'
REPORTS = Path('build/mdk-signed-preview')
PAIR = Path('build/mdk-signed-preview-apks')
NATIVE = '8a67a47805b5b78d3e9fb67c83c5b99870abb106191a83a312508897ac688b06'
FIXTURE_NAME = 'PR3371 qualification'
HISTORY = ('Replies and reactions work across both profiles.', 'Great — the demo conversation is ready to explore.')


def adb(*args, binary=False, timeout=120):
    """Never let a hosted qualification command select a personal device implicitly."""
    return subprocess.check_output(['adb', '-s', 'emulator-5554', *args], text=not binary, timeout=timeout)


def sha(path):
    """Bind every result to the APK that was actually installed."""
    return hashlib.sha256(path.read_bytes()).hexdigest()


def download(url, destination, expected=None):
    """Download published bytes without modifying signing, packaging or native libraries."""
    with urllib.request.urlopen(url, timeout=180) as response:
        destination.write_bytes(response.read(70 * 1024 * 1024))
    if expected and sha(destination) != expected:
        raise RuntimeError('Published baseline APK checksum mismatch')


def verify_apk(apk, source, expected_native, certificate):
    """Require the isolated release package, trusted signer, exact native bytes and embedded source suffix."""
    sdk = Path(os.environ['ANDROID_HOME'])
    tools = sorted((sdk / 'build-tools').iterdir())[-1]
    signing = subprocess.check_output([str(tools / 'apksigner'), 'verify', '--print-certs', str(apk)], text=True)
    if fixture_certificate(signing) != certificate:
        raise RuntimeError('Unexpected trusted preview certificate')
    badging = subprocess.check_output([str(tools / 'aapt2'), 'dump', 'badging', str(apk)], text=True)
    if not re.search(r"^package: name='" + re.escape(APP) + r"' versionCode='2000000000'", badging, re.M):
        raise RuntimeError('Unexpected preview package or version')
    if f'preview-pr3371-{source[:7]}-isolated' not in badging or 'application-testOnly' in badging:
        raise RuntimeError('Unexpected preview source or test-only flag')
    with zipfile.ZipFile(apk) as archive:
        abis = {name.split('/')[1] for name in archive.namelist() if name.startswith('lib/')}
        native = hashlib.sha256(archive.read('lib/arm64-v8a/libmarmot_uniffi.so')).hexdigest()
    if abis != {'arm64-v8a'} or native != expected_native:
        raise RuntimeError('Published preview native identity mismatch')
    return {'source': source, 'apk_sha256': sha(apk), 'native_sha256': native, 'signer_sha256': certificate,
            'package': APP, 'versionCode': 2000000000}


def prepare():
    """Connect the signed candidate to the complete provenance of the successful trusted-dispatch build."""
    pin = json.loads(Path('scripts/mdk-preview-baseline.json').read_text())
    REPORTS.mkdir(parents=True, exist_ok=True)
    PAIR.mkdir(parents=True, exist_ok=True)
    download(pin['url'], PAIR / 'baseline.apk', pin['apk_sha256'])
    candidate_url = 'https://github.com/marmot-protocol/whitenoise-android/releases/download/android-pr-previews/whitenoise-pr-3371-isolated.apk'
    download(candidate_url, PAIR / 'candidate.apk')
    baseline = verify_apk(PAIR / 'baseline.apk', pin['source'], pin['native_sha256'], pin['signer_sha256'])
    candidate_source = os.environ.get('PREVIEW_DIAGNOSTIC_SOURCE', os.environ['GITHUB_SHA'])
    candidate = verify_apk(PAIR / 'candidate.apk', candidate_source, NATIVE, pin['signer_sha256'])
    unsigned = Path('build/mdk-preview-unsigned')
    provenance = dict(line.split('=', 1) for line in (unsigned / 'provenance.env').read_text().splitlines())
    build = json.loads(Path('build/mdk-preview-build-run.json').read_text())
    if (build['conclusion'] != 'success' or build['event'] != 'workflow_dispatch' or build['head_branch'] != 'master' or
            build['path'] != '.github/workflows/android-pr-apk.yml' or
            str(build['run_number']) != provenance.get('run_number')):
        raise RuntimeError('Preview build did not use the successful trusted default-branch dispatch')
    if (len(provenance) != 4 or provenance.get('head_sha') != candidate_source or
            provenance.get('channel') != 'isolated' or provenance.get('pr_number') != '3371'):
        raise RuntimeError('Unsigned build provenance is not the exact candidate')
    apks = list(unsigned.glob('*.apk'))
    if len(apks) != 1:
        raise RuntimeError('Expected one exact-head unsigned preview')
    with zipfile.ZipFile(apks[0]) as original, zipfile.ZipFile(PAIR / 'candidate.apk') as signed:
        # The trusted signer adds only its three v1-signature entries; every application byte must agree.
        extras = set(signed.namelist()) - set(original.namelist())
        signatures = {'META-INF/MANIFEST.MF', 'META-INF/WHITENOI.SF', 'META-INF/WHITENOI.RSA'}
        if extras != signatures or set(original.namelist()) - set(signed.namelist()):
            raise RuntimeError('Signed preview ZIP inventory differs from the exact-head build')
        for name in original.namelist():
            if original.read(name) != signed.read(name):
                raise RuntimeError('Signed preview application bytes differ from the exact-head build')
    facts = {'baseline': baseline, 'candidate': candidate, 'build_provenance': provenance, 'phases': {}}
    if os.environ.get('PREVIEW_DIAGNOSTIC_SOURCE'):
        facts.update({'diagnostic_only': True, 'workflow_source': os.environ['GITHUB_SHA']})
    (REPORTS / 'apk-provenance.json').write_text(json.dumps(facts, indent=2) + '\n')
    return facts


class PreviewUi:
    """Interact only with visible English labels; no instrumentation APK, private keys or database edits."""

    def snapshot(self):
        """Refresh bounds immediately before each action and reject bootstrap failure screens."""
        adb('shell', 'uiautomator', 'dump', '/sdcard/pr3371-preview.xml')
        xml = adb('shell', 'cat', '/sdcard/pr3371-preview.xml')
        self.xml = xml
        root = ET.fromstring(xml)
        self.nodes = list(root.iter('node'))
        self.parents = {child: parent for parent in root.iter() for child in parent}
        if re.search(r'migration.*(?:failed|error)|database.*(?:newer|incompatible)', xml, re.I):
            raise RuntimeError('Preview showed a bootstrap/migration error')
        return self.nodes

    def matches(self, text):
        """Reject labels whose Compose action ancestor is disabled, even when the text itself is enabled."""
        return [node for node in self.nodes if text in (node.get('text'), node.get('content-desc'))
                and self.enabled(node)]

    def enabled(self, node):
        """A visible text child does not override the enabled state of its containing action."""
        while node is not None:
            if node.get('enabled') == 'false':
                return False
            node = self.parents.get(node)
        return True

    def open_demo_setup(self):
        """Re-enter the baseline screen after its asynchronous saved-demo receipt has loaded."""
        for _ in range(6):
            self.click('Developer Tools', scroll=True)
            self.snapshot()
            if self.matches('Create demo conversation'):
                self.click('Create demo conversation')
                self.click('Create demo')
                return
            # Baseline canBegin reads Job.isCompleted, which does not itself trigger recomposition.
            # Normal back/re-entry observes completion without changing the signed baseline or its data.
            time.sleep(2)
            adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
        raise RuntimeError('Baseline demo setup remained unavailable after bounded screen re-entry')

    def open_settings(self, timeout=90):
        """Verify the Settings destination; retry only visible routes belonging to the original profile."""
        deadline = time.monotonic() + timeout
        swipes = 0
        while time.monotonic() < deadline:
            self.snapshot()
            direct = self.matches(f'Open settings, {FIXTURE_NAME}')
            selector = self.matches(f'Switch Profile, {FIXTURE_NAME}')
            # The selector also has a Settings label, but no settings-home Back action or Profile Keys row.
            if self.matches('Settings') and self.matches('Back'):
                if self.matches('Profile Keys'):
                    return
                # Settings restores its viewport: return to the account rows before reading the public key.
                if swipes < 8:
                    size = re.findall(r'(\d+)x(\d+)', adb('shell', 'wm', 'size'))[-1]
                    width, height = map(int, size)
                    adb('shell', 'input', 'swipe', str(width // 2), str(height // 3),
                        str(width // 2), str(height * 4 // 5), '400')
                    swipes += 1
            elif self.matches('Switch Profile') and self.matches('Settings'):
                self.tap_node(self.matches('Settings')[0])
            elif self.matches('Help Improve White Noise') and self.matches('Close'):
                self.tap_node(self.matches('Close')[0])
            elif direct:
                self.tap_node(direct[0])
            elif selector:
                self.tap_node(selector[0])
            time.sleep(2)
        raise RuntimeError('Original fixture profile Settings destination unavailable')

    def open_completed_demo(self):
        """Observe the coordinator's explicit Ready action after account changes dismiss its original screen."""
        self.open_settings()
        self.click('Developer Tools', scroll=True)
        deadline = time.monotonic() + 180
        while time.monotonic() < deadline:
            self.snapshot()
            ready = self.matches('Open demo conversation')
            if ready and self.matches('The real two-profile conversation is ready.'):
                (REPORTS / 'baseline-demo-ready.xml').write_text(self.xml)
                self.tap_node(ready[0])
                return
            time.sleep(1)
        raise RuntimeError('Baseline demo coordinator did not report Ready')

    def tap_node(self, node):
        """Use the freshly observed node bounds and refuse malformed geometry."""
        bounds = list(map(int, re.findall(r'\d+', node.get('bounds', ''))))
        if len(bounds) != 4 or bounds[2] <= bounds[0] or bounds[3] <= bounds[1]:
            raise RuntimeError('Preview action has no visible bounds')
        adb('shell', 'input', 'tap', str((bounds[0] + bounds[2]) // 2), str((bounds[1] + bounds[3]) // 2))

    def click(self, text, timeout=90, scroll=False, last=False):
        """Wait for a meaningful visible action, optionally searching the settings list by bounded swipes."""
        deadline = time.monotonic() + timeout
        swipes = 0
        while time.monotonic() < deadline:
            self.snapshot()
            nodes = self.matches(text)
            if nodes:
                self.tap_node(nodes[-1] if last else nodes[0])
                return
            if self.matches('Help Improve White Noise') and self.matches('Close'):
                self.tap_node(self.matches('Close')[0])
            elif scroll and swipes < 8:
                size = re.findall(r'(\d+)x(\d+)', adb('shell', 'wm', 'size'))[-1]
                width, height = map(int, size)
                adb('shell', 'input', 'swipe', str(width // 2), str(height * 4 // 5),
                    str(width // 2), str(height // 3), '400')
                swipes += 1
            time.sleep(1)
        raise RuntimeError(f'Preview action unavailable: {text}')

    def public_identity(self):
        """Read only the public profile key; never open, copy or export a private key."""
        self.click('Profile Keys', scroll=True)
        self.snapshot()
        keys = re.findall(r'npub1[023456789acdefghjklmnpqrstuvwxyz]{58}', self.xml)
        if len(set(keys)) != 1:
            raise RuntimeError('Could not verify one public fixture identity')
        identity = hashlib.sha256(keys[0].encode()).hexdigest()
        adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
        return identity

    def retained_chat(self, phase):
        """Require real conversation content and a composer; a chat-list preview cannot pass."""
        deadline = time.monotonic() + 180
        while time.monotonic() < deadline:
            self.snapshot()
            texts = '\n'.join(node.get('text', '') for node in self.nodes)
            composer = all(self.matches(label) for label in ('Message', 'Add attachment', 'Dictate text'))
            if all(text in texts for text in HISTORY) and composer:
                (REPORTS / f'{phase}.xml').write_text(self.xml)
                (REPORTS / f'{phase}.png').write_bytes(adb('exec-out', 'screencap', '-p', binary=True))
                self.focus_composer(phase)
                return
            # Account changes dismiss the original Developer Tools scope. Reopen its explicit Ready action,
            # but never select a profile or a transient Johnny label while the coordinator owns setup.
            original_chat_list = (self.matches('Chats') and self.matches(f'Switch Profile, {FIXTURE_NAME}')
                                  and self.matches('Johnny Appleseed') and self.matches(HISTORY[1]))
            if phase == 'baseline' and original_chat_list and not composer:
                self.open_completed_demo()
            elif phase != 'baseline' and self.matches('Johnny Appleseed') and not composer:
                self.tap_node(self.matches('Johnny Appleseed')[0])
            elif self.matches('Help Improve White Noise') and self.matches('Close'):
                self.tap_node(self.matches('Close')[0])
            time.sleep(2)
        raise RuntimeError(f'{phase}: retained demo history and composer did not become usable')

    def focused_composer_node(self):
        """Locate the focused field over the empty composer's placeholder, including Compose's scroll semantics."""
        for placeholder in self.matches('Message'):
            container = self.parents.get(placeholder)
            if container is None:
                continue
            controls = {node.get('content-desc') for node in container.iter('node')}
            if not {'Add attachment', 'Dictate text'} <= controls:
                continue
            label_bounds = list(map(int, re.findall(r'\d+', placeholder.get('bounds', ''))))
            if len(label_bounds) != 4:
                continue
            for node in container.iter('node'):
                if (node.get('class') not in {'android.widget.EditText', 'android.widget.ScrollView'} or
                        node.get('focused') != 'true' or node.get('package') != APP or not self.enabled(node)):
                    continue
                bounds = list(map(int, re.findall(r'\d+', node.get('bounds', ''))))
                if (len(bounds) == 4 and bounds[0] <= label_bounds[0] < label_bounds[2] <= bounds[2] and
                        bounds[1] <= label_bounds[1] < label_bounds[3] <= bounds[3]):
                    return node
        return None

    def focus_composer(self, phase):
        """Require actual composer focus and a shown IME without sending or deleting fixture data."""
        self.click('Message')
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            self.snapshot()
            focused = self.focused_composer_node()
            keyboard = adb('shell', 'dumpsys', 'input_method')
            shown = bool(re.search(r'\bmInputShown=true\b', keyboard))
            observation = {'focused_composer': focused is not None, 'keyboard_shown': shown,
                           'focus_class': focused.get('class') if focused is not None else None}
            (REPORTS / f'{phase}-composer.xml').write_text(self.xml)
            (REPORTS / f'{phase}-composer.json').write_text(json.dumps(observation) + '\n')
            if focused is not None and shown:
                (REPORTS / f'{phase}-composer.png').write_bytes(adb('exec-out', 'screencap', '-p', binary=True))
                adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
                return
            time.sleep(1)
        raise RuntimeError(f'{phase}: compact composer did not accept focus and show its keyboard')


def launch():
    """Open the normal release Application and Activity, including native bootstrap."""
    adb('shell', 'am', 'start', '-W', '-n', APP + '/dev.ipf.whitenoise.android.MainActivity')


def stop():
    """Require full process death without clearing any package data."""
    adb('shell', 'am', 'force-stop', APP)
    if adb('shell', f'pidof {APP} || true').strip():
        raise RuntimeError('Preview process survived force-stop')


def offline(phase):
    """Retain the existing fail-closed radio/default-network proof so relay recovery cannot conceal data loss."""
    evidence = subprocess.check_output([sys.executable, 'scripts/maestro_apk.py', 'offline'], text=True, timeout=180)
    (REPORTS / f'{phase}-network.txt').write_text(evidence)


def main():
    """Run dependent baseline, in-place upgrade and same-data cold-restart checks on the exact signed pair."""
    if os.environ.get('GITHUB_ACTIONS') != 'true' or adb('shell', 'getprop', 'ro.kernel.qemu').strip() != '1':
        raise RuntimeError('Hosted disposable emulator required')
    if APP in adb('shell', 'pm', 'list', 'packages', APP):
        raise RuntimeError('Fresh isolated preview fixture required')
    facts = prepare()
    ui = PreviewUi()
    try:
        facts['emulator_abis'] = adb('shell', 'getprop', 'ro.product.cpu.abilist').strip()
        facts['native_bridge'] = adb('shell', 'getprop', 'ro.dalvik.vm.native.bridge').strip()
        facts['guest_graphics'] = {
            name: adb('shell', 'getprop', name).strip()
            for name in ('ro.hardware.egl', 'ro.hardware.vulkan', 'debug.hwui.renderer')
        }
        if 'arm64-v8a' not in facts['emulator_abis'].split(','):
            raise RuntimeError('Hosted image does not advertise support for the unchanged ARM64 preview')
        adb('shell', 'settings', 'put', 'secure', 'show_ime_with_hard_keyboard', '1')
        if adb('shell', 'settings', 'get', 'secure', 'show_ime_with_hard_keyboard').strip() != '1':
            raise RuntimeError('Hosted emulator did not enable its visible keyboard')
        adb('install', '-r', str(PAIR / 'baseline.apk'), timeout=180)
        adb('shell', 'pm', 'grant', APP, 'android.permission.POST_NOTIFICATIONS')
        launch()
        ui.click('Sign Up')
        ui.snapshot()
        fields = [node for node in ui.nodes if node.get('class') == 'android.widget.EditText']
        if len(fields) != 2:
            raise RuntimeError('Expected signup name and about fields')
        ui.tap_node(fields[0])
        adb('shell', 'input', 'text', FIXTURE_NAME.replace(' ', '%s'))
        adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
        ui.click('Sign Up', last=True)
        ui.open_settings(timeout=300)
        identity = ui.public_identity()
        ui.open_demo_setup()
        ui.retained_chat('baseline')
        facts['public_identity_sha256'] = identity
        facts['phases']['baseline'] = 'passed'
        stop()
        offline('before-upgrade')
        adb('install', '-r', str(PAIR / 'candidate.apk'), timeout=180)
        launch()
        ui.retained_chat('upgrade')
        facts['phases']['upgrade'] = 'passed'
        stop()
        offline('before-cold-restart')
        launch()
        ui.retained_chat('cold-restart')
        facts['phases']['cold_restart'] = 'passed'
        ui.click('Back')
        ui.open_settings()
        if ui.public_identity() != identity:
            raise RuntimeError('Public fixture identity changed across the upgrade')
        offline('after-retained-identity')
        facts['phases']['retained_identity'] = 'passed'
    finally:
        (REPORTS / 'qualification.json').write_text(json.dumps(facts, indent=2) + '\n')
        if hasattr(ui, 'xml'):
            (REPORTS / 'last-window.xml').write_text(ui.xml)
            # Failure screenshots diagnose visible selectors without replacing the original qualification error.
            try:
                (REPORTS / 'last-window.png').write_bytes(adb('exec-out', 'screencap', '-p', binary=True, timeout=10))
            except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
                (REPORTS / 'last-window-capture-error.txt').write_text(type(error).__name__ + '\n')


if __name__ == '__main__':
    main()
