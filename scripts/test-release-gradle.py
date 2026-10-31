#!/usr/bin/env python3
"""Resolve release task graphs with disposable signing inputs; never run tasks."""
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent.parent


def main():
    """Assert the application build's signing and task-isolation contracts without packaging."""
    with tempfile.TemporaryDirectory() as directory:
        temporary = Path(directory)
        keystore = temporary / 'fixture.keystore'
        keystore.touch()  # Configuration only: no packaging or signing runs.
        env = dict(os.environ)
        for prefix, alias in (('PRODUCTION', 'direct-fixture'), ('PLAY_UPLOAD', 'upload-fixture')):
            for suffix, value in (('KEYSTORE_PATH', str(keystore)), ('KEYSTORE_PASSWORD', 'fixture'),
                                  ('KEY_ALIAS', alias), ('KEY_PASSWORD', 'fixture')):
                env[f'WHITENOISE_{prefix}_{suffix}'] = value
        init = temporary / 'assert-signing.gradle'
        init.write_text('''gradle.taskGraph.whenReady {
    // Init scripts also run for buildSrc/included builds; only the main build owns :app.
    if (gradle.parent != null) return
    def app = gradle.rootProject.project(':app')
    // Graph-ready listeners run under Gradle project-state locks; resolve producer dependencies here.
    // Both JNI folder consumption and native packaging must wait for verified decoder outputs.
    def decoderProducer = app.tasks.named('prepareNativeMediaDecoder').get()
    ['Zapstore', 'Play'].each { flavor ->
        ['JniLibFolders', 'NativeLibs'].each { suffix ->
            def consumer = app.tasks.named("mergeDev${flavor}Debug${suffix}").get()
            assert consumer.taskDependencies.getDependencies(consumer).contains(decoderProducer) :
                "Missing decoder producer dependency for ${consumer.path}"
        }
    }
    def bundleMode = app.findProperty('whitenoise.playBundle') == 'true'
    def android = app.extensions.getByName('android')
    assert android.splits.abi.enable == !bundleMode : 'Wrong ABI split configuration'
    def expectedAlias = bundleMode ? 'upload-fixture' : 'direct-fixture'
    assert android.productFlavors.getByName('production').signingConfig.keyAlias == expectedAlias :
        'Unexpected signing key: run in a checkout without local signing overrides'
    println('Verified decoder producer dependencies and release signing assertions.')
}
''')
        cases = [
            ([':app:bundleProductionPlayRelease', '-Pwhitenoise.playBundle=true'], None),
            (['clean', ':app:bundleProdPlayRel', '-Pwhitenoise.playBundle=true'], None),
            ([':app:bundleProductionPlayRelease'], 'require -Pwhitenoise.playBundle=true'),
            ([':app:bundleProdPlayRel'], 'require -Pwhitenoise.playBundle=true'),
            ([':app:assembleProductionZapstoreRelease'], None),
            ([':app:packageProductionPlayRelease', '-Pwhitenoise.playBundle=true'], 'cannot package APKs'),
            ([':app:bundleProductionPlayRelease', ':app:assembleProductionPlayRelease',
              '-Pwhitenoise.playBundle=true'], 'cannot package APKs'),
        ]
        for arguments, expected_error in cases:
            result = subprocess.run(
                ['./gradlew', *arguments, '--dry-run', '--console=plain', '--init-script', str(init)],
                cwd=ROOT, env=env, capture_output=True, text=True, timeout=180)
            output = result.stdout + result.stderr
            if expected_error:
                assert result.returncode != 0 and expected_error in output, output
            else:
                assertions_ran = 'Verified decoder producer dependencies and release signing assertions.' in output
                assert result.returncode == 0 and assertions_ran, output
            print('PASS:', ' '.join(arguments), flush=True)


if __name__ == '__main__':
    main()
