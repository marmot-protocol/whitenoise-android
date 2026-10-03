"""Runner contracts: only closed metrics are kept, JUnit outcome is independent, and the guard rails refuse a real device."""

import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

import media_lifecycle_runner as runner


class FakeLedger:
    """A ledger whose snapshot grows on each read, to exercise the bounded completion wait."""

    def __init__(self, rows):
        self.rows = rows
        self.reads = 0

    def snapshot(self):
        """Reveal one more event per read, like a client still writing."""
        self.reads += 1
        return self.rows[:self.reads]


class MediaLifecycleRunnerTest(unittest.TestCase):
    """Host-only contracts; Android behavior is qualified by the device run, not by these."""

    def test_only_closed_metrics_are_extracted(self):
        """Unrelated instrumentation text, including a lookalike prefix, never becomes evidence."""
        output = "\n".join([
            "INSTRUMENTATION_STATUS: other=1",
            "INSTRUMENTATION_STATUS: controlled_attachment_json=" + json.dumps({"phase": "a", "n": 1}),
            "garbage controlled_attachment_json={}",
            "INSTRUMENTATION_STATUS: controlled_attachment_json=" + json.dumps({"phase": "b"}),
        ])
        self.assertEqual([{"phase": "a", "n": 1}, {"phase": "b"}], runner.metrics_of(output))

    def test_junit_outcome_requires_one_passing_test_and_no_failure(self):
        """A passing count alongside a failure marker is still a failure."""
        self.assertTrue(runner.passed("OK (1 test)"))
        self.assertFalse(runner.passed("OK (1 test)\nFAILURES!!!"))
        self.assertFalse(runner.passed("FAILURES!!!\nTests run: 1, Failures: 1"))
        self.assertFalse(runner.passed(""))

    def test_completion_wait_returns_when_every_upload_and_request_is_terminal(self):
        """Wait for the independently committed upload and request outcomes, not a fixed delay."""
        rows = [
            {"seq": 1, "request": None, "kind": "upload", "value": 5},
            {"seq": 2, "request": 1, "kind": "upload_complete", "value": 0},
            {"seq": 3, "request": None, "kind": "get", "value": 0},
            {"seq": 4, "request": 3, "kind": "complete", "value": 0},
        ]
        events, finalized = runner.wait_for_completion(FakeLedger(rows), 0, uploads=1, gets=1, timeout=2)
        self.assertTrue(finalized)
        self.assertEqual(rows, events)

    def test_completion_wait_keeps_unfinished_evidence_on_timeout(self):
        """An unfinished request is reported as unfinalized with every attempt retained."""
        rows = [{"seq": 1, "request": None, "kind": "upload", "value": 5},
                {"seq": 2, "request": None, "kind": "get", "value": 0}]
        events, finalized = runner.wait_for_completion(FakeLedger(rows), 0, uploads=1, gets=1, timeout=0.05)
        self.assertFalse(finalized)
        self.assertEqual(rows, events[:len(rows)])

    def test_real_device_or_unknown_profile_is_refused_before_any_adb_call(self):
        """The fixture is for disposable emulators only; a physical serial never reaches adb."""
        with tempfile.TemporaryDirectory() as directory, mock.patch.object(runner, "adb_command") as adb:
            for serial, profile in (("46131FDAS003CG", "reference-api30-arm64"),
                                    ("emulator-5554", "pixel-api37-arm64")):
                with self.assertRaises(ValueError):
                    runner.run("adb", serial, Path(directory), Path(directory) / "out.json", profile)
            adb.assert_not_called()


if __name__ == "__main__":
    unittest.main()
