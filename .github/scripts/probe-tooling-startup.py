#!/usr/bin/env python3
"""Temporary hosted measurement; remove after capturing its artifacts."""
import argparse, hashlib, html, importlib.util, json, os, re, shutil, subprocess, sys, time
from pathlib import Path

ROOT = Path.cwd()
OUT = Path(os.environ['RUNNER_TEMP']) / 'tooling-startup-measurement'
SEED = OUT / 'dependency-seed'
INIT = '''gradle.startParameter.profile = true
System.err.println("TOOLING_PROBE pid=" + ProcessHandle.current().pid() +
    " preview=" + System.getenv("PR_PREVIEW_CHANNEL") + " number=" + System.getenv("PR_NUMBER"))
'''
PROPERTIES = '''org.gradle.jvmargs=-Xmx3g -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8
org.gradle.workers.max=1
org.gradle.configuration-cache=false
'''

def copy_directory(source, target):
    target.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(['cp', '-a', '--reflink=auto', str(source), str(target)], check=True, timeout=300)

def home(name):
    target = OUT / 'homes' / name
    assert not target.exists(), 'Never replay a partially created sample home'
    copy_directory(SEED / 'caches/modules-2', target / 'caches/modules-2')
    copy_directory(SEED / 'wrapper/dists/gradle-9.7.1-bin', target / 'wrapper/dists/gradle-9.7.1-bin')
    (target / 'init.d').mkdir()
    (target / 'init.d/probe.gradle').write_text(INIT)
    (target / 'gradle.properties').write_text(PROPERTIES)
    return target

def run(command, env, path, timeout):
    start = time.monotonic()
    with path.open('w') as stream:
        result = subprocess.run(command, cwd=ROOT, env=env, stdout=stream,
                                stderr=subprocess.STDOUT, timeout=timeout)
    elapsed = time.monotonic() - start
    assert result.returncode == 0, f'{path.name} failed: inspect saved log'
    return {'command': command, 'wall_seconds': elapsed, 'log': str(path)}

def gradle(command, env, path):
    # GitHub's disposable runner; no Hermes shared-host admission wrapper.
    return run(command, env, path, 240)


def profiles():
    result = []
    for path in sorted((ROOT / 'build/reports/profile').glob('*.html')):
        rows = []
        for row in re.findall(r'<tr\b[^>]*>(.*?)</tr>', path.read_text(), re.S):
            cells = [html.unescape(re.sub(r'<[^>]+>', '', c)).strip()
                     for c in re.findall(r'<td\b[^>]*>(.*?)</td>', row, re.S)]
            if cells:
                rows.append(cells)
        result.append({'file': path.name, 'rows': rows})
    return result

def stop_private(env, directory):
    # All daemon registries in this user home belong solely to this probe.
    assert Path(env['GRADLE_USER_HOME']).is_relative_to(OUT / 'homes')
    run(['./gradlew', '--stop'], env, directory / 'stop-private.log', 60)

OUT.mkdir(exist_ok=False)
# Freeze one dependency seed before either measurement; no warm-cache advantage.
original_home = Path(os.environ.get('GRADLE_USER_HOME', str(Path.home() / '.gradle')))
subprocess.run(['./gradlew', '--stop'], cwd=ROOT, check=True, timeout=60)
copy_directory(original_home / 'caches/modules-2', SEED / 'caches/modules-2')
copy_directory(original_home / 'wrapper/dists/gradle-9.7.1-bin', SEED / 'wrapper/dists/gradle-9.7.1-bin')
workflow = (ROOT / '.github/workflows/android-ci.yml').read_text()
commands = re.findall(r'^\s*run: (\./gradlew :app:stageMarmotKitApiSignature.*)$', workflow, re.M)
assert len(commands) == 1 and '--daemon' in commands[0]


def measure(phase_name):
    flag = '--no-daemon' if phase_name == 'baseline' else '--daemon'
    api_command = __import__('shlex').split(commands[0].replace('--daemon', flag))
    phase = OUT / phase_name
    assert not phase.exists(), 'Reconcile saved samples instead of replaying a phase'
    phase.mkdir()
    results = []
    current_home = None
    spec = importlib.util.spec_from_file_location('system_labels_probe', ROOT / 'scripts/verify-system-labels.py')
    labels = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = labels
    spec.loader.exec_module(labels)
    variants = ['previewPlayRelease', 'previewZapstoreRelease']
    manifest_tasks = [':app:process' + variant[0].upper() + variant[1:] + 'ManifestForPackage'
                      for variant in variants]
    for name in ['cold1', 'cold2', 'warm1', 'warm2']:
        directory = phase / name
        directory.mkdir()
        if name.startswith('cold'):
            if current_home is not None:
                stop_private(env, phase / 'cold1')
                shutil.rmtree(current_home)
            for relative in ['.gradle', 'build', 'app/build']:
                source = ROOT / relative
                if source.exists():
                    # Preserve generated outputs, never delete tracked source or shared state.
                    target = directory / 'prior-outputs' / relative
                    target.parent.mkdir(parents=True, exist_ok=True)
                    shutil.move(str(source), str(target))
            current_home = home(phase_name + '-' + name)
        env = dict(os.environ)
        env.update(GRADLE_USER_HOME=str(current_home),
                   PR_NUMBER='42', PR_PREVIEW_CHANNEL='stable')
        # Keep profiles per sample, including the task-discovery invocation.
        report_directory = ROOT / 'build/reports/profile'
        if report_directory.exists():
            shutil.move(str(report_directory), str(directory / 'prior-profiles'))
        calls = [gradle(api_command, env, directory / 'api.log')]
        calls.append(gradle(['./gradlew', ':app:tasks', '--all', '--console=plain',
                             flag, '--stacktrace'], env, directory / 'discovery.log'))
        discovery = Path(calls[-1]['log']).read_text()
        discovered = re.findall(r'^process[A-Z]\w+ManifestForPackage$', discovery, re.M)
        assert len(discovered) == 12 and all(t.removeprefix(':app:') in discovered for t in manifest_tasks)
        manifest_hashes = {}
        for channel in ['stable', 'isolated']:
            channel_env = dict(env, PR_PREVIEW_CHANNEL=channel)
            calls.append(gradle(['./gradlew', *manifest_tasks, flag, '--stacktrace'],
                                channel_env, directory / (channel + '.log')))
            for variant in variants:
                manifest = labels.packaged_manifest(ROOT / 'app/build/intermediates/packaged_manifests', variant)
                labels.verify_manifest(manifest, labels.expected_identity(variant, channel, '42'))
                manifest_hashes[channel + '/' + variant] = hashlib.sha256(manifest.read_bytes()).hexdigest()
                target = directory / 'manifests' / channel / variant / 'AndroidManifest.xml'
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(manifest, target)
        shutil.copytree(report_directory, directory / 'profiles')
        data = {'name': name, 'home': str(current_home), 'calls': calls,
                'profiles': profiles(), 'source_head': subprocess.check_output(
                    ['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()}
        data['pids'] = [int(pid) for call in calls for pid in re.findall(
            r'TOOLING_PROBE pid=(\d+)', Path(call['log']).read_text())]
        assert len(data['pids']) == 4 and len(data['profiles']) == 4, data
        assert len(set(data['pids'])) == (4 if phase_name == 'baseline' else 1), data['pids']
        data['scope'] = 'API stage, all-12 task discovery, two preview manifests per channel; full twelve-manifest execution stays in hosted CI'
        data['manifest_hashes'] = manifest_hashes
        signature = ROOT / 'app/build/reports/marmotkit/marmotkit-api-signature.txt'
        data['api_hash'] = hashlib.sha256(signature.read_bytes()).hexdigest()
        shutil.copy2(signature, directory / 'api-signature.txt')
        (directory / 'sample.json').write_text(json.dumps(data, indent=2) + '\n')
        results.append(data)
        print(json.dumps({'sample': phase_name+'-'+name, 'wall_seconds': sum(c['wall_seconds'] for c in calls), 'pids': data['pids']}), flush=True)
    stop_private(env, phase)
    shutil.rmtree(current_home)
    (phase / 'summary.json').write_text(json.dumps(results, indent=2) + '\n')

try:
    measure('baseline')
    measure('candidate')
    # Prove a wrong label on a real generated manifest is rejected for that reason.
    fixture = OUT / 'candidate/cold1/manifests/stable/previewPlayRelease/AndroidManifest.xml'
    spec = importlib.util.spec_from_file_location('negative_labels', ROOT / 'scripts/verify-system-labels.py')
    labels = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = labels
    spec.loader.exec_module(labels)
    import xml.etree.ElementTree as ET
    tree = ET.parse(fixture)
    tree.getroot().find('application').set(labels.ANDROID_NS + 'label', 'Wrong label')
    wrong = OUT / 'wrong-label.xml'
    tree.write(wrong, encoding='unicode')
    try:
        labels.verify_manifest(wrong, labels.expected_identity('previewPlayRelease', 'stable', '42'))
    except AssertionError as error:
        assert 'Wrong label' in str(error) and 'expected' in str(error), error
        (OUT / 'negative-label-proof.txt').write_text(str(error) + '\n')
    else:
        raise AssertionError('Wrong generated label was accepted')
finally:
    # Only regular test evidence goes into the existing timing artifact, never caches.
    artifact = ROOT / 'build/reports/profile/oneoff-tooling-startup'
    artifact.parent.mkdir(parents=True, exist_ok=True)
    shutil.copytree(OUT, artifact, ignore=shutil.ignore_patterns('homes', 'dependency-seed', 'prior-outputs'))
print('Matched cold/warm startup and real-manifest negative checks PASS', flush=True)
