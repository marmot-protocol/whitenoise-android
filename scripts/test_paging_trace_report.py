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
        self.assertEqual(100, values["mention_motion_start_ns"])
        self.assertEqual(400_000_100, values["mention_motion_end_ns"])
        self.assertTrue(values["mention_qualified"])
        self.assertEqual(1500, values["mention_highlight_ms"])
        self.db.execute("update process_slice set upid=2 where name='WhiteNoise.conversation.mention.landed'")
        replaced = self.report.mention_phase_metrics(self.query, "ts>=100 and ts<=2000000000")
        self.assertIsNone(replaced["mention_tap_to_landing_ms"])
        self.assertIsNone(replaced["mention_motion_start_ns"])
        self.assertIsNone(replaced["mention_motion_end_ns"])

    def test_collection_tail_and_other_process_frames_do_not_dilute_motion_metrics(self):
        self.db.executescript("""
            create table slice(name text, ts integer, dur integer, track_id integer, depth integer);
            create table thread_track(id integer, utid integer);
            create table thread(utid integer, tid integer, upid integer);
            create table process(upid integer, pid integer, name text);
            create table thread_state(ts integer, dur integer, utid integer, state text);
            create table counter(ts integer, track_id integer);
            create table process_counter_track(id integer, name text);
        """)
        self.db.executemany("insert into process values(?,?,?)", [(1, 10, self.report.PKG), (2, 20, self.report.PKG)])
        self.db.executemany("insert into thread values(?,?,?)", [(1, 10, 1), (2, 20, 2)])
        self.db.executemany("insert into thread_track values(?,?)", [(1, 1), (2, 2)])
        self.db.executemany("insert into slice values(?,?,?,?,?)", [
            ("benchmark:paging-jump-to-mention", 0, 1000, 1, 0),
            ("Choreographer#doFrame 1", 90, 40, 1, 0),
            ("Choreographer#doFrame 2", 200, 50, 1, 0),
            ("Choreographer#doFrame 3", 450, 90, 1, 0),
            ("Choreographer#doFrame 4", 200, 800, 2, 0),
            ("Choreographer#doFrame endsAtTap", 60, 40, 1, 0),
            ("Choreographer#doFrame startsAtLanding", 400, 100, 1, 0),
            ("Choreographer#doFrame zeroDuration", 200, 0, 1, 0),
        ])
        self.db.executemany("insert into process_slice values(?,?,?,?,?)", [
            ("WhiteNoise.conversation.mention.total", 100, 800, self.report.PKG, 1),
            ("WhiteNoise.conversation.mention.landed", 400, 0, self.report.PKG, 1),
        ])
        self.db.executemany("insert into thread_state values(?,?,?,?)", [
            (80, 60, 1, "Running"), (350, 100, 1, "Running"), (100, 500, 2, "Running"),
        ])
        processor = types.SimpleNamespace(
            query=lambda sql: [] if sql.startswith("INCLUDE") else self.query(sql),
            close=lambda: None,
        )
        with patch.object(self.report, "TraceProcessor", return_value=processor), patch.object(
            self.report, "TraceProcessorConfig", return_value=None,
        ):
            row = self.report.analyse("ConversationPagingBenchmark_jumpToUnreadMentionFromHistory_iter000_fixture.perfetto-trace")
        self.assertTrue(row["mention_qualified"])
        self.assertEqual(2, row["frames"])
        self.assertAlmostEqual(300 / 1e6, row["frame_window_ms"])
        self.assertAlmostEqual(50 / 1e6, row["f_max"])
        self.assertAlmostEqual(90 / 1e6, row["main_running_ms"])

    def test_visible_read_without_landing_is_unqualified(self):
        self.db.execute("insert into process_slice values(?,?,?,?,?)", (
            "WhiteNoise.conversation.mention.total", 100, 900, self.report.PKG, 1,
        ))
        phases = self.report.mention_phase_metrics(self.query, "ts>=100 and ts<=1000")
        row = dict(test="jumpToUnreadMentionFromHistory", journey_ms=1, **phases)
        self.assertFalse(self.report.qualified_row(row))

    def test_ambiguous_landings_are_unqualified(self):
        self.db.executemany("insert into process_slice values(?,?,?,?,?)", [
            ("WhiteNoise.conversation.mention.total", 100, 900, self.report.PKG, 1),
            ("WhiteNoise.conversation.mention.landed", 400, 0, self.report.PKG, 1),
            ("WhiteNoise.conversation.mention.landed", 500, 0, self.report.PKG, 1),
        ])
        phases = self.report.mention_phase_metrics(self.query, "ts>=100 and ts<=1000")
        self.assertFalse(phases["mention_qualified"])

    def test_unfinished_or_out_of_total_landing_is_unqualified(self):
        for total_duration, landing_ts in [(-1, 400), (200, 400), (900, 50)]:
            with self.subTest(total_duration=total_duration, landing_ts=landing_ts):
                self.db.execute("delete from process_slice")
                self.db.executemany("insert into process_slice values(?,?,?,?,?)", [
                    ("WhiteNoise.conversation.mention.total", 100, total_duration, self.report.PKG, 1),
                    ("WhiteNoise.conversation.mention.landed", landing_ts, 0, self.report.PKG, 1),
                ])
                phases = self.report.mention_phase_metrics(self.query, "ts>=0 and ts<=1000")
                self.assertFalse(phases["mention_qualified"])

    def test_unqualified_visits_fail_summary_and_do_not_contribute_medians(self):
        import contextlib
        import io
        import json
        import tempfile

        row = dict(test="jumpToUnreadMentionFromHistory", it=0, journey_ms=99,
                   mention_qualified=False, frames=90, f_p99=22.7)
        with tempfile.NamedTemporaryFile(mode="w", suffix=".jsonl") as data:
            data.write(json.dumps(row) + "\n")
            data.flush()
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                status = self.report.main(["--summarize", data.name])
        self.assertEqual(1, status)
        self.assertIn("UNQUALIFIED", output.getvalue())
        self.assertNotIn("frame P99 med=", output.getvalue())

    def test_single_trace_preserves_unqualified_json_but_returns_failure(self):
        import contextlib
        import io
        import json

        row = dict(test="jumpToUnreadMentionFromHistory", it=0, journey_ms=99,
                   mention_qualified=False)
        output = io.StringIO()
        with patch.object(self.report, "analyse", return_value=row), contextlib.redirect_stdout(output):
            status = self.report.main(["fixture.perfetto-trace"])
        self.assertEqual(1, status)
        self.assertEqual(row, json.loads(output.getvalue()))

    def test_mixed_summary_fails_and_excludes_unsuccessful_frame_distribution(self):
        import contextlib
        import io
        import json
        import tempfile

        good = dict(test="jumpToUnreadMentionFromHistory", it=0, journey_ms=99,
                    mention_qualified=True, windows=0, window_sum=0, window_max=0,
                    apply_n=0, apply_sum=0, apply_max=0, prepare_sum=0,
                    edge_stop=0, runway_kept=0, edge_reached=0, frames=5,
                    f_p50=1, f_p90=1, f_p99=1, f_max=1, jank32=0,
                    main_running_ms=1, gpu_samples=0)
        bad = dict(good, it=1, mention_qualified=False, f_p99=1000, f_max=1000)
        with tempfile.NamedTemporaryFile(mode="w", suffix=".jsonl") as data:
            data.write(json.dumps(good) + "\n" + json.dumps(bad) + "\n")
            data.flush()
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                status = self.report.main(["--summarize", data.name])
        self.assertEqual(1, status)
        self.assertIn("UNQUALIFIED", output.getvalue())
        self.assertIn("frame P99 med=1.0ms", output.getvalue())
        self.assertNotIn("1000", output.getvalue())


if __name__ == "__main__":
    unittest.main()
