#!/usr/bin/env python3
"""Graph-only Gradle hook fixture: local Maven modules, no downloads or compilation."""
import argparse
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def module(repository, artifact, version):
    folder = repository / 'org/freemarker' / artifact / version
    folder.mkdir(parents=True, exist_ok=True)
    (folder / f'{artifact}-{version}.pom').write_text(
        '<project><modelVersion>4.0.0</modelVersion><groupId>org.freemarker</groupId>'
        f'<artifactId>{artifact}</artifactId><version>{version}</version></project>')
    with zipfile.ZipFile(folder / f'{artifact}-{version}.jar', 'w'):
        pass


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--gradle', type=Path, default=ROOT / 'gradlew')
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix='dependency-security-fixture-') as temporary:
        project = Path(temporary)
        repository = project / 'maven'
        for artifact, versions in {'freemarker': ['2.3.32', '2.3.35', '2.3.36'],
                                   'affected': ['2.3.32']}.items():
            for version in versions:
                module(repository, artifact, version)
        for artifact, versions in {'freemarker': ['2.3.32', '2.3.35', '2.3.36'],
                                   'affected': ['2.3.32']}.items():
            metadata = repository / 'org/freemarker' / artifact / 'maven-metadata.xml'
            metadata.write_text('<metadata><groupId>org.freemarker</groupId>'
                                f'<artifactId>{artifact}</artifactId><versioning>'
                                f'<latest>{versions[-1]}</latest><release>{versions[-1]}</release><versions>'
                                + ''.join(f'<version>{version}</version>' for version in versions)
                                + '</versions><lastUpdated>20261010000000</lastUpdated></versioning></metadata>')
        (project / 'gradle').mkdir()
        shutil.copyfile(ROOT / 'gradle/dependency-security.settings.gradle',
                        project / 'gradle/dependency-security.settings.gradle')
        (project / 'gradle/dependency-security.json').write_text(json.dumps({
            'schema': 1, 'minimum_versions': {'org.freemarker:freemarker': '2.3.35',
                                            'org.freemarker:affected': '2.3.35'}}))
        (project / 'settings.gradle').write_text(
            "rootProject.name='security-fixture'\napply from: 'gradle/dependency-security.settings.gradle'\n")
        (project / 'build.gradle').write_text(r'''
buildscript {
    repositories { maven { url = uri('maven') } }
    dependencies { classpath 'org.freemarker:freemarker:2.3.32' }
}
repositories { maven { url = uri('maven') } }
configurations { belowFloor; newer; dynamic; affectedRange }
dependencies {
    belowFloor 'org.freemarker:freemarker:2.3.32'
    newer 'org.freemarker:freemarker:2.3.36'
    dynamic 'org.freemarker:freemarker:2.+'
    affectedRange 'org.freemarker:affected:[2.3.0,2.3.34]'
}
tasks.register('verifySecurityFixture') {
    doLast {
        def selected = { configuration ->
            configuration.resolve()
            configuration.incoming.resolutionResult.allComponents.find {
                it.id instanceof org.gradle.api.artifacts.component.ModuleComponentIdentifier
            }.id.version
        }
        assert selected(configurations.belowFloor) == '2.3.35'
        assert selected(configurations.newer) == '2.3.36'
        assert selected(configurations.dynamic) == '2.3.36'
        assert buildscript.configurations.classpath.resolvedConfiguration.resolvedArtifacts*.moduleVersion*.id*.version == ['2.3.35']
        boolean rejected = false
        try { configurations.affectedRange.resolve() }
        catch (org.gradle.api.GradleException expected) { rejected = true }
        assert rejected
    }
}
''')
        completed = subprocess.run([str(args.gradle.resolve()), '--project-dir', str(project),
                                    '--offline', '--no-configuration-cache', '--console=plain',
                                    'verifySecurityFixture'], cwd=ROOT, timeout=120)
        if completed.returncode:
            raise SystemExit(completed.returncode)
        reports = [json.loads(path.read_text()) for path in (project / 'build/reports/dependency-security').glob('*.json')]
        assert any(r['scope'] == 'plugin' and r['configuration'] == 'classpath' for r in reports)
        assert any(r['scope'] == 'project' and r['configuration'] == 'belowFloor' for r in reports)
        print('Gradle fixture PASS: raised exact request, retained newer/dynamic selection, rejected affected-only range, plugin/project reports')


if __name__ == '__main__':
    main()
