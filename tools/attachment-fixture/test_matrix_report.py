"""Matrix contracts: samples own their ledger segment, shaped labels are true, and comparisons demand correctness."""

import contextlib
from copy import deepcopy
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock

from matrix_compare import load_checked, main as compare_main
from matrix_report import CIPHERTEXT_OVERHEAD, aggregate, attribute, check_matrix, compare, segment, stats
from matrix_tables import comparison, tables

MIB = 1024 * 1024
MS = 1_000_000


def measure(phase, size):
    """One sampled stage with finite latency and Java/native peaks."""
    return {"phase": phase, "success": True, "payload_bytes": size, "elapsed_ms": 25.0,
            "java_peak_bytes": 30 * MIB, "native_peak_bytes": 90 * MIB}


def profile(plan, down=0, up=0, latency=0, body_ms=None, name="unshaped", scale=1.0):
    """Construct the declared device and ledger shape for one profile; real sockets are tested separately."""
    events, foreground, seq, clock = [], [], 0, 0

    def add(request, kind, value, fixture, at):
        """Append one committed ledger event and return its sequence number."""
        nonlocal seq
        seq += 1
        events.append({"seq": seq, "request": request, "kind": kind, "value": value, "fixture": fixture, "at_ns": at})
        return seq

    marker = 0
    for size, reps in plan:
        for rep in range(1, reps + 1):
            marker += 1
            clock += MS
            add(None, "marker", marker, "control", clock)
            ciphertext = size + CIPHERTEXT_OVERHEAD
            upload = add(None, "upload", ciphertext, "u", clock)
            add(upload, "upload_bytes", ciphertext, "u", clock + 5 * MS)
            add(upload, "upload_complete", 0, "u", clock + 40 * MS)
            get = add(None, "get", 0, "u", clock + 50 * MS)
            add(get, "status", 200, "u", clock + 60 * MS)
            span = body_ms or max(1, ciphertext * 8 // max(down * 1000, 1) * 1000) if down else 20
            add(get, "body_bytes", ciphertext, "u", clock + 60 * MS + span * MS // 2)
            add(get, "complete", 0, "u", clock + 60 * MS + span * MS)
            row = {"phase": "matrix-sample", "size": size, "rep": rep, "marker": marker,
                   "prep_visible_ms": 4.0 * scale, "upload_publish_ms": 55.0 * scale, "reference_ms": 1.0,
                   "admission_ms": 6.0 * scale, "first_progress_ms": 14.0 * scale, "body_complete_ms": 70.0 * scale,
                   "ready_ms": 90.0 * scale, "feed_ready_ms": 250.0 * scale, "lease_ms": 262.0 * scale,
                   "digest_ms": 8.0, "warm_lease_ms": 7.0 * scale}
            foreground += [measure(f"matrix-upload-{marker}", size), measure(f"matrix-cold-{marker}", size),
                           measure(f"matrix-warm-{marker}", size), row]
    clock += MS
    add(None, "marker", marker + 1, "control", clock)
    boundary = len(events)
    sizes = sorted({size for size, _ in plan})
    recreated = []
    for size in sizes:
        recreated += [measure(f"matrix-recreated-native-{size}", size), measure(f"matrix-recreated-resolver-{size}", size),
                      {"phase": "matrix-recreated", "size": size, "native_lease_ms": 12.0}]
    recreated.append({"phase": "matrix-recreated-complete", "items": len(sizes)})
    return {"name": name, "shape": {"down_kbps": down, "up_kbps": up, "latency_ms": latency}, "plan": plan,
            "foreground_metrics": foreground, "recreated_metrics": recreated, "ledger": events, "boundary": boundary}


def raw(*profiles):
    """A complete raw matrix for one environment."""
    return {"schema": 1, "environment": {"api": "30", "abi": "arm64-v8a"}, "profiles": list(profiles)}


class MatrixTest(unittest.TestCase):
    """A matrix is evidence only when each cell owns its requests and every label is verifiable."""

    def test_valid_matrix_passes_and_claims_no_performance(self):
        """Samples own their segments, and the checker never reports a performance qualification."""
        result = check_matrix(raw(profile([(65536, 3), (MIB, 2)])))
        self.assertTrue(result["passed"], result)
        self.assertFalse(result["performance_qualified"])

    def test_segment_isolates_one_sample_between_numbered_markers(self):
        """Ordinary sample windows remain disjoint when every server event precedes the next marker."""
        events = profile([(65536, 2)])["ledger"]
        first, second = segment(events, 1), segment(events, 2)
        self.assertEqual(7, len(first))
        self.assertEqual(7, len(second))
        self.assertTrue(set(e["seq"] for e in first).isdisjoint(e["seq"] for e in second))
        self.assertIsNone(segment(events, 3))
        self.assertIsNone(segment(events, 99))

    def test_late_server_events_stay_with_their_admitted_request(self):
        """Final-byte and completion logging can follow the client's next marker without a refetch."""
        item = profile([(65536, 2)])
        ledger = item["ledger"]
        first_get = next(e["seq"] for e in ledger if e["kind"] == "get")
        late = [e for e in ledger if e["request"] == first_get and e["kind"] in ("body_bytes", "complete")]
        ledger[:] = [e for e in ledger if e not in late]
        next_marker = next(i for i, e in enumerate(ledger) if e["kind"] == "marker" and e["value"] == 2)
        ledger[next_marker + 1:next_marker + 1] = late
        sequences = {e["seq"]: i + 1 for i, e in enumerate(ledger)}
        for event in ledger:
            event["seq"] = sequences[event["seq"]]
            if event["request"] is not None:
                event["request"] = sequences[event["request"]]
        first_get = sequences[first_get]
        result = check_matrix(raw(item))
        self.assertTrue(result["passed"], result)
        first, second = segment(ledger, 1), segment(ledger, 2)
        self.assertEqual(1, sum(e["kind"] == "complete" for e in first))
        self.assertEqual(1, sum(e["kind"] == "complete" for e in second))
        self.assertTrue(set(e["seq"] for e in first).isdisjoint(e["seq"] for e in second))

        # Joining across a marker must not excuse a missing or duplicate acknowledgement.
        for duplicate in (False, True):
            altered = deepcopy(item)
            events = altered["ledger"]
            completion = next(e for e in events if e["kind"] == "complete" and e["request"] == first_get)
            if duplicate:
                events.append({**completion, "seq": 900})
            else:
                events.remove(completion)
                altered["boundary"] -= 1
            self.assertFalse(check_matrix(raw(altered))["passed"], duplicate)

    def test_an_orphan_response_cannot_be_dropped_by_request_joining(self):
        """An acknowledgement without an admitted parent remains material unexpected evidence."""
        item = profile([(65536, 2)])
        ledger = item["ledger"]
        marker = next(i for i, e in enumerate(ledger) if e["kind"] == "marker" and e["value"] == 2)
        ledger.insert(marker, {"seq": 900, "request": 899, "kind": "complete", "value": 0,
                               "fixture": "u", "at_ns": MS})
        item["boundary"] += 1
        self.assertFalse(check_matrix(raw(item))["passed"])

    def test_a_retry_a_head_or_a_refetch_fails(self):
        """One extra request in a sample's own segment is a correctness failure whatever the latency."""
        base = raw(profile([(65536, 2)]))
        for kind in ("get", "head"):
            altered = deepcopy(base)
            ledger = altered["profiles"][0]["ledger"]
            marker = next(i for i, e in enumerate(ledger) if e["kind"] == "marker" and e["value"] == 2)
            ledger.insert(marker, {"seq": 900, "request": None, "kind": kind, "value": 0, "fixture": "u", "at_ns": 1})
            altered["profiles"][0]["boundary"] += 1
            self.assertFalse(check_matrix(altered)["passed"], kind)

    def test_any_missing_event_or_marker_fails(self):
        """Removing a committed request event or a sample boundary is material evidence loss."""
        base = raw(profile([(65536, 2)]))
        ledger = base["profiles"][0]["ledger"]
        for index in range(len(ledger)):
            altered = deepcopy(base)
            del altered["profiles"][0]["ledger"][index]
            altered["profiles"][0]["boundary"] = min(altered["profiles"][0]["boundary"], len(altered["profiles"][0]["ledger"]))
            self.assertFalse(check_matrix(altered)["passed"], (index, ledger[index]["kind"]))

    def test_a_shaped_label_must_be_true(self):
        """A 4 Mbit/s label fails when the server observed the body arriving faster than the declared link."""
        # 1 MiB at 4 Mbit/s needs about 2.1 s; a 0.2 s body means the link was not constrained.
        fast = raw(profile([(MIB, 2)], down=4000, body_ms=200, name="constrained"))
        self.assertFalse(check_matrix(fast)["passed"])
        honest = raw(profile([(MIB, 2)], down=4000, body_ms=2100, name="constrained"))
        self.assertTrue(check_matrix(honest)["passed"], check_matrix(honest))

    def test_missing_or_invalid_measurements_fail(self):
        """Finite latency and peaks are required for every stage of every sample."""
        base = raw(profile([(65536, 2)]))
        for index, row in enumerate(base["profiles"][0]["foreground_metrics"]):
            for value in (None, True, -1, float("nan")):
                altered = deepcopy(base)
                target = altered["profiles"][0]["foreground_metrics"][index]
                field = "elapsed_ms" if "elapsed_ms" in target else "ready_ms"
                target[field] = value
                self.assertFalse(check_matrix(altered)["passed"], (index, field, value))
            break

    def test_restart_stage_must_read_every_size_and_never_acquire(self):
        """The recreated stage needs exact reads per kept size and no acquisition after the boundary."""
        base = raw(profile([(65536, 2), (MIB, 2)]))
        altered = deepcopy(base)
        altered["profiles"][0]["recreated_metrics"] = altered["profiles"][0]["recreated_metrics"][:-1]
        self.assertFalse(check_matrix(altered)["passed"])
        altered = deepcopy(base)
        altered["profiles"][0]["ledger"].append(
            {"seq": 901, "request": None, "kind": "get", "value": 0, "fixture": "u", "at_ns": 5})
        self.assertFalse(check_matrix(altered)["passed"])
        altered = deepcopy(base)
        altered["profiles"][0]["boundary"] = None
        self.assertFalse(check_matrix(altered)["passed"])

    def test_p95_is_reported_only_for_a_meaningful_sample(self):
        """A p95 from a handful of samples would be noise, so it is withheld below twenty."""
        self.assertIsNone(stats(list(range(1, 11)))["p95"])
        self.assertEqual(19, stats(list(range(1, 21)))["p95"])
        self.assertIsNone(stats([None, float("nan"), True, -1]))

    def test_attribution_names_the_dominant_layer_and_shares_sum_to_one(self):
        """The dominant component carries its layer, so work is routed to Android, MDK, storage or transport."""
        cell = {"prep_visible_ms": {"p50": 2.0}, "mdk_upload_ms": {"p50": 8.0}, "server_upload_ms": {"p50": 90.0}}
        result = attribute(cell, ("prep_visible_ms", "mdk_upload_ms", "server_upload_ms"))
        self.assertEqual("server_upload_ms", result["dominant"])
        self.assertEqual("transport", result["layer"])
        self.assertAlmostEqual(1.0, sum(result["shares"].values()), places=3)
        self.assertIsNone(attribute({}, ("prep_visible_ms",)))

    def test_a_subscription_delay_is_attributed_to_the_engine_not_to_android_materialization(self):
        """READY seen authoritatively but delivered late is a delivery delay; only post-delivery time is materialization."""
        report = aggregate(raw(profile([(65536, 4)])))
        cell = report["profiles"][0]["cells"][0]
        self.assertEqual(160.0, cell["subscription_delay_ms"]["p50"])
        self.assertEqual(12.0, cell["materialize_ms"]["p50"])
        self.assertEqual("subscription_delay_ms", cell["attribution"]["download"]["dominant"])
        self.assertEqual("ffi-mdk", cell["attribution"]["download"]["layer"])
        sample = deepcopy(raw(profile([(65536, 1)])))
        for row in sample["profiles"][0]["foreground_metrics"]:
            row.pop("feed_ready_ms", None)
        cell = aggregate(sample)["profiles"][0]["cells"][0]
        self.assertEqual(0.0, cell["subscription_delay_ms"]["p50"])

    def test_aggregate_exposes_distributions_requests_and_attribution(self):
        """Each cell carries its distributions, exact request and byte totals, and both attributions."""
        report = aggregate(raw(profile([(65536, 4)])))
        cell = report["profiles"][0]["cells"][0]
        self.assertEqual(4, cell["samples"])
        self.assertEqual({"uploads": 4, "gets": 4, "retries": 0}, cell["requests"])
        self.assertEqual(4 * (65536 + CIPHERTEXT_OVERHEAD), cell["bytes"]["downloaded"])
        self.assertIsNotNone(cell["attribution"]["download"])
        self.assertEqual(12.0, report["profiles"][0]["recreated_native_lease_ms"][65536]["p50"])

    def test_repeats_are_pooled_so_spread_includes_run_to_run_variation(self):
        """Three repeats of one matrix give three times the samples, and the spread covers all of them."""
        runs = [raw(profile([(65536, 4)], scale=scale)) for scale in (1.0, 1.2, 0.9)]
        pooled = aggregate(runs)
        cell = pooled["profiles"][0]["cells"][0]
        self.assertEqual(12, cell["samples"])
        self.assertEqual(3, pooled["runs"])
        self.assertEqual({"uploads": 12, "gets": 12, "retries": 0}, cell["requests"])
        single = aggregate(runs[0])["profiles"][0]["cells"][0]
        self.assertGreater(cell["ready_ms"]["max"] - cell["ready_ms"]["min"],
                           single["ready_ms"]["max"] - single["ready_ms"]["min"])

    def test_compare_flags_only_material_changes_and_requires_equal_correctness(self):
        """Faster is accepted only beyond run spread and ten percent, and never with a changed request count."""
        base = aggregate(raw(profile([(65536, 4)])))
        same = aggregate(raw(profile([(65536, 4)])))
        self.assertEqual([], compare(base, same)["faster"])
        self.assertTrue(compare(base, same)["accepted"])
        faster = aggregate(raw(profile([(65536, 4)], scale=0.5)))
        verdict = compare(base, faster)
        self.assertTrue(verdict["faster"])
        self.assertTrue(verdict["accepted"])
        slower = aggregate(raw(profile([(65536, 4)], scale=2.0)))
        self.assertFalse(compare(base, slower)["accepted"])
        changed = deepcopy(faster)
        changed["profiles"][0]["cells"][0]["requests"]["retries"] = 1
        self.assertFalse(compare(base, changed)["accepted"])
        self.assertTrue(compare(base, changed)["correctness_diffs"])

    def test_a_cell_missing_from_either_side_is_a_correctness_difference(self):
        """A candidate that measured only some of the baseline's profiles or sizes is incomplete, not unchanged."""
        both = aggregate(raw(profile([(65536, 4)]), profile([(65536, 4)], name="wifi")))
        only_unshaped = aggregate(raw(profile([(65536, 4)])))
        verdict = compare(both, only_unshaped)
        self.assertFalse(verdict["accepted"])
        self.assertEqual([{"cell": ("wifi", 65536), "missing_from": "candidate"}], verdict["correctness_diffs"])
        extra = compare(only_unshaped, both)
        self.assertFalse(extra["accepted"])
        self.assertEqual([{"cell": ("wifi", 65536), "missing_from": "baseline"}], extra["correctness_diffs"])
        fewer_sizes = aggregate(raw(profile([(65536, 4)])))
        more_sizes = aggregate(raw(profile([(65536, 4), (MIB, 2)])))
        self.assertFalse(compare(more_sizes, fewer_sizes)["accepted"])
        self.assertTrue(compare(more_sizes, more_sizes)["accepted"])

    def test_the_comparison_command_rejects_a_run_that_fails_correctness(self):
        """An extra HEAD inside a sample leaves every aggregate unchanged, so only the correctness check can see it."""
        good = raw(profile([(65536, 2)]))
        bad = deepcopy(good)
        ledger = bad["profiles"][0]["ledger"]
        marker = next(i for i, e in enumerate(ledger) if e["kind"] == "marker" and e["value"] == 2)
        ledger.insert(marker, {"seq": 900, "request": None, "kind": "head", "value": 0, "fixture": "u", "at_ns": 1})
        bad["profiles"][0]["boundary"] += 1
        with tempfile.TemporaryDirectory() as folder:
            paths = {}
            for name, run in (("good", good), ("bad", bad)):
                paths[name] = Path(folder) / f"{name}.json"
                paths[name].write_text(json.dumps({"qualified": True, "raw": run}))
            runs, violations = load_checked([paths["good"]])
            self.assertEqual(1, len(runs))
            self.assertEqual([], violations)
            _, violations = load_checked([paths["bad"]])
            self.assertTrue(violations)
            self.assertTrue(all(v.startswith("bad.json: ") for v in violations))
            argv = ["matrix_compare.py", "--baseline", str(paths["good"]), "--candidate", str(paths["bad"])]
            with mock.patch.object(sys, "argv", argv), contextlib.redirect_stdout(io.StringIO()) as out:
                with self.assertRaises(SystemExit) as raised:
                    compare_main()
            self.assertEqual(2, raised.exception.code)
            self.assertFalse(json.loads(out.getvalue())["accepted"])

    def run_compare(self, baseline, candidate):
        """Write each report dict to a file, run the comparison command and return its exit code and printed JSON."""
        with tempfile.TemporaryDirectory() as folder:
            groups = {}
            for side, reports in (("baseline", baseline), ("candidate", candidate)):
                groups[side] = []
                for index, report in enumerate(reports):
                    path = Path(folder) / f"{side}-{index}.json"
                    path.write_text(json.dumps(report))
                    groups[side].append(str(path))
            argv = ["matrix_compare.py", "--baseline", *groups["baseline"], "--candidate", *groups["candidate"]]
            with mock.patch.object(sys, "argv", argv), contextlib.redirect_stdout(io.StringIO()) as out:
                with self.assertRaises(SystemExit) as raised:
                    compare_main()
        text = out.getvalue()
        return raised.exception.code, json.loads(text[:text.index("\n}") + 2]) if text.startswith("{") else None

    def test_a_report_that_did_not_qualify_is_never_used_even_when_its_survivors_look_clean(self):
        """If one profile completed and another timed out, the survivors must not stand in for the whole matrix."""
        survivors = raw(profile([(65536, 2)]))
        good = {"qualified": True, "raw": survivors}
        partial = {"qualified": False, "stages_passed": False, "failure_class": "TimeoutExpired", "raw": survivors}
        self.assertTrue(check_matrix(survivors)["passed"], "the surviving profile on its own is clean")
        code, printed = self.run_compare([good], [partial])
        self.assertEqual(2, code)
        self.assertFalse(printed["accepted"])
        self.assertTrue(any("did not qualify" in v for v in printed["invalid_runs"]))
        code, _ = self.run_compare([partial], [good])
        self.assertEqual(2, code)
        for missing in ({"raw": survivors}, {"qualified": "yes", "raw": survivors}):
            self.assertEqual(2, self.run_compare([good], [missing])[0], missing)

    def test_a_report_without_a_raw_matrix_or_environment_is_rejected(self):
        """A qualified flag cannot vouch for data that is not there, or for data with no environment to compare."""
        good = {"qualified": True, "raw": raw(profile([(65536, 2)]))}
        for broken in ({"qualified": True, "raw": None}, {"qualified": True},
                       {"qualified": True, "raw": {**good["raw"], "environment": None}}):
            self.assertEqual(2, self.run_compare([good], [broken])[0], broken)

    def test_runs_from_different_environments_or_link_shapes_cannot_be_pooled(self):
        """Pooling hardware or links that differ would show a change of setup as a change of code."""
        other_environment = raw(profile([(65536, 2)]))
        other_environment["environment"] = {"api": "36", "abi": "arm64-v8a"}
        with self.assertRaises(ValueError):
            aggregate([raw(profile([(65536, 2)])), other_environment])
        with self.assertRaises(ValueError):
            aggregate([raw(profile([(65536, 2)], down=4000)), raw(profile([(65536, 2)], down=8000))])

    def test_a_candidate_from_another_environment_or_link_shape_is_not_accepted(self):
        """The same cells and counters on different hardware, or a differently shaped link, differ in setup, not code."""
        base = aggregate(raw(profile([(65536, 4)])))
        elsewhere = raw(profile([(65536, 4)]))
        elsewhere["environment"] = {"api": "36", "abi": "arm64-v8a"}
        verdict = compare(base, aggregate(elsewhere))
        self.assertFalse(verdict["accepted"])
        self.assertTrue(any("environment" in diff for diff in verdict["correctness_diffs"]))
        reshaped = compare(base, aggregate(raw(profile([(65536, 4)], down=4000))))
        self.assertFalse(reshaped["accepted"])
        self.assertTrue(any("shape" in diff for diff in reshaped["correctness_diffs"]))
        self.assertTrue(compare(base, aggregate(raw(profile([(65536, 4)]))))["accepted"])
        good = {"qualified": True, "raw": raw(profile([(65536, 4)]))}
        moved = {"qualified": True, "raw": elsewhere}
        code, printed = self.run_compare([good], [moved])
        self.assertEqual(1, code)
        self.assertFalse(printed["accepted"])
        self.assertEqual(2, self.run_compare([good, moved], [good])[0], "a mixed cohort is invalid input")

    def test_report_tables_render_every_cell_and_the_comparison_verdicts(self):
        """The markdown tables carry one row per cell and a verdict per compared metric."""
        base = aggregate([raw(profile([(65536, 4), (MIB, 2)])), raw(profile([(65536, 4), (MIB, 2)]))])
        text = tables(base)
        for heading in ("### Upload", "### Cold download", "### Warm, restart and memory", "### Dominant delay"):
            self.assertIn(heading, text)
        self.assertIn("| unshaped | 64 KiB | 8 |", text)
        self.assertIn("| unshaped | 1 MiB | 4 |", text)
        faster = aggregate([raw(profile([(65536, 4), (MIB, 2)], scale=0.5)) for _ in range(2)])
        table, verdicts = comparison(base, faster, ("lease_ms",))
        self.assertIn("lease_ms", table)
        self.assertIn("faster", table)
        self.assertTrue(verdicts["accepted"])

    def test_only_host_visible_metrics_can_reject_a_candidate(self):
        """A slower engine-side diagnostic is reported but cannot reject, while a slower reader-visible wait does."""
        base = aggregate([raw(profile([(65536, 4)])) for _ in range(2)])
        noisy = deepcopy(base)
        noisy["profiles"][0]["cells"][0]["subscription_delay_ms"]["p50"] *= 3
        verdict = compare(base, noisy)
        self.assertTrue(verdict["accepted"])
        self.assertTrue(any(c["metric"] == "subscription_delay_ms" for c in verdict["diagnostic_changes"]))
        slower = deepcopy(base)
        slower["profiles"][0]["cells"][0]["lease_ms"]["p50"] *= 3
        self.assertFalse(compare(base, slower)["accepted"])

    def test_post_ready_wait_is_what_the_shipping_path_adds_after_ready(self):
        """The wait after authoritative READY is the open-ready lease minus READY, whatever the feed did."""
        cell = aggregate(raw(profile([(65536, 4)])))["profiles"][0]["cells"][0]
        self.assertAlmostEqual(262.0 - 90.0, cell["post_ready_ms"]["p50"], places=3)


if __name__ == "__main__":
    unittest.main()
