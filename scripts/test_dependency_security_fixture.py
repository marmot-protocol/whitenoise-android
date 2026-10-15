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


def module(repository, artifact, version, group='org.freemarker'):
    """Create a local Maven dependency with bytes and a declared fixture licence."""
    folder = repository / group.replace('.', '/') / artifact / version
    folder.mkdir(parents=True, exist_ok=True)
    (folder / f'{artifact}-{version}.pom').write_text(
        f'<project><modelVersion>4.0.0</modelVersion><groupId>{group}</groupId>'
        f'<artifactId>{artifact}</artifactId><version>{version}</version>'
        '<licenses><license><name>Fixture licence</name><url>https://example.invalid/license</url>'
        '</license></licenses><build><plugins><plugin><artifactId>fixture-not-run</artifactId>'
        '<configuration><license><name>Plugin option, not a project licence</name>'
        '<url>https://example.invalid/unused</url></license></configuration>'
        '</plugin></plugins></build></project>')
    with zipfile.ZipFile(folder / f'{artifact}-{version}.jar', 'w'):
        pass


def main():
    """Exercise selection, file fingerprints and cached POMs without compiling an app."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--gradle', type=Path, default=ROOT / 'gradlew')
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix='dependency-security-fixture-') as temporary:
        project = Path(temporary)
        repository = project / 'maven'
        wire_versions = ['7.0.0-alpha03', '7.0.0-alpha04', '7.0.0-alpha04-SNAPSHOT', '7.0.0-alpha05']
        for version in wire_versions:
            module(repository, 'wire-runtime', version, group='com.squareup.wire')
        module(repository, 'legacy', '1.0')
        legacy_pom = repository / 'org/freemarker/legacy/1.0/legacy-1.0.pom'
        legacy_pom.write_text('<!DOCTYPE project [<!ELEMENT project ANY>]>' + legacy_pom.read_text())
        (repository / 'com/squareup/wire/wire-runtime/maven-metadata.xml').write_text(
            '<metadata><groupId>com.squareup.wire</groupId><artifactId>wire-runtime</artifactId>'
            '<versioning><latest>7.0.0-alpha05</latest><release>7.0.0-alpha05</release><versions>'
            + ''.join(f'<version>{version}</version>' for version in wire_versions)
            + '</versions><lastUpdated>20261010000000</lastUpdated></versioning></metadata>')
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
                                            'org.freemarker:affected': '2.3.35',
                                            'com.squareup.wire:wire-runtime': '6.4.5'},
            'fixed_prerelease_versions': {'com.squareup.wire:wire-runtime': '7.0.0-alpha04'}}))
        (project / 'settings.gradle').write_text(
            "rootProject.name='security-fixture'\ninclude ':producer'\n"
            "apply from: 'gradle/dependency-security.settings.gradle'\n")
        producer = project / 'producer'
        producer.mkdir()
        for name in ('classes.jar', 'metadata.jar'):
            with zipfile.ZipFile(producer / name, 'w'):
                pass
        (producer / 'build.gradle').write_text(r'''
repositories { maven { url = uri('../maven') } }
configurations {
    apiElements {
        canBeConsumed = true
        canBeResolved = false
        attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage, Usage.JAVA_API))
        outgoing.variants {
            classes {
                attributes.attribute(Attribute.of('artifactType', String), 'fixture-classes')
                artifact(file('classes.jar'))
            }
            metadata {
                attributes.attribute(Attribute.of('artifactType', String), 'fixture-metadata')
                artifact(file('metadata.jar'))
            }
        }
    }
}
dependencies { apiElements 'org.freemarker:freemarker:2.3.32' }
''')
        (project / 'build.gradle').write_text(r'''
buildscript {
    repositories { maven { url = uri('maven') } }
    dependencies { classpath 'org.freemarker:freemarker:2.3.32' }
}
repositories {
    // Keep Gradle's POM parser out of the optional licence-reader rejection case.
    maven {
        url = uri('maven')
        metadataSources { artifact() }
        content { includeModule('org.freemarker', 'legacy') }
    }
    maven {
        url = uri('maven')
        content { excludeModule('org.freemarker', 'legacy') }
    }
}
configurations {
    belowFloor; newer; dynamic; affectedRange; wireAlpha; wireSuffix; wireDynamic; legacyPom
    projectVariants {
        canBeConsumed = false
        attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage, Usage.JAVA_API))
    }
}
dependencies {
    belowFloor 'org.freemarker:freemarker:2.3.32'
    newer 'org.freemarker:freemarker:2.3.36'
    dynamic 'org.freemarker:freemarker:2.+'
    affectedRange 'org.freemarker:affected:[2.3.0,2.3.34]'
    wireAlpha 'com.squareup.wire:wire-runtime:7.0.0-alpha03'
    wireSuffix 'com.squareup.wire:wire-runtime:7.0.0-alpha04-SNAPSHOT'
    wireDynamic 'com.squareup.wire:wire-runtime:7.0.0-alpha04+'
    legacyPom 'org.freemarker:legacy:1.0'
    projectVariants project(':producer')
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
        assert selected(configurations.wireAlpha) == '7.0.0-alpha04'
        assert selected(configurations.wireSuffix) == '7.0.0-alpha04'
        assert selected(configurations.wireDynamic) == '7.0.0-alpha04'
        assert selected(configurations.legacyPom) == '1.0'
        def legacyArtifact = configurations.legacyPom.incoming.artifacts.artifacts.first().file
        assert new File(legacyArtifact.parentFile, 'legacy-1.0.pom').text.contains('<!DOCTYPE project')
        // Graph resolution and external-module observations must not select project artifacts.
        def projectResolution = configurations.projectVariants.incoming.resolutionResult
        def unresolvedProjects = projectResolution.allDependencies.findAll {
            it instanceof org.gradle.api.artifacts.result.UnresolvedDependencyResult
        }
        assert unresolvedProjects.empty : unresolvedProjects.collect { it.failure.message }.join('\n')
        def external = projectResolution.allComponents.find {
            it.id instanceof org.gradle.api.artifacts.component.ModuleComponentIdentifier
        }
        assert external != null
        assert external.id.group == 'org.freemarker'
        assert external.id.module == 'freemarker'
        assert external.id.version == '2.3.35'
        def producerEdge = projectResolution.allDependencies.find {
            it instanceof org.gradle.api.artifacts.result.ResolvedDependencyResult &&
                it.selected.id instanceof org.gradle.api.artifacts.component.ProjectComponentIdentifier &&
                it.selected.id.projectPath == ':producer'
        }
        assert producerEdge != null
        new File(rootProject.projectDir, 'expected-project-edge.json').text = groovy.json.JsonOutput.toJson(
            [from: producerEdge.from.id.displayName, to: producerEdge.selected.id.displayName])
        boolean ambiguousProjectArtifacts = false
        try { configurations.projectVariants.incoming.artifacts.artifacts }
        catch (org.gradle.api.GradleException expected) {
            def failure = expected
            while (failure != null) {
                if (failure.message?.contains('cannot choose between') ||
                        failure.message?.contains('More than one variant')) ambiguousProjectArtifacts = true
                failure = failure.cause
            }
        }
        assert ambiguousProjectArtifacts
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
        selected_report = next(r for r in reports if r['configuration'] == 'belowFloor')
        assert selected_report['artifacts'][0]['coordinate'] == 'org.freemarker:freemarker:2.3.35'
        assert len(selected_report['artifacts'][0]['sha256']) == 64
        assert selected_report['components'][0]['licenses'][0]['name'] == 'Fixture licence'
        assert len(selected_report['components'][0]['licenses']) == 1
        legacy_report = next(r for r in reports if r['configuration'] == 'legacyPom')
        assert legacy_report['components'][0]['licenses'] == []
        project_report = next(r for r in reports if r['configuration'] == 'projectVariants')
        assert project_report['artifact_coverage'] == 'resolved_configuration'
        assert [a['coordinate'] for a in project_report['artifacts']] == ['org.freemarker:freemarker:2.3.35']
        expected_project_edge = json.loads((project / 'expected-project-edge.json').read_text())
        assert expected_project_edge in project_report['edges'], project_report['edges']
        print('Gradle fixture PASS: raised exact request, retained newer/dynamic selection, '
              'rejected affected-only range and unsafe Wire alpha/suffixes, plugin/project reports, '
              'external artifacts isolated from ambiguous project variants')


if __name__ == '__main__':
    main()
