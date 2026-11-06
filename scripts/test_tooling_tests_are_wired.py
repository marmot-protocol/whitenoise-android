"""Fails when a scripts/test_*.py suite is not run by any CI workflow step."""

import pathlib
import re
import unittest

import yaml

ROOT = pathlib.Path(__file__).resolve().parent.parent


def _step_commands(workflow: dict) -> list[str]:
    # Only step `run` values execute; env values, path filters and comments do not.
    commands = []
    for job in (workflow.get("jobs") or {}).values():
        for step in job.get("steps") or []:
            if isinstance(step.get("run"), str):
                commands.append(step["run"])
    return commands


def _executable_lines(command: str) -> list[str]:
    return [line for line in command.splitlines() if not line.lstrip().startswith("#")]


def unwired_tooling_tests(root: pathlib.Path = ROOT) -> list[str]:
    lines = []
    for path in sorted((root / ".github" / "workflows").glob("*.yml")):
        for command in _step_commands(yaml.safe_load(path.read_text()) or {}):
            lines += _executable_lines(command)
    workflow_commands = "\n".join(lines)
    for script in sorted((root / "scripts").glob("*.sh")):
        if f"scripts/{script.name}" in workflow_commands:
            lines += _executable_lines(script.read_text())
    invoked = "\n".join(line for line in lines if "python" in line)
    missing = []
    for test in sorted((root / "scripts").glob("test_*.py")):
        if not re.search(rf"scripts[./]{re.escape(test.stem)}(\.py)?\b", invoked):
            missing.append(f"scripts/{test.name}")
    return missing


class ToolingTestsAreWiredTest(unittest.TestCase):
    def test_every_tooling_test_runs_in_a_workflow(self) -> None:
        self.assertEqual([], unwired_tooling_tests())

    def test_env_values_and_comments_do_not_count_as_wiring(self) -> None:
        workflow = {
            "env": {"UNUSED": "python3 -m unittest scripts.test_example"},
            "jobs": {
                "check": {
                    "env": {"ALSO_UNUSED": "python3 scripts/test_example.py"},
                    "steps": [{"run": "# python3 -m unittest scripts.test_example\necho done"}],
                }
            },
        }
        commands = [line for run in _step_commands(workflow) for line in _executable_lines(run)]
        self.assertEqual(["echo done"], commands)


if __name__ == "__main__":
    unittest.main()
