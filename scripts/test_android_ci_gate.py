"""Execute the workflow's aggregate gate against GitHub dependency outcomes."""

import json
import os
from pathlib import Path
import re
import subprocess
import textwrap
import unittest


WORKFLOW = Path(__file__).resolve().parents[1] / '.github/workflows/android-ci.yml'
APP_BUILD = Path(__file__).resolve().parents[1] / 'app/build.gradle.kts'


class AndroidCiGateTest(unittest.TestCase):
    """Keep failures from becoming successful skipped aggregate checks."""

    @classmethod
    def setUpClass(cls):
        """Read the actual inline shell gate, avoiding a separate test-only copy."""
        cls.workflow = WORKFLOW.read_text()
        cls.gate = cls.workflow.split('\n  validate:\n', 1)[1]
        cls.static_analysis = cls.workflow.split('\n  static-analysis:\n', 1)[1].split('\n  tests:\n', 1)[0]
        cls.tests_job = cls.workflow.split('\n  tests:\n', 1)[1].split('\n  validate:\n', 1)[0]
        cls.app_build = APP_BUILD.read_text()
        cls.script = textwrap.dedent(cls.gate.split('        run: |\n', 1)[1])
        match = re.search(r'^    needs: \[([^\]]+)\]$', cls.gate, re.MULTILINE)
        cls.dependencies = [job.strip() for job in match.group(1).split(',')]

    @staticmethod
    def named_step(job, name):
        """Return one step block, anchored by its stable display name."""
        marker = f'      - name: {name}\n'
        if job.count(marker) != 1:
            raise AssertionError(f'expected one {name!r} step, found {job.count(marker)}')
        remainder = job.split(marker, 1)[1]
        return marker + remainder.split('\n      - ', 1)[0]

    def run_gate(self, outcomes):
        """Run the production shell with synthetic, untrusted JSON input."""
        return subprocess.run(
            ['bash', '-c', self.script],
            env={**os.environ, 'JOB_RESULTS': json.dumps(outcomes)},
            capture_output=True, text=True, check=False,
        )

    def successful_outcomes(self):
        """Model GitHub's needs object, including empty per-job outputs."""
        return {job: {'result': 'success', 'outputs': {}} for job in self.dependencies}

    def test_aggregate_covers_every_job_and_runs_after_failures(self):
        """Every producer must reach the aggregate even after a dependency fails."""
        jobs = set(re.findall(r'^  ([a-z][a-z-]+):$', self.workflow.split('\njobs:\n', 1)[1], re.MULTILINE))
        self.assertEqual(set(self.dependencies), jobs - {'validate'})
        self.assertIn('    if: always()\n', self.gate)
        self.assertIn('    name: Compile, test, ktlint, detekt, Android lint\n', self.gate)
        self.assertIn('JOB_RESULTS: ${{ toJSON(needs) }}', self.gate)
        self.assertNotIn('continue-on-error:', self.gate)

    def test_static_analysis_isolated_by_flavor_without_duplicating_singletons(self):
        """Both lint variants run concurrently while ktlint and detekt run once."""
        self.assertIn("name: ktlint, detekt, and Android lint (${{ matrix.flavor }})", self.static_analysis)
        self.assertIn('      fail-fast: false\n', self.static_analysis)
        self.assertIn('        flavor: [Zapstore, Play]\n', self.static_analysis)
        ktlint = self.named_step(self.static_analysis, 'ktlint')
        detekt = self.named_step(self.static_analysis, 'detekt')
        lint = self.named_step(self.static_analysis, 'Android lint')
        self.assertIn("        if: matrix.flavor == 'Play'\n", ktlint)
        self.assertIn("        if: matrix.flavor == 'Play'\n", detekt)
        self.assertNotIn('\n        if:', lint)
        self.assertIn(':app:ktlintCheck :benchmark:ktlintCheck', ktlint)
        self.assertIn(':app:detekt', detekt)
        self.assertIn(':app:lintDev${{ matrix.flavor }}Debug', lint)
        self.assertNotIn(':app:lintDevZapstoreDebug :app:lintDevPlayDebug', self.static_analysis)
        self.assertIn('android-ci-reports-static-analysis-${{ matrix.flavor }}', self.static_analysis)
        self.assertIn('android-ci-gradle-profiles-static-analysis-${{ matrix.flavor }}', self.static_analysis)
        self.assertIn('cache-read-only: true', self.static_analysis)

    def test_full_unit_suite_owns_screenshot_verification_and_coverage_reuses_it(self):
        """Roborazzi verification runs once with every test and remains cache-correct."""
        expected_steps = {
            'Unit tests',
            'Coverage gate (Kover)',
            'Coverage report (Kover)',
        }
        test_invocations = {
            name: self.named_step(self.tests_job, name)
            for name in expected_steps
        }
        self.assertEqual(set(test_invocations), expected_steps)
        for name, invocation in test_invocations.items():
            with self.subTest(step=name):
                self.assertIn('-Proborazzi.test.verify=true', invocation)
        self.assertIn(
            ':app:testDev${{ matrix.flavor }}DebugUnitTest',
            test_invocations['Unit tests'],
        )
        self.assertNotIn('verifyRoborazziDev', self.tests_job)
        self.assertNotIn("--tests '", self.tests_job)
        self.assertIn('testDevZapstoreDebugUnitTest', self.app_build)
        self.assertIn('testDevPlayDebugUnitTest', self.app_build)
        self.assertIn('files(layout.projectDirectory.dir("src/test/snapshots").asFileTree)', self.app_build)
        self.assertIn('withPropertyName("roborazziSnapshots")', self.app_build)
        self.assertIn('withPathSensitivity(PathSensitivity.RELATIVE)', self.app_build)

    def test_only_play_tests_publish_gradle_cache_state(self):
        """Parallel analysis and fork runs cannot create competing cache writers."""
        setup_gradle = self.named_step(self.tests_job, 'Set up Gradle')
        expected_policy = """          cache-read-only: >-
            ${{ matrix.flavor != 'Play' ||
                (github.event_name == 'pull_request' &&
                 github.event.pull_request.head.repo.full_name != github.repository) }}
"""
        self.assertIn(expected_policy, setup_gradle)
        gradle_setup_steps = re.findall(
            r'(?ms)^      - name: Set up Gradle\n.*?(?=^      - |^  [a-z]|\Z)',
            self.workflow,
        )
        self.assertEqual(len(gradle_setup_steps), 3)
        for step in gradle_setup_steps:
            self.assertIn('cache-read-only:', step)

    def test_all_successful_jobs_pass(self):
        """A complete green matrix permits the existing required check to pass."""
        result = self.run_gate(self.successful_outcomes())
        self.assertEqual(result.returncode, 0, result.stderr)
        for job in self.dependencies:
            self.assertIn(f'{job}: success', result.stdout)

    def test_any_non_successful_job_blocks(self):
        """Failure, cancellation, and skipped matrix jobs all block the gate."""
        for job in self.dependencies:
            for outcome in ('failure', 'cancelled', 'skipped'):
                with self.subTest(job=job, outcome=outcome):
                    outcomes = self.successful_outcomes()
                    outcomes[job]['result'] = outcome
                    result = self.run_gate(outcomes)
                    self.assertNotEqual(result.returncode, 0)
                    self.assertIn(f'{job}: {outcome}', result.stdout)

    def test_empty_results_do_not_pass_vacuously(self):
        """Absent dependency evidence must never produce a green aggregate."""
        self.assertNotEqual(self.run_gate({}).returncode, 0)

    def test_missing_result_is_rejected(self):
        """An unexpected dependency schema fails closed."""
        outcomes = self.successful_outcomes()
        outcomes[self.dependencies[0]] = {'outputs': {}}
        self.assertNotEqual(self.run_gate(outcomes).returncode, 0)


if __name__ == '__main__':
    unittest.main()
