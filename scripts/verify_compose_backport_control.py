#!/usr/bin/env python3
"""Run behavioral regressions against unpatched Compose in a disposable CI copy."""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
PACKAGE = 'dev.ipf.whitenoise.android.ui.conversation.messages'
SHARED = {
    'baselineQueryDuringReusedItemMeasurementDoesNotPlaceChildrenForReal',
    'lookaheadBaselineQueryDuringReuseKeepsRectListConsistent',
    'rowAlignmentDuringFarSnapKeepsRectListConsistent',
}
BUBBLES = {
    'farSnapReusesAnotherMessagesBubbleWithoutCrashing',
    'centeringJumpReusesAnotherMessagesBubbleWithoutCrashing',
    'shippedKeyReactivatesEditedBubbleWithoutCrashing',
    'shippedKeyFarSnapAcrossMessagesKeepsRectListConsistent',
    'shippedKeyReactivationWithoutEditKeepsRectListConsistent',
}
IDENTITY = 'originalClassesAndNoBackportMarker'
EXPECTED_CLASSES = {
    **{name: f'{PACKAGE}.ComposeRectListBackportTest' for name in SHARED},
    **{name: f'{PACKAGE}.BubbleFooterRectListReuseTest' for name in BUBBLES},
    IDENTITY: f'{PACKAGE}.ComposeRectListUnpatchedIdentityTest',
}
OUTPUT = ROOT / 'build/reports/compose-backport-control'


def relevant_change(base, head):
    """Skip the expensive control on PRs which do not change this temporary backport."""
    paths = subprocess.check_output(
        ['git', 'diff', '--name-only', base, head, '--'], cwd=ROOT, text=True).splitlines()
    return any(path.startswith(('buildSrc/', 'third_party/compose-ui/'))
               or path in {'build.gradle.kts', 'gradle/compose-ui-backport.gradle.kts',
                           'scripts/verify_compose_backport_control.py'}
               or Path(path).name in {'ComposeRectListBackportTest.kt', 'ComposeRectListReuseFixture.kt',
                                      'BubbleFooterRectListReuseTest.kt'} for path in paths)


def verify_results(directory, returncode):
    """Require original-class identity and real RectList failures, never missing markers or build errors."""
    expected = SHARED | BUBBLES | {IDENTITY}
    cases = {}
    for path in directory.glob('TEST-*.xml'):
        for case in ET.parse(path).getroot().iter('testcase'):
            name = case.attrib['name']
            if name not in expected:
                continue
            assert case.attrib.get('classname') == EXPECTED_CLASSES[name], f'Unexpected test class: {name}'
            assert name not in cases, f'Duplicate control case: {name}'
            failure = case.find('failure')
            error = case.find('error')
            problem = failure if failure is not None else error
            trace = '' if problem is None else ''.join(problem.itertext())
            cases[name] = {'passed': problem is None and case.find('skipped') is None,
                           'rectlist_failure': bool(re.search(r'LayoutNode \d+ not found in RectList', trace)),
                           'skipped': case.find('skipped') is not None}
    (OUTPUT / 'cases.json').write_text(json.dumps(cases, indent=2) + '\n')
    assert set(cases) == expected, f'Missing control cases: {expected - set(cases)}'
    assert cases[IDENTITY]['passed'], 'Unpatched runtime identity was not established'
    assert returncode != 0, 'Unpatched behavioral control unexpectedly passed every case'
    for name, result in cases.items():
        assert result['passed'] or result['rectlist_failure'], f'Unexpected failure or skip: {name}'
    for name in ('baselineQueryDuringReusedItemMeasurementDoesNotPlaceChildrenForReal',
                 'shippedKeyReactivatesEditedBubbleWithoutCrashing'):
        assert cases[name]['rectlist_failure'], f'The regression did not fail with RectList: {name}'
    for name, result in sorted(cases.items()):
        print(f'{name}: {"expected RectList failure" if result["rectlist_failure"] else "passed coverage case"}')


def main():
    """Preserve the actual checkout and write only a disposable unpatched control plus its reports."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', required=True)
    parser.add_argument('--head', required=True)
    args = parser.parse_args()
    if not relevant_change(args.base, args.head):
        print('No backport changes; unpatched control not requested.')
        return
    assert os.environ.get('GITHUB_ACTIONS') == 'true', 'Compilation belongs on GitHub CI'
    OUTPUT.mkdir(parents=True, exist_ok=True)
    source = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    (OUTPUT / 'source.json').write_text(json.dumps({'checkout': source, 'pr_head': args.head,
                                                 'base': args.base}, indent=2) + '\n')
    with tempfile.TemporaryDirectory(prefix='compose-unpatched-') as temporary:
        scratch = Path(temporary)
        checkout = scratch / 'checkout'
        subprocess.run(['git', 'clone', '--shared', '--no-checkout', str(ROOT), str(checkout)], check=True)
        subprocess.run(['git', 'checkout', '--detach', source], cwd=checkout, check=True)
        build = checkout / 'build.gradle.kts'
        apply = 'apply(from = "gradle/compose-ui-backport.gradle.kts")'
        text = build.read_text()
        assert text.count(apply) == 1, 'Unexpected backport registration'
        build.write_text(text.replace(apply, '// Backport removed in this disposable negative control only.'))
        test = checkout / 'app/src/test/java' / PACKAGE.replace('.', '/') / 'ComposeRectListUnpatchedIdentityTest.kt'
        test.write_text(f'''package {PACKAGE}

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ComposeRectListUnpatchedIdentityTest {{
    @Test
    fun {IDENTITY}() {{
        assertNull(javaClass.classLoader?.getResource("META-INF/whitenoise-compose-rectlist-backport.properties"))
        val owner = Class.forName("androidx.compose.ui.node.AlignmentLinesOwner")
        assertFalse(owner.declaredMethods.any {{ it.name == "setPlacingForAlignment" }})
    }}
}}
''')
        command = ['./gradlew', ':app:testDevZapstoreDebugUnitTest', '--build-cache', '--profile',
                   '--no-daemon', '--stacktrace', '--console=plain']
        for name in sorted(SHARED):
            command.extend(['--tests', f'{PACKAGE}.ComposeRectListBackportTest.{name}'])
        command.extend(['--tests', f'{PACKAGE}.BubbleFooterRectListReuseTest',
                        '--tests', f'{PACKAGE}.ComposeRectListUnpatchedIdentityTest'])
        with (OUTPUT / 'gradle.log').open('w') as log:
            result = subprocess.run(command, cwd=checkout, stdout=log, stderr=subprocess.STDOUT,
                                    timeout=1200, check=False)
        results = checkout / 'app/build/test-results/testDevZapstoreDebugUnitTest'
        shutil.copytree(results, OUTPUT / 'test-results', dirs_exist_ok=True)
        verify_results(results, result.returncode)


if __name__ == '__main__':
    main()
