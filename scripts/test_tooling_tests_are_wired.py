"""Fails when a scripts/test_*.py suite is not run by any CI workflow."""

import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent


def _command_lines(text: str) -> list[str]:
    # Comments and YAML list items (path filters) name scripts without running them.
    return [line for line in text.splitlines() if not line.lstrip().startswith(("#", "-"))]


def unwired_tooling_tests(root: pathlib.Path = ROOT) -> list[str]:
    workflow_text = "\n".join(path.read_text() for path in sorted((root / ".github" / "workflows").glob("*.yml")))
    commands = _command_lines(workflow_text)
    workflow_commands = "\n".join(commands)
    for script in sorted((root / "scripts").glob("*.sh")):
        if f"scripts/{script.name}" in workflow_commands:
            commands += _command_lines(script.read_text())
    invoked = "\n".join(line for line in commands if "python" in line)
    missing = []
    for test in sorted((root / "scripts").glob("test_*.py")):
        if not re.search(rf"scripts[./]{re.escape(test.stem)}(\.py)?\b", invoked):
            missing.append(f"scripts/{test.name}")
    return missing


class ToolingTestsAreWiredTest(unittest.TestCase):
    def test_every_tooling_test_runs_in_a_workflow(self) -> None:
        self.assertEqual([], unwired_tooling_tests())


if __name__ == "__main__":
    unittest.main()
