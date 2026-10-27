"""Execute the workflow's aggregate gate against GitHub dependency outcomes."""

import io
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import textwrap
import time
import unittest

from scripts.check_fuzz_pr_triggers import pr_paths
from scripts.check_runtime_listener_coverage import OWNER, check_coverage
from scripts.select_instrumented_lanes import matches


WORKFLOW = Path(__file__).resolve().parents[1] / '.github/workflows/android-ci.yml'
PR_PRIMARY_MATRIX = (
    "        flavor: ${{ fromJSON(github.event_name == 'pull_request' && needs.changes.outputs.full_matrix != 'true' && '[\"Zapstore\"]' "
    "|| '[\"Zapstore\", \"Play\"]') }}\n"
)
APP_BUILD = Path(__file__).resolve().parents[1] / 'app/build.gradle.kts'


class AndroidCiGateTest(unittest.TestCase):
    """Keep failures from becoming successful skipped aggregate checks."""

    @classmethod
    def setUpClass(cls):
        """Read the actual inline shell gate, avoiding a separate test-only copy."""
        cls.workflow = WORKFLOW.read_text()
        cls.changes = cls.job_block(cls.workflow, 'changes')
        cls.tooling_contracts = cls.job_block(cls.workflow, 'tooling-contracts')
        cls.build_contracts = cls.job_block(cls.workflow, 'build-contracts')
        cls.compose_compiler = cls.job_block(cls.workflow, 'compose-compiler')
        cls.static_analysis = cls.job_block(cls.workflow, 'static-analysis')
        cls.compile_tests = cls.job_block(cls.workflow, 'compile-tests')
        cls.tests_job = cls.job_block(cls.workflow, 'tests')
        cls.secondary = cls.job_block(cls.workflow, 'secondary-variant')
        cls.gate = cls.job_block(cls.workflow, 'validate')
        cls.app_build = APP_BUILD.read_text()
        cls.script = cls.run_block(cls.gate)
        match = re.search(r'^    needs: \[([^\]]+)\]$', cls.gate, re.MULTILINE)
        cls.dependencies = [job.strip() for job in match.group(1).split(',')]

    def test_only_isolated_host_regressions_can_be_deferred(self):
        conditional = ('Test attachment fixture HTTP ledger and cancellation contracts',
                       'Test Maestro artifact and manual-only execution contracts')
        for name in conditional:
            step = self.named_step(self.tooling_contracts, name)
            self.assertIn("if: needs.changes.outputs.tooling_regressions != 'false'", step)
        source = self.named_step(self.tooling_contracts, 'Test Maestro production-source and screen contracts')
        self.assertNotIn('        if:', source)
        for suite in ('test_maestro_runtime.py', 'test_maestro_screen_coverage.py',
                      'test_maestro_modal_discovery.py'):
            self.assertIn(suite, source)
        for name in ('Validate committed dependency locks and supplier evidence',
                     'Validate fuzz PR production triggers',
                     'Validate Actions merge authorization boundary',
                     'Validate manual release guide',
                     'Test preview security and update contracts',
                     'Check committed screenshot golden owners'):
            self.assertNotIn('        if:', self.named_step(self.tooling_contracts, name))
        self.assertIn('    needs: changes', self.tooling_contracts)
        self.assertIn('tooling_regressions: ${{ steps.classify.outputs.tooling_regressions }}', self.changes)

    @staticmethod
    def job_block(workflow, name):
        """Return one top-level job without depending on workflow job order."""
        marker = f'\n  {name}:\n'
        if workflow.count(marker) != 1:
            raise AssertionError(f'expected one {name!r} job, found {workflow.count(marker)}')
        remainder = workflow.split(marker, 1)[1]
        next_job = re.search(r'^  [a-z][a-z-]*:\n', remainder, re.MULTILINE)
        body = remainder[:next_job.start()] if next_job else remainder
        if '    runs-on:' not in body or '    steps:\n' not in body:
            raise AssertionError(f'job {name!r} is incomplete')
        return marker + body

    @staticmethod
    def run_block(job):
        """Read one inline shell block, stopping at the next YAML-level field."""
        marker = '        run: |\n'
        if marker not in job:
            raise AssertionError('expected an inline aggregate run block')
        body = []
        for line in job.split(marker, 1)[1].splitlines(keepends=True):
            if line.strip() and not line.startswith('          '):
                break
            body.append(line)
        if not any(line.strip() for line in body):
            raise AssertionError('aggregate run block is empty')
        return textwrap.dedent(''.join(body))

    @staticmethod
    def named_step(job, name):
        """Return one step block, anchored by its stable display name."""
        marker = f'      - name: {name}\n'
        if job.count(marker) != 1:
            raise AssertionError(f'expected one {name!r} step, found {job.count(marker)}')
        remainder = job.split(marker, 1)[1]
        return marker + remainder.split('\n      - ', 1)[0]

    @staticmethod
    def gradle_steps(job):
        """Return every named step block that invokes the Gradle wrapper."""
        blocks = re.split(r'(?=^      - name: )', job, flags=re.MULTILINE)
        return [block for block in blocks if './gradlew' in block]

    def run_gate(self, outcomes, event='pull_request', draft='false', mode=''):
        """Run the production shell with synthetic, untrusted JSON input."""
        return subprocess.run(
            ['bash', '-c', self.script],
            env={**os.environ, 'JOB_RESULTS': json.dumps(outcomes), 'CI_EVENT': event, 'PR_DRAFT': draft, 'ANDROID_CI_MODE': mode},
            capture_output=True, text=True, check=False,
        )

    def successful_outcomes(self, event='pull_request', draft='false'):
        """Model GitHub's needs object, including empty per-job outputs.

        Ready PRs add the Play compile lane; every other event runs the full
        Play matrix instead, and drafts also defer Compose reports.
        """
        outcomes = {job: {'result': 'success', 'outputs': {}} for job in self.dependencies}
        outcomes['changes']['outputs'] = {'docs_only': 'false', 'supplemental_campaigns': 'true', 'precheck': 'false', 'ci_mode': 'legacy-pr' if event == 'pull_request' else 'full',
                                         'full_matrix': 'false' if event == 'pull_request' else 'true'}
        if event != 'pull_request' or draft == 'true':
            outcomes['secondary-variant']['result'] = 'skipped'
        if event == 'pull_request' and draft == 'true':
            outcomes['compose-compiler']['result'] = 'skipped'
        outcomes['compile-tests']['result'] = 'skipped'
        return outcomes

    def test_aggregate_script_excludes_following_conditional_step(self):
        """A later workflow-only step must never execute as aggregate shell."""
        job = (
            '    steps:\n'
            '      - name: Aggregate\n'
            '        run: |\n'
            "          printf '%s\\n' aggregate-only\n"
            '      # A conditional integration failure stays outside the gate.\n'
            '      - name: Integration failure\n'
            "        if: ${{ github.event_name == 'merge_group' }}\n"
            '        shell: bash\n'
            '        run: |\n'
            '          exit 91\n'
        )
        script = self.run_block(job)
        result = subprocess.run(['bash', '-c', script], capture_output=True, text=True, check=False)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, 'aggregate-only\n')
        self.assertNotIn('Integration failure', script)
        self.assertNotIn('exit 91', script)

    def test_aggregate_covers_every_job_and_runs_after_failures(self):
        """Every producer must reach the aggregate even after a dependency fails."""
        jobs = set(re.findall(r'^  ([a-z][a-z-]+):$', self.workflow.split('\njobs:\n', 1)[1], re.MULTILINE))
        self.assertEqual(set(self.dependencies), jobs - {'validate'})
        self.assertIn('    if: always()\n', self.gate)
        self.assertIn("    name: Compile, test, ktlint, detekt, Android lint${{ github.event.pull_request.draft "
                      "&& ' (draft fast lane)' || '' }}\n", self.gate)
        self.assertIn('JOB_RESULTS: ${{ toJSON(needs) }}', self.gate)
        self.assertNotIn('continue-on-error:', self.gate)

    def test_inventory_reporting_cannot_weaken_or_fail_security_enforcement(self):
        """Exports and comparisons cannot fail a lane or weaken its preceding floor gate."""
        comparison = self.named_step(self.build_contracts, 'Compare already-observed dependencies with exact PR base')
        self.assertIn('\n        continue-on-error: true\n', comparison)
        submission = (WORKFLOW.parent / 'dependency-submission.yml').read_text()
        jobs = [self.build_contracts, self.compose_compiler, self.static_analysis, self.compile_tests,
                self.tests_job, self.secondary, submission]
        for job in jobs:
            verification = self.named_step(job, 'Verify selected dependency security floors')
            export = self.named_step(job, 'Export observed dependency inventory')
            self.assertNotIn('continue-on-error:', verification)
            self.assertNotIn('--inventory', verification)
            self.assertIn('scripts/verify_dependency_security.py', verification)
            self.assertIn('\n        continue-on-error: true\n', export)
            self.assertIn('\n        if: success()\n', export)
            self.assertIn('--inventory', export)
            self.assertLess(job.index(verification), job.index(export))

    def test_inventory_comparison_uses_tested_merge_parent_despite_stale_event_base(self):
        """Execute the production step against a merge whose master parent advanced after the event."""
        comparison = self.named_step(self.build_contracts, 'Compare already-observed dependencies with exact PR base')
        script = textwrap.dedent(comparison.split('        run: |\n', 1)[1])
        with tempfile.TemporaryDirectory() as directory:
            def git(*arguments):
                """Keep the synthetic merge graph entirely within this disposable fixture."""
                return subprocess.check_output(['git', '-C', directory, *arguments], text=True).strip()
            git('init', '-q')
            git('config', 'user.name', 'Fixture')
            git('config', 'user.email', 'fixture@example.invalid')
            git('config', 'commit.gpgsign', 'false')
            git('commit', '--allow-empty', '-qm', 'event base')
            stale = git('rev-parse', 'HEAD')
            git('commit', '--allow-empty', '-qm', 'newer master')
            parent = git('rev-parse', 'HEAD')
            merge = git('commit-tree', 'HEAD^{tree}', '-p', parent, '-p', stale, '-m', 'tested merge')
            git('update-ref', 'HEAD', merge)
            capture = Path(directory) / 'arguments.txt'
            result = subprocess.run(
                ['bash', '-eo', 'pipefail', '-c',
                 'python3() { printf "%s\\n" "$@" > "$COMPARISON_ARGUMENTS"; }\n' + script],
                cwd=directory, env={**os.environ, 'BASE_SHA': stale, 'BASE_ARTIFACT': 'fixture',
                                    'COMPARISON_ARGUMENTS': str(capture)}, capture_output=True, text=True,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            arguments = capture.read_text().splitlines()
            self.assertEqual(arguments[arguments.index('--base-source') + 1], parent)
            self.assertEqual(arguments[arguments.index('--after-source') + 1], merge)
            self.assertNotEqual(parent, stale)

    def test_fuzz_trigger_policy_runs_outside_filtered_fuzz_workflow(self):
        """A missing parser path cannot skip the policy check itself."""
        events = self.workflow.split('\non:\n', 1)[1].split('\nconcurrency:', 1)[0]
        self.assertIn('  pull_request:\n    branches: [master]', events)
        self.assertNotIn('paths:', events)
        self.assertNotIn('paths-ignore:', events)
        self.assertNotIn('\n    if:', self.tooling_contracts.split('    steps:', 1)[0])
        step = self.named_step(self.tooling_contracts, 'Validate fuzz PR production triggers')
        self.assertNotIn('        if:', step)
        self.assertIn('python3 scripts/check_fuzz_pr_triggers.py\n', step)
        self.assertIn('python3 -m unittest scripts/test_check_fuzz_pr_triggers.py', step)
        self.assertNotIn('continue-on-error:', step)

    def test_compose_reports_run_independently_without_leaving_a_duplicate(self):
        """A full campaign measures Compose independently without duplicate compilation."""
        compile_step = self.named_step(
            self.compose_compiler,
            'Compile Compose metrics and stability reports',
        )
        self.assertIn(':app:compileStagingZapstoreReleaseKotlin', compile_step)
        self.assertIn('-Pwhitenoise.enableComposeCompilerReports=true', compile_step)
        self.assertIn(' --no-build-cache ', compile_step)
        # Reports cover only compiled files; a persistent workspace would compile incrementally.
        self.assertIn(':app:compileStagingZapstoreReleaseKotlin --rerun ', compile_step)
        self.assertIn(' --no-daemon ', compile_step)
        self.assertNotIn(':app:compileStagingZapstoreReleaseKotlin', self.build_contracts)
        self.assertIn('name: compose-compiler-reports', self.compose_compiler)
        self.assertIn('if-no-files-found: error', self.compose_compiler)
        self.assertIn('android-ci-gradle-profiles-compose-compiler', self.compose_compiler)

    def test_every_gradle_job_has_the_required_bootstrap(self):
        """Extracted jobs retain the same toolchain and artifact preparation."""
        required_steps = (
            'Checkout',
            'Configure MarmotKit cache directory',
            'Set up JDK 17',
            'Set up Gradle',
            'Read MarmotKit artifact pin',
            'Restore MarmotKit artifact',
        )
        for job_name, job in (
            ('build-contracts', self.build_contracts),
            ('compose-compiler', self.compose_compiler),
            ('static-analysis', self.static_analysis),
            ('compile-tests', self.compile_tests),
            ('tests', self.tests_job),
        ):
            for step_name in required_steps:
                with self.subTest(job=job_name, step=step_name):
                    self.named_step(job, step_name)

    def test_static_analysis_isolated_by_flavor_without_duplicating_singletons(self):
        """Both lint variants run concurrently while ktlint and detekt run once."""
        self.assertIn("name: Android lint (${{ matrix.flavor }})", self.static_analysis)
        self.assertIn('      fail-fast: false\n', self.static_analysis)
        self.assertIn(PR_PRIMARY_MATRIX, self.static_analysis)
        style = self.named_step(self.build_contracts, 'ktlint and detekt')
        lint = self.named_step(self.static_analysis, 'Android lint')
        self.assertIn("        if: matrix.phase == 'tooling'\n", style)
        self.assertNotIn('\n        if:', lint)
        self.assertIn(':app:ktlintCheck :benchmark:ktlintCheck :app:detekt', style)
        self.assertNotIn(':app:ktlintCheck', self.static_analysis)
        self.assertNotIn(':app:detekt', self.static_analysis)
        self.assertIn(':app:lintDev${{ matrix.flavor }}Debug', lint)
        self.assertNotIn(':app:lintDevZapstoreDebug :app:lintDevPlayDebug', self.static_analysis)
        self.assertIn('android-ci-reports-static-analysis-${{ matrix.flavor }}', self.static_analysis)
        self.assertIn('android-ci-gradle-profiles-static-analysis-${{ matrix.flavor }}', self.static_analysis)
        self.assertIn('cache-read-only:', self.static_analysis)

    def test_unit_deadline_reserves_early_failure_artifact_upload(self):
        self.assertIn('timeout-minutes: 40', self.tests_job)
        anchor = self.named_step(self.tests_job, 'Reserve unit-test diagnostics and upload time')
        self.assertIn('time.monotonic() + 32 * 60', anchor)
        self.assertLess(self.tests_job.index('      - name: Reserve unit-test'),
                        self.tests_job.index('      - name: Checkout'))
        unit = self.named_step(self.tests_job, 'Unit tests')
        self.assertIn('python3 scripts/run_unit_tests_with_diagnostics.py --', unit)
        self.assertIn('--no-daemon', unit)
        self.assertNotIn('continue-on-error', unit)
        upload = self.named_step(self.tests_job, 'Upload reports')
        self.assertIn('if: always()', upload)
        self.assertIn('timeout-minutes: 3', upload)
        self.assertIn('app/build/outputs/roborazzi/', upload)
        self.assertIn('app/build/reports/', upload)
        self.assertIn('app/build/test-results/', upload)
        self.assertIn('build/reports/profile/', upload)
        self.assertIn('scripts/test_unit_test_diagnostics.py', self.workflow)
        self.assertEqual(self.tests_job.count('      - name: Upload reports\n'), 1)
        self.assertLess(self.tests_job.index('      - name: Unit tests'),
                        self.tests_job.index('      - name: Upload reports'))
        self.assertLess(self.tests_job.index('      - name: Upload reports'),
                        self.tests_job.index('      - name: Require every committed screenshot'))

    def test_full_unit_suite_verifies_goldens_and_is_reused_by_coverage(self):
        """Identical verification inputs let coverage reuse the complete suite."""
        expected_steps = {
            'Unit tests',
            'Coverage gate (Kover)',
            'Coverage report (Kover)',
        }
        test_invocations = {
            name: self.named_step(self.tests_job, name)
            for name in expected_steps
        }
        for name, invocation in test_invocations.items():
            with self.subTest(step=name):
                self.assertIn('-Proborazzi.test.verify=true', invocation)
        self.assertIn(
            ':app:testDev${{ matrix.flavor }}DebugUnitTest',
            test_invocations['Unit tests'],
        )
        self.assertNotIn('verifyRoborazziDev', self.tests_job)
        self.assertNotIn("--tests '", self.tests_job)
        unit_tests = test_invocations['Unit tests']
        self.assertIn('        id: unit_tests\n', unit_tests)
        coverage_report = test_invocations['Coverage report (Kover)']
        self.assertIn(
            "if: ${{ !cancelled() && steps.unit_tests.outcome == 'success' && (!github.event.pull_request.draft || needs.changes.outputs.full_matrix == 'true') }}",
            coverage_report,
        )
        self.assertIn('testDevZapstoreDebugUnitTest', self.app_build)
        self.assertIn('testDevPlayDebugUnitTest', self.app_build)
        self.assertIn('files(layout.projectDirectory.dir("src/test/snapshots").asFileTree)', self.app_build)
        self.assertIn('withPropertyName("roborazziSnapshots")', self.app_build)
        self.assertIn('withPathSensitivity(PathSensitivity.RELATIVE)', self.app_build)

    def test_teardown_floor_reuses_report_and_propagates_report_failure(self):
        floor = self.named_step(self.tests_job, 'Runtime listener and viewport restoration coverage floors')
        self.assertIn("matrix.flavor == 'Zapstore'", floor)
        self.assertIn("steps.unit_tests.outcome == 'success'", floor)
        self.assertIn('COVERAGE_REPORT_OUTCOME: ${{ steps.coverage_report.outcome }}', floor)
        self.assertIn('test "$COVERAGE_REPORT_OUTCOME" = success', floor)
        self.assertIn('scripts/check_runtime_listener_coverage.py', floor)
        self.assertIn('app/build/reports/kover/reportDevZapstoreDebug.xml', floor)
        self.assertNotIn('continue-on-error:', floor)
        self.assertNotIn('./gradlew', floor)
        self.assertIn('        id: coverage_report\n',
                      self.named_step(self.tests_job, 'Coverage report (Kover)'))

    def test_viewport_floor_reuses_the_required_full_suite_report(self):
        floor = self.named_step(self.tests_job, 'Runtime listener and viewport restoration coverage floors')
        self.assertIn("matrix.flavor == 'Zapstore'", floor)
        self.assertIn("steps.unit_tests.outcome == 'success'", floor)
        self.assertIn('test "$COVERAGE_REPORT_OUTCOME" = success', floor)
        self.assertIn('scripts/check_viewport_restoration_coverage.py', floor)
        self.assertNotIn('continue-on-error:', floor)
        self.assertNotIn('./gradlew', floor)
        self.assertIn('scripts/test_check_viewport_restoration_coverage.py', self.tooling_contracts)

    def test_full_suite_compares_every_committed_golden(self):
        """Both full suites must prove complete golden coverage without filters."""
        self.assertIn(PR_PRIMARY_MATRIX, self.tests_job)
        self.assertNotIn('verifyRoborazziDev', self.workflow)
        self.assertNotIn('--tests ', self.tests_job)
        step = self.named_step(self.tests_job, 'Require every committed screenshot golden to be compared')
        self.assertIn('--results app/build/test-results/roborazzi/dev${{ matrix.flavor }}Debug/results-summary.json', step)
        self.assertNotIn('continue-on-error:', step)
        self.assertLess(self.tests_job.index('      - name: Unit tests'), self.tests_job.index(step))
        static = self.named_step(self.tooling_contracts, 'Check committed screenshot golden owners')
        self.assertNotIn('        if:', static)
        self.assertIn('python3 -m unittest scripts/test_check_screenshot_baseline_owners.py', static)
        self.assertIn('python3 scripts/check_screenshot_baseline_owners.py\n', static)

    def test_instrumented_dispatch_tooling_runs_without_an_emulator(self):
        """The dispatcher and required-case parser tests run in the fast tooling phase."""
        step = self.named_step(self.tooling_contracts, 'Test instrumented dispatch and required cases')
        self.assertNotIn('        if:', step)
        self.assertIn('python3 -m unittest scripts/test_run_android_instrumented_dispatch.py', step)
        self.assertIn('python3 -m unittest scripts/test_check_instrumented_required_cases.py', step)

    def test_queue_trigger_and_revision_are_distinct_from_pr_runs(self):
        self.assertIn('  merge_group:\n    types: [checks_requested]\n', self.workflow)
        self.assertIn("cancel-in-progress: ${{ github.event_name != 'merge_group' }}", self.workflow)
        self.assertIn("github.event_name == 'merge_group' && github.run_id || github.ref", self.workflow)
        self.assertIn('github.event.merge_group.base_sha ||', self.changes)
        self.assertIn('github.event.merge_group.head_sha ||', self.changes)
        self.assertIn('ref: ${{ github.sha }}', self.changes)

    def test_queue_aggregate_requires_real_successful_jobs(self):
        outcomes = self.successful_outcomes('merge_group')
        self.assertEqual(self.run_gate(outcomes, 'merge_group').returncode, 0)
        for dependency in self.dependencies:
            if dependency in {'secondary-variant', 'compile-tests'}:
                continue  # The queue runs both full variants instead.
            for result in ['failure', 'cancelled', 'skipped']:
                bad = self.successful_outcomes('merge_group')
                bad[dependency]['result'] = result
                with self.subTest(dependency=dependency, result=result):
                    self.assertNotEqual(self.run_gate(bad, 'merge_group').returncode, 0)

    def test_queue_rejects_documentation_shortcut(self):
        outcomes = self.successful_outcomes('merge_group')
        outcomes['changes']['outputs'].update(docs_only='true', supplemental_campaigns='false')
        for name in self.dependencies:
            if name not in {'changes', 'tooling-contracts'}:
                outcomes[name]['result'] = 'skipped'
        self.assertNotEqual(self.run_gate(outcomes, 'merge_group').returncode, 0)
        for name in ['changes', 'tooling-contracts']:
            bad = json.loads(json.dumps(outcomes))
            bad[name]['result'] = 'failure'
            self.assertNotEqual(self.run_gate(bad, 'merge_group').returncode, 0)
        self.assertNotEqual(self.run_gate(outcomes, 'push').returncode, 0)

    def test_job_caches(self):
        """Every workload retains its own task cache; forks remain read-only."""
        gradle_setup_steps = re.findall(
            r'(?ms)^      - name: Set up Gradle\n.*?(?=^      - |^  [a-z]|\Z)',
            self.workflow,
        )
        self.assertEqual(len(gradle_setup_steps), 7)
        for step in gradle_setup_steps:
            self.assertIn(
                "cache-read-only: ${{ github.event_name == 'merge_group' || (github.event_name == 'pull_request' && "
                "github.event.pull_request.head.repo.full_name != github.repository) }}",
                step,
            )
        self.assertNotIn('uses: actions/cache@', self.workflow)
        self.assertEqual(self.workflow.count('uses: actions/cache/restore@'), 7)
        self.assertEqual(self.workflow.count('uses: actions/cache/save@'), 1)
        save_step = self.named_step(self.tests_job, 'Save MarmotKit artifact')
        # Zapstore is the only flavor every event runs.
        self.assertIn("matrix.flavor == 'Zapstore'", save_step)
        self.assertIn("github.event_name != 'merge_group'", save_step)
        self.assertIn("github.event_name != 'pull_request'", save_step)
        self.assertIn(
            'github.event.pull_request.head.repo.full_name == github.repository',
            save_step,
        )
        self.assertIn("steps.marmotkit-cache.outputs.cache-hit != 'true'", save_step)

    def test_build_phases(self):
        """Packaging runs independently, with phase-specific diagnostics."""
        self.assertIn(
            """phase: ${{ fromJSON(((github.event.pull_request.draft && needs.changes.outputs.full_matrix != 'true') || needs.changes.outputs.precheck == 'true') && '["tooling"]' || '["tooling", "baseline"]') }}""",
            self.build_contracts)
        self.assertIn('fail-fast: false', self.build_contracts)
        for name, phase in (
            ('Compile (Kotlin)', 'tooling'),
            ('Assemble app for Baseline Profile verification', 'baseline'),
            ('Verify packaged Baseline Profile assets', 'baseline'),
        ):
            step = self.named_step(self.build_contracts, name)
            self.assertIn(f"if: matrix.phase == '{phase}'", step)
        self.assertIn('android-ci-reports-build-contracts-${{ matrix.phase }}', self.build_contracts)
        self.assertIn('android-ci-gradle-profiles-build-contracts-${{ matrix.phase }}', self.build_contracts)

    def test_parallel_test_workers(self):
        """Parallelism changes execution, never the full-suite scope or caching."""
        self.assertIn(
            "ORG_GRADLE_PROJECT_ciTestForks: ${{ github.event.pull_request.head.repo.fork && '2' || '6' }}",
            self.tests_job,
        )
        root_build = (WORKFLOW.parents[2] / 'build.gradle.kts').read_text()
        self.assertIn('providers.gradleProperty("ciTestForks").map(String::toInt).getOrElse(1)', root_build)
        self.assertIn('outputs.doNotCacheIf("CI test assertions must execute")', root_build)

    def test_dependency_qualification_follows_a_restored_committed_trust_root(self):
        """After a capture, qualification runs only once the trust-root check has restored and proved committed bytes."""
        job = self.build_contracts
        retain = job.index('      - name: Retain dependency-control bootstrap candidates\n')
        require = job.index('      - name: Require the committed dependency trust root for qualification\n')
        qualify = job.index('      - name: Qualify dependency submission graph generation\n')
        self.assertLess(retain, require)
        self.assertLess(require, qualify)
        step = self.named_step(job, 'Require the committed dependency trust root for qualification')
        self.assertIn("if: matrix.phase == 'tooling'", step)
        self.assertIn("RESTORE_CAPTURE: ${{ github.event_name == 'workflow_dispatch' && "
                      "(inputs.capture_dependency_inputs || inputs.capture_benchmark_dependency_inputs) }}", step)
        self.assertIn('./scripts/require-committed-dependency-trust-root.sh', step)

    def test_trust_root_check_restores_capture_rewrites_and_rejects_them_otherwise(self):
        """A capture's rewritten or new locks are restored in capture mode and rejected in every other mode."""
        script = WORKFLOW.parents[2] / 'scripts/require-committed-dependency-trust-root.sh'
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            # The throwaway repository ignores the machine's git configuration, such as commit signing.
            isolated = {**os.environ, 'GIT_CONFIG_GLOBAL': os.devnull, 'GIT_CONFIG_NOSYSTEM': '1'}

            def git(*arguments):
                """Run git in the throwaway repository and return its output."""
                return subprocess.run(['git', *arguments], cwd=root, check=True, capture_output=True,
                                      text=True, env=isolated).stdout

            def check(restore):
                """Run the trust-root check with or without capture restoration."""
                return subprocess.run(['bash', str(script)], cwd=root, capture_output=True, text=True, check=False,
                                      env={**isolated, 'RESTORE_CAPTURE': restore})

            git('init', '-q')
            git('config', 'user.email', 'ci@example.invalid')
            git('config', 'user.name', 'CI')
            (root / 'gradle').mkdir()
            (root / 'app').mkdir()
            committed = {'gradle/verification-metadata.xml': '<committed/>\n', 'gradle.lockfile': 'empty=\n',
                         'app/gradle.lockfile': 'example:library:1.0=releaseRuntimeClasspath\nempty=\n'}
            for relative, text in committed.items():
                (root / relative).write_text(text)
            git('add', '.')
            git('commit', '-q', '-m', 'trust root')
            self.assertEqual(0, check('false').returncode)

            def capture():
                """Rewrite the trust root the way a capture run does, including a new lockfile."""
                (root / 'gradle/verification-metadata.xml').write_text('<captured/>\n')
                (root / 'app/gradle.lockfile').write_text('example:library:2.0=releaseRuntimeClasspath\nempty=\n')
                (root / 'app/buildscript-gradle.lockfile').write_text('empty=classpath\n')

            capture()
            self.assertNotEqual(0, check('false').returncode)
            (root / 'gradle/verification-metadata.xml').write_text('<committed/>\n')
            (root / 'app/gradle.lockfile').write_text(committed['app/gradle.lockfile'])
            rejected = check('false')
            self.assertNotEqual(0, rejected.returncode)
            self.assertIn('uncommitted lockfiles', rejected.stderr)
            capture()
            restored = check('true')
            self.assertEqual(0, restored.returncode, restored.stderr)
            for relative, text in committed.items():
                self.assertEqual(text, (root / relative).read_text())
            self.assertFalse((root / 'app/buildscript-gradle.lockfile').exists())
            self.assertEqual('', git('status', '--porcelain'))

    def test_lightweight_tooling_and_test_builds_reuse_the_gradle_daemon(self):
        """Measured lightweight steps reuse a JVM; heavier tooling stays isolated."""
        invocations = self.gradle_steps(self.tests_job)
        self.assertGreaterEqual(len(invocations), 3)
        # Only the supervised full-suite invocation needs its own daemon lifetime.
        unit = self.named_step(self.tests_job, 'Unit tests')
        self.assertIn('--no-daemon', unit)
        invocations = [step for step in invocations if '      - name: Unit tests' not in step]
        tooling = [step for step in self.gradle_steps(self.build_contracts)
                   if "if: matrix.phase == 'tooling'" in step and
                   '- name: Capture dependency-control bootstrap candidates' not in step]
        self.assertEqual(len(tooling), 4)
        capture = self.named_step(self.build_contracts, 'Capture dependency-control bootstrap candidates')
        self.assertIn("if: always() && matrix.phase == 'tooling' && github.event_name == 'workflow_dispatch' "
                      "&& (inputs.capture_dependency_inputs || inputs.capture_benchmark_dependency_inputs)", capture)
        self.assertNotIn('assemble', capture)
        retained = self.named_step(self.build_contracts, 'Retain dependency-control bootstrap candidates')
        self.assertIn("github.event_name == 'workflow_dispatch' && "
                      "(inputs.capture_dependency_inputs || inputs.capture_benchmark_dependency_inputs)", retained)
        self.assertIn('--write-locks --write-verification-metadata sha256', capture)
        self.assertIn('--no-daemon', capture)
        label_step = self.named_step(self.build_contracts,
                                     'Verify packaged system labels for every app variant')
        self.assertIn('./scripts/test-system-labels.sh', label_step)
        self.assertIn('./gradlew --stop', label_step)
        self.assertLess(label_step.index('./scripts/test-system-labels.sh'),
                        label_step.index('./gradlew --stop'))
        self.assertLess(self.build_contracts.index(label_step),
                        self.build_contracts.index('      - name: Compile (Kotlin)'))
        self.assertLess(self.build_contracts.index(label_step),
                        self.build_contracts.index('      - name: Verify production signing and bundle task isolation'))
        api = self.named_step(self.build_contracts, 'Prepare MarmotKit API signature')
        self.assertTrue(all(' --daemon ' in step for step in invocations + [api]))
        self.assertTrue(all(' --no-daemon ' not in step for step in invocations + [api]))

        for name in ('Compile (Kotlin)', 'ktlint and detekt',
                     'Assemble app for Baseline Profile verification'):
            isolated = self.named_step(self.build_contracts, name)
            self.assertIn(' --no-daemon ', isolated)
            self.assertNotIn(' --daemon ', isolated)
        labels = (WORKFLOW.parents[2] / 'scripts/test-system-labels.sh').read_text()
        self.assertIn('common_args=(--daemon --stacktrace)', labels)
        self.assertIn('PR_PREVIEW_CHANNEL=stable', labels)
        self.assertIn('PR_PREVIEW_CHANNEL=isolated', labels)

    def test_all_successful_jobs_pass(self):
        """A complete green matrix permits the existing required check to pass."""
        result = self.run_gate(self.successful_outcomes())
        self.assertEqual(result.returncode, 0, result.stderr)
        for job in self.dependencies:
            self.assertIn(f"{job}: {self.successful_outcomes()[job]['result']}", result.stdout)

    def test_release_lint_remains_required(self):
        """Hoisting lint out of APK builds must preserve both release checks."""
        workflow = WORKFLOW.with_name('android-repro-verify.yml').read_text()
        self.assertIn('variant: [Production, Staging]', workflow)
        self.assertIn(':app:lintVital${{ matrix.variant }}ZapstoreRelease', workflow)
        gate = self.job_block(workflow, 'verify')
        self.assertIn('needs: [changes, build, lint]', gate)
        self.assertIn('if: always()', gate)
        step = self.named_step(gate, 'Require independent builds and release lint')
        script = textwrap.dedent(step.split('        run: |\n', 1)[1])
        for campaign in ('true', 'false', '', 'unexpected'):
            for event in ('pull_request', 'merge_group', 'schedule', 'workflow_dispatch', 'push'):
                for changes in ('success', 'failure', 'skipped', 'cancelled'):
                    for build in ('success', 'failure', 'cancelled', 'skipped'):
                        for lint in ('success', 'failure', 'cancelled', 'skipped'):
                            result = subprocess.run(['bash', '-c', script], env={**os.environ,
                                'CHANGES_RESULT': changes, 'CAMPAIGNS': campaign,
                                'CI_EVENT': event, 'BUILD_RESULT': build, 'LINT_RESULT': lint},
                                capture_output=True, check=False)
                            expected = changes == lint == 'success' and (
                                campaign == 'true' and build == 'success' or
                                campaign == 'false' and event in {'pull_request', 'merge_group'} and build == 'skipped')
                            self.assertEqual(result.returncode == 0, expected,
                                             (campaign, event, changes, build, lint))

    def test_release_workflows_defer_only_whole_draft_runs(self):
        """Drafts skip every release lane; a partial skip or non-draft skip stays red."""
        repro = WORKFLOW.with_name('android-repro-verify.yml').read_text()
        step = self.named_step(self.job_block(repro, 'verify'), 'Require independent builds and release lint')
        script = textwrap.dedent(step.split('        run: |\n', 1)[1])
        for draft in ('true', 'false', ''):
            for changes in ('success', 'skipped'):
                for build in ('success', 'skipped'):
                    for lint in ('success', 'skipped'):
                        result = subprocess.run(['bash', '-c', script], env={**os.environ,
                            'CHANGES_RESULT': changes, 'CAMPAIGNS': '' if changes == 'skipped' else 'true',
                            'CI_EVENT': 'pull_request', 'BUILD_RESULT': build, 'LINT_RESULT': lint,
                            'DRAFT_PR': draft}, capture_output=True, check=False)
                        expected = (changes == build == lint == 'skipped' and draft == 'true' or
                                    changes == build == lint == 'success')
                        self.assertEqual(result.returncode == 0, expected, (draft, changes, build, lint))
        runtime = WORKFLOW.with_name('android-release-runtime.yml').read_text()
        step = self.named_step(self.job_block(runtime, 'verify'), 'Require both runtime-verifier lanes')
        script = textwrap.dedent(step.split('        run: |\n', 1)[1])
        for draft in ('true', 'false', ''):
            for production in ('success', 'skipped', 'failure'):
                for staging in ('success', 'skipped', 'failure'):
                    result = subprocess.run(['bash', '-c', script], env={**os.environ,
                        'PRODUCTION_RESULT': production, 'STAGING_ANDROID17_RESULT': staging,
                        'DRAFT_PR': draft}, capture_output=True, check=False)
                    expected = (production == staging == 'success' or
                                production == staging == 'skipped' and draft == 'true')
                    self.assertEqual(result.returncode == 0, expected, (draft, production, staging))
        for workflow in (repro, runtime):
            events = workflow.split('\non:\n', 1)[1].split('\nconcurrency:', 1)[0]
            self.assertIn("  push:\n    branches: [master]\n    tags:\n      - '*'\n", events)
            self.assertIn('  schedule:\n', events)
            self.assertIn('  merge_group:\n    types: [checks_requested]\n', events)
            self.assertIn('    paths:\n', events)
            self.assertIn("      - 'app/build.gradle.kts'\n", events)
            self.assertIn("      - 'gradle/**'\n", events)

    def test_release_path_filters_cover_every_root_script_build_input(self):
        """A diff touching only a Gradle-declared script input still starts release checks."""
        scripts = set(re.findall(r'inputs\.file\(rootProject\.file\("(scripts/[^"]+)"\)\)', self.app_build))
        self.assertIn('scripts/prepare_marmotkit_artifact.py', scripts)
        # Compose stability configuration changes the release bytecode.
        scripts |= set(re.findall(r'stabilityConfigurationFiles\.add\([^)]*file\("([^"]+)"\)', self.app_build))
        self.assertIn('config/compose-stability.conf', scripts)
        for name in ('android-release-runtime.yml', 'android-repro-verify.yml'):
            triggers = pr_paths(WORKFLOW.with_name(name).read_text())
            for script in sorted(scripts):
                with self.subTest(workflow=name, script=script):
                    self.assertTrue(matches(script, triggers))

    def test_any_non_successful_job_blocks(self):
        """Failure, cancellation, and skipped matrix jobs all block the gate."""
        for job in self.dependencies:
            for outcome in ('failure', 'cancelled', 'skipped', 'success'):
                if outcome == self.successful_outcomes()[job]['result']:
                    continue
                with self.subTest(job=job, outcome=outcome):
                    outcomes = self.successful_outcomes()
                    outcomes[job]['result'] = outcome
                    result = self.run_gate(outcomes)
                    self.assertNotEqual(result.returncode, 0)
                    self.assertIn(f'{job}: {outcome}', result.stdout)

    def docs_outcomes(self):
        outcomes = self.successful_outcomes()
        outcomes['changes']['outputs'].update(docs_only='true', supplemental_campaigns='false')
        for job in self.dependencies:
            if job not in {'changes', 'tooling-contracts'}:
                outcomes[job]['result'] = 'skipped'
        return outcomes

    def test_preview_security_contracts_always_run_without_building_apks(self):
        step = self.named_step(self.tooling_contracts, "Test preview security and update contracts")
        self.assertNotIn("        if:", step)
        self.assertNotIn("continue-on-error:", step)
        self.assertIn("bash .github/scripts/test-pr-preview-contract.sh", step)
        self.assertIn("bash .github/scripts/test-pr-preview-validation.sh", step)
        self.assertNotIn("./gradlew", step)

    def test_slow_tooling_does_not_block_android_job_start(self):
        self.assertIn('    needs: changes\n', self.tooling_contracts.split('    steps:', 1)[0])
        # Jobs that compile the shared debug variants wait only for the compile job.
        shared = ('build-contracts', 'static-analysis', 'tests', 'secondary-variant')
        for name in ('offline-zsp', 'compile', 'build-contracts', 'compose-compiler',
                     'compile-tests', 'static-analysis', 'tests', 'secondary-variant'):
            job = self.job_block(self.workflow, name)
            self.assertIn('    needs: [changes, compile]\n' if name in shared else '    needs: changes\n', job)
            self.assertIn("needs.changes.outputs.docs_only != 'true'", job)
            if name == 'compose-compiler':
                self.assertIn("needs.changes.outputs.supplemental_campaigns != 'false'", job)
            self.assertNotIn('    needs: tooling-contracts', job)

    def test_docs_only_accepts_only_classified_skips(self):
        self.assertEqual(self.run_gate(self.docs_outcomes()).returncode, 0)
        for value in ('false', '', 'unexpected'):
            outcomes = self.docs_outcomes()
            outcomes['changes']['outputs']['docs_only'] = value
            self.assertNotEqual(self.run_gate(outcomes).returncode, 0)
        outcomes = self.docs_outcomes()
        outcomes['changes']['outputs'] = {}
        self.assertNotEqual(self.run_gate(outcomes).returncode, 0)

    def test_docs_classification_never_masks_failures_or_cancellations(self):
        for job in self.dependencies:
            for outcome in ('failure', 'cancelled'):
                outcomes = self.docs_outcomes()
                outcomes[job]['result'] = outcome
                self.assertNotEqual(self.run_gate(outcomes).returncode, 0)

    def test_docs_mode_cannot_be_used_for_master_pushes(self):
        result = subprocess.run(['bash', '-c', self.script],
                                env={**os.environ, 'JOB_RESULTS': json.dumps(self.docs_outcomes()),
                                     'CI_EVENT': 'push', 'ANDROID_CI_MODE': ''}, capture_output=True, check=False)
        self.assertNotEqual(result.returncode, 0)

    def test_campaign_deferral_is_explicit_and_cannot_mask_failed_reports(self):
        outcomes = self.successful_outcomes()
        outcomes['changes']['outputs']['supplemental_campaigns'] = 'false'
        outcomes['compose-compiler']['result'] = 'skipped'
        self.assertEqual(self.run_gate(outcomes).returncode, 0)
        for value in ('true', '', 'unexpected', None):
            outcomes['changes']['outputs']['supplemental_campaigns'] = value
            self.assertNotEqual(self.run_gate(outcomes).returncode, 0)
        outcomes['changes']['outputs']['supplemental_campaigns'] = 'false'
        for result in ('failure', 'cancelled'):
            outcomes['compose-compiler']['result'] = result
            self.assertNotEqual(self.run_gate(outcomes).returncode, 0)

    def test_campaigns_run_nightly_and_manual(self):
        for filename in ('android-ci.yml', 'android-repro-verify.yml'):
            workflow = WORKFLOW.with_name(filename).read_text()
            events = workflow.split('\non:\n', 1)[1].split('\nconcurrency:', 1)[0]
            self.assertIn('  schedule:\n', events)
            self.assertIn('  workflow_dispatch:', events)
            self.assertIn('${{ github.event_name }}-${{ github.event_name ==', workflow)
            self.assertIn("'merge_group' && github.run_id || github.ref", workflow)
            self.assertIn('  pull_request:\n    branches: [master]', events)
            self.assertIn('    types: [opened, synchronize, reopened, ready_for_review]\n', events)

    def test_drafts_defer_only_heavy_lanes(self):
        """Marking a draft ready re-runs CI; drafts keep primary lint/tests/style."""
        for job in (self.static_analysis, self.tests_job, self.changes, self.tooling_contracts):
            self.assertNotIn('github.event.pull_request.draft', job.split('    steps:', 1)[0])
        self.assertIn('!github.event.pull_request.draft', self.compose_compiler.split('    steps:', 1)[0])
        self.assertIn('!github.event.pull_request.draft', self.secondary.split('    steps:', 1)[0])
        for name in ('Coverage gate (Kover)', 'Coverage report (Kover)',
                     'Runtime listener and viewport restoration coverage floors'):
            self.assertIn('!github.event.pull_request.draft', self.named_step(self.tests_job, name))
        self.assertNotIn('draft', self.named_step(self.tests_job, 'Unit tests'))
        self.assertIn('PR_DRAFT: ${{ github.event.pull_request.draft == true }}', self.gate)

    def test_draft_aggregate_never_reports_the_required_context(self):
        """A ready run must not inherit a green draft result for the same head."""
        name = re.search(r'^    name: (.+)$', self.gate, re.MULTILINE).group(1)
        required = 'Compile, test, ktlint, detekt, Android lint'
        static, expression = name.split('${{', 1)
        self.assertEqual(static, required)
        draft, ready = re.fullmatch(
            r" github\.event\.pull_request\.draft && '([^']*)' \|\| '([^']*)' }}", expression).groups()
        self.assertNotEqual(static + draft, required)
        self.assertEqual(static + ready, required)

    def test_pull_requests_run_one_full_variant_and_compile_the_other(self):
        self.assertIn("github.event_name == 'pull_request'", self.secondary.split('    steps:', 1)[0])
        step = self.named_step(self.secondary, 'Compile Play and run its flavor-sensitive unit tests')
        self.assertIn(':app:testDevPlayDebugUnitTest', step)
        self.assertEqual(step.count(':app:compileDevPlayDebugAndroidTestKotlin'), 1)
        self.assertNotIn(':app:compileDevPlayDebugKotlin', step)
        self.assertNotIn(':app:compileDevPlayDebugUnitTestKotlin', step)
        self.assertNotIn(':app:assemble', step)
        self.assertNotIn(':app:compileDevZapstore', step)
        self.assertIn("grep -rlF 'BuildConfig.SELF_UPDATE_ENABLED' app/src/test/java", step)
        self.assertNotIn('grep -q', step)
        flavor_tests = [path for path in (APP_BUILD.parent / 'src/test/java').rglob('*.kt')
                        if 'BuildConfig.SELF_UPDATE_ENABLED' in path.read_text()]
        self.assertTrue(flavor_tests)

    def test_ready_pr_requires_secondary_variant(self):
        self.assertEqual(self.run_gate(self.successful_outcomes()).returncode, 0)
        for result in ('skipped', 'failure', 'cancelled'):
            outcomes = self.successful_outcomes()
            outcomes['secondary-variant']['result'] = result
            self.assertNotEqual(self.run_gate(outcomes).returncode, 0, result)

    def test_full_matrix_events_reject_the_pr_only_lane(self):
        for event in ('push', 'schedule', 'workflow_dispatch', 'merge_group'):
            with self.subTest(event=event):
                outcomes = self.successful_outcomes(event)
                self.assertEqual(self.run_gate(outcomes, event).returncode, 0)
                outcomes['secondary-variant']['result'] = 'success'
                self.assertNotEqual(self.run_gate(outcomes, event).returncode, 0)
                # A draft flag outside a PR event is never a reason to skip.
                draft = self.successful_outcomes(event)
                draft['compose-compiler']['result'] = 'skipped'
                self.assertNotEqual(self.run_gate(draft, event, 'true').returncode, 0)

    def test_draft_skips_are_accepted_only_for_draft_prs(self):
        draft = self.successful_outcomes(draft='true')
        self.assertEqual(self.run_gate(draft, draft='true').returncode, 0)
        self.assertNotEqual(self.run_gate(draft, draft='false').returncode, 0)
        for value in ('', 'unexpected'):
            self.assertNotEqual(self.run_gate(draft, draft=value).returncode, 0)
        for job in self.dependencies:
            for result in ('failure', 'cancelled'):
                with self.subTest(job=job, result=result):
                    bad = self.successful_outcomes(draft='true')
                    bad[job]['result'] = result
                    self.assertNotEqual(self.run_gate(bad, draft='true').returncode, 0)
        for job in ('changes', 'tooling-contracts', 'offline-zsp', 'build-contracts',
                    'static-analysis', 'tests'):
            bad = self.successful_outcomes(draft='true')
            bad[job]['result'] = 'skipped'
            self.assertNotEqual(self.run_gate(bad, draft='true').returncode, 0, job)

    def precheck_outcomes(self, draft='false'):
        outcomes = self.successful_outcomes(draft=draft)
        outcomes['compile-tests']['result'] = 'success'
        outcomes['changes']['outputs'].update(precheck='true', ci_mode='precheck', supplemental_campaigns='false')
        for job in ('tests', 'static-analysis', 'secondary-variant', 'compose-compiler'):
            outcomes[job]['result'] = 'skipped'
        return outcomes

    def test_queue_precheck_accepts_only_its_exact_skip_set(self):
        for draft in ('true', 'false'):
            outcomes = self.precheck_outcomes(draft)
            self.assertEqual(self.run_gate(outcomes, draft=draft, mode='merge-queue').returncode, 0)
            for job in self.dependencies:
                for invalid in ('failure', 'cancelled', 'skipped', 'success'):
                    if invalid == outcomes[job]['result']:
                        continue
                    changed = json.loads(json.dumps(outcomes))
                    changed[job]['result'] = invalid
                    self.assertNotEqual(self.run_gate(changed, draft=draft, mode='merge-queue').returncode, 0,
                                        (draft, job, invalid))

    def test_precheck_cannot_satisfy_integration_or_inactive_queue(self):
        for event in ('merge_group', 'push', 'schedule', 'workflow_dispatch', 'unknown'):
            self.assertNotEqual(self.run_gate(self.precheck_outcomes(), event, mode='merge-queue').returncode, 0)
        for mode in ('', 'unknown', 'legacy'):
            self.assertNotEqual(self.run_gate(self.precheck_outcomes(), mode=mode).returncode, 0)
        for value in ('', 'unknown', None):
            outcomes = self.successful_outcomes()
            outcomes['changes']['outputs']['precheck'] = value
            self.assertNotEqual(self.run_gate(outcomes).returncode, 0)
        outcomes = self.precheck_outcomes()
        outcomes['changes']['outputs']['supplemental_campaigns'] = 'true'
        self.assertNotEqual(self.run_gate(outcomes, mode='merge-queue').returncode, 0)

    def test_full_pr_matches_integration_and_does_not_accept_any_core_skip(self):
        for draft in ('true', 'false'):
            outcomes = self.successful_outcomes('merge_group')
            self.assertEqual(self.run_gate(outcomes, 'pull_request', draft=draft).returncode, 0)
            for job in self.dependencies:
                if job in {'secondary-variant', 'compile-tests'}:
                    continue
                bad = json.loads(json.dumps(outcomes))
                bad[job]['result'] = 'skipped'
                self.assertNotEqual(self.run_gate(bad, draft=draft).returncode, 0, job)
        self.assertIn('timeout-minutes: 30', self.named_step(self.tests_job, 'Unit tests'))
        self.assertIn('timeout-minutes: 40', self.tests_job)

    def test_full_core_allows_only_explicit_supplemental_deferral(self):
        for event in ('pull_request', 'merge_group', 'push'):
            outcomes = self.successful_outcomes('merge_group')
            outcomes['changes']['outputs']['supplemental_campaigns'] = 'false'
            outcomes['compose-compiler']['result'] = 'skipped'
            self.assertEqual(self.run_gate(outcomes, event).returncode, 0)
            for job in ('tests', 'static-analysis', 'build-contracts', 'offline-zsp'):
                bad = json.loads(json.dumps(outcomes))
                bad[job]['result'] = 'skipped'
                self.assertNotEqual(self.run_gate(bad, event).returncode, 0, (event, job))
            for result in ('failure', 'cancelled', 'success'):
                bad = json.loads(json.dumps(outcomes))
                bad['compile-tests']['result'] = result
                self.assertNotEqual(self.run_gate(bad, event).returncode, 0)

    def test_only_queue_concurrency_uses_distinct_noncancelling_attempts(self):
        for filename in ('android-ci.yml', 'android-release-runtime.yml',
                         'android-repro-verify.yml', 'fuzz-pr.yml'):
            workflow = WORKFLOW.with_name(filename).read_text()
            concurrency = workflow.split('\nconcurrency:\n', 1)[1].split('\n\n', 1)[0]
            self.assertIn("github.event_name == 'merge_group' && github.run_id || github.ref", concurrency)
            self.assertIn("cancel-in-progress: ${{ github.event_name != 'merge_group' }}", concurrency)

    def test_live_label_observation_fails_closed_and_is_read_only(self):
        step = self.named_step(self.changes, 'Read current full-CI override')
        script = textwrap.dedent(step.split('        run: |\n', 1)[1])
        self.assertIn('issues: read', self.changes)
        self.assertNotIn('issues: write', self.changes)
        self.assertIn('    types: [opened, synchronize, reopened, ready_for_review]\n', self.workflow)
        for body, status, expected in [('[[]]', '0', []),
                                       ('[[{"name":"ci:full"}]]', '0', ['ci:full']),
                                       ('not-json', '0', None), ('', '1', None)]:
            with tempfile.TemporaryDirectory() as directory:
                output = Path(directory) / 'output'
                result = subprocess.run(['bash', '-euo', 'pipefail', '-c',
                    'gh() { printf "%s" "$OBSERVED"; return "$API_STATUS"; }\n' + script],
                    env={**os.environ, 'GITHUB_OUTPUT': str(output), 'GH_REPO': 'example/repository',
                         'PR_NUMBER': '1', 'OBSERVED': body, 'API_STATUS': status}, capture_output=True, text=True)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(json.loads(output.read_text().split('=', 1)[1]), expected)

    def test_full_draft_has_no_remaining_step_level_draft_deferral(self):
        for job in (self.build_contracts, self.compose_compiler, self.static_analysis,
                    self.tests_job, self.compile_tests, self.secondary, self.job_block(self.workflow, 'offline-zsp')):
            for line in job.split('    steps:', 1)[1].splitlines():
                if 'if:' in line and 'github.event.pull_request.draft' in line:
                    self.assertIn("needs.changes.outputs.full_matrix == 'true'", line)

    def test_test_compilation_is_isolated_by_flavor_only_in_precheck(self):
        header = self.compile_tests.split('    steps:', 1)[0]
        self.assertIn('    needs: changes\n', header)
        self.assertIn("    if: needs.changes.outputs.docs_only != 'true' && needs.changes.outputs.precheck == 'true'\n", header)
        self.assertNotIn('draft', header)
        self.assertIn('        flavor: [Zapstore, Play]\n', header)
        self.assertIn('      fail-fast: false\n', header)
        self.assertIn('name: Compile test sources (${{ matrix.flavor }})', header)
        step = self.named_step(self.compile_tests, 'Compile main and test source sets')
        for source in ('', 'UnitTest', 'AndroidTest'):
            for language in ('Kotlin', 'JavaWithJavac'):
                self.assertIn(':app:compileDev${{ matrix.flavor }}Debug' + source + language, step)
        self.assertIn('--max-workers=1', step)
        self.assertIn('--no-daemon', step)
        self.assertNotIn('assemble', step)
        self.assertNotIn('Xmx', step)
        self.assertIn('timeout-minutes: 25', step)
        self.assertIn('timeout-minutes: 35', self.compile_tests)
        tooling = self.named_step(self.build_contracts, 'Compile (Kotlin)')
        self.assertIn(':app:compileDevZapstoreDebugAndroidTestKotlin', tooling)
        self.assertIn('if [[ "$CI_FULL_MATRIX" != true ]]; then', tooling)
        self.assertIn(':app:compileDevPlayDebugAndroidTestKotlin', self.secondary)
        suite = self.named_step(self.tests_job, 'Unit tests')
        self.assertIn('if [[ "$CI_FULL_MATRIX" == true ]]; then', suite)
        self.assertIn('compile_tasks+=(:app:compileDev${{ matrix.flavor }}DebugAndroidTestKotlin)', suite)
        self.assertIn('./gradlew "${compile_tasks[@]}" :app:testDev', suite)
        self.assertIn(':benchmark:compileDevZapstoreBenchmarkReleaseKotlin', tooling)
        self.assertIn(':benchmark:assembleDevZapstoreBenchmarkRelease', tooling)
        precheck = tooling.split('if [[ "$CI_PRECHECK" == true ]]; then', 1)[1].split('else', 1)[0]
        self.assertNotIn('assemble', precheck)
        self.assertIn("needs.changes.outputs.precheck == 'true'", self.build_contracts)
        for job in (self.static_analysis, self.tests_job, self.secondary, self.compose_compiler):
            self.assertIn("needs.changes.outputs.precheck != 'true'", job.split('    steps:', 1)[0])
        self.assertIn("cancel-in-progress: ${{ github.event_name != 'merge_group' }}", self.workflow)
        for name in ('Upload reports', 'Upload Gradle timing reports',
                     'Verify selected dependency security floors'):
            self.named_step(self.compile_tests, name)
        self.assertIn('android-ci-reports-compile-tests-${{ matrix.flavor }}', self.compile_tests)

    def test_instrumented_compile_tasks_have_one_owner_per_mode(self):
        """Execute real task selectors with a recording wrapper, never Android tooling."""
        for precheck, full in (('true', 'false'), ('false', 'false'), ('false', 'true')):
            owners = []
            for job, step_name, flavor in (
                (self.build_contracts, 'Compile (Kotlin)', 'Zapstore'),
                (self.tests_job, 'Unit tests', 'Zapstore'),
                (self.tests_job, 'Unit tests', 'Play'),
            ):
                if precheck == 'true' and job == self.tests_job:
                    continue
                step = self.named_step(job, step_name)
                script = textwrap.dedent(step.split('        run: |\n', 1)[1])
                script = script.replace('${{ matrix.flavor }}', flavor)
                with tempfile.TemporaryDirectory() as directory:
                    wrapper = Path(directory) / 'gradlew'
                    wrapper.write_text('#!/bin/bash\nprintf "%s\\n" "$@"\n')
                    wrapper.chmod(0o755)
                    diagnostics = Path(directory) / 'scripts/run_unit_tests_with_diagnostics.py'
                    diagnostics.parent.mkdir()
                    diagnostics.write_bytes(Path(__file__).with_name('run_unit_tests_with_diagnostics.py').read_bytes())
                    result = subprocess.run(['bash', '-euo', 'pipefail', '-c', script],
                        cwd=directory, capture_output=True, text=True,
                        env={**os.environ, 'CI_PRECHECK': precheck, 'CI_FULL_MATRIX': full, 'CI_BASELINE_PHASE': 'true',
                             'UNIT_TEST_DEADLINE_MONOTONIC': str(time.monotonic() + 5)})
                    self.assertEqual(result.returncode, 0, result.stderr)
                    owners.extend(argument for argument in result.stdout.splitlines()
                                  if argument.endswith('AndroidTestKotlin'))
            expected = ([] if precheck == 'true' else
                        [':app:compileDevZapstoreDebugAndroidTestKotlin'] if full == 'false' else
                        [':app:compileDevZapstoreDebugAndroidTestKotlin',
                         ':app:compileDevPlayDebugAndroidTestKotlin'])
            self.assertEqual(owners, expected, (precheck, full))

    def test_benchmark_release_is_packaged_by_one_phase_per_run(self):
        """R8 minifies the release build once: in baseline when it runs, otherwise in tooling."""
        baseline = self.named_step(self.build_contracts, 'Assemble app for Baseline Profile verification')
        self.assertIn(':benchmark:assembleDevZapstoreBenchmarkRelease', baseline)
        tooling = self.named_step(self.build_contracts, 'Compile (Kotlin)')
        script = textwrap.dedent(tooling.split('        run: |\n', 1)[1])
        for precheck, baseline_phase, packages in (('false', 'true', False), ('false', 'false', True),
                                                   ('true', 'false', False)):
            with tempfile.TemporaryDirectory() as directory:
                wrapper = Path(directory) / 'gradlew'
                wrapper.write_text('#!/bin/bash\nprintf "%s\\n" "$@"\n')
                wrapper.chmod(0o755)
                result = subprocess.run(['bash', '-euo', 'pipefail', '-c', script], cwd=directory,
                                        capture_output=True, text=True,
                                        env={**os.environ, 'CI_PRECHECK': precheck, 'CI_FULL_MATRIX': 'false',
                                             'CI_BASELINE_PHASE': baseline_phase})
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(':benchmark:assembleDevZapstoreBenchmarkRelease' in result.stdout.splitlines(),
                             packages, (precheck, baseline_phase))

    def test_missing_or_extra_dependency_evidence_blocks(self):
        for job in self.dependencies:
            outcomes = self.successful_outcomes()
            del outcomes[job]
            self.assertNotEqual(self.run_gate(outcomes).returncode, 0)
        outcomes = self.successful_outcomes()
        outcomes['unexpected'] = {'result': 'success', 'outputs': {}}
        self.assertNotEqual(self.run_gate(outcomes).returncode, 0)

    def test_empty_results_do_not_pass_vacuously(self):
        """Absent dependency evidence must never produce a green aggregate."""
        self.assertNotEqual(self.run_gate({}).returncode, 0)

    def test_robolectric_module_openings_apply_to_all_test_jvms(self):
        """JDK 17+ access is needed by unit, screenshot and custom replay tests."""
        test_tasks = self.app_build.split('tasks.withType<Test>().configureEach {', 1)[1]
        test_tasks = test_tasks.split('\ntasks.register<Test>', 1)[0]
        for module in (
            'java.base/java.lang',
            'java.base/java.util',
            'java.base/java.io',
            'java.base/java.net',
            'java.base/java.security',
            'java.base/java.text',
            'java.base/jdk.internal.access',
            'java.desktop/java.awt.font',
            'jdk.compiler/com.sun.tools.javac.api',
        ):
            with self.subTest(module=module):
                self.assertIn(f'"--add-opens={module}=ALL-UNNAMED"', test_tasks)
        self.assertIn('    jvmArgs(\n', test_tasks)

    def test_invariant_gate_registry_is_an_input_of_every_test_task(self):
        """A registry-only edit must re-run InvariantGateRegistryTest instead of reusing cached test results."""
        test_tasks = self.app_build.split('tasks.withType<Test>().configureEach {', 1)[1]
        test_tasks = test_tasks.split('\ntasks.register<Test>', 1)[0]
        match = re.search(
            r'\n    inputs\s*\.file\(rootProject\.file\("docs/invariant-gates\.md"\)\)'
            r'\s*\.withPropertyName\("invariantGateRegistry"\)'
            r'\s*\.withPathSensitivity\(PathSensitivity\.RELATIVE\)',
            test_tasks,
        )
        self.assertIsNotNone(match, 'declare the registry as a top-level input of the shared Test configuration')

    def test_instrumented_invariant_gates_are_an_input_of_every_test_task(self):
        """Every instrumented Kotlin path can be registered, so deletions must invalidate cached validation."""
        test_tasks = self.app_build.split('tasks.withType<Test>().configureEach {', 1)[1]
        test_tasks = test_tasks.split('\ntasks.register<Test>', 1)[0]
        match = re.search(
            r'\n    inputs\s*\.files\(rootProject\.fileTree\("app/src/androidTest"\) \{ include\("\*\*/\*\.kt"\) \}\)'
            r'\s*\.withPropertyName\("instrumentedInvariantGates"\)'
            r'\s*\.withPathSensitivity\(PathSensitivity\.RELATIVE\)',
            test_tasks,
        )
        self.assertIsNotNone(match, 'declare the instrumented gate tree as a top-level input of the shared Test configuration')

    def test_proposed_invariant_gate_checker_tests_run_in_android_ci(self):
        """The gate workflow tests only the base checker, so a changed checker must be tested on its own tree."""
        step = self.named_step(self.tooling_contracts, 'Test PR GitHub scripts')
        self.assertIn('.github/scripts/check-invariant-gate.test.js', step)
        self.assertNotIn('continue-on-error', step)

    def test_missing_result_is_rejected(self):
        """An unexpected dependency schema fails closed."""
        outcomes = self.successful_outcomes()
        outcomes[self.dependencies[0]] = {'outputs': {}}
        self.assertNotEqual(self.run_gate(outcomes).returncode, 0)


class RuntimeListenerCoverageTest(unittest.TestCase):
    """Reject missing coverage and prove pure cancellation tests are insufficient."""

    def report(self, counters, extra=''):
        classes = ''.join(
            f'<class name="{name}"><counter type="LINE" covered="{covered}" missed="{missed}"/>'
            f'{extra}</class>' for name, covered, missed in counters
        )
        return io.StringIO(f'<report><package name="state">{classes}</package></report>')

    def test_measured_production_coverage_passes_without_double_counting_methods(self):
        report = self.report([(OWNER, 15, 1), (OWNER + '$cleanup', 1, 0)],
                             '<method><counter type="LINE" covered="0" missed="999"/></method>')
        self.assertEqual(check_coverage(report), (16, 17))

    def test_pure_owner_tests_do_not_satisfy_resource_coverage(self):
        with self.assertRaisesRegex(ValueError, '10/17'):
            check_coverage(self.report([(OWNER, 10, 6), (OWNER + '$cleanup', 0, 1)]))

    def test_missing_empty_or_generated_only_owner_is_rejected(self):
        for counters in ([], [(OWNER, 0, 0)], [(OWNER + '$cleanup', 1, 0)],
                         [(OWNER + 'Unrelated', 100, 0)]):
            with self.subTest(counters=counters), self.assertRaises(ValueError):
                check_coverage(self.report(counters))

    def test_unrelated_classes_cannot_inflate_coverage(self):
        with self.assertRaises(ValueError):
            check_coverage(self.report([(OWNER, 9, 8), (OWNER + 'Unrelated', 1000, 0)]))

    def test_invalid_or_missing_class_counters_are_rejected(self):
        for covered in ('-1', '1.5', '', 'NaN'):
            with self.subTest(covered=covered), self.assertRaises(ValueError):
                check_coverage(self.report([(OWNER, covered, 1)]))
        for counter in ('', '<counter type="LINE" covered="16" missed="1"/>' * 2):
            with self.subTest(counter=counter), self.assertRaises(ValueError):
                check_coverage(io.StringIO(
                    f'<report><package><class name="{OWNER}">{counter}</class></package></report>'
                ))


if __name__ == '__main__':
    unittest.main()
