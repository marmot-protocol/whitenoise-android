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
        self.db.execute("create table slice(name text, ts integer, dur integer)")

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
        self.db.executemany("insert into slice values(?,?,?)", [
            ("WhiteNoise.conversation.mention.correction", 200, 2_000_000),
            ("WhiteNoise.conversation.mention.correction", 400, 3_000_000),
            ("WhiteNoise.conversation.mention.correction", 50, 9_000_000),
            ("WhiteNoise.conversation.mention.correction", 600, -1),
            ("WhiteNoise.conversation.mention.animation", 300, 20_000_000),
            ("WhiteNoise.conversation.page.apply", 300, 999_000_000),
        ])
        values = self.report.mention_phase_metrics(self.query, "ts>=100 and ts<=1000")
        self.assertEqual(2, values["mention_correction_n"])
        self.assertEqual(5, values["mention_correction_ms"])
        self.assertEqual(20, values["mention_animation_ms"])
        self.assertIsNone(values["mention_availability_ms"])


if __name__ == "__main__":
    unittest.main()
