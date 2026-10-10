"""Trace report correctness on missing, overlapping and cancelled mention phases."""
import importlib.util
import sqlite3
import sys
import types
import unittest
from pathlib import Path
from unittest.mock import patch


class MentionTraceReportTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        # Reporting SQL does not require starting/downloading a Perfetto binary.
        stub = types.ModuleType("perfetto.trace_processor")
        stub.TraceProcessor = object
        stub.TraceProcessorConfig = object
        spec = importlib.util.spec_from_file_location("paging_report", Path(__file__).with_name("paging_trace_report.py"))
        cls.report = importlib.util.module_from_spec(spec)
        with patch.dict(sys.modules, {"perfetto": types.ModuleType("perfetto"), "perfetto.trace_processor": stub}):
            spec.loader.exec_module(cls.report)

    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.addCleanup(self.db.close)
        self.db.execute("create table process_slice(name text, ts integer, dur integer, process_name text, upid integer)")

    def query(self, sql):
        cursor = self.db.execute(sql)
        fields = [c[0] for c in cursor.description]
        return [types.SimpleNamespace(**dict(zip(fields, row))) for row in cursor]

    def test_absent_phases_are_unmeasured_not_zero_milliseconds(self):
        values = self.report.mention_phase_metrics(self.query, "ts>=100 and ts<=1000")
        self.assertIsNone(values["mention_animation_ms"])
        self.assertIsNone(values["mention_total_ms"])
        self.assertEqual(0, values["mention_correction_n"])

    def test_multiple_corrections_are_counted_but_outside_and_unfinished_slices_are_excluded(self):
        fixtures = [
            ("WhiteNoise.conversation.mention.correction", 200, 2_000_000),
            ("WhiteNoise.conversation.mention.correction", 400, 3_000_000),
            ("WhiteNoise.conversation.mention.correction", 50, 9_000_000),
            ("WhiteNoise.conversation.mention.correction", 600, -1),
            ("WhiteNoise.conversation.mention.animation", 300, 20_000_000),
            ("WhiteNoise.conversation.page.apply", 300, 999_000_000),
        ]
        self.db.executemany(
            "insert into process_slice values(?,?,?,?,1)",
            [(name, ts, dur, self.report.PKG) for name, ts, dur in fixtures],
        )
        values = self.report.mention_phase_metrics(self.query, "ts>=100 and ts<=1000")
        self.assertEqual(2, values["mention_correction_n"])
        self.assertEqual(5, values["mention_correction_ms"])
        self.assertEqual(20, values["mention_animation_ms"])
        self.assertIsNone(values["mention_availability_ms"])

    def test_other_process_async_slices_cannot_contaminate_receiver_measurements(self):
        self.db.executemany("insert into process_slice values(?,?,?,?,?)", [
            ("WhiteNoise.conversation.mention.correction", 200, 2_000_000, self.report.PKG, 1),
            ("WhiteNoise.conversation.mention.correction", 300, 900_000_000, "fixture.sender", 2),
        ])
        values = self.report.mention_phase_metrics(self.query, "ts>=100 and ts<=1000")
        self.assertEqual(1, values["mention_correction_n"])
        self.assertEqual(2, values["mention_correction_ms"])

    def test_tap_to_reached_landing_excludes_highlight_hold_and_rejects_process_replacement(self):
        self.db.executemany("insert into process_slice values(?,?,?,?,?)", [
            ("WhiteNoise.conversation.mention.total", 100, 1_900_000_000, self.report.PKG, 1),
            ("WhiteNoise.conversation.mention.landed", 400_000_100, 0, self.report.PKG, 1),
            ("WhiteNoise.conversation.mention.highlight", 400_000_100, 1_500_000_000, self.report.PKG, 1),
        ])
        values = self.report.mention_phase_metrics(self.query, "ts>=100 and ts<=2000000000")
        self.assertEqual(400, values["mention_tap_to_landing_ms"])
        self.assertEqual(1500, values["mention_highlight_ms"])
        self.db.execute("update process_slice set upid=2 where name='WhiteNoise.conversation.mention.landed'")
        replaced = self.report.mention_phase_metrics(self.query, "ts>=100 and ts<=2000000000")
        self.assertIsNone(replaced["mention_tap_to_landing_ms"])


if __name__ == "__main__":
    unittest.main()
