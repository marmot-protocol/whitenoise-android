"""Synthetic producer-shaped captures test evidence admission, never Android support."""
import copy
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

if __package__:
    from . import summarize_media_av_capability as report
else:
    import summarize_media_av_capability as report


def capture():
    token = "a" * 32
    rows = [{"type": "header", "capture_schema": 3, "run_token": token, "api": 30,
             "app_uid": 10001, "fingerprint": "synthetic-api30", "package": "fixture.app",
             "production_route": False}]
    for request, (case, stage, media_format) in enumerate(report.CASES, 1):
        data = b"" if case == "empty_range" else (b"v" if media_format == "video/mp4" else b"a") * 64
        if case == "truncated_probe":
            data = data[:16]
        row = {"type": "case", "case": case, "stage": stage, "request": request,
               "format": media_format, "run_token": token, "input_bytes": len(data),
               "input_sha256": hashlib.sha256(data).hexdigest()}
        if case in {"empty_range", "overflow_range"}:
            row.update(outcome="parent_range_rejected", worker_started=False)
        else:
            uid, pid = 99000 + request, 4000 + request
            observation = {
                "request": request, "kernel_ready_uid": uid, "kernel_ready_pid": pid,
                "kernel_reply_uid": uid, "elapsed_ms": 5100, "operation_elapsed_ms": 5001,
                "retirement_elapsed_ms": 90, "isolated": True, "internet_granted": False,
                "runtime_initialized": False, "private_canary_denied": True,
                "private_canary_errno": 13, "input_write_errno": 9, "success": False,
                "pixels": False, "output_read": False, "worker_soft_timeout": False,
                "duration_ms": 0, "waveform_bars": 0, "death_observed_before_output_read": True,
                **{name: True for name in report.RECEIPTS},
            }
            phase = {"retriever_source": "retriever_construct", "extractor": "extractor_source",
                     "codec": "extractor_source"}.get(stage, stage)
            log = {"worker_reported_uid": uid, "worker_reported_pid": pid,
                   "ready_kernel_uid": uid, "ready_kernel_pid": pid, "phase": phase,
                   "outcome": "begin", "error_class": "none", "error_code": 0,
                   "worker_elapsed_ns": 1000000}
            terminal = dict(log, outcome="exception", error_class="IOException", worker_elapsed_ns=2000000)
            row.update(outcome="no_result", observation=observation, diagnostics_qualified=True,
                       worker_diagnostics=[log, terminal])
        rows.append(row)
    return rows


def encode(rows):
    return ("\n".join(json.dumps(row) for row in rows) + "\n").encode()


def succeed(row):
    row["outcome"] = "success"
    row["observation"]["success"] = True
    phase = {"retriever_source": "retriever_metadata", "extractor": "extractor_tracks",
             "codec": "codec_start"}.get(row["stage"], row["stage"])
    row["worker_diagnostics"][-1].update(outcome="success", error_class="none", phase=phase)


class CapabilitySummaryTests(unittest.TestCase):
    def reject(self, rows):
        with self.assertRaises(ValueError):
            report.summarize(encode(rows))

    def test_complete_negative_matrix_preserves_exceptions_without_claiming_support(self):
        result = report.summarize(encode(capture()))
        self.assertTrue(result["capture_complete"])
        self.assertTrue(result["characterization_complete"])
        self.assertFalse(result["all_parsers_supported"])
        self.assertFalse(result["production_route"])
        self.assertFalse(result["qualification"])
        self.assertEqual(["attributable_exception"] * 8 + ["parent_range_rejected"] * 2,
                         [item["status"] for item in result["matrix"]])

    def test_soft_refusal_is_complete_negative_characterization(self):
        rows = capture()
        row = rows[1]
        row.update(outcome="worker_soft_timeout_no_result", capability_status="unqualified_timeout",
                   production_route=False, soft_refusal_ms=5000)
        row["observation"]["worker_soft_timeout"] = True
        row["worker_diagnostics"][-1].update(phase="watchdog", outcome="timeout", error_class="none")
        result = report.summarize(encode(rows))
        self.assertTrue(result["characterization_complete"])
        self.assertEqual("unqualified_timeout", result["matrix"][0]["status"])
        self.assertFalse(result["all_parsers_supported"])
        row["observation"]["success"] = True
        self.reject(rows)

    def test_deadline_retains_capture_but_fails_characterization(self):
        rows = capture()
        row = rows[1]
        row["outcome"] = "reply_deadline_unqualified"
        row["observation"].update(timely_terminal_reply=False, terminal_reply_seen=False)
        for name in ("isolated", "internet_granted", "runtime_initialized", "private_canary_denied",
                     "private_canary_errno", "kernel_reply_uid"):
            del row["observation"][name]
        row["worker_diagnostics"] = row["worker_diagnostics"][:1]
        result = report.summarize(encode(rows))
        self.assertTrue(result["capture_complete"])
        self.assertFalse(result["characterization_complete"])
        self.assertFalse(result["all_parsers_supported"])
        row["observation"]["output_read"] = True
        self.reject(rows)

    def test_terminal_only_deadline_matches_the_instrumented_producer(self):
        """A pre-parser reply exception remains a captured unqualified deadline."""
        rows = capture()
        row = rows[1]
        row['outcome'] = 'reply_deadline_unqualified'
        row['observation'].update(timely_terminal_reply=False, terminal_reply_seen=False)
        for name in ('isolated', 'internet_granted', 'runtime_initialized', 'private_canary_denied',
                     'private_canary_errno', 'kernel_reply_uid'):
            del row['observation'][name]
        row['worker_diagnostics'] = [dict(row['worker_diagnostics'][-1], phase='reply')]
        result = report.summarize(encode(rows))
        self.assertTrue(result['capture_complete'])
        self.assertFalse(result['characterization_complete'])
        self.assertFalse(result['all_parsers_supported'])
        self.assertEqual('unqualified_timeout', result['matrix'][0]['status'])
        row['worker_diagnostics'] *= 2
        self.reject(rows)

    def test_healthy_operation_success_is_separate_from_production_qualification(self):
        rows = capture()
        for row in rows[1:8]:
            succeed(row)
        rows[7]["observation"]["waveform_bars"] = 64
        rows[2]["observation"]["duration_ms"] = 1000
        rows[3]["observation"].update(duration_ms=1000, pixels=True, output_read=True)
        result = report.summarize(encode(rows))
        self.assertTrue(result["all_parsers_supported"])
        self.assertFalse(result["qualification"])
        rows[7]["observation"]["waveform_bars"] = 63
        self.reject(rows)

    def test_successful_metadata_without_poster_does_not_claim_full_support(self):
        rows = capture()
        for row in rows[1:8]:
            succeed(row)
            row["observation"]["duration_ms"] = 1000
        rows[7]["observation"]["waveform_bars"] = 64
        result = report.summarize(encode(rows))
        self.assertFalse(result["all_parsers_supported"])
        self.assertEqual("success", result["matrix"][2]["status"])
        self.assertFalse(result["matrix"][2]["required_output_present"])

    def test_rejects_forged_early_phase_success_and_successful_truncated_input(self):
        for index, phase in ((2, "retriever_construct"), (4, "extractor_source"),
                             (6, "codec_create"), (7, "extractor_tracks"), (8, "probe")):
            with self.subTest(index=index):
                rows = capture()
                succeed(rows[index])
                rows[index]["worker_diagnostics"][-1]["phase"] = phase
                if index == 7:
                    rows[index]["observation"]["waveform_bars"] = 64
                self.reject(rows)

    def test_rejects_missing_duplicate_reordered_or_unknown_cases(self):
        for mutate in (lambda rows: rows.pop(), lambda rows: rows.append(rows[1]),
                       lambda rows: rows.__setitem__(2, rows[1]),
                       lambda rows: rows[1].update(case="other"),
                       lambda rows: rows[1].update(stage="extractor"),
                       lambda rows: rows[1].update(request=True)):
            rows = capture()
            mutate(rows)
            self.reject(rows)

    def test_rejects_legacy_header_and_mixed_runtime_or_corpus(self):
        changes = [(0, "capture_schema", 2), (0, "app_uid", True), (0, "api", 29),
                   (0, "production_route", True), (1, "run_token", "b" * 32),
                   (1, "input_sha256", "bad"), (2, "input_sha256", "b" * 64),
                   (8, "input_bytes", 15), (9, "input_sha256", "b" * 64),
                   (10, "input_bytes", -1)]
        for index, key, value in changes:
            with self.subTest(index=index, key=key):
                rows = capture()
                rows[index][key] = value
                self.reject(rows)

    def test_requires_every_lifetime_and_isolation_receipt(self):
        changes = [(name, False) for name in report.RECEIPTS]
        changes += [("isolated", False), ("internet_granted", True), ("runtime_initialized", True),
                    ("private_canary_denied", False), ("private_canary_errno", 0),
                    ("input_write_errno", 0), ("kernel_reply_uid", 1), ("kernel_ready_uid", 10001),
                    ("kernel_ready_pid", 0), ("elapsed_ms", -1), ("operation_elapsed_ms", 6000),
                    ("output_read", True), ("success", "false")]
        for key, value in changes:
            with self.subTest(key=key):
                rows = capture()
                rows[1]["observation"][key] = value
                self.reject(rows)

    def test_diagnostics_cannot_fabricate_identity_or_terminal(self):
        changes = [("worker_reported_uid", 99002), ("ready_kernel_pid", 9), ("phase", "unknown"),
                   ("outcome", "success"), ("error_class", "none"), ("worker_elapsed_ns", 0)]
        for key, value in changes:
            with self.subTest(key=key):
                rows = capture()
                rows[1]["worker_diagnostics"][-1][key] = value
                self.reject(rows)
        for logs in ([], [capture()[1]["worker_diagnostics"][-1]],
                     capture()[1]["worker_diagnostics"] * 2):
            rows = capture()
            rows[1]["worker_diagnostics"] = logs
            self.reject(rows)

    def test_parent_rejections_cannot_include_worker_output(self):
        rows = capture()
        rows[9]["observation"] = copy.deepcopy(rows[1]["observation"])
        self.reject(rows)

    def test_json_duplicates_invalid_input_and_byte_bound(self):
        raw = encode(capture())
        for invalid in (raw.replace(b'"api": 30', b'"api": 30, "api": 36'),
                        b'[]\n' * 11, b'\xff', b' ' * (report.MAX_BYTES + 1)):
            with self.assertRaises((ValueError, UnicodeError)):
                report.summarize(invalid)

    def test_cli_outputs_deterministic_json_and_rejects_partial_capture(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "capture.jsonl"
            path.write_bytes(encode(capture()))
            command = [sys.executable, str(Path(report.__file__)), str(path)]
            first = subprocess.run(command, capture_output=True, check=True)
            second = subprocess.run(command, capture_output=True, check=True)
            self.assertEqual(first.stdout, second.stdout)
            self.assertFalse(json.loads(first.stdout)["qualification"])
            path.write_bytes(encode(capture()[:-1]))
            failed = subprocess.run(command, capture_output=True)
            self.assertEqual(1, failed.returncode)
            self.assertEqual(b"", failed.stdout)
            self.assertIn(b"exactly ten cases", failed.stderr)


if __name__ == "__main__":
    unittest.main()
