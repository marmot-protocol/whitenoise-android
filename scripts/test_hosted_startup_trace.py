"""Host-only CLI fixtures. Fake ADB/Gradle never build or contact a device."""
import json
import os
import re
from types import SimpleNamespace
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import types
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]

FAKE_ADB = '''#!/usr/bin/env python3
import json,os,pathlib,sys,time
a=sys.argv[3:];mode=os.environ.get('FAKE_MODE','ok')
with open('adb-calls.jsonl','a') as f:f.write(json.dumps(a)+'\\n')
if a[:2]==['shell','getprop']:
 print('29' if mode=='unsupported' else {'ro.build.version.sdk':os.environ.get('FAKE_API','30'),'ro.product.cpu.abi':'x86_64'}.get(a[2],'fixture-runtime'))
elif a[:2]==['shell','cat']:
 if a[2].endswith('available_events'):
  needed='sched:sched_switch sched:sched_waking sched:sched_process_fork sched:sched_process_exit task:task_newtask task:task_rename'
  if mode=='long-missing-event':needed=needed.replace('sched:sched_process_exit','')
  prefix='other:event\\n'*(100000 if mode=='events-over-bound' else 2000 if mode in ('long-events','long-missing-event') else 0)
  print(prefix+needed)
 else:print('fixture-boot')
elif a[:3]==['shell','atrace','--list_categories']:print('am - Activity\\ndalvik - VM\\nbinder_driver - Binder')
elif a[:2]==['shell','perfetto']:
 if '--query' in a and mode=='unavailable':sys.exit(3)
 if any(x.startswith('--detach=') for x in a):
  data=sys.stdin.buffer.read();pathlib.Path('config-stdin.bin').write_bytes(data)
  if a[a.index('-c')+1]!='-' or not a[a.index('-o')+1].startswith('/data/misc/perfetto-traces/wnstartup'):sys.exit(19)
  if mode=='start-fail':sys.exit(7)
 if '--stop' in a and mode=='flush-fail':sys.exit(8)
 if '--stop' in a and mode=='flush-timeout':time.sleep(15)
 if '--stop' in a and mode=='split-deadlines':time.sleep(7)
elif a[:2]==['shell','stat']:
 if mode=='stat-fail':sys.exit(8)
 print('invalid' if mode=='stat-invalid' else 0 if mode=='empty-trace' else 134217729 if mode=='over-cap' else 134217728 if mode=='oversize' else 4194304 if mode=='split-deadlines' else 9)
elif a and a[0]=='pull':
 if mode=='pull-fail':sys.exit(8)
 if mode=='split-deadlines':time.sleep(5)
 pathlib.Path(a[2]).write_bytes(b'T'*4194304 if mode=='split-deadlines' else b'LOSS_TEST' if mode=='loss' else b'SHORT' if mode=='size-mismatch' else b'FAKETRACE')
elif a[:2]==['shell','rm'] and mode=='cleanup-fail':sys.exit(9)
elif a[:2]==['shell','test'] and mode=='existing-output':sys.exit(1)
'''


class HostedStartupTraceTests(unittest.TestCase):
    def fixture(self, mode='ok', code=0):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        (root/'scripts').mkdir()
        for name in ('hosted-startup-trace.py','hosted-startup-trace.init.gradle'):
            shutil.copyfile(ROOT/'scripts'/name, root/'scripts'/name)
        (root/'scripts/check_instrumented_required_cases.py').write_text("import os,sys\nsys.exit(29 if os.environ.get('FAKE_MODE')=='missing-case' else 0)\n")
        (root/'scripts/check_media_av_paced_report.py').write_text("import os,sys\nsys.exit(31 if os.environ.get('FAKE_MODE')=='paced-invalid' else 0)\n")
        report=root/'app/build/outputs/connected_android_test_additional_output/fake/media-av-paced-performance.jsonl'
        report.parent.mkdir(parents=True);report.write_text('{}\n')
        (root/'bin').mkdir()
        adb = root/'bin/adb';adb.write_text(FAKE_ADB);adb.chmod(0o755)
        git=root/'bin/git';git.write_text('#!/bin/sh\nprintf \"%s\\n\" \"$GITHUB_SHA\"\n');git.chmod(0o755)
        gradle = root/'gradlew'
        gradle.write_text('#!/bin/sh\nprintf "%s\\n" "$@" > gradle-arguments.txt\npython3 scripts/hosted-startup-trace.py start || exit $?\nexit '+str(code)+'\n')
        gradle.chmod(0o755)
        env = {**os.environ,'PATH':str(root/'bin')+os.pathsep+os.environ['PATH'],'FAKE_MODE':mode,
               'GITHUB_EVENT_NAME':'workflow_dispatch','GITHUB_RUN_ID':'7','GITHUB_RUN_ATTEMPT':'1','GITHUB_JOB':'instrumented','GITHUB_SHA':'a'*40}
        return root, env

    def execute(self, mode='ok', code=0):
        root, env = self.fixture(mode,code)
        r = subprocess.run(['python3','scripts/hosted-startup-trace.py','run'],cwd=root,env=env,capture_output=True,timeout=35)
        receipt = json.loads((root/'app/build/reports/media-startup-trace/capture-receipt.json').read_text())
        calls = [json.loads(l) for l in (root/'adb-calls.jsonl').read_text().splitlines()]
        return root,r,receipt,calls

    def test_actual_entrypoint_positive_retains_raw_and_never_qualifies(self):
        root,r,receipt,calls=self.execute()
        self.assertEqual(r.returncode,0,r.stderr)
        self.assertFalse(receipt['capture_qualified'])
        self.assertTrue((root/'app/build/reports/media-startup-trace/startup.perfetto-trace').exists())
        self.assertEqual(sum('--stop' in c for c in calls),1)
        self.assertTrue(any(c[:1]==['pull'] for c in calls))

    def test_actual_entrypoint_uses_only_healthy_release_like_selector(self):
        root,r,receipt,_=self.execute()
        self.assertEqual(r.returncode,0,r.stderr)
        arguments=(root/'gradle-arguments.txt').read_text().splitlines()
        self.assertIn(':app:connectedDevZapstoreBenchmarkReleaseAndroidTest',arguments)
        self.assertNotIn(':app:connectedDevZapstoreDebugAndroidTest',arguments)
        healthy='dev.ipf.whitenoise.android.media.sandbox.MediaWorkerHealthyBaselineDeviceTest#characterizeHealthyDirectStillTransportWithFixedSyntheticCorpus'
        self.assertIn('-Pandroid.testInstrumentationRunnerArguments.class='+healthy,arguments)
        self.assertEqual((root/'app/build/reports/media-startup-trace/required-cases.txt').read_text(),healthy+'\n')
        self.assertFalse(receipt['capture_qualified'])

    def test_static_gradle_api_selects_variant_before_task_and_keeps_release_flags(self):
        init=(ROOT/'scripts/hosted-startup-trace.init.gradle').read_text()
        self.assertIn("finalizeDsl { android ->",init)
        self.assertIn("android.testBuildType = 'benchmarkRelease'",init)
        self.assertIn('benchmark.debuggable || !benchmark.minifyEnabled || !benchmark.shrinkResources',init)
        self.assertLess(init.index('finalizeDsl'),init.index('projectsEvaluated'))
        self.assertIn("benchmark.initWith(android.buildTypes.getByName('release'))",init)
        self.assertIn("benchmark.signingConfig = android.signingConfigs.getByName('debug')",init)
        self.assertIn("benchmark.buildConfigField('boolean', 'ENABLE_PERFORMANCE_TEST_SELECTORS', 'true')",init)
        self.assertLess(init.index('benchmark.initWith'),init.index('benchmark.signingConfig'))
        self.assertLess(init.index('benchmark.initWith'),init.index('benchmark.buildConfigField'))
        self.assertLess(init.index('benchmark.buildConfigField'),init.index('if (benchmark.debuggable'))

    def test_default_benchmark_keeps_existing_identity_without_release_initialization(self):
        build=(ROOT/'app/build.gradle.kts').read_text().split('create("benchmarkRelease") {',1)[1].split('\n        }',1)[0]
        self.assertNotIn('initWith',build)
        self.assertIn('signingConfigs.getByName("debug")',build)
        self.assertIn('buildConfigField("boolean", "ENABLE_PERFORMANCE_TEST_SELECTORS", "true")',build)

    def test_unsupported_runtime_stops_before_instrumentation_and_start(self):
        _,r,receipt,calls=self.execute('unsupported')
        self.assertEqual(r.returncode,2)
        self.assertIsNone(receipt['test_exit_code'])
        self.assertFalse(any(any(x.startswith('--detach=') for x in c) for c in calls))

    def test_unavailable_perfetto_has_no_fallback(self):
        _,r,receipt,calls=self.execute('unavailable')
        self.assertEqual(r.returncode,2)
        self.assertIn('Perfetto unavailable',receipt['setup_error'])
        self.assertFalse(any(any(x.startswith('--detach=') for x in c) for c in calls))

    def test_nonzero_instrumentation_retained_and_cleanup_attempted(self):
        root,r,receipt,calls=self.execute(code=23)
        self.assertEqual(r.returncode,23)
        self.assertEqual(receipt['test_exit_code'],23)
        self.assertTrue((root/'app/build/reports/media-startup-trace/instrumentation.json').exists())
        self.assertTrue(any(c[:2]==['shell','rm'] for c in calls))

    def test_loss_like_raw_never_becomes_qualified(self):
        _,r,receipt,_=self.execute('loss')
        self.assertEqual(r.returncode,0)
        self.assertFalse(receipt['capture_qualified'])
        self.assertIn('UNVERIFIED',receipt['loss_gate'])

    def test_at_cap_trace_is_not_pulled_or_qualified(self):
        _,r,receipt,calls=self.execute('oversize')
        self.assertEqual(r.returncode,2)
        self.assertTrue(any('oversized' in i for i in receipt['issues']))
        self.assertFalse(any(c[:1]==['pull'] for c in calls))
        self.assertEqual(receipt['cleanup'],'HELD')
        self.assertFalse(any(c[:2]==['shell','rm'] for c in calls))

    def test_failed_trace_retrieval_preserves_remote_evidence(self):
        for mode in ('stat-fail','stat-invalid','empty-trace','over-cap','pull-fail','size-mismatch'):
            with self.subTest(mode=mode):
                _,r,receipt,calls=self.execute(mode)
                self.assertEqual(r.returncode,2)
                self.assertEqual(receipt['cleanup'],'HELD')
                self.assertFalse(receipt['capture_qualified'])
                self.assertNotIn('trace_sha256',receipt)
                self.assertFalse(any(c[:2]==['shell','rm'] for c in calls))
                self.assertEqual(sum('--stop' in c for c in calls),1)

    def test_verified_local_trace_allows_only_owned_remote_cleanup(self):
        _,r,receipt,calls=self.execute()
        self.assertEqual(r.returncode,0,r.stderr)
        self.assertEqual(receipt['cleanup'],'CONFIRMED')
        self.assertEqual(len(receipt['trace_sha256']),64)
        self.assertEqual([c for c in calls if c[:2]==['shell','rm']],
                         [['shell','rm','-f',receipt['remote']]])
        self.assertFalse(receipt['capture_qualified'])

    def test_flush_failure_holds_owned_remote_files_no_cleanup_or_retry(self):
        _,r,receipt,calls=self.execute('flush-fail')
        self.assertEqual(r.returncode,2)
        self.assertEqual(receipt['cleanup'],'HELD')
        self.assertFalse(any(c[:2]==['shell','rm'] for c in calls))
        self.assertEqual(sum('--stop' in c for c in calls),1)

    def test_flush_timeout_is_finite_and_holds_evidence(self):
        _,r,receipt,calls=self.execute('flush-timeout')
        self.assertEqual(r.returncode,2)
        self.assertEqual(receipt['cleanup'],'HELD')
        self.assertFalse(any(c[:2]==['shell','rm'] for c in calls))

    def test_cleanup_failure_is_not_success(self):
        _,r,receipt,_=self.execute('cleanup-fail')
        self.assertEqual(r.returncode,2)
        self.assertIn('task-owned remote cleanup unverified',receipt['issues'])

    def test_overlap_refuses_existing_output_before_adb(self):
        root,env=self.fixture();(root/'app/build/reports/media-startup-trace').mkdir(parents=True)
        r=subprocess.run(['python3','scripts/hosted-startup-trace.py','run'],cwd=root,env=env,capture_output=True)
        self.assertNotEqual(r.returncode,0)
        self.assertFalse((root/'adb-calls.jsonl').exists())

    def test_missing_targeted_xml_case_is_not_a_passing_capture(self):
        _,r,receipt,_=self.execute('missing-case')
        self.assertEqual(r.returncode,29)
        self.assertEqual(receipt['test_exit_code'],29)

    def test_static_dispatch_and_focused_inventory_markers(self):
        current=(ROOT/'scripts/run-android-instrumented-tests.sh').read_text()
        self.assertIn('MediaContainmentAcceptance',current)
        self.assertIn('MediaAvCapabilityDeviceTest',current)
        self.assertIn('media_startup_trace="${8:-false}"',current)
        workflow=(ROOT/'.github/workflows/android-instrumented.yml').read_text()
        self.assertIn('media_startup_trace:\n',workflow)
        self.assertIn('app/build/reports/media-startup-trace/',workflow)
        ci=(ROOT/'.github/workflows/android-ci.yml').read_text()
        self.assertIn('scripts/test_hosted_startup_trace.py',ci)

    def test_static_workflow_expressions_reject_overlap_flags_and_retain_failure_apks(self):
        """Static workflow expressions reject overlap flags and retain failure apks."""
        workflow=(ROOT/'.github/workflows/android-instrumented.yml').read_text()
        marker='      - name: Reject incompatible startup trace inputs\n'
        condition=workflow.split(marker,1)[1].splitlines()[0].strip().removeprefix('if: ')
        defaults=dict(media_trace_target='healthy',range_selection_only=False,media_startup_trace=True,media_sandbox_only=True,media_sandbox_api='30',
                      maestro_pilot=False,responsiveness_only=False,outbound_share_only=False,
                      mdk_adoption_only=False,mdk_qualification=False,mdk_preview_qualification=False,
                      review_demo_e2e=False,document_provider_matrix=False)
        def evaluate(expression,event='workflow_dispatch',status='failure',**changes):
            """Evaluate the actual workflow expression with the supplied synthetic dispatch state."""
            values={**defaults,**changes}
            def normalized(text):
                """Normalize the selected workflow expression for comparison without replacing its admission conditions."""
                text=text.strip().removeprefix('${{').removesuffix('}}').replace('&&',' and ').replace('||',' or ')
                return re.sub(r'!(?!=)','not ',text).strip()
            context=dict(github=SimpleNamespace(event_name=event),
                inputs=SimpleNamespace(**{**values,'maestro_suite':'onboarding'}),job=SimpleNamespace(status=status),
                always=lambda:True,startsWith=lambda text,prefix:text.startswith(prefix),
                success=lambda:status=='success',failure=lambda:status=='failure',
                cancelled=lambda:status=='cancelled',format=lambda *a:'standard')
            validation=re.search(r'^          TRACE_MODE_VALID: (.+)$',workflow,re.M).group(1)
            allowed=eval(normalized(validation),{'__builtins__':{}},context)
            context['needs']=SimpleNamespace(changes=SimpleNamespace(outputs=SimpleNamespace(
                connected='true',attachment_fixture='true',media_trace_allowed='true' if allowed else '')))
            context['runtime_build_result']='success'
            return eval(normalized(expression).replace('needs.maestro-runtime-build.result','runtime_build_result'),{'__builtins__':{}},context)
        self.assertFalse(evaluate(condition))
        for flag in ('maestro_pilot','responsiveness_only','outbound_share_only','mdk_adoption_only','review_demo_e2e','document_provider_matrix'):
            self.assertTrue(evaluate(condition,**{flag:True}))
        self.assertTrue(evaluate(condition,media_sandbox_only=False))
        self.assertTrue(evaluate(condition,media_sandbox_api='36'))
        self.assertFalse(evaluate(condition,media_trace_target='av',media_sandbox_api='30'))
        self.assertFalse(evaluate(condition,media_trace_target='av',media_sandbox_api='36'))
        self.assertTrue(evaluate(condition,media_trace_target='av',media_sandbox_api='34'))
        self.assertTrue(evaluate(condition,event='push'))
        cancellation=re.search(r'^  cancel-in-progress: (.+)$',workflow,re.M).group(1)
        self.assertFalse(evaluate(cancellation))
        self.assertTrue(evaluate(cancellation,media_startup_trace=False))
        group=re.search(r'^  group: (.+)$',workflow,re.M).group(1)
        self.assertEqual(evaluate(group),'android-media-startup-trace')
        self.assertIn("!inputs.media_sandbox_only",workflow.split('  attachment-fixture:',1)[1].split('    name:',1)[0])
        revision=workflow.split('      - name: Record instrumentation checkout revision\n',1)[1]
        retention_condition=re.search(r'^        if: (.+)$',revision,re.M).group(1)
        self.assertTrue(evaluate(retention_condition))
        self.assertFalse(evaluate(retention_condition,status='cancelled'))
        for job in ('attachment-fixture','maestro-pilot','maestro-runtime-build','maestro-runtime','maestro-runtime-summary'):
            block=workflow.split('  '+job+':\n',1)[1]
            job_condition=re.search(r'^    if: (.+)$',block,re.M).group(1)
            self.assertFalse(evaluate(job_condition,media_sandbox_only=False,maestro_pilot=True))
        connected=workflow.split('  instrumented:\n',1)[1]
        self.assertTrue(evaluate(re.search(r'^    if: (.+)$',connected,re.M).group(1),media_sandbox_only=False,maestro_pilot=True))

    def test_full_kernel_event_list_after_preview_is_validated_by_actual_cli(self):
        root,r,receipt,calls=self.execute('long-events')
        self.assertEqual(r.returncode,0,r.stderr)
        report=root/'app/build/reports/media-startup-trace'
        metadata=json.loads((report/'available-events.json').read_text())
        self.assertGreater(metadata['stdout_bytes'],8192)
        self.assertNotIn('sched:sched_switch',metadata['stdout_text'])
        self.assertIn('sched:sched_switch',(report/'available-events.stdout').read_text())
        self.assertEqual(sum('--stop' in c for c in calls),1)
        self.assertFalse(receipt['capture_qualified'])

    def test_full_long_list_still_refuses_a_missing_required_event(self):
        _,r,receipt,calls=self.execute('long-missing-event')
        self.assertEqual(r.returncode,2)
        self.assertIn('required kernel event unavailable:sched:sched_process_exit',receipt['setup_error'])
        self.assertFalse(any(any(x.startswith('--detach=') for x in c) for c in calls))

    def test_over_bound_event_capture_refuses_instead_of_using_head_tail(self):
        _,r,receipt,calls=self.execute('events-over-bound')
        self.assertEqual(r.returncode,2)
        self.assertIn('required full capture exceeds bound:available-events',receipt['setup_error'])
        self.assertFalse(any(any(x.startswith('--detach=') for x in c) for c in calls))

    def test_static_init_hook_uses_injected_execoperations_and_explicit_spec(self):
        script=(ROOT/'scripts/hosted-startup-trace.init.gradle').read_text()
        wrapper=(ROOT/'gradle/wrapper/gradle-wrapper.properties').read_text()
        self.assertIn('gradle-9.8.2-bin.zip',wrapper)
        self.assertIn('import org.gradle.process.ExecOperations',script)
        self.assertRegex(script,r'@Inject\s+abstract ExecOperations getExecOperations\(\)')
        self.assertIn('selected.objects.newInstance(StartupTraceExecution)',script)
        self.assertIn('execution.execOperations.exec { spec ->',script)
        self.assertIn('spec.workingDir(workspace)',script)
        self.assertIn("selected.tasks.named('connectedDevZapstoreBenchmarkReleaseAndroidTest')",script)
        self.assertLess(script.index('doFirst {'),script.index('execution.execOperations.exec {'))
        self.assertIn("spec.commandLine 'python3', 'scripts/hosted-startup-trace.py', 'start'",script)
        self.assertNotRegex(script,r'\b(?:selected|project)\.exec\s*\{')
        # Static API-shape contract only; fake Gradle does not evaluate this script.

    def test_actual_cli_stdin_config_and_unique_owned_output_only(self):
        root,r,receipt,calls=self.execute()
        self.assertEqual(r.returncode,0,r.stderr)
        self.assertEqual((root/'config-stdin.bin').read_text(),(root/'app/build/reports/media-startup-trace/config.pbtxt').read_text())
        start=next(c for c in calls if any(v.startswith('--detach=') for v in c))
        self.assertEqual(start[start.index('-c')+1],'-')
        self.assertRegex(receipt['remote'],r'^/data/misc/perfetto-traces/wnstartup[0-9a-f]{32}\.perfetto-trace$')
        self.assertEqual(start[start.index('-o')+1],receipt['remote'])
        self.assertEqual([c for c in calls if c[:2]==['shell','rm']],[['shell','rm','-f',receipt['remote']]])
        self.assertFalse(any(c[0]=='push' or c[:2] in (['shell','mkdir'],['shell','rmdir']) for c in calls))
        self.assertEqual(receipt['cleanup'],'CONFIRMED')

    def test_actual_cli_start_failure_is_explicit_no_stop_or_cleanup_retry(self):
        root,r,receipt,calls=self.execute('start-fail')
        self.assertEqual(r.returncode,1)
        self.assertEqual(receipt['test_exit_code'],1)
        self.assertEqual(json.loads((root/'app/build/reports/media-startup-trace/start.json').read_text())['code'],7)
        self.assertIn('trace start failed',receipt['start_failure']['error'])
        self.assertEqual(receipt['cleanup'],'HELD')
        self.assertTrue((root/'app/build/reports/media-startup-trace/start.failed').exists())
        self.assertFalse(any('--stop' in c or c[:2]==['shell','rm'] for c in calls))
        self.assertIn('UNVERIFIED',receipt['loss_gate']);self.assertIn('UNVERIFIED',receipt['phase_mapping_gate'])

    def test_actual_cli_every_early_receipt_has_unverified_gates(self):
        for mode in ('unsupported','flush-fail','existing-output'):
            with self.subTest(mode=mode):
                _,r,receipt,calls=self.execute(mode)
                self.assertNotEqual(r.returncode,0)
                self.assertFalse(receipt['capture_qualified'])
                self.assertIn('UNVERIFIED',receipt['loss_gate']);self.assertIn('UNVERIFIED',receipt['phase_mapping_gate'])
                self.assertIn(receipt['cleanup'],('HELD','NOT_OWNED'))
                if mode=='existing-output':
                    self.assertEqual(receipt['cleanup'],'NOT_OWNED')
                    self.assertFalse(any('--stop' in c or c[:2]==['shell','rm'] for c in calls))

    def test_actual_cli_stop_pull_and_cleanup_use_separate_budgets(self):
        root,r,receipt,calls=self.execute('split-deadlines')
        self.assertEqual(r.returncode,0,r.stderr)
        self.assertEqual(receipt['cleanup'],'CONFIRMED')
        report=root/'app/build/reports/media-startup-trace'
        self.assertGreater(json.loads((report/'stop.json').read_text())['elapsed_seconds'],6)
        self.assertGreater(json.loads((report/'pull.json').read_text())['elapsed_seconds'],4)
        self.assertEqual(receipt['pull_timeout_seconds'],12)
        self.assertTrue(any(c[:2]==['shell','rm'] for c in calls))

    def test_actual_cli_unexpected_finish_error_retains_test_failure_and_receipt(self):
        root,env=self.fixture(code=23)
        gradle=root/'gradlew';text=gradle.read_text();text=text.replace('exit 23',"printf invalid > app/build/reports/media-startup-trace/start.failed\nexit 23");gradle.write_text(text)
        result=subprocess.run(['python3','scripts/hosted-startup-trace.py','run'],cwd=root,env=env,capture_output=True,timeout=35)
        receipt=json.loads((root/'app/build/reports/media-startup-trace/capture-receipt.json').read_text())
        self.assertEqual(result.returncode,23)
        self.assertEqual(receipt['test_exit_code'],23)
        self.assertEqual(receipt['finish_error']['error_class'],'JSONDecodeError')
        self.assertEqual(receipt['cleanup'],'HELD')
        self.assertIn('UNVERIFIED',receipt['loss_gate']);self.assertIn('UNVERIFIED',receipt['phase_mapping_gate'])

    def test_injected_unexpected_setup_error_has_complete_receipt(self):
        root,env=self.fixture()
        helper=types.ModuleType('trace_setup_fixture')
        path=ROOT/'scripts/hosted-startup-trace.py'
        exec(compile(path.read_bytes(),str(path),'exec'),helper.__dict__)
        helper.OUT=root/'app/build/reports/media-startup-trace';helper.STATE=helper.OUT/'state.json'
        with patch.dict(os.environ,env),patch.object(helper,'command',side_effect=ValueError('synthetic unexpected setup error')):
            self.assertEqual(helper.run(),2)
        receipt=json.loads((helper.OUT/'capture-receipt.json').read_text())
        self.assertEqual(receipt['setup_error_class'],'ValueError')
        self.assertEqual(receipt['cleanup'],'NOT_OWNED')
        self.assertIn('UNVERIFIED',receipt['loss_gate']);self.assertIn('UNVERIFIED',receipt['phase_mapping_gate'])

    def test_trace_dispatch_actual_shell_positive_and_invalid_modes(self):
        for args, expected in [
            (['workflow_dispatch','false','false','false','true','false','false','true','30'],0),
            (['pull_request','false','false','false','true','false','false','true','30'],2),
            (['workflow_dispatch','false','false','false','true','false','false','true','36'],2),
            (['workflow_dispatch','true','false','false','true','false','false','true','30'],2),
            (['workflow_dispatch','false','false','false','false','false','false','true','30'],2),
        ]:
            with self.subTest(args=args), tempfile.TemporaryDirectory() as d:
                root=Path(d);(root/'scripts').mkdir()
                (root/'scripts/hosted-startup-trace.py').write_text("print('TRACE_SELECTED')")
                r=subprocess.run(['bash',str(ROOT/'scripts/run-android-instrumented-tests.sh'),*args],cwd=root,capture_output=True,text=True)
                self.assertEqual(r.returncode,expected,r.stderr)
                self.assertEqual('TRACE_SELECTED' in r.stdout,expected==0)


    def test_opt_in_minified_trace_compiles_only_existing_healthy_fixture_closure(self):
        init = (ROOT/'scripts/hosted-startup-trace.init.gradle').read_text()
        block = init.split('def traceSources = [', 1)[1].split(']', 1)[0]
        selected = re.findall(r"'(dev/[^']+\.kt)'", block)
        expected = [
            'dev/ipf/whitenoise/android/ManualDeviceFixture.kt',
            'dev/ipf/whitenoise/android/media/sandbox/MediaWorkerHealthyBaselineDeviceTest.kt',
            'dev/ipf/whitenoise/android/media/sandbox/MediaWorkerBaseline.kt',
            'dev/ipf/whitenoise/android/media/sandbox/MediaWorkerBaselineBinding.kt',
        ]
        self.assertEqual(selected, expected)
        self.assertIn("def instrumented = android.sourceSets.getByName('androidTest')", init)
        self.assertIn('instrumented.java.setIncludes(traceSources)', init)
        self.assertIn('instrumented.kotlin.setIncludes(traceSources)', init)
        # All paths exist; selecting the CLI class does not select debug-only
        # instrumentation sources or put their services in the minified app.
        root = ROOT/'app/src/androidTest/java'
        for relative in selected:
            text = (root/relative).read_text()
            self.assertNotRegex(text, r'AvProbe|AV_READY|NotificationTiming|NotificationCatchUp')
        self.assertIn('MediaWorkerBaseline.runHealthy()', (root/expected[1]).read_text())
        self.assertIn('private const val CASES = 100', (root/expected[2]).read_text())
        self.assertIn('includeNegativeProbes = false', (root/expected[2]).read_text())
        self.assertIn('IsolatedMediaTransport(binding', (root/expected[2]).read_text())
        # Normal validation jobs do not load the opt-in trace source scope.
        # The existing benchmark dependency capture intentionally loads it.
        workflow = (ROOT/'.github/workflows/android-ci.yml').read_text()
        self.assertEqual(workflow.count('hosted-startup-trace.init.gradle'), 1)
        self.assertNotIn('hosted-startup-trace.init.gradle', workflow.split('  compile-tests:', 1)[1])
        self.assertIn('inputs.capture_benchmark_dependency_inputs', workflow)



    def test_top_level_av_route_survives_agp_comma_truncation_and_requires_all_methods(self):
        """Exercise dispatcher, actual capture and real checker beneath a source-evidenced AGP transport fixture."""
        directory = ROOT/'app/src/androidTest/java/dev/ipf/whitenoise/android/media/sandbox'
        discovered = []
        for source in directory.glob('*.kt'):
            for method in re.findall(r'@MediaAvTraceCase\s+fun\s+(\w+)\s*\(', source.read_text()):
                discovered.append('dev.ipf.whitenoise.android.media.sandbox.' + source.stem + '#' + method)
        import ast
        producer = ast.parse((ROOT/'scripts/hosted-startup-trace.py').read_text())
        constants = {node.targets[0].id: ast.literal_eval(node.value) for node in producer.body
                     if isinstance(node, ast.Assign) and isinstance(node.targets[0], ast.Name)
                     and node.targets[0].id in ('AV_CASE', 'AV_PLAYBACK_CASE', 'AV_SURFACE_CASE', 'AV_ANNOTATION')}
        required = [constants[key] for key in ('AV_CASE', 'AV_PLAYBACK_CASE', 'AV_SURFACE_CASE')]
        self.assertCountEqual(discovered, required)
        marker = (directory/'MediaAvTraceCase.kt').read_text()
        self.assertIn('@Target(AnnotationTarget.FUNCTION)', marker)
        self.assertIn('@Retention(AnnotationRetention.RUNTIME)', marker)
        # Existing production dispatcher documents this actual AGP boundary. The
        # fake Gradle below models it; this is not an AGP/device execution claim.
        dispatcher = (ROOT/'scripts/run-android-instrumented-tests.sh').read_text()
        self.assertIn('first comma', dispatcher)
        for api in ('30', '36'):
            for legacy in (True, False):
                with self.subTest(api=api, legacy_comma_selector=legacy):
                    root, env = self.fixture()
                    env['FAKE_API'] = api
                    shutil.copyfile(ROOT/'scripts/run-android-instrumented-tests.sh', root/'scripts/run-android-instrumented-tests.sh')
                    shutil.copyfile(ROOT/'scripts/check_instrumented_required_cases.py', root/'scripts/check_instrumented_required_cases.py')
                    (root/'annotated-methods.json').write_text(json.dumps(discovered))
                    if legacy:
                        script = root/'scripts/hosted-startup-trace.py'
                        source = script.read_text()
                        original = "av_selector = '-Pandroid.testInstrumentationRunnerArguments.annotation=' + AV_ANNOTATION"
                        replacement = "av_selector = " + repr('-Pandroid.testInstrumentationRunnerArguments.class=' + ','.join(required))
                        self.assertIn(original, source)
                        script.write_text(source.replace(original, replacement))
                    (root/'gradlew').write_text(r'''#!/usr/bin/env python3
import json,pathlib,subprocess,sys,xml.etree.ElementTree as ET
root=pathlib.Path('.')
args=sys.argv[1:]
(root/'gradle-arguments.txt').write_text('\n'.join(args)+'\n')
(root/'gradle-invocations.txt').open('a').write('one\n')
subprocess.run([sys.executable,'scripts/hosted-startup-trace.py','start'],check=True)
selected=[]
for arg in args:
 if arg.startswith('-Pandroid.testInstrumentationRunnerArguments.class='):
  selected=arg.split('=',1)[1].split(',')[:1]  # Known AGP comma-loss behavior.
 if arg=='-Pandroid.testInstrumentationRunnerArguments.annotation=dev.ipf.whitenoise.android.media.sandbox.MediaAvTraceCase':
  selected=json.loads((root/'annotated-methods.json').read_text())
suite=ET.Element('testsuite')
for case in selected:
 classname,method=case.split('#');ET.SubElement(suite,'testcase',classname=classname,name=method)
out=root/'app/build/outputs/androidTest-results/connected';out.mkdir(parents=True)
ET.ElementTree(suite).write(out/'results.xml')
''')
                    result = subprocess.run(
                        ['bash', 'scripts/run-android-instrumented-tests.sh', 'workflow_dispatch',
                         'false', 'false', 'false', 'true', 'false', 'false', 'true', api, 'false', 'av'],
                        cwd=root, env=env, capture_output=True, timeout=35,
                    )
                    report = root/'app/build/reports/media-startup-trace'
                    receipt = json.loads((report/'capture-receipt.json').read_text())
                    self.assertEqual((root/'gradle-invocations.txt').read_text(), 'one\n')
                    self.assertEqual((report/'required-cases.txt').read_text().splitlines(), required)
                    self.assertIn('duration_ms: 600000', (report/'config.pbtxt').read_text())
                    self.assertIn('timeout_msec=600000', (root/'gradle-arguments.txt').read_text())
                    self.assertEqual(receipt['cleanup'], 'CONFIRMED')
                    if legacy:
                        self.assertNotEqual(result.returncode, 0)
                        errors = (report/'required-case.stderr').read_text()
                        self.assertIn(required[1], errors)
                        self.assertIn(required[2], errors)
                        self.assertNotEqual(receipt['test_exit_code'], 0)
                        self.assertFalse((report/'paced-report-validation.json').exists())
                    else:
                        self.assertEqual(result.returncode, 0, result.stderr)
                        self.assertEqual(receipt['test_exit_code'], 0)
                        self.assertTrue((report/'paced-report-validation.json').exists())
                    self.assertFalse(receipt['capture_qualified'])

    def test_fixed_av_actual_cli_both_supported_apis(self):
        """Fixed av actual cli both supported apis."""
        for api in ('30', '36'):
            with self.subTest(api=api):
                root, env = self.fixture()
                env['FAKE_API'] = api
                result = subprocess.run(['python3', 'scripts/hosted-startup-trace.py', 'run-av', api], cwd=root, env=env, capture_output=True, timeout=35)
                self.assertEqual(result.returncode, 0, result.stderr)
                report = root/'app/build/reports/media-startup-trace'
                receipt = json.loads((report/'capture-receipt.json').read_text())
                self.assertEqual(receipt['trace_mode'], 'av')
                self.assertEqual(receipt['android_api'], api)
                self.assertFalse(receipt['capture_qualified'])
                self.assertIn('sampled RSS', receipt['memory_scope'])
                args = (root/'gradle-arguments.txt').read_text().splitlines()
                self.assertIn(':app:connectedDevZapstoreDebugAndroidTest', args)
                self.assertIn('-PmediaAvStartupTrace=true', args)
                self.assertIn('-Pandroid.testInstrumentationRunnerArguments.timeout_msec=600000', args)
                case='dev.ipf.whitenoise.android.media.sandbox.MediaAvPerformanceDeviceTest#measureProductionCostsOnUnchangedCorpus'
                paced='dev.ipf.whitenoise.android.media.sandbox.MediaAvPlaybackPerformanceDeviceTest#characterizePacedProductionPlaybackOnUnchangedCorpus'
                surface='dev.ipf.whitenoise.android.media.sandbox.IsolatedAttachmentPlayerDeviceTest#pausedSeekingSpeedPauseAndRepeatUseRealRawPlayback'
                self.assertIn('-Pandroid.testInstrumentationRunnerArguments.annotation='
                              'dev.ipf.whitenoise.android.media.sandbox.MediaAvTraceCase', args)
                self.assertFalse(any(arg.startswith('-Pandroid.testInstrumentationRunnerArguments.class=') for arg in args))
                self.assertEqual(receipt['required_case'],case)
                self.assertEqual(receipt['additional_required_cases'],[paced,surface])
                self.assertIn('paced_report_sha256',receipt)
                self.assertTrue((report/'paced-report-validation.json').exists())
                self.assertEqual((report/'required-cases.txt').read_text(),case+'\n'+paced+'\n'+surface+'\n')
                config=(root/'config-stdin.bin').read_text()
                self.assertEqual(config,(report/'config.pbtxt').read_text())
                self.assertIn('duration_ms: 600000',config)
                self.assertIn('proc_stats_poll_ms: 100',config)
                self.assertIn('max_file_size_bytes: 134217728',config)
                self.assertIn('sched/sched_process_exit',config)

    def test_av_requires_retained_valid_paced_report(self):
        """A successful JUnit selection cannot hide missing or malformed paced evidence."""
        for mode in ('paced-invalid', 'paced-missing'):
            root, env = self.fixture(mode)
            if mode == 'paced-missing':
                (root/'app/build/outputs/connected_android_test_additional_output/fake/media-av-paced-performance.jsonl').unlink()
            result = subprocess.run(['python3','scripts/hosted-startup-trace.py','run-av','30'],
                cwd=root,env=env,capture_output=True,timeout=35)
            self.assertNotEqual(result.returncode,0)
            receipt=json.loads((root/'app/build/reports/media-startup-trace/capture-receipt.json').read_text())
            self.assertFalse(receipt['capture_qualified'])

    def test_av_wrong_api_and_non_dispatch_never_start(self):
        """Av wrong api and non dispatch never start."""
        for args,event in [(['run-av','34'],'workflow_dispatch'),(['run-av','30'],'pull_request'),(['run-av','36'],'push')]:
            root,env=self.fixture();env['GITHUB_EVENT_NAME']=event
            result=subprocess.run(['python3','scripts/hosted-startup-trace.py',*args],cwd=root,env=env,capture_output=True,timeout=10)
            self.assertNotEqual(result.returncode,0)
            self.assertFalse((root/'adb-calls.jsonl').exists())

    def test_av_failures_preserve_holds_and_instrumentation_result(self):
        """Av failures preserve holds and instrumentation result."""
        for mode,code in [('start-fail',0),('flush-fail',0),('oversize',0),('missing-case',0),('ok',23)]:
            root,env=self.fixture(mode,code)
            result=subprocess.run(['python3','scripts/hosted-startup-trace.py','run-av','30'],cwd=root,env=env,capture_output=True,timeout=35)
            receipt=json.loads((root/'app/build/reports/media-startup-trace/capture-receipt.json').read_text())
            self.assertNotEqual(result.returncode,0)
            self.assertFalse(receipt['capture_qualified'])
            calls=[json.loads(x) for x in (root/'adb-calls.jsonl').read_text().splitlines()]
            if mode in ('start-fail','flush-fail','oversize'):
                self.assertEqual(receipt['cleanup'],'HELD')
                self.assertFalse(any(x[:2]==['shell','rm'] for x in calls))
            if code: self.assertEqual(result.returncode,code)

    def test_av_shell_selection_and_backward_compatible_default(self):
        """Av shell selection and backward compatible default."""
        for api,target,event,trace,expected in [('30','av','workflow_dispatch','true',0),('36','av','workflow_dispatch','true',0),('34','av','workflow_dispatch','true',2),('30','other','workflow_dispatch','true',2),('30','av','pull_request','true',2),('30','av','workflow_dispatch','false',2),('30','healthy','workflow_dispatch','true',0),('36','healthy','workflow_dispatch','true',2)]:
            with self.subTest(api=api,target=target,event=event,trace=trace),tempfile.TemporaryDirectory() as td:
                root=Path(td);(root/'scripts').mkdir();(root/'scripts/hosted-startup-trace.py').write_text('import sys;print(sys.argv[1:])')
                args=[event,'false','false','false','true','false','false',trace,api,'false',target]
                result=subprocess.run(['bash',str(ROOT/'scripts/run-android-instrumented-tests.sh'),*args],cwd=root,capture_output=True,text=True)
                self.assertEqual(result.returncode,expected,result.stderr)
                if expected==0:self.assertIn("['run-av', '"+api+"']" if target=='av' else "['run']",result.stdout)

    def test_av_init_branch_keeps_real_debug_closure_and_fixed_task_hook(self):
        """Av init branch keeps real debug closure and fixed task hook."""
        init=(ROOT/'scripts/hosted-startup-trace.init.gradle').read_text()
        av=init.split("} else {",1)[1]
        self.assertIn("selected.tasks.named('connectedDevZapstoreDebugAndroidTest')",av)
        self.assertIn("spec.commandLine 'python3', 'scripts/hosted-startup-trace.py', 'start'",av)
        self.assertNotIn('setIncludes',av)
        self.assertNotIn('initWith',av)
        self.assertIn("if (avTrace == 'false')",init)



    def test_workflow_av_selection_defaults_and_concurrency_are_explicit(self):
        """Workflow av selection defaults and concurrency are explicit."""
        import yaml
        workflow=(ROOT/'.github/workflows/android-instrumented.yml').read_text()
        parsed=yaml.safe_load(workflow)
        dispatch=(parsed.get('on') or parsed[True])['workflow_dispatch']['inputs']
        self.assertEqual(dispatch['media_trace_target']['default'],'healthy')
        self.assertEqual(dispatch['media_trace_target']['options'],['healthy','av'])
        self.assertIn("inputs.media_trace_target == 'av' && (!inputs.media_startup_trace || github.event_name != 'workflow_dispatch')",workflow)
        self.assertIn("inputs.media_trace_target == 'av' && inputs.media_sandbox_api != '30' && inputs.media_sandbox_api != '36'",workflow)
        self.assertIn("inputs.media_trace_target || 'healthy'",workflow)
        self.assertIn("RETAIN_STARTUP_TRACE: ${{ inputs.media_startup_trace && inputs.media_trace_target != 'av' }}",workflow)
        self.assertIn("inputs.media_startup_trace && 'android-media-startup-trace'",workflow)
        self.assertIn("cancel-in-progress: ${{ !(github.event_name == 'workflow_dispatch' && (inputs.maestro_pilot || inputs.media_startup_trace",workflow)
        # YAML and source contracts only; GitHub expression execution is not simulated.



    def test_all_job_admission_rejects_invalid_av_and_preserves_legacy(self):
        """All job admission rejects invalid av and preserves legacy."""
        import yaml
        current=yaml.safe_load((ROOT/'.github/workflows/android-instrumented.yml').read_text())['jobs']
        # Golden selectors from the pre-AV workflow; independent of the current selectors.
        before = {'changes': {'if': '${{ !github.event.pull_request.draft && !inputs.mdk_preview_qualification }}'},
         'attachment-fixture': {'if': '${{ !inputs.media_startup_trace && '
                                      "needs.changes.outputs.attachment_fixture == 'true' && "
                                      '!inputs.mdk_adoption_only && !inputs.range_selection_only && '
                                      '!inputs.outbound_share_only && !inputs.media_sandbox_only && '
                                      "(github.event_name != 'workflow_dispatch' || (!inputs.maestro_pilot "
                                      '&& !inputs.media_sandbox_only && !inputs.responsiveness_only)) && '
                                      '!inputs.mdk_qualification && !inputs.mdk_preview_qualification }}'},
         'instrumented': {'if': "needs.changes.outputs.connected == 'true' && (inputs.media_startup_trace "
                                "|| github.event_name != 'workflow_dispatch' || !inputs.maestro_pilot || "
                                'inputs.responsiveness_only || inputs.media_sandbox_only || '
                                'inputs.outbound_share_only || inputs.mdk_adoption_only || '
                                'inputs.range_selection_only) && !inputs.mdk_qualification && '
                                '!inputs.mdk_preview_qualification'},
         'maestro-pilot': {'if': '${{ !inputs.media_startup_trace && github.event_name == '
                                 "'workflow_dispatch' && inputs.maestro_pilot && !inputs.mdk_adoption_only "
                                 '&& !inputs.range_selection_only && !inputs.media_sandbox_only && '
                                 '!inputs.responsiveness_only && !inputs.outbound_share_only && '
                                 '!inputs.mdk_qualification && !inputs.mdk_preview_qualification && '
                                 "!startsWith(inputs.maestro_suite, 'runtime-') }}"},
         'maestro-runtime-build': {'if': '${{ !inputs.media_startup_trace && github.event_name == '
                                         "'workflow_dispatch' && inputs.maestro_pilot && "
                                         '!inputs.mdk_adoption_only && !inputs.range_selection_only && '
                                         '!inputs.media_sandbox_only && !inputs.responsiveness_only && '
                                         '!inputs.outbound_share_only && !inputs.mdk_qualification && '
                                         '!inputs.mdk_preview_qualification && '
                                         "startsWith(inputs.maestro_suite, 'runtime-') }}"},
         'maestro-runtime': {'if': '${{ !inputs.media_startup_trace }}'},
         'maestro-runtime-summary': {'if': '${{ !inputs.media_startup_trace && always() && '
                                           "github.event_name == 'workflow_dispatch' && "
                                           'inputs.maestro_pilot && !inputs.mdk_adoption_only && '
                                           '!inputs.range_selection_only && !inputs.media_sandbox_only && '
                                           '!inputs.responsiveness_only && !inputs.outbound_share_only && '
                                           '!inputs.mdk_qualification && !inputs.mdk_preview_qualification '
                                           "&& startsWith(inputs.maestro_suite, 'runtime-') }}"},
         'mdk-qualification': {'if': "github.event_name == 'workflow_dispatch' && inputs.mdk_qualification "
                                     '&& !inputs.mdk_preview_qualification'},
         'mdk-signed-preview': {'if': "github.event_name == 'workflow_dispatch' && "
                                      'inputs.mdk_preview_qualification'}}
        defaults=dict(media_trace_target='healthy',media_startup_trace=False,media_sandbox_only=False,
                      media_sandbox_api='30',range_selection_only=False,maestro_pilot=False,
                      responsiveness_only=False,outbound_share_only=False,mdk_adoption_only=False,
                      mdk_qualification=False,mdk_preview_qualification=False,review_demo_e2e=False,
                      document_provider_matrix=False,maestro_suite='onboarding')
        def evaluate(expr,event='workflow_dispatch',draft=False,changes_output=None,cancelled=False,runtime_build_result='success',**values):
            """Evaluate the actual workflow expression with the supplied synthetic dispatch state."""
            def normalized(expression):
                """Normalize the selected workflow expression for comparison without replacing its admission conditions."""
                expression=expression.strip().removeprefix('${{').removesuffix('}}').replace('&&',' and ').replace('||',' or ')
                return re.sub(r'!(?!=)','not ',expression).strip()
            context=dict(
                inputs=SimpleNamespace(**{**defaults,**values}),
                github=SimpleNamespace(event_name=event,event=SimpleNamespace(pull_request=SimpleNamespace(draft=draft))),
                always=lambda:True,cancelled=lambda:cancelled,runtime_build_result=runtime_build_result,
                startsWith=lambda text,prefix:text.startswith(prefix))
            if changes_output is None:
                allowed=eval(normalized(current['changes']['steps'][0]['env']['TRACE_MODE_VALID']),{'__builtins__':{}},context)
                changes_output='true' if allowed else ''
            context['needs']=SimpleNamespace(changes=SimpleNamespace(outputs=SimpleNamespace(
                connected='true',attachment_fixture='true',media_trace_allowed=changes_output)))
            return bool(eval(normalized(expr).replace('needs.maestro-runtime-build.result','runtime_build_result'),{'__builtins__':{}},context))
        flags=['maestro_pilot','responsiveness_only','outbound_share_only','mdk_adoption_only',
               'mdk_qualification','mdk_preview_qualification','range_selection_only','review_demo_e2e',
               'document_provider_matrix','media_sandbox_only','media_startup_trace']
        cases=[{}]+[{flag:True} for flag in flags]+[dict(maestro_pilot=True,maestro_suite='runtime-all')]
        for event in ['pull_request','push','schedule','workflow_dispatch','merge_group']:
            for draft in [False,True]:
                for target in ['healthy','']:
                    for values in cases:
                        for job in current:
                            with self.subTest(event=event,draft=draft,target=target,values=values,job=job):
                                self.assertEqual(evaluate(current[job]['if'],event,draft,media_trace_target=target,**values),
                                                 evaluate(before[job]['if'],event,draft,media_trace_target=target,**values))
        valid=dict(media_trace_target='av',media_startup_trace=True,media_sandbox_only=True)
        for api in ['30','36']:
            values={**valid,'media_sandbox_api':api}
            self.assertTrue(evaluate(current['instrumented']['if'],**values))
            for job in current:
                if job not in ('changes','instrumented'):self.assertFalse(evaluate(current[job]['if'],**values))
        invalid=[dict(media_trace_target='av'),dict(media_trace_target='other')]
        invalid += [{**valid,flag:True} for flag in flags if flag not in ('media_sandbox_only','media_startup_trace')]
        invalid += [{**valid,'media_startup_trace':False,'maestro_pilot':True},
                    {**valid,'media_startup_trace':False,'mdk_qualification':True},
                    {**valid,'media_startup_trace':False,'mdk_preview_qualification':True},
                    {**valid,'media_sandbox_only':False},{**valid,'media_sandbox_api':'34'}]
        for values in invalid:
            self.assertTrue(evaluate(current['changes']['if'],**values))
            for job in current:
                if job!='changes':self.assertFalse(evaluate(current[job]['if'],**values),(job,values))
        for event in ['pull_request','push','schedule','merge_group']:
            for job in current:
                if job!='changes':self.assertFalse(evaluate(current[job]['if'],event,**valid),(job,event))
        first=current['changes']['steps'][0]
        self.assertIn('Validate media trace selection',first['name'])
        self.assertNotIn('if',first)
        self.assertEqual(first['id'],'media-trace-selection')
        self.assertEqual(current['changes']['outputs']['media_trace_allowed'], '${{ steps.media-trace-selection.outputs.allowed }}')
        for values in invalid:
            self.assertFalse(evaluate(first['env']['TRACE_MODE_VALID'],**values))
        self.assertTrue(evaluate(first['env']['TRACE_MODE_VALID'],**valid))
        for target in ('healthy',''):
            self.assertTrue(evaluate(first['env']['TRACE_MODE_VALID'],media_trace_target=target))
        for result,expected in [('true',0),('false',1),('',1)]:
            with tempfile.TemporaryDirectory() as temporary:
                output=Path(temporary)/'output'
                run=subprocess.run(['bash','-c',first['run']],env={**os.environ,'TRACE_MODE_VALID':result,
                    'GITHUB_OUTPUT':str(output)},capture_output=True)
                self.assertEqual(run.returncode,expected)
                self.assertEqual(output.read_text() if output.exists() else '', 'allowed=true\n' if expected==0 else '')
        # Every consumer names changes directly; the independent signed preview never consumes its output.
        for job in current:
            if job in ('changes','mdk-signed-preview'):continue
            needs=current[job]['needs']
            self.assertIn('changes',[needs] if isinstance(needs,str) else needs)
            self.assertFalse(evaluate(current[job]['if'],changes_output=''))
        self.assertNotIn('needs',current['mdk-signed-preview'])
        self.assertNotIn('needs.changes',current['mdk-signed-preview']['if'])
        self.assertIn('always()',current['maestro-runtime-summary']['if'])
        runtime=dict(maestro_pilot=True,maestro_suite='runtime-all')
        self.assertTrue(evaluate(current['maestro-runtime-summary']['if'],changes_output='true',**runtime))
        self.assertFalse(evaluate(current['maestro-runtime-summary']['if'],changes_output='',**runtime))
        # Newly dependent campaigns bypass a later unrelated changes failure, honoring cancellation.
        for job in ('maestro-pilot','maestro-runtime-build','mdk-qualification'):
            self.assertIn('!cancelled()',current[job]['if'])
        self.assertTrue(evaluate(current['maestro-pilot']['if'],changes_output='true',maestro_pilot=True))
        self.assertFalse(evaluate(current['maestro-pilot']['if'],changes_output='true',cancelled=True,maestro_pilot=True))
        for target,expected in [('healthy',True),('',True),('av',False),('other',False)]:
            self.assertEqual(evaluate(current['mdk-signed-preview']['if'],changes_output='',
                mdk_preview_qualification=True,media_trace_target=target),expected)
        def admitted(job,results,**values):
            # GitHub adds success() when a job expression has no status check. Model that
            # dependency gate as well as evaluating the actual checked-in condition.
            """Return whether the actual workflow admission expression accepts this synthetic input combination."""
            definition=current[job]
            expression=definition['if']
            dependencies=definition.get('needs',[])
            if isinstance(dependencies,str):dependencies=[dependencies]
            explicit_status=re.search(r'\b(always|cancelled|success|failure)\(\)',expression)
            if not explicit_status and (values.get('cancelled',False) or any(
                    results.get(name,'success')!='success' for name in dependencies)):
                return False
            return evaluate(expression,runtime_build_result=results.get('maestro-runtime-build','success'),**values)
        for result in ('failure','cancelled'):
            results={'changes':result}
            # A successful first validation can survive a later unrelated changes failure.
            for job,values in [('maestro-pilot',dict(maestro_pilot=True)),
                               ('maestro-runtime-build',runtime),
                               ('mdk-qualification',dict(mdk_qualification=True))]:
                self.assertTrue(admitted(job,results,changes_output='true',**values),(job,result))
                self.assertFalse(admitted(job,results,changes_output='true',cancelled=True,**values))
                self.assertFalse(admitted(job,results,changes_output='',**values))
            # Existing changes-dependent jobs retain their implicit dependency success gate.
            self.assertFalse(admitted('instrumented',results,changes_output='true'))
            self.assertFalse(admitted('attachment-fixture',results,changes_output='true'))
            self.assertTrue(admitted('maestro-runtime',results,changes_output='true',**runtime))
            self.assertFalse(admitted('maestro-runtime',results,changes_output='true',cancelled=True,**runtime))
            for build_result in ('failure','skipped','cancelled'):
                self.assertFalse(admitted('maestro-runtime',{**results,'maestro-runtime-build':build_result},
                                          changes_output='true',**runtime))
            # always() must reconcile a failed/skipped runtime while validation remains admitted.
            runtime_results={**results,'maestro-runtime-build':result,'maestro-runtime':result}
            self.assertTrue(admitted('maestro-runtime-summary',runtime_results,
                                     changes_output='true',**runtime))
            self.assertFalse(admitted('maestro-runtime-summary',runtime_results,
                                      changes_output='',**runtime))
            self.assertTrue(admitted('mdk-signed-preview',results,changes_output='',
                                     mdk_preview_qualification=True))
        for output in ('','false','TRUE','1'):
            for job,values in [('maestro-pilot',dict(maestro_pilot=True)),
                               ('maestro-runtime-build',runtime),
                               ('mdk-qualification',dict(mdk_qualification=True))]:
                self.assertFalse(admitted(job,{'changes':'skipped'},changes_output=output,**values))
        self.assertTrue(admitted('maestro-runtime-summary',{'changes':'success','maestro-runtime':'cancelled'},
                                 changes_output='true',cancelled=True,**runtime))
        self.assertFalse(admitted('mdk-signed-preview',{},changes_output='',cancelled=True,
                                  mdk_preview_qualification=True))
        # Exactly one long AV allow expression remains, before any checkout.
        workflow=(ROOT/'.github/workflows/android-instrumented.yml').read_text()
        self.assertEqual(workflow.count("inputs.media_trace_target == 'av' && inputs.media_startup_trace && inputs.media_sandbox_only"),1)


if __name__ == '__main__':
    unittest.main()
