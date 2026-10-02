"""Execute the workflow's aggregate gate against GitHub dependency outcomes."""

import io
import json
import os
from pathlib import Path
import re
import subprocess
import textwrap
import unittest

from scripts.check_runtime_listener_coverage import OWNER, check_coverage


WORKFLOW = Path(__file__).resolve().parents[1] / '.github/workflows/android-ci.yml'
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
        cls.tests_job = cls.job_block(cls.workflow, 'tests')
        cls.gate = cls.job_block(cls.workflow, 'validate')
        cls.app_build = APP_BUILD.read_text()
        cls.script = textwrap.dedent(cls.gate.split('        run: |\n', 1)[1])
        match = re.search(r'^    needs: \[([^\]]+)\]$', cls.gate, re.MULTILINE)
        cls.dependencies = [job.strip() for job in match.group(1).split(',')]

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

    def run_gate(self, outcomes):
        """Run the production shell with synthetic, untrusted JSON input."""
        return subprocess.run(
            ['bash', '-c', self.script],
            env={**os.environ, 'JOB_RESULTS': json.dumps(outcomes), 'CI_EVENT': 'pull_request'},
            capture_output=True, text=True, check=False,
        )

    def successful_outcomes(self):
        """Model GitHub's needs object, including empty per-job outputs."""
        outcomes = {job: {'result': 'success', 'outputs': {}} for job in self.dependencies}
        outcomes['changes']['outputs'] = {'docs_only': 'false', 'supplemental_campaigns': 'true'}
        return outcomes

    def test_aggregate_covers_every_job_and_runs_after_failures(self):
        """Every producer must reach the aggregate even after a dependency fails."""
        jobs = set(re.findall(r'^  ([a-z][a-z-]+):$', self.workflow.split('\njobs:\n', 1)[1], re.MULTILINE))
        self.assertEqual(set(self.dependencies), jobs - {'validate'})
        self.assertIn('    if: always()\n', self.gate)
        self.assertIn('    name: Compile, test, ktlint, detekt, Android lint\n', self.gate)
        self.assertIn('JOB_RESULTS: ${{ toJSON(needs) }}', self.gate)
        self.assertNotIn('continue-on-error:', self.gate)

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
            ('tests', self.tests_job),
        ):
            for step_name in required_steps:
                with self.subTest(job=job_name, step=step_name):
                    self.named_step(job, step_name)

    def test_static_analysis_isolated_by_flavor_without_duplicating_singletons(self):
        """Both lint variants run concurrently while ktlint and detekt run once."""
        self.assertIn("name: Android lint (${{ matrix.flavor }})", self.static_analysis)
        self.assertIn('      fail-fast: false\n', self.static_analysis)
        self.assertIn('        flavor: [Zapstore, Play]\n', self.static_analysis)
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
            "if: ${{ !cancelled() && steps.unit_tests.outcome == 'success' }}",
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
        self.assertIn('        flavor: [Zapstore, Play]\n', self.tests_job)
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

    def test_job_caches(self):
        """Every workload retains its own task cache; forks remain read-only."""
        gradle_setup_steps = re.findall(
            r'(?ms)^      - name: Set up Gradle\n.*?(?=^      - |^  [a-z]|\Z)',
            self.workflow,
        )
        self.assertEqual(len(gradle_setup_steps), 4)
        for step in gradle_setup_steps:
            self.assertIn(
                "cache-read-only: ${{ github.event_name == 'pull_request' && "
                "github.event.pull_request.head.repo.full_name != github.repository }}",
                step,
            )
        self.assertNotIn('uses: actions/cache@', self.workflow)
        self.assertEqual(self.workflow.count('uses: actions/cache/restore@'), 4)
        self.assertEqual(self.workflow.count('uses: actions/cache/save@'), 1)
        save_step = self.named_step(self.tests_job, 'Save MarmotKit artifact')
        self.assertIn("matrix.flavor == 'Play'", save_step)
        self.assertIn("github.event_name != 'pull_request'", save_step)
        self.assertIn(
            'github.event.pull_request.head.repo.full_name == github.repository',
            save_step,
        )
        self.assertIn("steps.marmotkit-cache.outputs.cache-hit != 'true'", save_step)

    def test_build_phases(self):
        """Packaging runs independently, with phase-specific diagnostics."""
        self.assertIn('phase: [tooling, baseline]', self.build_contracts)
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
        self.assertIn("ORG_GRADLE_PROJECT_ciTestForks: '3'", self.tests_job)
        root_build = (WORKFLOW.parents[2] / 'build.gradle.kts').read_text()
        self.assertIn('providers.gradleProperty("ciTestForks").map(String::toInt).getOrElse(1)', root_build)
        self.assertIn('outputs.doNotCacheIf("CI test assertions must execute")', root_build)

    def test_lightweight_tooling_and_test_builds_reuse_the_gradle_daemon(self):
        """Measured lightweight steps reuse a JVM; heavier tooling stays isolated."""
        invocations = self.gradle_steps(self.tests_job)
        self.assertGreaterEqual(len(invocations), 3)
        tooling = [step for step in self.gradle_steps(self.build_contracts)
                   if "if: matrix.phase == 'tooling'" in step]
        self.assertEqual(len(tooling), 4)
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
            self.assertIn(f'{job}: success', result.stdout)

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
            for event in ('pull_request', 'schedule', 'workflow_dispatch', 'push'):
                for changes in ('success', 'failure', 'skipped', 'cancelled'):
                    for build in ('success', 'failure', 'cancelled', 'skipped'):
                        for lint in ('success', 'failure', 'cancelled', 'skipped'):
                            result = subprocess.run(['bash', '-c', script], env={**os.environ,
                                'CHANGES_RESULT': changes, 'CAMPAIGNS': campaign,
                                'CI_EVENT': event, 'BUILD_RESULT': build, 'LINT_RESULT': lint},
                                capture_output=True, check=False)
                            expected = changes == lint == 'success' and (
                                campaign == 'true' and build == 'success' or
                                campaign == 'false' and event == 'pull_request' and build == 'skipped')
                            self.assertEqual(result.returncode == 0, expected,
                                             (campaign, event, changes, build, lint))

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
        self.assertNotIn('    needs:', self.tooling_contracts.split('    steps:', 1)[0])
        for name in ('offline-zsp', 'build-contracts', 'compose-compiler',
                     'static-analysis', 'tests'):
            job = self.job_block(self.workflow, name)
            self.assertIn('    needs: changes\n', job)
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
                                     'CI_EVENT': 'push'}, capture_output=True, check=False)
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

    def test_campaigns_run_nightly_and_manual_with_draft_event_parity(self):
        for filename in ('android-ci.yml', 'android-repro-verify.yml'):
            workflow = WORKFLOW.with_name(filename).read_text()
            events = workflow.split('\non:\n', 1)[1].split('\nconcurrency:', 1)[0]
            self.assertIn('  schedule:\n', events)
            self.assertIn('  workflow_dispatch:', events)
            self.assertIn('${{ github.event_name }}-${{ github.ref }}', workflow)
            self.assertIn('  pull_request:\n    branches: [master]', events)
        # A readiness-only trigger or draft guard would create a second CI phase.
        for path in WORKFLOW.parent.glob('*.yml'):
            text = path.read_text()
            self.assertNotIn('ready_for_review', text, path.name)
            self.assertNotRegex(text, r'github\.event\.pull_request\.draft', path.name)

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
