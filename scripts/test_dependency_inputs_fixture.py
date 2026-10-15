#!/usr/bin/env python3
"""Hosted Gradle integration: strict locks and byte verification in a source archive."""
import argparse
from functools import partial
import hashlib
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import shutil
import subprocess
import tempfile
import threading
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]


class FixtureRepository(SimpleHTTPRequestHandler):
    """Serve only synthetic fixture bytes; force refreshes to observe changed content."""

    def do_GET(self):
        """Avoid timestamp granularity hiding tampered bytes behind a 304 response."""
        if 'If-Modified-Since' in self.headers:
            del self.headers['If-Modified-Since']
        super().do_GET()

    def log_message(self, *arguments):
        """Keep expected repository misses out of the hosted test output."""


def publish(repository, name, dependency=None, payload=b'original'):
    """Create inspectable synthetic Maven inputs without compiling a class."""
    folder = repository / 'fixture' / name / '1.0'
    folder.mkdir(parents=True, exist_ok=True)
    dependency_xml = '' if dependency is None else (
        '<dependencies><dependency><groupId>fixture</groupId><artifactId>' + dependency +
        '</artifactId><version>1.0</version></dependency></dependencies>')
    pom = folder / (name + '-1.0.pom')
    pom.write_text('<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId>' +
                   '<artifactId>' + name + '</artifactId><version>1.0</version>' + dependency_xml + '</project>')
    jar = folder / (name + '-1.0.jar')
    with zipfile.ZipFile(jar, 'w') as archive:
        archive.writestr(zipfile.ZipInfo('fixture.txt'), payload)
    return pom, jar


def invoke(gradle, project, *options, expect_failure=None, task='resolveFixture'):
    """Require Gradle's actual resolution outcome and a relevant failure diagnostic."""
    result = subprocess.run([str(gradle.resolve()), '--project-dir', str(project), '--console=plain',
                             '--no-configuration-cache', task, *options], cwd=ROOT,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=180)
    if expect_failure is None:
        if result.returncode:
            raise RuntimeError(result.stdout)
    elif result.returncode == 0 or not any(word in result.stdout.lower() for word in expect_failure):
        raise RuntimeError('Expected dependency-input failure: ' + result.stdout)
    return result.stdout


def main():
    """Keep synthetic trust roots isolated while exercising the production settings hook."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--gradle', type=Path, default=ROOT / 'gradlew')
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix='dependency-inputs-fixture-') as temporary:
        project = Path(temporary)
        repository = project / 'repository'
        publish(repository, 'first')
        publish(repository, 'second')
        publish(repository, 'tool')
        pom, _ = publish(repository, 'root', dependency='first')
        (project / 'android-sdk.jar').write_bytes(b'Synthetic SDK file input')
        server = ThreadingHTTPServer(('127.0.0.1', 0), partial(FixtureRepository, directory=str(repository)))
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            (project / 'gradle').mkdir()
            shutil.copyfile(ROOT / 'gradle/dependency-inputs.settings.gradle',
                            project / 'gradle/dependency-inputs.settings.gradle')
            (project / 'settings.gradle').write_text(
                "rootProject.name='archive-with-reviewed-inputs'\n"
                "apply from: 'gradle/dependency-inputs.settings.gradle'\n")
            (project / 'build.gradle').write_text('''
buildscript {
    repositories { maven {
        url = uri('http://127.0.0.1:PORT/')
        allowInsecureProtocol = true
    } }
    dependencies { classpath 'fixture:tool:1.0' }
}
repositories { maven {
    url = uri('http://127.0.0.1:PORT/')
    allowInsecureProtocol = true // Synthetic loopback repository, never a production supplier.
} }
configurations { fixtureRuntime }
dependencies { fixtureRuntime 'fixture:root:1.0' }
tasks.register('resolveLateSdkFixture') {
    doLast {
        def sdk = configurations.maybeCreate('androidApis')
        dependencies.add('androidApis', files('android-sdk.jar'))
        if (project.findProperty('fixtureUnreviewedSdkModule') == 'true') {
            dependencies.add('androidApis', 'fixture:second:1.0')
        }
        assert sdk.resolve() == [file('android-sdk.jar')].toSet()
    }
}
tasks.register('resolveFixture') {
    doLast { assert configurations.fixtureRuntime.resolve().size() == 2 }
}
'''.replace('PORT', str(server.server_port)))
            invoke(args.gradle, project, 'resolveLateSdkFixture', '--write-locks', '--write-verification-metadata', 'sha256')
            invoke(args.gradle, project, task='resolveLateSdkFixture')
            invoke(args.gradle, project, '-PfixtureUnreviewedSdkModule=true', task='resolveLateSdkFixture',
                   expect_failure=['lock state', 'locked', 'strictly'])
            invoke(args.gradle, project)
            plugin_lock = project / 'buildscript-gradle.lockfile'
            plugin_bytes = plugin_lock.read_bytes()
            plugin_lock.unlink()
            invoke(args.gradle, project, expect_failure=['lock state', 'locked', 'strictly'])
            plugin_lock.write_bytes(plugin_bytes)
            invoke(args.gradle, project)
            lock = project / 'gradle.lockfile'
            before = lock.read_bytes()
            publish(repository, 'root', dependency='second')
            # Review the new POM bytes, leaving the old transitive selection locked.
            metadata = project / 'gradle/verification-metadata.xml'
            tree = ET.parse(metadata)
            ns = {'v': 'https://schema.gradle.org/dependency-verification'}
            checksum = tree.find(".//v:component[@name='root']/v:artifact[@name='root-1.0.pom']/v:sha256", ns)
            if checksum is None:
                raise RuntimeError('Fixture POM metadata was not verified')
            checksum.set('value', hashlib.sha256(pom.read_bytes()).hexdigest())
            tree.write(metadata, encoding='utf-8', xml_declaration=True)
            invoke(args.gradle, project, '--refresh-dependencies', expect_failure=['lock state', 'locked', 'strictly'])
            invoke(args.gradle, project, '--refresh-dependencies', '--write-locks',
                   '--write-verification-metadata', 'sha256')
            if before == lock.read_bytes() or 'fixture:second:1.0=' not in lock.read_text():
                raise RuntimeError('Reviewed transitive update did not produce an inspectable lock diff')
            invoke(args.gradle, project)
            _, jar = publish(repository, 'second', payload=b'tampered')
            invoke(args.gradle, project, '--refresh-dependencies',
                   expect_failure=['dependency verification failed', 'checksum verification failed'])
            publish(repository, 'second')
            invoke(args.gradle, project, '--refresh-dependencies')
            invoke(args.gradle, project, '--dependency-verification', 'off', expect_failure=['must remain strict'])
            print('Gradle input fixture PASS: source archive, transitive drift rejected, reviewed update '
                  'accepted, substituted bytes rejected, verification bypass rejected, late SDK-file lock enforced')
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=5)


if __name__ == '__main__':
    main()
