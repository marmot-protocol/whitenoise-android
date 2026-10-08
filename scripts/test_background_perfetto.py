"""Exercise collector file descriptors with a child modeling Android SELinux."""

import json
import os
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile
import unittest


class BackgroundPerfettoTest(unittest.TestCase):
    """Keep file-backed config and diagnostic descriptors out of the collector."""

    def invoke_collector(self, body, *, existing_trace=False, existing_log=False):
        """Execute the sourced device function with an observable collector child."""
        root = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            config = folder / "capture.pbtxt"
            config.write_text("duration_ms: 5000\n")
            fake = folder / "perfetto"
            fake.write_text(f"#!{sys.executable}\n" + '''import json, os, stat, sys
state = {str(fd): stat.S_ISFIFO(os.fstat(fd).st_mode) for fd in (0, 1, 2)}
with open(os.environ["DESCRIPTOR_STATE"], "w") as out:
    json.dump(state, out)
if not all(state.values()):
    sys.exit(71)
if sys.stdin.read() != "duration_ms: 5000\\n":
    sys.exit(72)
''' + body)
            fake.chmod(0o755)
            log = folder / "collector.log"
            if existing_log:
                log.write_text("preserved rejected diagnostics\n")
            state = folder / "descriptors.json"
            trace = folder / "trace"
            if existing_trace == "symlink":
                trace.symlink_to("missing_trace_target")
            elif existing_trace:
                trace.write_bytes(b"preserved trace")
            env = os.environ | {"PATH": str(folder) + os.pathsep + os.environ["PATH"],
                                "DESCRIPTOR_STATE": str(state)}
            command = ". " + shlex.quote(str(root / "scripts/background_perfetto.sh"))
            command += "; background_perfetto_start " + " ".join(
                shlex.quote(str(value)) for value in (config, trace, log)
            )
            result = subprocess.run(["bash", "-c", command], env=env,
                                    capture_output=True, text=True)
            return result, log.read_text() if log.exists() else None, (
                json.loads(state.read_text()) if state.exists() else None
            ), trace.readlink() if trace.is_symlink() else (
                trace.read_bytes() if trace.exists() else None
            )

    def test_collector_uses_pipes_and_retains_its_pid(self):
        """A collector denied shell-data files must still read config and print PID."""
        result, log, state, _ = self.invoke_collector('print("12345")\n')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("12345", result.stdout.strip())
        self.assertEqual("12345", log.strip())
        self.assertTrue(all(state.values()))

    def test_collector_failure_cannot_be_hidden_by_successful_tee(self):
        """Retain rejected launch diagnostics without returning a success PID."""
        result, log, _, _ = self.invoke_collector(
            'print("12345")\nprint("collector_rejected", file=sys.stderr)\nsys.exit(23)\n'
        )
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("", result.stdout)
        self.assertIn("collector_rejected", log)

    def test_missing_or_ambiguous_pid_is_rejected(self):
        """A completed pipeline alone does not establish collector ownership."""
        for body in ('print("startup_notice")\n', 'print("12345\\n67890")\n', 'print("0")\n'):
            with self.subTest(body=body):
                result, _, _, _ = self.invoke_collector(body)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual("", result.stdout)

    def test_existing_trace_is_preserved_without_launching(self):
        """Never truncate output that could belong to another active collector."""
        result, log, state, trace = self.invoke_collector(
            'print("12345")\n', existing_trace=True
        )
        self.assertNotEqual(0, result.returncode)
        self.assertIsNone(log)
        self.assertIsNone(state)
        self.assertEqual(b"preserved trace", trace)

    def test_dangling_trace_symlink_is_preserved_without_launching(self):
        """A missing symlink target still belongs to an existing output path."""
        result, log, state, target = self.invoke_collector(
            'print("12345")\n', existing_trace="symlink"
        )
        self.assertNotEqual(0, result.returncode)
        self.assertIsNone(log)
        self.assertIsNone(state)
        self.assertEqual(Path("missing_trace_target"), target)

    def test_previous_launch_diagnostics_are_preserved_without_launching(self):
        """Retries must choose a fresh log instead of erasing the rejected attempt."""
        result, log, state, _ = self.invoke_collector(
            'print("12345")\n', existing_log=True
        )
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("preserved rejected diagnostics\n", log)
        self.assertIsNone(state)


if __name__ == "__main__":
    unittest.main()
