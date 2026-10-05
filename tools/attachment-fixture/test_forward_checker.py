"""Forward qualification must fail when any sample, phase, receipt, marker or request proof is absent or wrong."""

from copy import deepcopy
import unittest

from forward_checker import (CEILING_MS, CIPHERTEXT_OVERHEAD, FINAL_MARKER, MARKERS, PAYLOAD_BYTES, PHASE_FIELDS,
                             REPETITIONS, SOURCE, VARIANTS, check_forward, expected_gets, expected_uploads)

MIB = 1024 * 1024
CIPHERTEXT = PAYLOAD_BYTES + CIPHERTEXT_OVERHEAD
LINE = ("schema=2 app_rev=app1234 mdk_rev=mdk5678 session=p#1 op=message_forward phase=forward_complete "
        "elapsed_ms=600 duration_ms=0 result=success layer=android count=1")


def measure(phase, elapsed=420.5):
    """One sampled stage with finite latency and peaks, as the device probe reports it."""
    return {"phase": phase, "success": True, "payload_bytes": PAYLOAD_BYTES, "elapsed_ms": elapsed,
            "java_peak_bytes": 20 * MIB, "native_peak_bytes": 60 * MIB}


def sample(variant, rep, total=600.0):
    """One forward row that satisfies the variant's source contract and every phase requirement."""
    contract = SOURCE[variant]
    return {"phase": "forward-sample", "variant": variant, "rep": rep, "marker": MARKERS[variant] + rep,
            "payload_bytes": PAYLOAD_BYTES, "source_memory_cached": contract["memory"],
            "source_host_disk_cached": contract["host_disk"], "source_native_retained": contract["native"],
            "success": True, "total_ms": total, "delivered_ms": total + 300.0, "delivered_exact": True,
            "terminal": "Completed", "sent_messages": 1, "uploaded_attachments": 1,
            "source_lookup_hit": contract["lookup_hit"], "source_lookup_ms": 2, "source_reference_resolved_ms": None,
            "source_download_ms": 40 if contract["download"] else None,
            "source_download_result": "success" if contract["download"] else None,
            "source_ready_ms": 45, "upload_ms": 120, "upload_attempts": 1, "commit_lock_wait_ms": None,
            "publish_ms": 200, "publish_attempts": 1, "convergence_attempts": 0, "complete_result": "success",
            "wnperf_lines": [LINE]}


def metrics():
    """The complete device metric set: direct sends, one source open, fifteen forwards, the timeline and the stage."""
    rows = []
    for rep in range(1, REPETITIONS + 1):
        rows += [measure(f"forward-direct-{rep}"),
                 {"phase": "forward-direct", "rep": rep, "payload_bytes": PAYLOAD_BYTES, "send_ms": 410.0,
                  "reference_ms": 50.0}]
    for variant in VARIANTS:
        if variant == "retained":
            rows.append(measure("forward-source-open"))
        for rep in range(1, REPETITIONS + 1):
            rows += [measure(f"forward-{variant}-{rep}"), sample(variant, rep)]
    expected = REPETITIONS * (1 + len(VARIANTS))
    rows.append({"phase": "forward-destination-timeline", "sent_media_messages": expected,
                 "distinct_ciphertexts": expected, "direct": REPETITIONS,
                 "forwards": REPETITIONS * len(VARIANTS)})
    rows.append({"phase": "fixture-stage", "stage": "forward-complete"})
    return rows


class LedgerBuilder:
    """Builds the server ledger the probe's marker plan produces, with independently committed events."""

    def __init__(self):
        self.rows = []

    def event(self, kind, value=0, fixture="control", request=None):
        """Append one event and return its sequence number."""
        seq = len(self.rows) + 1
        self.rows.append({"seq": seq, "request": request, "kind": kind, "value": value, "fixture": fixture})
        return seq

    def upload(self, token):
        """One complete 1 KiB ciphertext upload."""
        seq = self.event("upload", CIPHERTEXT, token)
        self.event("upload_complete", 0, token, seq)

    def get(self, token):
        """One complete 200 acquisition of the whole ciphertext."""
        seq = self.event("get", 0, token)
        self.event("status", 200, token, seq)
        self.event("body_bytes", CIPHERTEXT, token, seq)
        self.event("complete", 0, token, seq)

    def marker(self, number):
        """One numbered boundary."""
        self.event("marker", number)


def events():
    """The whole ledger of one passing run, in the probe's order."""
    ledger = LedgerBuilder()
    ledger.upload("upload-source")
    ledger.upload("upload-own")
    for rep in range(1, REPETITIONS + 1):
        ledger.marker(MARKERS["direct"] + rep)
        ledger.upload(f"upload-direct-{rep}")
    for variant in VARIANTS:
        source = "upload-own" if variant == "cached" else "upload-source"
        if variant == "retained":
            ledger.marker(MARKERS["retained"])
            ledger.get("upload-source")
        for rep in range(1, REPETITIONS + 1):
            ledger.marker(MARKERS[variant] + rep)
            token = f"upload-{variant}-{rep}"
            ledger.upload(token)
            if SOURCE[variant]["download"]:
                ledger.get(source)
            ledger.get(token)
    ledger.marker(FINAL_MARKER)
    return ledger.rows


def sample_index(rows, variant, rep):
    """Position of one forward row in a metric list."""
    return next(i for i, m in enumerate(rows) if m.get("phase") == "forward-sample" and m["variant"] == variant
                and m["rep"] == rep)


class ForwardEvidenceTest(unittest.TestCase):
    """A closure claim needs fifteen completed forwards with every phase timed, one receipt each and a clean ledger."""

    def test_complete_evidence_passes_and_reports_the_direct_ratio_without_claiming_a_device(self):
        """The checker enforces the ceilings, reports the ratio to a direct send and qualifies no physical device."""
        result = check_forward(metrics(), events())
        self.assertTrue(result["passed"], result)
        self.assertFalse(result["physical_device_qualified"])
        self.assertEqual(410.0, result["direct_send_median_ms"])
        for variant in VARIANTS:
            self.assertEqual(REPETITIONS, result["variants"][variant]["samples"])
            self.assertTrue(result["variants"][variant]["within_direct_ratio_target"])
        self.assertEqual(expected_uploads(), sum(e["kind"] == "upload" for e in events()))
        self.assertEqual(expected_gets(), sum(e["kind"] == "get" for e in events()))

    def test_every_missing_metric_fails(self):
        """Removing any one measurement, direct row, forward row, timeline row or stage invalidates the run."""
        rows = metrics()
        for index in range(len(rows)):
            with self.subTest(phase=rows[index].get("phase"), index=index):
                self.assertFalse(check_forward(rows[:index] + rows[index + 1:], events())["passed"])

    def test_every_missing_ledger_event_fails(self):
        """A missing upload, completion, marker, GET, status, body count or terminal is material evidence loss."""
        ledger = events()
        for index in range(len(ledger)):
            with self.subTest(kind=ledger[index]["kind"], index=index):
                self.assertFalse(check_forward(metrics(), ledger[:index] + ledger[index + 1:])["passed"])

    def test_a_forward_over_its_ceiling_fails_while_a_slow_but_bounded_one_only_misses_the_ratio_target(self):
        """The issue ceilings are enforced per variant; the two-times-direct relation is reported, not enforced."""
        for variant in VARIANTS:
            rows = metrics()
            rows[sample_index(rows, variant, 3)]["total_ms"] = CEILING_MS[variant] + 1
            self.assertFalse(check_forward(rows, events())["passed"], variant)
            rows = metrics()
            rows[sample_index(rows, variant, 3)]["total_ms"] = CEILING_MS[variant] - 1
            rows[sample_index(rows, variant, 3)]["delivered_ms"] = CEILING_MS[variant] + 100
            result = check_forward(rows, events())
            self.assertTrue(result["passed"], result)
            self.assertFalse(result["variants"][variant]["within_direct_ratio_target"])

    def test_delivery_observed_before_completion_or_inexact_fails(self):
        """A receipt earlier than the terminal state, or with different bytes, contradicts the forward."""
        rows = metrics()
        rows[sample_index(rows, "uncached", 1)]["delivered_ms"] = 100.0
        self.assertFalse(check_forward(rows, events())["passed"])
        rows = metrics()
        rows[sample_index(rows, "cached", 5)]["delivered_exact"] = False
        self.assertFalse(check_forward(rows, events())["passed"])

    def test_every_source_layer_and_lookup_state_must_match_the_variant(self):
        """Each pre-state boolean and the forward's own lookup result are individually required."""
        for variant in VARIANTS:
            for field in ("source_memory_cached", "source_host_disk_cached", "source_native_retained",
                          "source_lookup_hit"):
                rows = metrics()
                row = rows[sample_index(rows, variant, 2)]
                row[field] = not row[field]
                with self.subTest(variant=variant, field=field):
                    self.assertFalse(check_forward(rows, events())["passed"])

    def test_a_download_where_the_contract_forbids_it_or_none_where_it_requires_one_fails(self):
        """The source download pair must appear exactly when the variant's contract says the source is fetched."""
        rows = metrics()
        row = rows[sample_index(rows, "cached", 1)]
        row["source_download_ms"], row["source_download_result"] = 30, "success"
        self.assertFalse(check_forward(rows, events())["passed"])
        rows = metrics()
        row = rows[sample_index(rows, "uncached", 1)]
        row["source_download_ms"], row["source_download_result"] = None, None
        self.assertFalse(check_forward(rows, events())["passed"])
        rows = metrics()
        rows[sample_index(rows, "retained", 4)]["source_download_result"] = "failure"
        self.assertFalse(check_forward(rows, events())["passed"])

    def test_retries_convergence_or_a_non_completed_terminal_fail(self):
        """A forward that needed a second upload, a convergence pass or did not complete is not a clean forward."""
        for field, value in (("upload_attempts", 2), ("publish_attempts", 0), ("convergence_attempts", 1),
                             ("terminal", "Failed"), ("complete_result", "failure"), ("sent_messages", 2),
                             ("uploaded_attachments", 0), ("success", False), ("marker", 99)):
            rows = metrics()
            rows[sample_index(rows, "retained", 2)][field] = value
            with self.subTest(field=field):
                self.assertFalse(check_forward(rows, events())["passed"])

    def test_an_uncached_forward_without_its_source_lookup_timing_is_not_qualified(self):
        """A missed lookup still has a duration, so a sample whose lookup was never timed cannot qualify."""
        for variant in ("uncached", "retained", "cached"):
            rows = metrics()
            sampled = rows[sample_index(rows, variant, 3)]
            sampled["source_lookup_ms"] = None
            sampled["wnperf_lines"] = [line for line in sampled["wnperf_lines"] if "forward_source_lookup" not in line]
            with self.subTest(variant=variant):
                result = check_forward(rows, events())
                self.assertFalse(result["passed"])
                self.assertTrue(any("source_lookup_ms was not timed" in v for v in result["violations"]), result)

    def test_every_phase_must_be_timed_and_the_lock_wait_must_be_a_measurement_when_present(self):
        """Each phase duration is individually required; the lock wait may be absent but never malformed."""
        for field in PHASE_FIELDS:
            for value in (None, float("nan"), -1, True):
                rows = metrics()
                rows[sample_index(rows, "uncached", 5)][field] = value
                with self.subTest(field=field, value=value):
                    self.assertFalse(check_forward(rows, events())["passed"])
        rows = metrics()
        rows[sample_index(rows, "uncached", 5)]["commit_lock_wait_ms"] = "x"
        self.assertFalse(check_forward(rows, events())["passed"])
        rows = metrics()
        rows[sample_index(rows, "uncached", 5)]["commit_lock_wait_ms"] = 12
        self.assertTrue(check_forward(rows, events())["passed"])
        rows = metrics()
        rows[sample_index(rows, "uncached", 5)]["wnperf_lines"] = []
        self.assertFalse(check_forward(rows, events())["passed"])

    def test_timeline_counts_and_duplicates_fail(self):
        """The destination must hold exactly one distinct copy per send; a repeated row is a changed fixture."""
        for field in ("sent_media_messages", "distinct_ciphertexts", "direct", "forwards"):
            rows = metrics()
            next(m for m in rows if m.get("phase") == "forward-destination-timeline")[field] -= 1
            with self.subTest(field=field):
                self.assertFalse(check_forward(rows, events())["passed"])
        rows = metrics()
        self.assertFalse(check_forward(rows + [rows[sample_index(rows, "cached", 1)]], events())["passed"])
        self.assertFalse(check_forward(rows + [measure("forward-direct-1")], events())["passed"])
        self.assertFalse(check_forward(rows + [dict(sample("cached", 1), variant="other")], events())["passed"])
        self.assertFalse(check_forward(["not a metric"], events())["passed"])

    def test_unexpected_requests_fail(self):
        """An extra acquisition inside a sample or after the final marker, a HEAD or a disconnect fails the ledger."""
        ledger = events()
        extra = {"seq": 10_000, "request": None, "kind": "get", "value": 0, "fixture": "upload-source"}
        self.assertFalse(check_forward(metrics(), ledger + [extra])["passed"])
        inside = deepcopy(ledger)
        position = next(i for i, e in enumerate(inside)
                        if e["kind"] == "marker" and e["value"] == MARKERS["cached"] + 2)
        inside.insert(position, dict(extra, seq=inside[position - 1]["seq"]))
        for index, event in enumerate(inside):
            event["seq"] = index + 1
        self.assertFalse(check_forward(metrics(), inside)["passed"])
        for kind in ("head", "disconnect", "upload_disconnect"):
            with self.subTest(kind=kind):
                self.assertFalse(check_forward(metrics(), ledger + [dict(extra, kind=kind)])["passed"])

    def test_wrong_ciphertext_size_or_status_fails(self):
        """A short body, a short upload or a non-200 status is a failed transfer whatever the probe reported."""
        ledger = deepcopy(events())
        next(e for e in ledger if e["kind"] == "body_bytes")["value"] -= 1
        self.assertFalse(check_forward(metrics(), ledger)["passed"])
        ledger = deepcopy(events())
        next(e for e in ledger if e["kind"] == "status")["value"] = 206
        self.assertFalse(check_forward(metrics(), ledger)["passed"])
        ledger = deepcopy(events())
        next(e for e in ledger if e["kind"] == "upload")["value"] += 1
        self.assertFalse(check_forward(metrics(), ledger)["passed"])


if __name__ == "__main__":
    unittest.main()
