"""Exercise the real production allow-list and fail-closed trigger policy."""
from pathlib import Path
import unittest

from scripts.check_fuzz_pr_triggers import TriggerPolicyError, pr_paths, production_sources, validate


ROOT = Path(__file__).resolve().parents[1]
BUILD = (ROOT / 'fuzz/build.gradle.kts').read_text()
WORKFLOW = (ROOT / '.github/workflows/fuzz-pr.yml').read_text()


def covered_workflow() -> str:
    entries = production_sources(BUILD) + ['fuzz/**', '.github/workflows/fuzz-pr.yml']
    return 'on:\n  pull_request:\n    paths:\n' + ''.join(f"      - '{entry}'\n" for entry in entries)


class FuzzPrTriggerPolicyTest(unittest.TestCase):
    def test_actual_production_sources_are_covered(self):
        validate(BUILD, WORKFLOW)

    def test_complete_allowlist_and_extra_triggers_pass(self):
        validate(BUILD, covered_workflow())

    def test_removing_each_real_source_fails(self):
        for path in production_sources(BUILD):
            with self.subTest(path=path), self.assertRaisesRegex(TriggerPolicyError, path):
                validate(BUILD, covered_workflow().replace(f"      - '{path}'\n", ''))

    def test_wildcard_is_not_substituted_for_literal_source(self):
        path = production_sources(BUILD)[0]
        modified = covered_workflow().replace(path, 'app/src/main/java/dev/*')
        with self.assertRaisesRegex(TriggerPolicyError, 'missing literal'):
            validate(BUILD, modified)

    def test_later_negation_cannot_cancel_literal_trigger(self):
        excluded = covered_workflow() + "      - '!app/**'\n"
        with self.assertRaisesRegex(TriggerPolicyError, 'exclusions'):
            validate(BUILD, excluded)

    def test_escaped_double_quoted_exclusions_fail_closed(self):
        for escape in (r'\x21', r'\u0021'):
            excluded = covered_workflow() + f'      - "{escape}app/**"\n'
            with self.subTest(escape=escape), self.assertRaisesRegex(TriggerPolicyError, 'escapes'):
                validate(BUILD, excluded)

    def test_missing_or_duplicate_mappings_fail(self):
        for text in ('on:\n  push:\n', covered_workflow() * 2,
                     covered_workflow().replace('    paths:', '    paths-ignore:')):
            with self.subTest(text=text), self.assertRaises(TriggerPolicyError):
                validate(BUILD, text)

    def test_quoted_keys_and_yaml_string_forms(self):
        workflow = covered_workflow().replace('on:', '"on":').replace('pull_request:', "'pull_request':")
        workflow = workflow.replace("'fuzz/**'", '"fuzz/**" # extra trigger')
        validate(BUILD, workflow)
        self.assertIn('fuzz/**', pr_paths(workflow))

    def test_unsupported_dynamic_or_duplicate_allowlists_fail(self):
        path = 'dev/ipf/whitenoise/android/core/ProfileLink.kt'
        for body in ('', f'"{path}", "{path}",', f'"{path}" + suffix,', 'dynamicSource,'):
            build = 'val fuzzProductionIncludes = listOf(\n' + body + '\n)\n'
            with self.subTest(body=body), self.assertRaises(TriggerPolicyError):
                production_sources(build)

    def test_chained_allowlist_initializers_fail_closed(self):
        path = 'dev/ipf/whitenoise/android/media/NewParser.kt'
        literal = 'val fuzzProductionIncludes = listOf(\n"dev/ipf/whitenoise/android/core/ProfileLink.kt",\n)'
        for suffix in (f'.plus("{path}")', f' + listOf("{path}")',
                       f'\n    .plus("{path}")', f'\n    + listOf("{path}")',
                       f' // continuation\n    .plus("{path}")'):
            with self.subTest(suffix=suffix), self.assertRaises(TriggerPolicyError):
                production_sources(literal + suffix + '\n')

    def test_unknown_yaml_configuration_fails_closed(self):
        for line in ('      - ["fuzz/**", "app/**"]', '      - &anchor fuzz/**', '\t- fuzz/**'):
            workflow = 'on:\n  pull_request:\n    paths:\n' + line + '\n'
            with self.subTest(line=line), self.assertRaises(TriggerPolicyError):
                validate(BUILD, workflow)

    def test_sources_outside_package_fail(self):
        build = 'val fuzzProductionIncludes = listOf(\n"../outside.kt",\n)\n'
        with self.assertRaisesRegex(TriggerPolicyError, 'unsupported production source'):
            production_sources(build)


if __name__ == '__main__':
    unittest.main()
