"""Measure real DEX code_items and fail closed on budget, mapping and provenance mismatches."""
import contextlib
import io
import json
from pathlib import Path
import re
import struct
import tempfile
import unittest
from unittest.mock import patch
import zipfile
import zlib

from scripts.dex_method_budget import (ART_PATHOLOGICAL_LIMIT, BudgetInputError, DexFile, load_config, main,
                                       markdown_summary, original_identity, read_mapping, read_uleb128)

REPO_ROOT = Path(__file__).resolve().parent.parent
MAP_ID = 'a' * 64
SOURCE_SHA = 'b' * 40
R8_MARKER = '~~R8{"backend":"dex","compilation-mode":"release","min-api":30,"pg-map-id":"%s","version":"9.4.24"}'
D8_MARKER = '~~D8{"backend":"dex","compilation-mode":"release","min-api":30,"version":"9.4.24"}'


def uleb128(value):
    """Encode one unsigned LEB128 value."""
    out = bytearray()
    while True:
        byte, value = value & 0x7F, value >> 7
        out.append(byte | (0x80 if value else 0))
        if not value:
            return bytes(out)


def build_dex(classes, strings_extra=(), version=b'039'):
    """Assemble a minimal valid DEX file.

    Each class is (descriptor, methods) or (descriptor, methods, (static, instance)) field
    counts, and each method is (name, params, returns, insns, registers, virtual). insns None
    declares an abstract method without code.
    """
    strings, types, protos, method_ids = [], [], [], []

    def string(value):
        """Intern a string and return its string_id index."""
        if value not in strings:
            strings.append(value)
        return strings.index(value)

    def type_index(descriptor):
        """Intern a type descriptor and return its type_id index."""
        string(descriptor)
        if descriptor not in types:
            types.append(descriptor)
        return types.index(descriptor)

    def proto_index(params, returns):
        """Intern a prototype and return its proto_id index."""
        key = (tuple(params), returns)
        string('V' + 'L' * len(params))
        for item in (*params, returns):
            type_index(item)
        if key not in protos:
            protos.append(key)
        return protos.index(key)

    plan = []
    for descriptor, methods, *fields in classes:
        type_index(descriptor)
        entries = []
        for name, params, returns, insns, registers, virtual in methods:
            method_ids.append((type_index(descriptor), proto_index(params, returns), string(name)))
            entries.append((len(method_ids) - 1, insns, registers, virtual))
        plan.append((descriptor, entries, fields[0] if fields else (0, 0)))
    for value in strings_extra:
        string(value)

    string_ids = 0x70
    type_ids = string_ids + 4 * len(strings)
    proto_ids = type_ids + 4 * len(types)
    method_table = proto_ids + 12 * len(protos)
    class_defs = method_table + 8 * len(method_ids)
    data_start = class_defs + 32 * len(plan)
    data = bytearray()

    def place(blob, align=1):
        """Append a data item at the requested alignment and return its absolute offset."""
        while (data_start + len(data)) % align:
            data.append(0)
        offset = data_start + len(data)
        data.extend(blob)
        return offset

    string_offsets = [place(uleb128(len(value)) + value.encode('utf-8') + b'\0') for value in strings]
    proto_rows = []
    for params, returns in protos:
        parameters = 0
        if params:
            parameters = place(struct.pack('<I', len(params)) +
                               b''.join(struct.pack('<H', types.index(item)) for item in params), 4)
        proto_rows.append(struct.pack('<III', strings.index('V' + 'L' * len(params)), types.index(returns),
                                      parameters))
    class_rows = []
    for descriptor, entries, (statics, instances) in plan:
        code = {index: (place(struct.pack('<HHHHII', registers, 0, 0, 0, 0, insns) + bytes(2 * insns), 4)
                        if insns is not None else 0)
                for index, insns, registers, _virtual in entries}
        blob = bytearray(uleb128(statics) + uleb128(instances))
        groups = [[entry for entry in entries if not entry[3]], [entry for entry in entries if entry[3]]]
        blob += uleb128(len(groups[0])) + uleb128(len(groups[1]))
        # Each field is a field_idx_diff and multi-byte access flags, which the parser must skip.
        for field in range(statics + instances):
            blob += uleb128(0 if field in (0, statics) else 1) + uleb128(0x4019)
        for group in groups:
            previous = 0
            for index, _insns, _registers, _virtual in sorted(group):
                blob += uleb128(index - previous) + uleb128(1) + uleb128(code[index])
                previous = index
        class_rows.append(struct.pack('<8I', types.index(descriptor), 1, 0xFFFFFFFF, 0, 0xFFFFFFFF, 0,
                                      place(bytes(blob)), 0))
    body = (b''.join(struct.pack('<I', offset) for offset in string_offsets) +
            b''.join(struct.pack('<I', strings.index(value)) for value in types) +
            b''.join(proto_rows) +
            b''.join(struct.pack('<HHI', *row) for row in method_ids) +
            b''.join(class_rows))
    size = 0x70 + len(body) + len(data)
    header = bytearray(b'dex\n' + version + b'\0' + bytes(24))
    header += struct.pack('<20I', size, 0x70, 0x12345678, 0, 0, 0,
                          len(strings), string_ids, len(types), type_ids, len(protos), proto_ids, 0, 0,
                          len(method_ids), method_table, len(plan), class_defs, len(data), data_start)
    dex = bytearray(header + body + data)
    return reseal(dex)


def reseal(dex):
    """Recompute a DEX checksum after a test mutates its bytes."""
    dex = bytearray(dex)
    struct.pack_into('<I', dex, 8, zlib.adler32(bytes(dex[12:])))
    return bytes(dex)


def sample_classes():
    """Two residual classes: one large composable, one overload pair and an abstract method."""
    return [
        ('La;', [('a', ['Lb;', 'I'], 'V', 900, 40, False),
                 ('b', [], 'V', None, 0, True)]),
        ('Lb;', [('c', ['I'], 'V', 120, 5, False),
                 ('c', ['J'], 'V', 300, 7, True)]),
    ]


SAMPLE_MAPPING = f'''# compiler: R8
# compiler_version: 9.4.24
# min_api: 30
# pg_map_id: {MAP_ID}
dev.example.ui.ScreenKt -> a:
# {{"id":"sourceFile","fileName":"Screen.kt"}}
    java.lang.String label -> f
    1:4:java.lang.String dev.example.Labels.label():9:9 -> a
    1:4:void dev.example.ui.ContentKt.Screen(dev.example.State,int):40 -> a
    1:4:void ScreenKt.Screen(State,int):0 -> a
      # {{"id":"com.android.tools.r8.synthesized"}}
      # {{"id":"com.android.tools.r8.residualsignature","signature":"(Lb;I)V"}}
    5:9:void dev.example.ui.ContentKt.Screen(dev.example.State,int):41:45 -> a
    5:9:void ScreenKt.Screen(State,int):0 -> a
    10:12:void ScreenKt.Screen(State,int):0 -> a
    void abstractHook() -> b
dev.example.State -> b:
    1:3:void apply(int):10:12 -> c
    4:6:void apply(long):20:22 -> c
'''


class DexFixture:
    """Write an APK, mapping and budget config into a temporary directory."""

    def __init__(self, root, classes=None, mapping=SAMPLE_MAPPING, marker=R8_MARKER % MAP_ID, variants=None):
        """Create the default artifact set that the per-test overrides adjust."""
        self.root = Path(root)
        self.apk = self.root / 'app.apk'
        self.mapping = self.root / 'mapping.txt'
        self.config = self.root / 'budgets.json'
        self.reports = self.root / 'reports'
        dex = build_dex(classes or sample_classes(), [marker])
        with zipfile.ZipFile(self.apk, 'w') as archive:
            archive.writestr('classes.dex', dex)
            archive.writestr('META-INF/com/android/build/gradle/app-metadata.properties',
                             'appMetadataVersion=1.1\nandroidGradlePluginVersion=9.4.1\n')
        self.mapping.write_text(mapping, encoding='utf-8')
        self.write_config(variants or {'productionZapstoreRelease': budget('shipping', 1000, 100)})

    def write_config(self, variants):
        """Replace the budget config with the given variant entries."""
        self.config.write_text(json.dumps({'schema': 1, 'variants': variants}), encoding='utf-8')

    def run(self, variant='productionZapstoreRelease', environment=None, with_mapping=True, top=3):
        """Run the CLI and return (exit code, stdout, stderr)."""
        stdout, stderr = io.StringIO(), io.StringIO()
        mapping = ['--mapping', str(self.mapping)] if with_mapping else []
        with patch.dict('os.environ', environment or {}, clear=True), \
                contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            code = main(['--apk', str(self.apk), *mapping, '--variant', variant, '--source-sha', SOURCE_SHA,
                         '--config', str(self.config), '--report-dir', str(self.reports), '--top', str(top)])
        return code, stdout.getvalue(), stderr.getvalue()

    def report(self, variant='productionZapstoreRelease'):
        """Load the JSON report written for a variant."""
        return json.loads((self.reports / f'dex-method-sizes-{variant}.json').read_text(encoding='utf-8'))


def budget(kind, insns, registers):
    """A complete reviewed budget entry for tests."""
    return {'kind': kind, 'max_insns_code_units': insns, 'max_registers': registers,
            'basis': {'source_sha': SOURCE_SHA, 'artifact': 'fixture', 'largest_insns_code_units': 900,
                      'largest_registers': 40}}


class DexParsingTest(unittest.TestCase):
    """The parser reads code_item sizes exactly and rejects malformed DEX bytes."""

    def test_art_limit_is_a_quarter_of_uint16(self):
        """ART skips compilation at UINT16_MAX / 4 = 16383 code units or registers."""
        self.assertEqual(16383, ART_PATHOLOGICAL_LIMIT)

    def test_uleb128_decodes_multibyte_values_and_rejects_overlong_or_truncated_input(self):
        """LEB128 values follow the DEX encoding and never read past five bytes or the buffer."""
        self.assertEqual((127, 1), read_uleb128(b'\x7f', 0))
        self.assertEqual((128, 2), read_uleb128(b'\x80\x01', 0))
        self.assertEqual((624485, 3), read_uleb128(b'\xe5\x8e\x26', 0))
        with self.assertRaises(BudgetInputError):
            read_uleb128(b'\x80\x80\x80\x80\x80\x01', 0)
        with self.assertRaises(BudgetInputError):
            read_uleb128(b'\x80', 0)

    def test_reads_insns_and_registers_for_direct_and_virtual_methods_and_skips_abstract_ones(self):
        """Every method with a code_item is reported with its residual identity; abstract methods have none."""
        dex = DexFile(build_dex(sample_classes()), 'classes.dex')
        methods = {(item['class'], item['name'], item['proto']): (item['insns_code_units'], item['registers'])
                   for item in dex.methods()}
        self.assertEqual({('La;', 'a', '(Lb;I)V'): (900, 40), ('Lb;', 'c', '(I)V'): (120, 5),
                          ('Lb;', 'c', '(J)V'): (300, 7)}, methods)

    def test_methods_after_static_and_instance_fields_keep_their_sizes(self):
        """Encoded fields are skipped two LEB128 values at a time before methods are read."""
        classes = [('La;', [('a', [], 'V', 70, 3, False), ('b', [], 'V', 90, 4, True)], (2, 3))]
        dex = DexFile(build_dex(classes), 'classes.dex')
        self.assertEqual({'a': (70, 3), 'b': (90, 4)},
                         {item['name']: (item['insns_code_units'], item['registers']) for item in dex.methods()})

    def test_method_index_diffs_accumulate_across_many_methods(self):
        """Multi-byte method_idx_diff values decode against the right method_id."""
        filler = [(f'f{index}', [], 'V', None, 0, False) for index in range(150)]
        methods = [('m150', [], 'V', 10, 1, False), ('m151', [], 'V', 10, 1, True)]
        dex = DexFile(build_dex([('Lfiller;', filler), ('La;', methods)]), 'classes.dex')
        self.assertEqual(['m150', 'm151'], sorted(item['name'] for item in dex.methods()))

    def test_markers_expose_the_r8_map_id(self):
        """R8's embedded marker carries the pg-map-id that binds the mapping to the DEX."""
        dex = DexFile(build_dex(sample_classes(), [R8_MARKER % MAP_ID]), 'classes.dex')
        self.assertEqual([('R8', MAP_ID)], [(tool, marker['pg-map-id']) for tool, marker in dex.markers()])

    def test_malformed_dex_inputs_fail_instead_of_measuring_nothing(self):
        """Bad magic, unknown versions, checksums, sizes and code_item bounds are all rejected."""
        valid = build_dex(sample_classes())
        code_offset = valid.index(struct.pack('<HHHHII', 40, 0, 0, 0, 0, 900))
        cases = {
            'magic': b'xex' + valid[3:],
            'version': build_dex(sample_classes(), version=b'041'),
            'checksum': valid[:-1] + bytes([valid[-1] ^ 1]),
            'size': reseal(valid + b'\0\0\0\0'),
            'header': valid[:0x60],
            'code bounds': reseal(valid[:code_offset + 12] + struct.pack('<I', 0x7FFFFFFF) + valid[code_offset + 16:]),
        }
        for name, data in cases.items():
            with self.subTest(name), self.assertRaises(BudgetInputError):
                list(DexFile(data, 'classes.dex').methods())


class MappingTest(unittest.TestCase):
    """Identities follow R8's map, eliding synthesized and inlined frames like retrace."""

    def mapped(self, wanted):
        """Read the sample mapping for the given residual classes."""
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / 'mapping.txt'
            path.write_text(SAMPLE_MAPPING, encoding='utf-8')
            return read_mapping(path, wanted)

    def test_synthesized_outer_frame_resolves_to_the_wrapped_source_method(self):
        """A synthesized wrapper and its inlined callee never hide the real source method."""
        header, classes, runs = self.mapped({'a'})
        self.assertEqual(MAP_ID, header['pg_map_id'])
        identity = original_identity({'class': 'La;', 'name': 'a', 'proto': '(Lb;I)V'}, classes,
                                     {value: key for key, value in classes.items()}, runs[('a', 'a')])
        self.assertEqual('dev.example.ui.ContentKt.Screen(dev.example.State,int) -> void', identity)

    def test_overloads_without_residual_signatures_resolve_through_translated_types(self):
        """Overloads sharing a residual name are told apart by their DEX prototype."""
        _header, classes, runs = self.mapped({'b'})
        reverse = {value: key for key, value in classes.items()}
        for proto, expected in (('(J)V', 'apply(long)'), ('(I)V', 'apply(int)')):
            identity = original_identity({'class': 'Lb;', 'name': 'c', 'proto': proto}, classes, reverse,
                                         runs[('b', 'c')])
            self.assertEqual(f'dev.example.State.{expected} -> void', identity)

    def test_unmapped_methods_and_non_r8_maps_are_explicit(self):
        """A method absent from the map says so, and a map without an R8 header is rejected."""
        identity = original_identity({'class': 'Lz;', 'name': 'q', 'proto': '()V'}, {}, {}, [])
        self.assertEqual('z.q (no mapping entry)', identity)
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / 'mapping.txt'
            path.write_text('a.B -> a:\n', encoding='utf-8')
            with self.assertRaises(BudgetInputError):
                read_mapping(path, set())


class BudgetCommandTest(unittest.TestCase):
    """The CLI writes an artifact-bound report and fails on violations or untrustworthy inputs."""

    def setUp(self):
        """Give each test its own temporary artifact directory."""
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def test_within_budget_passes_and_records_artifact_provenance(self):
        """A passing run records source, APK, DEX, mapping, toolchain and CI provenance."""
        fixture = DexFixture(self.root)
        code, stdout, _stderr = fixture.run(environment={'GITHUB_RUN_ID': '42', 'GITHUB_WORKFLOW': 'Verify'})
        self.assertEqual(0, code)
        report = fixture.report()
        provenance = report['provenance']
        self.assertEqual(SOURCE_SHA, provenance['source_sha'])
        self.assertEqual(MAP_ID, provenance['mapping']['pg_map_id'])
        self.assertEqual('9.4.1', provenance['android_gradle_plugin'])
        self.assertEqual({'github_run_id': '42', 'github_workflow': 'Verify'}, provenance['github'])
        self.assertRegex(provenance['apk']['sha256'], r'^[0-9a-f]{64}$')
        self.assertEqual(['classes.dex'], [item['name'] for item in provenance['dex_files']])
        self.assertEqual([900, 300, 120], [row['insns_code_units'] for row in report['largest_by_insns']])
        self.assertEqual(100, report['largest_by_insns'][0]['insns_budget_headroom'])
        self.assertEqual(16383 - 900, report['largest_by_insns'][0]['insns_art_headroom'])
        self.assertEqual([], report['violations'])
        self.assertIn('| 900 | 40 | `dev.example.ui.ContentKt.Screen', stdout)

    def test_instruction_budget_violation_fails_with_the_source_and_residual_method(self):
        """An over-budget method fails the check and names both identities and the budget."""
        fixture = DexFixture(self.root, variants={'productionZapstoreRelease': budget('shipping', 899, 100)})
        code, _stdout, stderr = fixture.run()
        self.assertEqual(1, code)
        self.assertIn('dev.example.ui.ContentKt.Screen(dev.example.State,int)', stderr)
        self.assertIn('La;->a(Lb;I)V in classes.dex', stderr)
        self.assertIn('900 code units / 40 registers; budget 899 / 100', stderr)
        self.assertEqual(['La;->a(Lb;I)V'], [row['residual'] for row in fixture.report()['violations']])

    def test_register_budget_violation_fails(self):
        """A method over the register budget fails even when its instruction count fits."""
        fixture = DexFixture(self.root, variants={'productionZapstoreRelease': budget('shipping', 1000, 39)})
        self.assertEqual(1, fixture.run()[0])

    def test_mapping_from_another_r8_run_fails(self):
        """A map id that differs from the DEX marker cannot describe this artifact."""
        fixture = DexFixture(self.root, marker=R8_MARKER % ('c' * 64))
        code, _stdout, stderr = fixture.run()
        self.assertEqual(2, code)
        self.assertIn('does not match the DEX marker', stderr)

    def test_shipping_and_benchmark_budgets_reject_the_other_variant_shape(self):
        """Benchmark (unobfuscated) sizes never pass for shipping sizes, nor the reverse."""
        unobfuscated = SAMPLE_MAPPING.replace('dev.example.ui.ScreenKt -> a:', 'a -> a:').replace(
            'dev.example.State -> b:', 'b -> b:')
        fixture = DexFixture(self.root, mapping=unobfuscated)
        self.assertEqual(2, fixture.run()[0])
        fixture = DexFixture(self.root, variants={'devZapstoreBenchmarkRelease': budget('benchmark', 1000, 100)})
        self.assertEqual(2, fixture.run('devZapstoreBenchmarkRelease')[0])

    def test_benchmark_d8_output_is_measured_by_source_name_without_a_mapping(self):
        """Unminified D8 benchmark output keeps source names, so it needs and accepts no mapping."""
        classes = [('Ldev/example/ui/ScreenKt;', [('Screen', ['Ldev/example/State;', 'I'], 'V', 900, 40, False)])]
        variants = {'devZapstoreBenchmarkRelease': budget('benchmark', 1000, 100)}
        fixture = DexFixture(self.root, classes=classes, marker=D8_MARKER, variants=variants)
        code, stdout, _stderr = fixture.run('devZapstoreBenchmarkRelease', with_mapping=False)
        self.assertEqual(0, code)
        report = fixture.report('devZapstoreBenchmarkRelease')
        self.assertEqual('D8', report['provenance']['compiler'])
        self.assertIsNone(report['provenance']['mapping'])
        self.assertEqual('dev.example.ui.ScreenKt.Screen(dev.example.State,int) -> void',
                         report['largest_by_insns'][0]['original'])
        self.assertIn('D8 9.4.24, no mapping', stdout)
        self.assertEqual(2, fixture.run('devZapstoreBenchmarkRelease')[0])

    def test_shipping_budgets_require_r8_output_in_every_dex_and_its_mapping(self):
        """D8 output, a DEX missing R8's marker, or a missing mapping cannot pass a shipping budget."""
        self.assertEqual(2, DexFixture(self.root, marker=D8_MARKER).run(with_mapping=False)[0])
        self.assertEqual(2, DexFixture(self.root).run(with_mapping=False)[0])
        fixture = DexFixture(self.root)
        with zipfile.ZipFile(fixture.apk, 'a') as archive:
            archive.writestr('classes2.dex', build_dex([('Lc;', [('d', [], 'V', 5, 1, False)])]))
        code, _stdout, stderr = fixture.run()
        self.assertEqual(2, code)
        self.assertIn('classes2.dex: no R8 marker', stderr)

    def test_method_exactly_at_its_budget_passes(self):
        """A budget is the largest allowed size, so a method of exactly that size passes."""
        fixture = DexFixture(self.root, variants={'productionZapstoreRelease': budget('shipping', 900, 40)})
        self.assertEqual(0, fixture.run()[0])

    def test_tied_sizes_keep_every_reported_row_named(self):
        """Naming and reporting share one ordering, so a tie at the top-N cut never drops a name."""
        classes = [('Lz;', [('a', [], 'V', 50, 1, False)]), ('La;', [('b', [], 'V', 50, 1, False)])]
        variants = {'devZapstoreBenchmarkRelease': budget('benchmark', 1000, 100)}
        fixture = DexFixture(self.root, classes=classes, marker=D8_MARKER, variants=variants)
        self.assertEqual(0, fixture.run('devZapstoreBenchmarkRelease', with_mapping=False, top=1)[0])
        report = fixture.report('devZapstoreBenchmarkRelease')
        for row in report['largest_by_insns'] + report['largest_by_registers']:
            self.assertTrue(row['original'], row)

    def test_failure_text_offers_a_budget_review_only_below_arts_limit(self):
        """At ART's limit the only remedy is a split; below it, a reviewed budget change is possible."""
        fixture = DexFixture(self.root, variants={'productionZapstoreRelease': budget('shipping', 899, 100)})
        self.assertIn('Split the method or review a new budget', fixture.run()[2])
        classes = [('La;', [('a', [], 'V', ART_PATHOLOGICAL_LIMIT, 1, False)])]
        variants = {'devZapstoreBenchmarkRelease': budget('benchmark', ART_PATHOLOGICAL_LIMIT - 1, 100)}
        fixture = DexFixture(self.root, classes=classes, marker=D8_MARKER, variants=variants)
        code, _stdout, stderr = fixture.run('devZapstoreBenchmarkRelease', with_mapping=False)
        self.assertEqual(1, code)
        self.assertIn('Split the method (docs/performance.md', stderr)
        self.assertNotIn('review a new budget', stderr)

    def test_variant_flag_is_checked_against_output_metadata(self):
        """AGP's output-metadata.json beside the APK must name the budgeted variant and list the APK."""
        fixture = DexFixture(self.root)
        metadata = self.root / 'output-metadata.json'
        metadata.write_text(json.dumps({'variantName': 'stagingZapstoreRelease',
                                        'elements': [{'outputFile': 'app.apk'}]}), encoding='utf-8')
        self.assertEqual(2, fixture.run()[0])
        metadata.write_text(json.dumps({'variantName': 'productionZapstoreRelease',
                                        'elements': [{'outputFile': 'app.apk'}]}), encoding='utf-8')
        self.assertEqual(0, fixture.run()[0])
        self.assertEqual('output-metadata.json', fixture.report()['provenance']['variant_check'])
        metadata.write_text(json.dumps({'variantName': 'productionZapstoreRelease',
                                        'elements': [{'outputFile': 'other.apk'}]}), encoding='utf-8')
        self.assertEqual(2, fixture.run()[0])
        for malformed in ('{not json', json.dumps({'variantName': 'productionZapstoreRelease'})):
            metadata.write_text(malformed, encoding='utf-8')
            self.assertEqual(2, fixture.run()[0])
        metadata.unlink()
        self.assertEqual(0, fixture.run()[0])
        self.assertTrue(fixture.report()['provenance']['variant_check'].startswith('unchecked'))

    def test_step_summary_escapes_merged_method_names(self):
        """Merged R8 sources joined by " | " stay inside one Markdown table cell."""
        fixture = DexFixture(self.root)
        fixture.run()
        report = fixture.report()
        report['largest_by_insns'][0]['original'] = 'a.B.c() -> void | a.B.d() -> void'
        row = [line for line in markdown_summary(report).splitlines() if 'a.B.c()' in line][0]
        self.assertIn('`a.B.c() -> void \\| a.B.d() -> void`', row)
        self.assertEqual(6, len(re.findall(r'(?<!\\)\|', row)))

    def test_missing_or_malformed_inputs_fail(self):
        """Missing APK, mapping or DEX, and DEX without an R8 marker, all fail rather than pass."""
        fixture = DexFixture(self.root)
        fixture.mapping.unlink()
        self.assertEqual(2, fixture.run()[0])
        fixture = DexFixture(self.root)
        fixture.apk.unlink()
        self.assertEqual(2, fixture.run()[0])
        fixture = DexFixture(self.root)
        with zipfile.ZipFile(fixture.apk, 'w') as archive:
            archive.writestr('resources.arsc', b'')
        self.assertEqual(2, fixture.run()[0])
        fixture = DexFixture(self.root)
        with zipfile.ZipFile(fixture.apk, 'w') as archive:
            archive.writestr('classes.dex', build_dex(sample_classes()))
        code, _stdout, stderr = fixture.run()
        self.assertEqual(2, code)
        self.assertIn('no R8 marker', stderr)
        abstract_only = [('La;', [('a', [], 'V', None, 0, False)])]
        self.assertEqual(2, DexFixture(self.root, classes=abstract_only).run()[0])
        variants = {'devZapstoreBenchmarkRelease': budget('benchmark', 1000, 100)}
        unmarked = DexFixture(self.root, variants=variants, marker='plain string')
        self.assertEqual(2, unmarked.run('devZapstoreBenchmarkRelease', with_mapping=False)[0])

    def test_mapping_with_another_min_api_fails(self):
        """The map's min_api must equal the R8 marker's, or it describes another build."""
        fixture = DexFixture(self.root, mapping=SAMPLE_MAPPING.replace('# min_api: 30', '# min_api: 31'))
        code, _stdout, stderr = fixture.run()
        self.assertEqual(2, code)
        self.assertIn('min_api', stderr)

    def test_incomplete_or_unsafe_budget_configs_fail(self):
        """Unknown variants, extra keys, budgets at ART's limit and unbound bases are rejected."""
        fixture = DexFixture(self.root)
        self.assertEqual(2, fixture.run('stagingZapstoreRelease')[0])
        entries = [
            {**budget('shipping', 1000, 100), 'note': 'x'},
            budget('shipping', ART_PATHOLOGICAL_LIMIT, 100),
            budget('shipping', 1000, 0),
            budget('release', 1000, 100),
            {**budget('shipping', 1000, 100), 'basis': {**budget('shipping', 1, 1)['basis'], 'source_sha': 'HEAD'}},
        ]
        for entry in entries:
            with self.subTest(entry):
                fixture.write_config({'productionZapstoreRelease': entry})
                self.assertEqual(2, fixture.run()[0])

    def test_source_sha_must_be_a_full_commit(self):
        """Provenance cannot be recorded against a branch name or abbreviated commit."""
        fixture = DexFixture(self.root)
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            main(['--apk', str(fixture.apk), '--mapping', str(fixture.mapping), '--variant', 'x',
                  '--source-sha', 'master', '--config', str(fixture.config)])


class CommittedBudgetTest(unittest.TestCase):
    """The committed config covers exactly the variants CI measures, each with a sound budget."""

    def test_benchmark_budget_only_warns_and_always_keeps_its_report(self):
        """A D8-only benchmark size never fails the required build check, and its report survives a pass."""
        workflow = (REPO_ROOT / '.github/workflows/android-ci.yml').read_text(encoding='utf-8')
        steps = {step.split('\n', 1)[0]: step for step in workflow.split('\n      - name: ')[1:]}
        check = steps['Check benchmark DEX method budget']
        self.assertIn('\n        id: dex_budget_benchmark\n', check)
        self.assertIn('\n        continue-on-error: true\n', check)
        self.assertIn('--variant devZapstoreBenchmarkRelease', check)
        warn = steps['Warn on benchmark DEX method budget']
        self.assertIn("always() && matrix.phase == 'baseline' && steps.dex_budget_benchmark.outcome == 'failure'", warn)
        self.assertIn('::warning', warn)
        self.assertNotIn('exit 1', warn)
        self.assertIn("always() && matrix.phase == 'baseline'", steps['Upload benchmark DEX method report'])

    def test_every_measured_variant_has_a_reviewed_budget_of_the_right_kind(self):
        """Workflow --variant arguments and config entries match, and kinds follow the build type."""
        workflows = ''.join(path.read_text(encoding='utf-8')
                            for path in (REPO_ROOT / '.github/workflows').glob('*.yml'))
        measured = set(re.findall(r'--variant (\w+)', workflows))
        config = json.loads((REPO_ROOT / 'config/dex-method-budgets.json').read_text(encoding='utf-8'))
        self.assertEqual({'productionZapstoreRelease', 'stagingZapstoreRelease', 'devZapstoreBenchmarkRelease'},
                         measured)
        self.assertEqual(measured, set(config['variants']))
        for variant in measured:
            entry = load_config(REPO_ROOT / 'config/dex-method-budgets.json', variant)
            expected = 'benchmark' if variant.endswith('BenchmarkRelease') else 'shipping'
            self.assertEqual(expected, entry['kind'], variant)
            self.assertLess(entry['basis']['largest_insns_code_units'], entry['max_insns_code_units'], variant)
            self.assertLess(entry['basis']['largest_registers'], entry['max_registers'], variant)


if __name__ == '__main__':
    unittest.main()
