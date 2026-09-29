import json
import unittest

from scripts.media_latency_report import parse_status


def _matrix_lines(template, sizes=("small", "medium", "large")):
    """Build the fixed size matrix expected from the guarded instrumentation test."""
    return [
        f"INSTRUMENTATION_STATUS: media_probe_json={json.dumps(dict(template, operation=operation, size=size))}"
        for size in sizes
        for operation in ("preparation", "upload", "download")
    ]


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
            _matrix_lines(aggregate)
            + [
                "INSTRUMENTATION_STATUS: media_probe_native=size=small phase=body_transfer attempts=2 successes=2 failures=0 duration_sum_ms=30 overflow_count=0",
                "INSTRUMENTATION_STATUS: media_probe=phase=image_decode_ms count=2 p50=1.0 p95=2.0 max=2.0",
                "OK (1 test)",
                "INSTRUMENTATION_CODE: -1",
            ]
        )
        report = parse_status(output)
        self.assertTrue(report["complete"])
        self.assertEqual(aggregate, report["aggregates"][2])
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

    def test_component_numbers_keep_exponents_and_reject_bad_tokens(self):
        """Small timings survive conversion without accepting truncated or non-finite values."""
        prefix = "INSTRUMENTATION_STATUS: media_probe=phase=image_decode_ms count=2 "
        report = parse_status(prefix + "p50=5.0E-4 p95=6e-4 max=0.001")
        self.assertEqual(0.0005, report["component_metrics"][0]["p50"])
        for value in ("5.0E-4junk", "inf", "1e999", "-1", "1.2.3"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                parse_status(prefix + f"p50={value} p95=1 max=1")

    def test_matrix_requires_all_operation_size_pairs(self):
        """A successful runner cannot certify an incomplete or duplicate matrix."""
        template = {
            "schema": 1,
            "operation": "download",
            "size": "small",
            "payload_bytes_per_sample": 1,
            "samples": 1,
            "successes": 1,
            "failures": 0,
            "payload_bytes_total": 1,
            "network_bytes": None,
            "duration_ms": {"p50": 1, "p95": 1, "max": 1},
            "peak_heap_bytes": {"java": 1, "native": 1},
        }
        success = ["OK (1 test)", "INSTRUMENTATION_CODE: -1"]
        matrix = _matrix_lines(template)
        self.assertTrue(parse_status("\n".join(matrix + success))["complete"])
        for partial in (matrix[:-1], matrix + matrix[:1], matrix + _matrix_lines(template, ("near_limit",))[:1]):
            with self.subTest(record_count=len(partial)):
                self.assertFalse(parse_status("\n".join(partial + success))["complete"])
        self.assertTrue(parse_status("\n".join(matrix + _matrix_lines(template, ("near_limit",)) + success))["complete"])


if __name__ == "__main__":
    unittest.main()
