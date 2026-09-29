import json
import unittest

from scripts.media_latency_report import parse_status


class MediaLatencyReportTest(unittest.TestCase):
    """Keep the report parser closed to identifiers and honest about failed runs."""

    def test_parses_bounded_aggregate_and_native_phase(self):
        """Known aggregate, native, and component records survive conversion."""
        aggregate = {
            "schema": 1,
            "operation": "download",
            "size": "small",
            "payload_bytes_per_sample": 65536,
            "samples": 2,
            "successes": 2,
            "failures": 0,
            "payload_bytes_total": 131072,
            "network_bytes": None,
            "duration_ms": {"p50": 10.0, "p95": 20.0, "max": 20.0},
            "peak_heap_bytes": {"java": 100, "native": 200},
        }
        output = "\n".join(
            [
                f"INSTRUMENTATION_STATUS: media_probe_json={json.dumps(aggregate)}",
                "INSTRUMENTATION_STATUS: media_probe_native=size=small phase=body_transfer attempts=2 successes=2 failures=0 duration_sum_ms=30 overflow_count=0",
                "INSTRUMENTATION_STATUS: media_probe=phase=image_decode_ms count=2 p50=1.0 p95=2.0 max=2.0",
                "OK (1 test)",
                "INSTRUMENTATION_CODE: -1",
            ]
        )
        report = parse_status(output)
        self.assertTrue(report["complete"])
        self.assertEqual(aggregate, report["aggregates"][0])
        self.assertEqual(2, report["native_phases"][0]["attempts"])
        self.assertEqual("image_decode_ms", report["component_metrics"][0]["phase"])

    def test_rejects_identifiers_and_false_network_byte_claims(self):
        """Unexpected identities and unmeasured network bytes are rejected."""
        bad = "INSTRUMENTATION_STATUS: media_probe_native=phase=download account=secret attempts=1"
        with self.assertRaises(ValueError):
            parse_status(bad)
        record = {
            "schema": 1,
            "operation": "download",
            "size": "small",
            "payload_bytes_per_sample": 1,
            "samples": 1,
            "successes": 1,
            "failures": 0,
            "payload_bytes_total": 1,
            "network_bytes": 1,
            "duration_ms": {"p50": 1, "p95": 1, "max": 1},
            "peak_heap_bytes": {"java": 1, "native": 1},
        }
        with self.assertRaises(ValueError):
            parse_status(f"INSTRUMENTATION_STATUS: media_probe_json={json.dumps(record)}")

    def test_failed_instrumentation_is_not_complete(self):
        """A non-success instrumentation code remains visibly incomplete."""
        self.assertFalse(parse_status("INSTRUMENTATION_CODE: 0")["complete"])

    def test_success_code_alone_does_not_hide_instrumentation_failure(self):
        """The runner's code cannot turn a crash or failed assertion into a complete probe."""
        sample = "INSTRUMENTATION_STATUS: media_probe=phase=image_decode_ms count=1 p50=1 p95=1 max=1"
        for ending in (
            "INSTRUMENTATION_CODE: -1",
            "OK (0 tests)\nINSTRUMENTATION_CODE: -1",
            "OK (1 test)\nFAILURES!!!\nINSTRUMENTATION_CODE: -1",
            "OK (1 test)\nINSTRUMENTATION_ABORTED: Process crashed\nINSTRUMENTATION_CODE: -1",
        ):
            with self.subTest(ending=ending):
                self.assertFalse(parse_status(f"{sample}\n{ending}")["complete"])


if __name__ == "__main__":
    unittest.main()
