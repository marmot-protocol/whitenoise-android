"""Matrix contracts: samples own their ledger segment, shaped labels are true, and comparisons demand correctness."""

from copy import deepcopy
import unittest

from matrix_report import CIPHERTEXT_OVERHEAD, aggregate, attribute, check_matrix, compare, segment, stats

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
        """A sample sees only the events between its marker and the next one."""
        events = profile([(65536, 2)])["ledger"]
        first, second = segment(events, 1), segment(events, 2)
        self.assertEqual(7, len(first))
        self.assertEqual(7, len(second))
        self.assertTrue(set(e["seq"] for e in first).isdisjoint(e["seq"] for e in second))
        self.assertIsNone(segment(events, 3))
        self.assertIsNone(segment(events, 99))

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
        self.assertEqual({65536: 12.0}, report["profiles"][0]["recreated_native_lease_ms"])

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


if __name__ == "__main__":
    unittest.main()
