"""Malformed-evidence regressions for the paced report; fixtures never build or contact devices."""
import copy
import unittest
from scripts.check_media_av_paced_report import EXPECTED, validate


def control_evidence(row):
    """Populate synthetic causal windows so positive coverage exercises every control/output join."""
    row['end_ns'] = 500
    row['controls'] = []
    previous_activation, previous_seek = 0, 0
    for iteration in range(10):
        activation = iteration + 1
        for kind, request in [('resume', 20 + iteration * 20), ('seek', 26 + iteration * 20)]:
            seek = previous_seek + (kind == 'seek')
            completion_field = 'audio_progress_observed_ns' if kind == 'resume' else 'matching_progress_observed_ns'
            operation = dict(kind=kind, iteration=iteration, request_ns=request,
                activation=activation, seek=seek, previous_activation=previous_activation,
                previous_seek=previous_seek, **{completion_field: request + 4})
            if kind == 'resume':
                row['workers'].append([activation, 99010 + activation, 200 + activation, request + 1])
            else:
                early, late = (1000, 6500) if row['input'] == 'original-video.mp4' else (100, 600)
                operation['target_ms'] = early if iteration % 2 == 0 else late
            row['controls'].append(operation)
            lifetime = 100 + activation * 20 + seek
            row['audio'] += [[activation, seek, lifetime, request + 2, 0, 0],
                             [activation, seek, lifetime, request + 3, 128, 0]]
            row['submissions'].append([activation, seek, 0, 0, 0, request + 2, request + 3, 1])
            row['canvas_phases'].append([request + 2, request + 2, request + 3, request + 3])
            row['main_calls'].append(dict(operation=kind, post_ns=request, entry_ns=request + 1,
                getter_start_ns=request + 1, getters_end_ns=request + 1, return_ns=request + 2))
            previous_activation, previous_seek = activation, seek


def campaign():
    """Create a finite synthetic report shape, never device-performance evidence."""
    rows = []
    keys = [(name, 'startup_eos', iteration) for name in EXPECTED for iteration in range(3)]
    keys += [(name, 'controls', 0) for name in ('original-video.mp4', 'tone-pcm.wav')]
    for name, operation, iteration in keys:
        rows.append(dict(input=name, operation=operation, iteration=iteration, run_token='fixture',
            outcome='observed', observation_overflow=False,
            physical_presentation='unsupported_SurfaceView_canvas_post_is_not_presentation',
            threshold_verdict='pending_frozen_budget_or_valid_comparator', start_ns=1,
            prepare_play_request_ns=2, prepare_play_return_ns=3, end_ns=10,
            caller_uid=1000, caller_pid=100, preparation_authenticated_uid=99001, workers=[[0, 99000, 200, 4]],
            submissions=[[0, 0, 0, 0, 0, 5, 6, 1]], canvas_phases=[[5,5,6,6]],
            audio=[[0,0,7,5,0,0],[0,0,7,6,128,0]],
            main_calls=[dict(operation='eos', state=4, post_ns=4, entry_ns=5,
                             getter_start_ns=6, getters_end_ns=7, return_ns=8)],
            controls=[dict(kind=kind, iteration=i) for kind in ('resume', 'seek') for i in range(10)]
                     if operation == 'controls' else []))
        if operation == 'controls':
            control_evidence(rows[-1])
    rows.append(dict(record_type='completion', run_token='fixture', measurement_rows=32,
        startup_observations=30, resume_observations=20, seek_observations=20, failed_rows=0))
    return rows


def autoplay_campaign():
    """The actual attachment owner can omit an unrequested probe, while every raw identity/output remains required."""
    rows = campaign()
    for row in rows[:-1]:
        row.update(schema='media_av_paced_performance_v2', playback_owner='IsolatedAttachmentPlayer',
                   preparation_mode='attachment_autoplay_optional_probe_not_requested',
                   request_order='play_then_prepare', preparation_authenticated_uid=None,
                   preparation_identity_scope='not_requested_attachment_autoplay_raw_header',
                   optional_preparation_receipt_unchanged=True)
    return rows


class PacedReportTests(unittest.TestCase):
    """Reject incomplete evidence rather than silently converting absence into success."""
    def test_complete_shape_remains_unqualified(self):
        """A complete fake shape grants neither measured timing nor security/performance qualification."""
        self.assertEqual(validate(campaign())['verdict'], 'complete_observations_only')

    def test_actual_attachment_autoplay_shape_retains_raw_identity_and_output_requirements(self):
        self.assertEqual(validate(autoplay_campaign())['verdict'], 'complete_observations_only')
        changes = [lambda r: r.update(playback_owner='RawVoicePlaybackPlayer'),
                   lambda r: r.pop('playback_owner'),
                   lambda r: r.update(preparation_mode='optional'),
                   lambda r: r.update(request_order='prepare_then_play'),
                   lambda r: r.update(schema='media_av_paced_performance_v1'),
                   lambda r: r.update(optional_preparation_receipt_unchanged=False),
                   lambda r: r.update(optional_preparation_receipt_unchanged=1),
                   lambda r: r.update(preparation_authenticated_uid=99001),
                   lambda r: r.pop('preparation_authenticated_uid'),
                   lambda r: r.update(workers=[]),
                   lambda r: r['workers'][0].__setitem__(1, r['caller_uid']),
                   lambda r: r['workers'][0].__setitem__(2, r['caller_pid']),
                   lambda r: r['workers'][0].__setitem__(1, True),
                   lambda r: r['workers'][0].__setitem__(2, 200.0),
                   lambda r: r['workers'][0].__setitem__(3, 9),
                   lambda r: r.update(submissions=[]),
                   lambda r: r.update(audio=[])]
        for change in changes:
            rows = autoplay_campaign()
            row = next(r for r in rows if r.get('input') == 'original-video.mp4')
            change(row)
            with self.subTest(change=change), self.assertRaises(ValueError):
                validate(rows)

    def test_unknown_or_missing_preparation_mode_cannot_downgrade_authentication(self):
        rows = autoplay_campaign()
        rows[0].pop('preparation_mode')
        with self.assertRaises(ValueError):
            validate(rows)
        rows = campaign()
        rows[0]['preparation_authenticated_uid'] = None
        with self.assertRaises(ValueError):
            validate(rows)
        rows = autoplay_campaign()
        rows[0] = campaign()[0]
        with self.assertRaises(ValueError):
            validate(rows)
        rows = campaign()
        rows[0]['schema'] = 'future_schema'
        with self.assertRaises(ValueError):
            validate(rows)

    def test_missing_phase_overflow_generation_and_presentation_fail(self):
        """Each independently broken evidence boundary rejects the report."""
        changes = [lambda r: r.pop(0), lambda r: r[0].update(observation_overflow=True),
                   lambda r: r[0].update(controls=[] , operation='controls'),
                   lambda r: r[0]['submissions'][0].__setitem__(0, 99),
                   lambda r: r[0].update(physical_presentation='PASS'),
                   lambda r: r[0].update(workers=[]),
                   lambda r: r[0]['main_calls'][0].update(timeout=True),
                   lambda r: r[0].update(outcome='failed'),
                   lambda r: [row.update(submissions=[]) for row in r[:-1]],
                   lambda r: [row.update(canvas_phases=[]) for row in r[:-1]],
                   lambda r: [row.update(audio=[]) for row in r[:-1]],
                   lambda r: [row.update(audio=[[0,0,7,5,0,0],[0,0,7,6,0,0]]) for row in r[:-1]],
                   lambda r: r[0]['audio'][0].__setitem__(0, 99),
                   lambda r: r[0]['audio'][0].__setitem__(3, 100)]
        for change in changes:
            rows = copy.deepcopy(campaign())
            change(rows)
            with self.assertRaises(ValueError):
                validate(rows)

    def test_control_labels_without_causal_outputs_are_rejected(self):
        """A kind/iteration skeleton cannot claim any resume/seek observations."""
        rows = campaign()
        row = next(row for row in rows if row.get('operation') == 'controls')
        row['controls'] = [dict(kind=op['kind'], iteration=op['iteration']) for op in row['controls']]
        with self.assertRaises(ValueError):
            validate(rows)

    def test_control_clock_generation_and_output_windows_are_required(self):
        """Missing clocks, stale generations and pre-request/replayed output all fail independently."""
        changes = [lambda row: row['controls'][0].pop('request_ns'),
                   lambda row: row['controls'][0].update(audio_progress_observed_ns=1000),
                   lambda row: row['controls'][0].update(activation=0),
                   lambda row: row['controls'][1].update(previous_seek=99),
                   lambda row: row['controls'][1].update(target_ms=999),
                   lambda row: row.update(audio=[sample for sample in row['audio'] if sample[0] != 1]),
                   lambda row: row.update(submissions=[frame for frame in row['submissions'] if frame[0] != 1],
                                         canvas_phases=[phase for phase in row['canvas_phases'] if phase[0] < 20 or phase[0] > 30]),
                   lambda row: row['controls'][0].update(request_ns=24),
                   lambda row: row['controls'][0].update(previous_activation=77)]
        for change in changes:
            rows = campaign()
            row = next(row for row in rows if row.get('operation') == 'controls')
            change(row)
            with self.assertRaises(ValueError):
                validate(rows)
