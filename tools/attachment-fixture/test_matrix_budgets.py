"""Budget contracts: a ceiling rejects a regression, a missing measurement is never a pass, and targets only inform."""

from copy import deepcopy
import contextlib
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from matrix_budgets import MIB, evaluate, limit, load_budgets, main as budgets_main, table
from test_matrix_report import profile, raw


def stats(value):
    """A distribution that is exactly one value."""
    return {"n": 3, "min": value, "p50": value, "p95": None, "max": value, "mean": value}


def cell(size, **values):
    """One aggregated cell carrying only the named metrics."""
    return {"size": size, "samples": 3, **{k: stats(v) for k, v in values.items()}}


def aggregate_of(*cells, link="unshaped", restart=None):
    """A one-link aggregate with the given cells."""
    return {"profiles": [{"name": link, "cells": list(cells), "recreated_native_lease_ms": restart or {}}]}


class MatrixBudgetsTest(unittest.TestCase):
    """The shipped budget file and the evaluator that applies it."""

    def setUp(self):
        """The real budget definitions, so the contract tests cover what CI will apply."""
        self.budgets = load_budgets()

    def test_the_shipped_budgets_are_well_formed_and_cover_every_phase_the_issue_names(self):
        """Preparation, first progress, completion, verification, materialization and memory all have a ceiling."""
        ids = {b["id"] for b in self.budgets}
        for needed in ("preparation", "first-visible-progress", "transfer-completion-overhead", "verification",
                       "post-ready-materialization", "cold-java-peak", "cold-native-peak"):
            self.assertIn(needed, ids)
        for budget in self.budgets:
            self.assertGreaterEqual(budget["ceiling"]["fixed"], 0)
            target = budget.get("target")
            if target:
                self.assertLessEqual(limit(target, 30 * MIB), limit(budget["ceiling"], 30 * MIB), budget["id"])

    def test_a_cell_within_every_ceiling_passes_and_a_regression_fails(self):
        """The same cell passes at its measured value and fails once a budgeted metric is tripled."""
        good = cell(65536, prep_visible_ms=2.0, first_progress_ms=30.0, post_ready_ms=6.0, warm_lease_ms=3.0)
        self.assertTrue(evaluate(aggregate_of(good), self.budgets)["passed"] is False)  # unmeasured budgets are missing
        only = [b for b in self.budgets if b["id"] in ("preparation", "first-visible-progress", "post-ready-materialization")]
        self.assertTrue(evaluate(aggregate_of(good), only)["passed"])
        slow = deepcopy(good)
        slow["post_ready_ms"] = stats(240.0)
        verdict = evaluate(aggregate_of(slow), only)
        self.assertFalse(verdict["passed"])
        self.assertEqual(["post-ready-materialization"], [v["budget"] for v in verdict["violations"]])

    def test_a_missing_measurement_is_a_violation_never_a_pass(self):
        """A budget with no value for a cell cannot pass by omission."""
        verdict = evaluate(aggregate_of(cell(65536, prep_visible_ms=1.0)), self.budgets)
        self.assertFalse(verdict["passed"])
        self.assertTrue(any(v["observed"] is None for v in verdict["violations"]))

    def test_the_ceiling_grows_with_the_payload(self):
        """The same materialization time passes for a large file and fails for a small one."""
        budget = next(b for b in self.budgets if b["id"] == "post-ready-materialization")
        self.assertTrue(evaluate(aggregate_of(cell(30 * MIB, post_ready_ms=548.0)), [budget])["passed"])
        self.assertFalse(evaluate(aggregate_of(cell(65536, post_ready_ms=548.0)), [budget])["passed"])

    def test_memory_is_compared_in_mib_and_restart_reads_come_from_the_link_level_table(self):
        """Byte peaks convert to MiB, and the restart lease is read per size from the profile."""
        memory = next(b for b in self.budgets if b["id"] == "cold-java-peak")
        self.assertTrue(evaluate(aggregate_of(cell(30 * MIB, cold_java_peak_bytes=89 * MIB)), [memory])["passed"])
        self.assertFalse(evaluate(aggregate_of(cell(30 * MIB, cold_java_peak_bytes=130 * MIB)), [memory])["passed"])
        restart = next(b for b in self.budgets if b["id"] == "restart-lease")
        self.assertTrue(evaluate(aggregate_of(cell(MIB), restart={MIB: stats(9.0)}), [restart])["passed"])
        self.assertFalse(evaluate(aggregate_of(cell(MIB), restart={MIB: stats(900.0)}), [restart])["passed"])

    def test_a_link_filter_skips_other_links_and_an_unmet_target_is_reported_not_failed(self):
        """Engine upload is budgeted on the unshaped link only, and a missed target never fails the check."""
        upload = next(b for b in self.budgets if b["id"] == "engine-upload")
        shaped = aggregate_of(cell(MIB, mdk_upload_ms=900.0), link="constrained")
        skipped = evaluate(shaped, [upload])
        self.assertEqual([], [r for r in skipped["rows"] if r["link"] == "constrained"])
        self.assertEqual([("engine-upload", "unshaped")], [(r["budget"], r["link"]) for r in skipped["rows"]],
                         "the link the budget names was never measured")
        materialize = next(b for b in self.budgets if b["id"] == "post-ready-materialization")
        result = evaluate(aggregate_of(cell(30 * MIB, post_ready_ms=548.0)), [materialize])
        self.assertTrue(result["passed"])
        self.assertFalse(result["rows"][0]["met_target"])
        self.assertIn("target not met", table(result))

    def test_a_budget_that_names_a_link_fails_when_that_link_was_not_measured(self):
        """Omitting the profile a budget is defined on cannot make the budget pass, and the table says so."""
        upload = next(b for b in self.budgets if b["id"] == "engine-upload")
        result = evaluate(aggregate_of(cell(MIB, mdk_upload_ms=1.0), link="constrained"), [upload])
        self.assertFalse(result["passed"])
        self.assertIn("link not measured", table(result))
        measured = evaluate(aggregate_of(cell(MIB, mdk_upload_ms=1.0)), [upload])
        self.assertTrue(measured["passed"])

    def run_budgets(self, *reports):
        """Write each report dict to a file, run the budget command and return its exit code and printed output."""
        with tempfile.TemporaryDirectory() as folder:
            paths = []
            for index, report in enumerate(reports):
                path = Path(folder) / f"report-{index}.json"
                path.write_text(json.dumps(report))
                paths.append(str(path))
            with mock.patch.object(sys, "argv", ["matrix_budgets.py", *paths]), \
                    contextlib.redirect_stdout(io.StringIO()) as out:
                with self.assertRaises(SystemExit) as raised:
                    budgets_main()
        return raised.exception.code, out.getvalue()

    def test_the_budget_command_refuses_reports_it_cannot_trust_before_evaluating_any_ceiling(self):
        """Timings that meet a ceiling prove nothing from a failed run or one with the wrong requests."""
        clean = raw(profile([(65536, 3)]))
        good = {"qualified": True, "raw": clean}
        self.assertNotEqual(2, self.run_budgets(good)[0], "a qualified, correct report is evaluated")
        code, printed = self.run_budgets({"qualified": False, "failure_class": "TimeoutExpired", "raw": clean})
        self.assertEqual(2, code)
        self.assertIn("did not qualify", printed)
        extra_head = deepcopy(clean)
        ledger = extra_head["profiles"][0]["ledger"]
        marker = next(i for i, e in enumerate(ledger) if e["kind"] == "marker" and e["value"] == 2)
        ledger.insert(marker, {"seq": 900, "request": None, "kind": "head", "value": 0, "fixture": "u", "at_ns": 1})
        extra_head["profiles"][0]["boundary"] += 1
        self.assertEqual(2, self.run_budgets({"qualified": True, "raw": extra_head})[0])
        elsewhere = deepcopy(clean)
        elsewhere["environment"] = {"api": "36", "abi": "arm64-v8a"}
        self.assertEqual(2, self.run_budgets(good, {"qualified": True, "raw": elsewhere})[0])

    def test_a_malformed_budget_file_is_rejected(self):
        """An unknown schema or a budget without a ceiling must not silently pass everything."""
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "budgets.json"
            for body in ({"schema": 2, "budgets": []}, {"schema": 1, "budgets": [{"id": "x", "metric": "m", "unit": "ms"}]}):
                path.write_text(json.dumps(body))
                with self.assertRaises(ValueError):
                    load_budgets(path)


if __name__ == "__main__":
    unittest.main()
