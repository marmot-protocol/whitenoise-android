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

    def test_failure_categories_keep_the_stage_actionable_without_native_details(self):
        """Classify recognized headers while discarding sensitive messages and every raw stack frame."""
        for exception, expected in [("java.lang.OutOfMemoryError", "out-of-memory"),
                                    ("kotlinx.coroutines.TimeoutCancellationException", "timeout"),
                                    ("java.util.concurrent.TimeoutException", "timeout"),
                                    ("java.lang.AssertionError", "assertion"),
                                    ("dev.ipf.NativeException", "instrumentation-failure")]:
            with self.subTest(exception=exception):
                text = f"INSTRUMENTATION_STATUS: stack={exception}: private-identity https://secret.invalid\nFAILURES!!!"
                category = runner.failure_kind(text)
                self.assertEqual(category, expected)
                self.assertNotIn("private", category)
                self.assertNotIn("secret", category)
        self.assertIsNone(runner.failure_kind("OK (1 test)"))

    def test_lookalike_exception_text_is_never_treated_as_a_stack_header(self):
        """Failure messages and unstructured output cannot masquerade as a trusted exception category."""
        for text in ("java.lang.OutOfMemoryError", "INSTRUMENTATION_STATUS: message=java.lang.OutOfMemoryError",
                     "INSTRUMENTATION_STATUS: stack=java.lang.OutOfMemoryErrorSuffix: secret", ""):
            with self.subTest(text=text):
                self.assertEqual(runner.failure_kind(text), "instrumentation-failure")

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

    def test_a_disconnect_is_terminal_only_when_the_caller_names_it(self):
        """A cancelled attempt ends with a disconnect, which is never silently accepted as a completed request."""
        rows = [
            {"seq": 1, "request": None, "kind": "upload", "value": 5},
            {"seq": 2, "request": 1, "kind": "upload_complete", "value": 0},
            {"seq": 3, "request": None, "kind": "get", "value": 0},
            {"seq": 4, "request": 3, "kind": "disconnect", "value": 0},
        ]
        _, default = runner.wait_for_completion(FakeLedger(rows), 0, uploads=1, gets=1, timeout=0.05)
        self.assertFalse(default)
        _, named = runner.wait_for_completion(FakeLedger(rows), 0, uploads=1, gets=1, timeout=2,
                                              terminal_kinds=("complete", "disconnect"))
        self.assertTrue(named)

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
            for serial, profile in (("PHYSICAL000TEST", "reference-api30-arm64"),
                                    ("emulator-5554", "pixel-api37-arm64")):
                with self.assertRaises(ValueError):
                    runner.run("adb", serial, Path(directory), Path(directory) / "out.json", profile)
            adb.assert_not_called()

    def test_the_restart_read_preserves_the_runtime_and_the_tile_stage_targets_only_the_tile_test(self):
        """Stage two keeps the restored runtime for stage three, which runs the tile test and no other class."""
        with mock.patch.object(runner.subprocess, "run") as run:
            run.return_value.stdout = "OK (1 test)"
            runner.instrument("adb", "emulator-5554", (4001, 4002), "read", "3b6c9c1e-0000-4000-8000-000000000000",
                              preserve=True)
            runner.instrument("adb", "emulator-5554", (4001, 4002), "read", "3b6c9c1e-0000-4000-8000-000000000000",
                              target=runner.TILES)
        preserved, tiles = (call.args[0] for call in run.call_args_list)
        self.assertIn("fixtureMediaPreserve", preserved)
        self.assertEqual(runner.PROBE, preserved[preserved.index("class") + 1])
        self.assertNotIn("fixtureMediaPreserve", tiles)
        self.assertEqual(runner.TILES, tiles[tiles.index("class") + 1])
        self.assertFalse({"uninstall", "install", "clear"} & set(preserved + tiles))


if __name__ == "__main__":
    unittest.main()
