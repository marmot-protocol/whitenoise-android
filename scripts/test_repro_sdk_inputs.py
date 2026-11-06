"""SDK equality fixtures; no Gradle, Java, Android build or network execution."""
import copy
import json
import os
import subprocess
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('sdk_inputs', Path(__file__).with_name('repro_sdk_inputs.py'))
sdk_inputs = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sdk_inputs)


class ResolvedSdkInputsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.sdk = Path(self.temp.name)
        for name in ('platforms/android-37.1', 'platforms/android-37.2', 'build-tools/36.0.0',
                     'ndk/29.0.14206865', 'cmake/3.22.1', 'build-tools/37.0.0'):
            root = self.sdk / name; root.mkdir(parents=True)
            (root / 'source.properties').write_text(name)
        self.jar = self.sdk / 'platforms/android-37.1/android.jar'; self.jar.write_bytes(b'used compile API')
        self.unused = self.sdk / 'platforms/android-37.2/android.jar'; self.unused.write_bytes(b'unused API')
        self.compiler = self.sdk / 'ndk/29.0.14206865/clang'; self.compiler.write_bytes(b'used compiler')
        self.signer = self.sdk / 'build-tools/37.0.0/apksigner'
        self.signer.write_bytes(b'actual APK verifier'); self.signer.chmod(0o755)
        self.report = {'schema': 1, 'sdk': str(self.sdk), 'bootClasspath': [str(self.jar)],
                       'buildToolsVersion': '36.0.0', 'ndk': str(self.compiler.parent), 'cmakeVersion': None}

    def test_unused_platform_drift_is_informational_but_used_bytes_are_required(self):
        original = sdk_inputs.record(self.report, self.signer)
        self.unused.write_bytes(b'hosted-image rollout')
        self.assertEqual(original, sdk_inputs.record(self.report, self.signer))
        for path in (self.jar, self.compiler, self.signer, self.sdk / 'build-tools/36.0.0/source.properties'):
            before = path.read_bytes(); path.write_bytes(before + b'drift')
            self.assertNotEqual(original, sdk_inputs.record(self.report, self.signer), path)
            path.write_bytes(before)

    def test_resolved_platform_is_not_hardcoded_and_cmake_is_compared_when_configured(self):
        changed = dict(self.report, bootClasspath=[str(self.unused)], cmakeVersion='3.22.1')
        original = sdk_inputs.record(changed, self.signer)
        self.assertIn('platforms/android-37.2', original['packages'])
        self.assertNotIn('platforms/android-37.1', original['packages'])
        (self.sdk / 'cmake/3.22.1/cmake').write_bytes(b'actual selected cmake')
        self.assertNotEqual(original, sdk_inputs.record(changed, self.signer))

    def test_missing_report_inputs_and_foreign_or_missing_packages_fail_closed(self):
        edits = [('bootClasspath', []), ('bootClasspath', [str(self.sdk / 'missing.jar')]),
                 ('buildToolsVersion', 'missing'), ('ndk', str(self.sdk / 'platforms/android-37.1')),
                 ('cmakeVersion', 'missing'), ('schema', 2)]
        for key, value in edits:
            with self.subTest(key=key), self.assertRaises((ValueError, FileNotFoundError)):
                sdk_inputs.record(dict(self.report, **{key: value}), self.signer)
        incomplete = copy.deepcopy(self.report); del incomplete['ndk']
        with self.assertRaises(ValueError): sdk_inputs.record(incomplete, self.signer)

    def test_current_verifier_records_older_source_tree_without_new_helper(self):
        old_source = self.sdk / 'old-source'; wrapper = old_source / 'gradle/wrapper'
        wrapper.mkdir(parents=True)
        for name in ('gradle-wrapper.jar', 'gradle-wrapper.properties'):
            (wrapper / name).write_bytes(b'older source wrapper fixture')
        self.assertFalse((old_source / 'scripts/repro_sdk_inputs.py').exists())
        jdk = self.sdk / 'jdk'; (jdk / 'bin').mkdir(parents=True)
        java = jdk / 'bin/java'; java.write_text('#!/bin/sh\nexit 0\n'); java.chmod(0o755)
        jvm = self.sdk / 'jvm.properties'; jvm.write_text('fixture JVM properties\n')
        Path(str(jvm) + '.sdk.json').write_text(json.dumps(self.report))
        evidence = self.sdk / 'evidence'
        script = Path(__file__).with_name('repro-verify.sh')
        result = subprocess.run(
            ['bash', '-c', 'source "$1"; repro_verify_record_build_environment "$2" "$3" "$4" "$4" "" "$5"',
             'fixture', str(script), str(evidence), 'd' * 40, str(jvm), str(old_source)],
            env=dict(os.environ, JAVA_HOME=str(jdk), ANDROID_HOME=str(self.sdk), ANDROID_SDK_ROOT=str(self.sdk),
                     REPRO_VERIFY_APKSIGNER_PATH=str(self.signer)), capture_output=True, text=True,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        recorded = (evidence / 'repro-build-environment.txt').read_text()
        self.assertIn(sdk_inputs.digest(self.jar), recorded)
        self.assertTrue((evidence / 'repro-installed-sdk-inventory.txt').is_file())

    def test_selected_package_symlink_identity_and_executability_are_inputs(self):
        link = self.compiler.with_name('clang++'); link.symlink_to('clang')
        original = sdk_inputs.record(self.report, self.signer)
        self.compiler.chmod(0o755)
        self.assertNotEqual(original, sdk_inputs.record(self.report, self.signer))
        link.unlink(); link.symlink_to(self.unused)
        with self.assertRaises(ValueError): sdk_inputs.record(self.report, self.signer)


if __name__ == '__main__': unittest.main()
