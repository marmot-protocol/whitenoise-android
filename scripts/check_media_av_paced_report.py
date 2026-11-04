#!/usr/bin/env python3
"""Check complete paced observation shape; no thresholds, RSS attribution or presentation PASS is inferred."""
import json
from pathlib import Path
import sys

EXPECTED = {'red-h264.mp4', 'red-vp9.webm', 'red-av1.webm', 'red-hevc.mp4',
            'tone-aac.m4a', 'tone-opus.ogg', 'tone-mp3.mp3', 'tone-pcm.wav',
            'original-video.mp4', 'original-wave.wav'}


def validate_controls(row, video_required, ready_by_activation):
    """Require one causal output window for every ordered resume/seek, never an inferred count from labels."""
    controls = row.get('controls', [])
    expected_order = [(kind, iteration) for iteration in range(10) for kind in ('resume', 'seek')]
    if [(op.get('kind'), op.get('iteration')) for op in controls] != expected_order:
        raise ValueError('missing or reordered control observations')
    last_completion = row['start_ns']
    previous_generation = None
    for op in controls:
        kind = op['kind']
        completion_field = 'audio_progress_observed_ns' if kind == 'resume' else 'matching_progress_observed_ns'
        fields = ('request_ns', completion_field, 'activation', 'seek', 'previous_activation', 'previous_seek')
        if any(not isinstance(op.get(field), int) or isinstance(op.get(field), bool) for field in fields):
            raise ValueError('control clocks/generations absent or malformed')
        request, completion = op['request_ns'], op[completion_field]
        activation, seek = op['activation'], op['seek']
        prior = op['previous_activation'], op['previous_seek']
        if not last_completion <= request < completion <= row['end_ns'] or completion - request > 15_000_000_000:
            raise ValueError('control window outside bounded observation interval')
        if min(activation, seek, *prior) < 0 or activation not in ready_by_activation or prior[0] not in ready_by_activation:
            raise ValueError('control generation lacks authenticated worker')
        if not any(sample[0] == prior[0] and sample[1] == prior[1] and sample[3] <= request
                   for sample in row.get('audio', [])):
            raise ValueError('control prior generation lacks pre-request audio sample')
        if previous_generation is not None and prior != previous_generation:
            raise ValueError('control generation chain mismatch')
        if kind == 'resume':
            if activation <= prior[0] or not request <= ready_by_activation[activation] <= completion:
                raise ValueError('resume lacks a fresh post-request authenticated activation')
        else:
            target = 1000 if op['iteration'] % 2 == 0 else 6500
            if not video_required:
                target = 100 if op['iteration'] % 2 == 0 else 600
            if activation != prior[0] or seek <= prior[1] or op.get('target_ms') != target:
                raise ValueError('seek target/applied generation mismatch')
        if not any(call.get('operation') == kind and request <= call.get('post_ns', -1) <=
                   call.get('entry_ns', -1) <= call.get('return_ns', -1) <= completion
                   for call in row['main_calls']):
            raise ValueError('control window lacks its completed Main command')
        matching = [sample for sample in row.get('audio', []) if sample[0] == activation and
                    sample[1] == seek and request <= sample[3] <= completion]
        progressed = False
        earlier = {}
        for sample in matching:
            lifetime, time_ns, head = sample[2], sample[3], sample[4]
            old = earlier.get(lifetime)
            if old and time_ns > old[0] and head > old[1]:
                progressed = True
            earlier[lifetime] = time_ns, head
        if not progressed:
            raise ValueError('control lacks post-request matching AudioTrack advancement')
        if video_required and not any(frame[0] == activation and frame[1] == seek and frame[2] >= 0 and
                frame[7] == 1 and request <= frame[5] <= frame[6] <= completion for frame in row['submissions']):
            raise ValueError('control lacks post-request matching timed video post')
        previous_generation = activation, seek
        last_completion = completion



def validate_preparation(row):
    """Bind absence of an optional probe to the actual attachment autoplay owner; raw authentication stays required."""
    schema = row.get('schema')
    fields = ('playback_owner', 'preparation_mode', 'request_order', 'optional_preparation_receipt_unchanged')
    if schema == 'media_av_paced_performance_v2' or any(field in row for field in fields):
        expected = {'schema': 'media_av_paced_performance_v2',
                    'playback_owner': 'IsolatedAttachmentPlayer',
                    'preparation_mode': 'attachment_autoplay_optional_probe_not_requested',
                    'request_order': 'play_then_prepare',
                    'preparation_identity_scope': 'not_requested_attachment_autoplay_raw_header',
                    'optional_preparation_receipt_unchanged': True}
        if (any(row.get(k) != value for k, value in expected.items()) or
                row.get('optional_preparation_receipt_unchanged') is not True):
            raise ValueError('invalid attachment autoplay preparation binding')
        if 'preparation_authenticated_uid' not in row or row['preparation_authenticated_uid'] is not None:
            raise ValueError('unrequested preparation cannot claim an authenticated UID')
    else:
        # Historical prepare-then-play rows retain their completed automatic-worker receipt requirement.
        if schema not in (None, 'media_av_paced_performance_v1'):
            raise ValueError('unknown paced observation schema')
        uid = row.get('preparation_authenticated_uid')
        if type(uid) is not int or uid <= 0 or uid == row['caller_uid']:
            raise ValueError('authenticated preparation worker UID absent')


def validate(records):
    """Reject missing phases, overflow, malformed clocks/generations and fabricated unsupported presentation."""
    if len(records) != 33 or records[-1].get('record_type') != 'completion':
        raise ValueError('expected 32 measurement rows and one completion')
    complete = records[-1]
    if (complete.get('measurement_rows'), complete.get('startup_observations'),
        complete.get('resume_observations'), complete.get('seek_observations'),
        complete.get('failed_rows')) != (32, 30, 20, 20, 0):
        raise ValueError('incomplete campaign counts')
    schemas = {row.get('schema') for row in records[:-1]}
    if 'media_av_paced_performance_v2' in schemas and schemas != {'media_av_paced_performance_v2'}:
        raise ValueError('mixed legacy and attachment autoplay campaign')
    keys = set()
    for row in records[:-1]:
        key = row.get('input'), row.get('operation'), row.get('iteration')
        if key in keys:
            raise ValueError('duplicate observation')
        keys.add(key)
        if row.get('run_token') != complete.get('run_token') or not row.get('run_token'):
            raise ValueError('campaign identity mismatch')
        if row.get('outcome') != 'observed' or row.get('observation_overflow') is not False:
            raise ValueError('failed or overflowing observation')
        if row.get('physical_presentation') != 'unsupported_SurfaceView_canvas_post_is_not_presentation':
            raise ValueError('physical presentation is not measured')
        if row.get('threshold_verdict') != 'pending_frozen_budget_or_valid_comparator':
            raise ValueError('unreviewed performance verdict')
        if not (row['start_ns'] <= row['prepare_play_request_ns'] <= row['prepare_play_return_ns'] <= row['end_ns']):
            raise ValueError('invalid campaign clock order')
        validate_preparation(row)
        if not row.get('workers'):
            raise ValueError('authenticated playback worker identity absent')
        activations = set()
        ready_by_activation = {}
        for activation, uid, pid, ready in row['workers']:
            if any(type(value) is not int for value in (activation, uid, pid, ready)):
                raise ValueError('malformed authenticated worker receipt')
            if uid <= 0 or pid <= 0 or uid == row['caller_uid'] or pid == row['caller_pid'] or not row['start_ns'] <= ready <= row['end_ns']:
                raise ValueError('invalid authenticated worker receipt')
            activations.add(activation)
            ready_by_activation[activation] = min(ready, ready_by_activation.get(activation, ready))
        for activation, seek, pts, before, after, start, end, posted in row['submissions']:
            if pts >= 0 and (activation not in activations or start < ready_by_activation[activation]):
                raise ValueError('submission without matching activation receipt')
            if seek < 0 or pts < -1 or not row['start_ns'] <= start <= end <= row['end_ns'] or posted not in (0, 1):
                raise ValueError('invalid submission observation')
        video_required = row['input'].startswith('red-') or row['input'] == 'original-video.mp4'
        audio_required = not row['input'].startswith('red-')
        if video_required and (not row.get('canvas_phases') or not any(
                frame[2] >= 0 and frame[7] == 1 for frame in row['submissions'])):
            raise ValueError('required timed video submission/canvas evidence absent')
        for frame in row['submissions']:
            if frame[7] == 1 and not any(frame[5] <= phase[0] <= phase[3] <= frame[6]
                                         for phase in row.get('canvas_phases', [])):
                raise ValueError('successful sink attempt missing actual canvas phases')
        previous_audio = {}
        advanced = False
        for activation, seek, lifetime, time_ns, hardware_frames, underruns in row.get('audio', []):
            if activation not in activations or seek < 0 or lifetime == 0 or hardware_frames < 0 or underruns < 0:
                raise ValueError('invalid audio generation/device observation')
            if not ready_by_activation[activation] <= time_ns <= row['end_ns']:
                raise ValueError('audio clock outside observation interval')
            key = activation, seek, lifetime
            previous = previous_audio.get(key)
            if previous:
                if time_ns < previous[0] or hardware_frames < previous[1] or underruns < previous[2]:
                    raise ValueError('nonmonotonic same-sink audio observation')
                advanced |= hardware_frames > previous[1] and time_ns > previous[0]
            previous_audio[key] = time_ns, hardware_frames, underruns
        if audio_required and not advanced:
            raise ValueError('required real AudioTrack progression evidence absent')
        for acquired_start, acquired, drawn, posted in row.get('canvas_phases', []):
            if not acquired_start <= acquired <= drawn <= posted:
                raise ValueError('invalid canvas phase clocks')
            if not any(frame[5] <= acquired_start <= posted <= frame[6] and frame[7] == 1
                       for frame in row['submissions']):
                raise ValueError('canvas phases lack enclosing successful sink attempt')
        if not row.get('main_calls') or any(call.get('timeout') for call in row['main_calls']):
            raise ValueError('missing or timed-out Main probes')
        for call in row['main_calls']:
            if call['operation'] == 'skipped_probe_periods':
                continue
            if not call['post_ns'] <= call['entry_ns'] <= call['getter_start_ns'] <= call['getters_end_ns'] <= call['return_ns']:
                raise ValueError('invalid Main probe clock order')
        if row['operation'] == 'controls':
            validate_controls(row, video_required, ready_by_activation)
        elif not any(call.get('state') == 4 for call in row['main_calls']):
            raise ValueError('startup playback did not reach EOS')
    expected = {(name, 'startup_eos', iteration) for name in EXPECTED for iteration in range(3)}
    expected |= {(name, 'controls', 0) for name in ('original-video.mp4', 'tone-pcm.wav')}
    if keys != expected:
        raise ValueError('changed/missing baseline rows')
    return {'measurement_rows': 32, 'verdict': 'complete_observations_only',
            'remaining': 'review thresholds; trace loss; caller/worker sampled RSS lifetime joins; physical presentation unsupported'}


def main(arguments):
    """Validate exactly one retained report, rejecting empty, oversized or malformed evidence."""
    if len(arguments) != 1:
        raise ValueError('one paced JSONL report required')
    path = Path(arguments[0])
    if not 0 < path.stat().st_size <= 16 * 1024 * 1024:
        raise ValueError('report size outside bound')
    result = validate([json.loads(line) for line in path.read_text().splitlines()])
    print(json.dumps(result, sort_keys=True))


if __name__ == '__main__':
    main(sys.argv[1:])
