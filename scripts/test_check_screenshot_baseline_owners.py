"""Behavioral tests for the committed screenshot-golden owner registry."""

import importlib.util
import io
import json
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    'screenshot_baseline_owners', ROOT / 'scripts/check_screenshot_baseline_owners.py',
)
owners = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(owners)
CAPTURING_SCREENSHOT = '''
class ChatRowScreenshotTest {
    @Test
    fun light() = composeRule.onRoot().captureRoboImage("src/test/snapshots/chat_row_light.png")
}
'''
MIXED_OWNER = '''
class HistoryBoundaryTest {
    @Test
    fun lightBoundary() = capture("light")

    @Test
    @Suppress("LongMethod") // Keep the comment between annotations.
    fun boundaryKeepsAccessibilityLabel() = assertTrue(true)

    private fun capture(name: String) = composeRule.onRoot().captureRoboImage("src/test/snapshots/$name.png")
}
'''
REGISTRY = '*ScreenshotTest\n*.HistoryBoundaryTest.lightBoundary\n'


class ScreenshotOwnerFixture:
    """A throwaway repository holding unit-test sources, goldens and a registry."""

    def __init__(self, directory, registry=REGISTRY, sources=None, goldens=('chat_row_light.png', 'light.png')):
        """Write the given sources, PNG names and registry under the temporary root."""
        self.root = Path(directory)
        package = self.root / 'app/src/test/java/dev/example'
        package.mkdir(parents=True)
        for name, text in (sources or {
            'ChatRowScreenshotTest': CAPTURING_SCREENSHOT,
            'HistoryBoundaryTest': MIXED_OWNER,
        }).items():
            (package / f'{name}.kt').write_text(text)
        snapshots = self.root / owners.SNAPSHOTS
        snapshots.mkdir(parents=True)
        for golden in goldens:
            (snapshots / golden).write_bytes(b'png')
        (self.root / owners.REGISTRY).parent.mkdir(parents=True)
        (self.root / owners.REGISTRY).write_text(registry)

    def summary(self, *compared, kind='unchanged'):
        """Model Roborazzi's results-summary.json for goldens compared in this checkout."""
        snapshots = self.root / owners.SNAPSHOTS
        return {'results': [{'golden_file_path': str(snapshots / name), 'type': kind} for name in compared]}

    def run(self, *argv):
        """Run the CLI against this fixture and capture its status and output."""
        out, err = io.StringIO(), io.StringIO()
        status = owners.main(list(argv), root=self.root, out=out, err=err)
        return status, out.getvalue(), err.getvalue()


class ScreenshotBaselineOwnersTest(unittest.TestCase):
    """Committed goldens stay owned by the curated screenshot selection."""

    def fixture(self, **kwargs):
        """Create an isolated fixture repository that is removed after the test."""
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        return ScreenshotOwnerFixture(directory.name, **kwargs)

    def test_repository_registry_is_consistent(self):
        """The checked-in registry resolves against the real unit-test sources."""
        self.assertEqual(owners.static_problems(ROOT, (ROOT / owners.REGISTRY).read_text()), [])

    def test_complete_registry_passes(self):
        """A suffix owner plus a method-level owner covers both capturing classes."""
        status, out, err = self.fixture().run()
        self.assertEqual(status, 0, err)
        self.assertIn('every committed golden has a selected owner', out)

    def test_removed_owner_filter_fails(self):
        """Dropping a mixed-name owner leaves its capturing class unselected."""
        status, _, err = self.fixture(registry='*ScreenshotTest\n').run()
        self.assertEqual(status, 1)
        self.assertIn('HistoryBoundaryTest calls captureRoboImage but is not selected', err)

    def test_removed_suffix_filter_fails(self):
        """Dropping the *ScreenshotTest rule orphans every screenshot class."""
        status, _, err = self.fixture(registry='*.HistoryBoundaryTest.lightBoundary\n').run()
        self.assertEqual(status, 1)
        self.assertIn('ChatRowScreenshotTest calls captureRoboImage but is not selected', err)

    def test_stale_method_filter_fails(self):
        """A renamed or deleted golden-producing method cannot stay registered."""
        status, _, err = self.fixture(registry=REGISTRY + '*.HistoryBoundaryTest.darkBoundary\n').run()
        self.assertEqual(status, 1)
        self.assertIn('*.HistoryBoundaryTest.darkBoundary names no @Test method', err)

    def test_annotated_test_method_is_recognized(self):
        """Annotations and comments between @Test and fun still declare a test method."""
        registry = REGISTRY + '*.HistoryBoundaryTest.boundaryKeepsAccessibilityLabel\n'
        status, _, err = self.fixture(registry=registry).run()
        self.assertEqual(status, 0, err)

    def test_owner_without_captures_fails(self):
        """A registered class must actually capture a Roborazzi image."""
        sources = {'ChatRowScreenshotTest': CAPTURING_SCREENSHOT, 'PlainTest': 'class PlainTest { @Test fun a() {} }'}
        status, _, err = self.fixture(registry='*ScreenshotTest\n*.PlainTest\n', sources=sources).run()
        self.assertEqual(status, 1)
        self.assertIn('*.PlainTest names a class that captures no Roborazzi image', err)

    def test_missing_owner_class_fails(self):
        """A filter naming a deleted class is reported instead of selecting nothing."""
        status, _, err = self.fixture(registry=REGISTRY + '*.GoneTest\n').run()
        self.assertEqual(status, 1)
        self.assertIn('*.GoneTest must name exactly one unit-test class, found 0', err)

    def test_diagnostic_capture_may_stay_unselected(self):
        """A capture that owns no committed golden can be declared diagnostic-only."""
        status, _, err = self.fixture(registry='*ScreenshotTest\ndiagnostic: HistoryBoundaryTest\n').run()
        self.assertEqual(status, 0, err)

    def test_diagnostic_cannot_also_be_an_owner(self):
        """One class is either a golden owner or diagnostic-only, never both."""
        status, _, err = self.fixture(registry=REGISTRY + 'diagnostic: HistoryBoundaryTest\n').run()
        self.assertEqual(status, 1)
        self.assertIn('HistoryBoundaryTest is both a golden owner and diagnostic-only', err)

    def test_malformed_and_duplicate_filters_fail(self):
        """Only the three supported filter shapes are accepted, each at most once."""
        status, _, err = self.fixture(registry=REGISTRY + 'HistoryBoundaryTest\n*ScreenshotTest\n').run()
        self.assertEqual(status, 1)
        self.assertIn("unsupported filter 'HistoryBoundaryTest'", err)
        self.assertIn("duplicate filter '*ScreenshotTest'", err)

    def test_gradle_arguments_preserve_registry_order(self):
        """The CI step receives one --tests pair per registered owner."""
        status, out, err = self.fixture().run('--gradle-test-args')
        self.assertEqual(status, 0, err)
        self.assertEqual(
            out.splitlines(),
            ['--tests', '*ScreenshotTest', '--tests', '*.HistoryBoundaryTest.lightBoundary'],
        )

    def test_gradle_arguments_refuse_an_empty_registry(self):
        """An empty selection must never fall back to running the whole unit suite."""
        status, out, err = self.fixture(registry='# nothing\n').run('--gradle-test-args')
        self.assertEqual(status, 1)
        self.assertEqual(out, '')
        self.assertIn('registry selects no owners', err)

    def test_results_covering_every_golden_pass(self):
        """A verification run that compared every committed PNG passes."""
        fixture = self.fixture()
        results = fixture.root / 'summary.json'
        results.write_text(json.dumps(fixture.summary('chat_row_light.png', 'light.png')))
        status, _, err = fixture.run('--results', str(results))
        self.assertEqual(status, 0, err)

    def test_uncompared_golden_fails_by_name(self):
        """A committed PNG that no selected test compared is named in the failure."""
        fixture = self.fixture()
        results = fixture.root / 'summary.json'
        results.write_text(json.dumps(fixture.summary('chat_row_light.png')))
        status, _, err = fixture.run('--results', str(results))
        self.assertEqual(status, 1)
        self.assertIn('light.png was not compared', err)

    def test_recorded_or_foreign_results_do_not_count_as_comparisons(self):
        """Fresh recordings and images outside the golden directory prove nothing."""
        fixture = self.fixture()
        summary = fixture.summary('chat_row_light.png')
        summary['results'] += fixture.summary('light.png', kind='recorded')['results']
        summary['results'].append({'golden_file_path': str(fixture.root / 'build/light.png'), 'type': 'unchanged'})
        results = fixture.root / 'summary.json'
        results.write_text(json.dumps(summary))
        status, _, err = fixture.run('--results', str(results))
        self.assertEqual(status, 1)
        self.assertIn('light.png was not compared', err)

    def test_missing_results_summary_fails(self):
        """A verification step that produced no summary cannot pass the coverage check."""
        fixture = self.fixture()
        status, _, err = fixture.run('--results', str(fixture.root / 'absent.json'))
        self.assertEqual(status, 1)
        self.assertIn('missing results summary', err)


if __name__ == '__main__':
    unittest.main()
