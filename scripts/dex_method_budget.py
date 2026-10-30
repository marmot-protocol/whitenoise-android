#!/usr/bin/env python3
"""Report DEX method sizes from an existing APK and enforce reviewed per-variant budgets; never compile it."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import sys
import zipfile
import zlib

# ART's optimizing compiler, both dex2oat and the JIT, refuses any method whose
# code_item instruction or register count reaches UINT16_MAX / 4. That method
# then runs interpreted forever, with no JIT fallback on devices that disable it.
# See Compiler::IsPathologicalCase in AOSP art/compiler/compiler.cc.
ART_PATHOLOGICAL_LIMIT = 0xFFFF // 4
SUPPORTED_DEX_VERSIONS = {b'035', b'037', b'038', b'039', b'040'}
DEX_HEADER_SIZE = 0x70
DEX_ENDIAN_CONSTANT = 0x12345678
MAX_DEX_BYTES = 256 * 1024 * 1024
VARIANT_KINDS = {'shipping', 'benchmark'}
VARIANT_KEYS = {'kind', 'max_insns_code_units', 'max_registers', 'basis'}
BASIS_KEYS = {'source_sha', 'artifact', 'largest_insns_code_units', 'largest_registers'}
REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_CONFIG = REPO_ROOT / 'config/dex-method-budgets.json'
APP_METADATA = 'META-INF/com/android/build/gradle/app-metadata.properties'
GITHUB_PROVENANCE = ('GITHUB_REPOSITORY', 'GITHUB_WORKFLOW', 'GITHUB_JOB', 'GITHUB_EVENT_NAME',
                     'GITHUB_REF', 'GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT')
PRIMITIVE_DESCRIPTORS = {'void': 'V', 'boolean': 'Z', 'byte': 'B', 'char': 'C', 'short': 'S',
                         'int': 'I', 'long': 'J', 'float': 'F', 'double': 'D'}
CLASS_LINE = re.compile(r'(\S+) -> (\S+):')
METHOD_LINE = re.compile(r' {4}(?:(\d+:\d+):)?(\S+) ([^\s(]+)\(([^)]*)\)(?::\d+(?::\d+)?)? -> (\S+)')
EXIT_VIOLATION = 1
EXIT_INPUT = 2


class BudgetInputError(ValueError):
    """A missing, malformed or mismatched input; the check must fail instead of passing."""


def read_uleb128(data, offset):
    """Decode one unsigned LEB128 value of at most five bytes and return it with the next offset."""
    result = 0
    for index in range(5):
        if offset >= len(data):
            raise BudgetInputError('truncated uleb128 value')
        byte = data[offset]
        offset += 1
        result |= (byte & 0x7F) << (7 * index)
        if byte < 0x80:
            if result > 0xFFFFFFFF:
                raise BudgetInputError('uleb128 value exceeds 32 bits')
            return result, offset
    raise BudgetInputError('uleb128 value is longer than five bytes')


def decode_mutf8(raw):
    """Decode Modified UTF-8, including its two-byte NUL and surrogate-pair encoding."""
    try:
        text = raw.replace(b'\xc0\x80', b'\x00').decode('utf-8', 'surrogatepass')
    except UnicodeDecodeError as error:
        raise BudgetInputError('DEX string is not Modified UTF-8') from error
    try:
        return text.encode('utf-16', 'surrogatepass').decode('utf-16')
    except UnicodeDecodeError:
        return text


class DexFile:
    """Bounds-checked reader for the identifiers and code_items of one DEX file."""

    def __init__(self, data, name):
        """Validate the header, checksum and table bounds before any method is read."""
        self.data = data
        self.name = name
        if len(data) < DEX_HEADER_SIZE:
            raise self.error('shorter than the DEX header')
        if data[:4] != b'dex\n' or data[7] != 0:
            raise self.error('missing DEX magic')
        if data[4:7] not in SUPPORTED_DEX_VERSIONS:
            raise self.error(f'unsupported DEX version {data[4:7].decode("ascii", "replace")}; '
                             'extend the parser before trusting its sizes')
        if zlib.adler32(data[12:]) != self.u4(8):
            raise self.error('checksum does not match its contents')
        (file_size, header_size, endian_tag, _link_size, _link_off, _map_off,
         self.string_count, self.string_offset, self.type_count, self.type_offset,
         self.proto_count, self.proto_offset, _field_count, _field_offset,
         self.method_count, self.method_offset, self.class_count, self.class_offset,
         _data_size, _data_offset) = struct.unpack_from('<20I', data, 32)
        if file_size != len(data) or header_size != DEX_HEADER_SIZE or endian_tag != DEX_ENDIAN_CONSTANT:
            raise self.error('header size, file size or endian tag is invalid')
        for count, offset, item_size in ((self.string_count, self.string_offset, 4),
                                         (self.type_count, self.type_offset, 4),
                                         (self.proto_count, self.proto_offset, 12),
                                         (self.method_count, self.method_offset, 8),
                                         (self.class_count, self.class_offset, 32)):
            if count and (offset < DEX_HEADER_SIZE or offset + count * item_size > len(data)):
                raise self.error('an identifier table lies outside the file')
        self.strings = {}

    def error(self, message):
        """Name this DEX file in a parse failure."""
        return BudgetInputError(f'{self.name}: {message}')

    def u2(self, offset):
        """Read a little-endian u2 that must lie inside the file."""
        if offset < 0 or offset + 2 > len(self.data):
            raise self.error('read past the end of the file')
        return struct.unpack_from('<H', self.data, offset)[0]

    def u4(self, offset):
        """Read a little-endian u4 that must lie inside the file."""
        if offset < 0 or offset + 4 > len(self.data):
            raise self.error('read past the end of the file')
        return struct.unpack_from('<I', self.data, offset)[0]

    def string_bytes(self, index):
        """Return the raw Modified UTF-8 bytes of one string_id."""
        if index >= self.string_count:
            raise self.error(f'string index {index} is out of range')
        offset = self.u4(self.string_offset + index * 4)
        _length, start = read_uleb128(self.data, offset)
        end = self.data.find(b'\0', start)
        if end < 0:
            raise self.error('string data is not NUL-terminated')
        return self.data[start:end]

    def string(self, index):
        """Decode one string_id once and cache it."""
        if index not in self.strings:
            self.strings[index] = decode_mutf8(self.string_bytes(index))
        return self.strings[index]

    def type_descriptor(self, index):
        """Return a type_id's descriptor, such as Lcom/example/Foo;."""
        if index >= self.type_count:
            raise self.error(f'type index {index} is out of range')
        return self.string(self.u4(self.type_offset + index * 4))

    def proto_descriptor(self, index):
        """Return a proto_id as a JVM method descriptor such as (ILjava/lang/String;)V."""
        if index >= self.proto_count:
            raise self.error(f'proto index {index} is out of range')
        base = self.proto_offset + index * 12
        returns = self.type_descriptor(self.u4(base + 4))
        parameters_offset = self.u4(base + 8)
        parameters = []
        if parameters_offset:
            if parameters_offset % 4:
                raise self.error('a parameter type_list is misaligned')
            parameters = [self.type_descriptor(self.u2(parameters_offset + 4 + 2 * item))
                          for item in range(self.u4(parameters_offset))]
        return '(' + ''.join(parameters) + ')' + returns

    def markers(self):
        """Return every D8/R8/L8 compilation marker embedded in the string table."""
        found = []
        for index in range(self.string_count):
            raw = self.string_bytes(index)
            if raw[:2] == b'~~' and raw[2:5] in (b'R8{', b'D8{', b'L8{'):
                try:
                    found.append((raw[2:4].decode('ascii'), json.loads(decode_mutf8(raw[4:]))))
                except json.JSONDecodeError as error:
                    raise self.error('a compilation marker is not valid JSON') from error
        return found

    def code_item_sizes(self, offset):
        """Return (insns_size in 16-bit code units, registers_size) for one code_item."""
        if offset % 4 or offset < DEX_HEADER_SIZE or offset + 16 > len(self.data):
            raise self.error(f'code_item at {offset} is misaligned or outside the file')
        insns = self.u4(offset + 12)
        if offset + 16 + insns * 2 > len(self.data):
            raise self.error(f'code_item at {offset} runs past the end of the file')
        return insns, self.u2(offset)

    def methods(self):
        """Yield every defined method with code as a dict of identity and code_item sizes."""
        for class_index in range(self.class_count):
            base = self.class_offset + class_index * 32
            class_data = self.u4(base + 24)
            if not class_data:
                continue
            offset = class_data
            counts = []
            for _ in range(4):
                value, offset = read_uleb128(self.data, offset)
                counts.append(value)
            for _ in range(2 * (counts[0] + counts[1])):
                _value, offset = read_uleb128(self.data, offset)
            for method_total in counts[2:]:
                method_index = 0
                for _ in range(method_total):
                    diff, offset = read_uleb128(self.data, offset)
                    _flags, offset = read_uleb128(self.data, offset)
                    code_offset, offset = read_uleb128(self.data, offset)
                    method_index += diff
                    if method_index >= self.method_count:
                        raise self.error(f'method index {method_index} is out of range')
                    if not code_offset:
                        continue
                    insns, registers = self.code_item_sizes(code_offset)
                    method_base = self.method_offset + method_index * 8
                    yield {
                        'dex': self.name,
                        'class': self.type_descriptor(self.u2(method_base)),
                        'name': self.string(self.u4(method_base + 4)),
                        'proto': self.proto_descriptor(self.u2(method_base + 2)),
                        'insns_code_units': insns,
                        'registers': registers,
                    }


def sha256_bytes(data):
    """Hex SHA-256 of an in-memory artifact."""
    return hashlib.sha256(data).hexdigest()


def sha256_file(path):
    """Hex SHA-256 of a file read in chunks."""
    digest = hashlib.sha256()
    with open(path, 'rb') as stream:
        for chunk in iter(lambda: stream.read(1 << 20), b''):
            digest.update(chunk)
    return digest.hexdigest()


def dex_entry_order(name):
    """Sort classes.dex, classes2.dex, … numerically as ART loads them."""
    number = name[len('classes'):-len('.dex')]
    return int(number or 1)


def read_apk(apk):
    """Parse every classesN.dex in an APK and return methods, per-DEX provenance and compiler markers."""
    try:
        archive = zipfile.ZipFile(apk)
    except (OSError, zipfile.BadZipFile) as error:
        raise BudgetInputError(f'{apk}: not a readable APK') from error
    with archive:
        names = sorted((info for info in archive.infolist()
                        if re.fullmatch(r'classes\d*\.dex', info.filename)),
                       key=lambda info: dex_entry_order(info.filename))
        if not names:
            raise BudgetInputError(f'{apk}: contains no classes*.dex')
        if any(info.file_size > MAX_DEX_BYTES for info in names):
            raise BudgetInputError(f'{apk}: a DEX entry exceeds {MAX_DEX_BYTES} bytes')
        metadata = (archive.read(APP_METADATA).decode('utf-8', 'replace')
                    if APP_METADATA in archive.namelist() else '')
        methods, dex_files, markers = [], [], {}
        for info in names:
            data = archive.read(info.filename)
            dex = DexFile(data, info.filename)
            markers[info.filename] = dex.markers()
            dex_methods = list(dex.methods())
            methods.extend(dex_methods)
            dex_files.append({'name': info.filename, 'sha256': sha256_bytes(data),
                              'bytes': len(data), 'methods_with_code': len(dex_methods)})
    app_metadata = dict(line.split('=', 1) for line in metadata.splitlines() if '=' in line)
    return methods, dex_files, markers, app_metadata


def artifact_compiler(kind, markers, has_mapping):
    """Name the compiler that wrote the APK's DEX and check that the variant kind and mapping fit it.

    Shipping variants must be R8 output in every DEX, measured with the mapping from
    the same build. A benchmark variant may instead be plain D8 output, which keeps
    source names and therefore has no mapping.
    """
    tools = {tool for dex_markers in markers.values() for tool, _marker in dex_markers}
    if 'R8' in tools:
        unmarked = sorted(name for name, dex_markers in markers.items()
                          if not any(tool == 'R8' for tool, _marker in dex_markers))
        if unmarked:
            raise BudgetInputError(f'{", ".join(unmarked)}: no R8 marker beside R8 output in the same APK')
        if not has_mapping:
            raise BudgetInputError('R8 output needs --mapping from the same build to name its methods')
        return 'R8'
    if kind == 'shipping':
        raise BudgetInputError('no R8 marker; shipping budgets apply only to post-R8 output')
    if 'D8' not in tools:
        raise BudgetInputError('no D8 or R8 marker; cannot tell which compiler produced this DEX')
    if has_mapping:
        raise BudgetInputError('D8 output keeps source names and has no R8 mapping; omit --mapping')
    return 'D8'


def java_type_name(descriptor):
    """Spell a DEX type descriptor, such as [Ljava/lang/String;, as Java source does."""
    dimensions = len(descriptor) - len(descriptor.lstrip('['))
    element = descriptor[dimensions:]
    primitive = {code: name for name, code in PRIMITIVE_DESCRIPTORS.items()}.get(element)
    return (primitive or element[1:-1].replace('/', '.')) + '[]' * dimensions


def source_identity(method):
    """Name a method from unobfuscated D8 output, where residual names are the source names."""
    parameters, returns = method['proto'][1:].split(')')
    types = tuple(java_type_name(item) for item in re.findall(r'\[*(?:[ZBCSIJFD]|L[^;]+;)', parameters))
    return render_signature((java_type_name(returns), method['name'], types),
                            method['class'][1:-1].replace('/', '.'))


def java_type_descriptor(java_type, original_to_residual):
    """Translate a source type name from the R8 map to its residual DEX descriptor."""
    dimensions = 0
    while java_type.endswith('[]'):
        dimensions += 1
        java_type = java_type[:-2]
    if java_type in PRIMITIVE_DESCRIPTORS:
        element = PRIMITIVE_DESCRIPTORS[java_type]
    else:
        element = 'L' + original_to_residual.get(java_type, java_type).replace('.', '/') + ';'
    return '[' * dimensions + element


def read_mapping(path, wanted_classes):
    """Stream an R8 map: header fields, every class rename, and outer method frames for wanted classes.

    Inlined frames share a minified line range with the frame that contains them,
    innermost first, so the last line of each same-range run names the method that
    exists in the DEX. Each run is kept so a synthesized outer frame can be elided
    the way retrace does.
    """
    header, classes, methods = {}, {}, {}
    current = previous_key = last_original = None
    try:
        stream = open(path, encoding='utf-8')
    except OSError as error:
        raise BudgetInputError(f'{path}: R8 mapping is missing or unreadable') from error
    try:
        with stream:
            for line in stream:
                line = line.rstrip('\n')
                if not line.startswith(' '):
                    if line.startswith('#'):
                        field = re.fullmatch(r'# (compiler|compiler_version|min_api|pg_map_id): (\S+)', line)
                        if field:
                            header[field[1]] = field[2]
                        continue
                    match = CLASS_LINE.fullmatch(line)
                    if not match:
                        raise BudgetInputError(f'{path}: unrecognized mapping line: {line[:120]}')
                    classes[match[2]] = match[1]
                    current = match[2] if match[2] in wanted_classes else None
                    previous_key = last_original = None
                    continue
                if current is None:
                    continue
                stripped = line.strip()
                if stripped.startswith('# {'):
                    metadata = json.loads(stripped[2:])
                    if last_original is not None and metadata.get('id') == 'com.android.tools.r8.residualsignature':
                        last_original['residual'] = metadata.get('signature')
                    if last_original is not None and metadata.get('id') == 'com.android.tools.r8.synthesized':
                        last_original['synthesized'] = True
                    continue
                match = METHOD_LINE.fullmatch(line)
                if not match:
                    previous_key = last_original = None
                    continue
                minified_range, returns, name, parameters, residual_name = match.groups()
                key = (residual_name, minified_range)
                frame = {'returns': returns, 'name': name, 'parameters': tuple(filter(None, parameters.split(','))),
                         'residual': None, 'synthesized': False}
                runs = methods.setdefault((current, residual_name), [])
                if key == previous_key and minified_range is not None:
                    runs[-1].append(frame)
                else:
                    runs.append([frame])
                previous_key, last_original = key, frame
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise BudgetInputError(f'{path}: R8 mapping is not valid UTF-8 or has malformed metadata') from error
    if header.get('compiler') != 'R8' or 'pg_map_id' not in header:
        raise BudgetInputError(f'{path}: not an R8 mapping with a pg_map_id header')
    return header, classes, methods


def frame_signature(frame):
    """Key a mapped frame by its source return type, name and parameter types."""
    return frame['returns'], frame['name'], frame['parameters']


def render_signature(signature, owner):
    """Render a source method compactly; very long Compose parameter lists collapse to a count."""
    returns, name, parameters = signature
    qualified = name if '.' in name else f'{owner}.{name}'
    shown = ','.join(parameters) if len(parameters) <= 4 else f'{",".join(parameters[:3])},… {len(parameters)} params'
    return f'{qualified}({shown}) -> {returns}'


def original_identity(method, classes, original_to_residual, runs):
    """Name the source method behind one residual DEX method, listing every candidate when the map is ambiguous.

    R8 records a residual signature or synthesized marker once, after a method's
    first line range. A synthesized outer frame is elided in favour of the source
    frame it wraps, as retrace does.
    """
    residual_class = method['class'][1:-1].replace('/', '.')
    original_class = classes.get(residual_class, residual_class)
    frames = [frame for run in runs for frame in run]
    synthesized = {frame_signature(frame) for frame in frames if frame['synthesized']}
    residuals = {frame_signature(frame): frame['residual'] for frame in frames if frame['residual']}
    shown_by_outer = {}
    for run in runs:
        outer = frame_signature(run[-1])
        shown = next((frame_signature(frame) for frame in reversed(run)
                      if frame_signature(frame) not in synthesized), outer)
        shown_by_outer.setdefault(outer, set()).add(shown)

    def matches(outer):
        """Compare an outer frame with the DEX proto, by residual signature when R8 recorded one."""
        if outer in residuals:
            return residuals[outer] == method['proto']
        returns, _name, parameters = outer
        return ('(' + ''.join(java_type_descriptor(item, original_to_residual) for item in parameters) + ')' +
                java_type_descriptor(returns, original_to_residual)) == method['proto']

    def source_names(outers):
        """Prefer the source frames a synthesized method wraps over the synthesized frame itself."""
        shown = set().union(*(shown_by_outer[outer] for outer in outers)) if outers else set()
        return {signature for signature in shown if signature not in synthesized} or shown

    exact = source_names([outer for outer in shown_by_outer if matches(outer)])
    chosen = exact or source_names(list(shown_by_outer))
    if not chosen:
        return f'{original_class}.{method["name"]} (no mapping entry)'
    names = sorted(render_signature(signature, original_class) for signature in chosen)
    return ' | '.join(names) + ('' if exact or len(names) == 1 else ' (ambiguous)')


def load_config(path, variant):
    """Load the reviewed budget for one variant and reject incomplete or unsafe entries."""
    try:
        config = json.loads(Path(path).read_text(encoding='utf-8'))
    except (OSError, json.JSONDecodeError) as error:
        raise BudgetInputError(f'{path}: budget config is missing or not JSON') from error
    if config.get('schema') != 1 or not isinstance(config.get('variants'), dict):
        raise BudgetInputError(f'{path}: budget config must have schema 1 and a variants object')
    entry = config['variants'].get(variant)
    if not isinstance(entry, dict):
        raise BudgetInputError(f'{path}: no reviewed budget for variant {variant}')
    if set(entry) != VARIANT_KEYS or entry['kind'] not in VARIANT_KINDS:
        raise BudgetInputError(f'{path}: {variant} must define exactly {sorted(VARIANT_KEYS)} '
                               f'with kind in {sorted(VARIANT_KINDS)}')
    for key in ('max_insns_code_units', 'max_registers'):
        value = entry[key]
        if type(value) is not int or not 0 < value < ART_PATHOLOGICAL_LIMIT:
            raise BudgetInputError(f'{path}: {variant}.{key} must be a positive integer below '
                                   f'ART\'s {ART_PATHOLOGICAL_LIMIT} limit')
    basis = entry['basis']
    if (not isinstance(basis, dict) or set(basis) != BASIS_KEYS
            or not re.fullmatch(r'[0-9a-f]{40}', str(basis.get('source_sha')))):
        raise BudgetInputError(f'{path}: {variant}.basis must record {sorted(BASIS_KEYS)} '
                               'for the measured artifact')
    return entry


def require_variant_shape(kind, classes):
    """Keep shipping and benchmark measurements apart: only shipping variants are obfuscated."""
    renamed = sum(1 for residual, original in classes.items() if residual != original)
    if kind == 'shipping' and not renamed:
        raise BudgetInputError('a shipping budget was given an unobfuscated artifact; '
                               'benchmark sizes must never stand in for shipping sizes')
    if kind == 'benchmark' and renamed:
        raise BudgetInputError('a benchmark budget was given an obfuscated artifact; '
                               'pass the benchmarkRelease APK and mapping')


def require_bound_mapping(markers, header):
    """Prove the mapping was written by the same R8 run as every DEX in the APK."""
    map_ids = {marker.get('pg-map-id') for marker in markers}
    if map_ids != {header['pg_map_id']}:
        raise BudgetInputError(f'R8 map id {header["pg_map_id"]} does not match the DEX marker(s) '
                               f'{sorted(str(item) for item in map_ids)}')
    min_apis = {str(marker.get('min-api')) for marker in markers}
    if 'min_api' in header and min_apis != {header['min_api']}:
        raise BudgetInputError(f'R8 map min_api {header["min_api"]} does not match the DEX marker(s)')


def describe(method, identities, budget):
    """Render one method as a report row with headroom to the budget and to ART's limit."""
    key = (method['dex'], method['class'], method['name'], method['proto'])
    return {
        'dex': method['dex'],
        'residual': f'{method["class"]}->{method["name"]}{method["proto"]}',
        'original': identities.get(key, ''),
        'insns_code_units': method['insns_code_units'],
        'registers': method['registers'],
        'insns_budget_headroom': budget['max_insns_code_units'] - method['insns_code_units'],
        'insns_art_headroom': ART_PATHOLOGICAL_LIMIT - method['insns_code_units'],
    }


def build_report(args, budget, measured, header, identities):
    """Assemble the artifact-bound report and the list of budget violations."""
    methods, dex_files, markers, app_metadata, compiler = measured
    by_insns = sorted(methods, key=lambda item: (-item['insns_code_units'], item['dex'], item['class'],
                                                 item['name'], item['proto']))
    by_registers = sorted(methods, key=lambda item: (-item['registers'], item['dex'], item['class'],
                                                     item['name'], item['proto']))
    violations = [item for item in by_insns if item['insns_code_units'] > budget['max_insns_code_units']]
    violations += [item for item in by_registers if item['registers'] > budget['max_registers']
                   and item not in violations]
    provenance = {
        'variant': args.variant,
        'kind': budget['kind'],
        'source_sha': args.source_sha,
        'apk': {'path': str(args.apk), 'sha256': sha256_file(args.apk), 'bytes': Path(args.apk).stat().st_size},
        'dex_files': dex_files,
        'compiler': compiler,
        'dex_marker': next(marker for dex_markers in markers.values() for tool, marker in dex_markers
                           if tool == compiler),
        'mapping': ({'path': str(args.mapping), 'sha256': sha256_file(args.mapping), **header}
                    if header else None),
        'android_gradle_plugin': app_metadata.get('androidGradlePluginVersion', 'unknown'),
        'budget_config': {'path': str(args.config), 'sha256': sha256_file(args.config)},
        'github': {key.lower(): os.environ[key] for key in GITHUB_PROVENANCE if os.environ.get(key)},
    }
    return {
        'schema': 1,
        'provenance': provenance,
        'art_pathological_limit': ART_PATHOLOGICAL_LIMIT,
        'budget': budget,
        'methods_with_code': len(methods),
        'largest_by_insns': [describe(item, identities, budget) for item in by_insns[:args.top]],
        'largest_by_registers': [describe(item, identities, budget) for item in by_registers[:args.top]],
        'violations': [describe(item, identities, budget) for item in violations],
    }


def markdown_summary(report):
    """Render the largest methods and any violations as a GitHub step summary table."""
    provenance, budget = report['provenance'], report['budget']
    mapping = provenance['mapping']
    compiler = (f'R8 {mapping.get("compiler_version", "?")}, map id `{mapping["pg_map_id"][:16]}…`'
                if mapping else f'{provenance["compiler"]} {provenance["dex_marker"].get("version", "?")}, no mapping')
    lines = [f'### DEX method sizes: {provenance["variant"]} ({provenance["kind"]})', '',
             f'Source `{provenance["source_sha"]}`, APK sha256 `{provenance["apk"]["sha256"]}`, {compiler}.', '',
             f'Budget: {budget["max_insns_code_units"]} code units and {budget["max_registers"]} registers '
             f'(ART skips compilation at {report["art_pathological_limit"]}).', '',
             '| Code units | Registers | Method | Residual | DEX |', '| ---: | ---: | --- | --- | --- |']
    for row in report['largest_by_insns'][:10]:
        lines.append(f'| {row["insns_code_units"]} | {row["registers"]} | `{row["original"]}` | '
                     f'`{row["residual"]}` | {row["dex"]} |')
    if report['violations']:
        lines += ['', f'**{len(report["violations"])} method(s) exceed the reviewed budget.**']
    return '\n'.join(lines) + '\n'


def parse_args(argv):
    """Parse the artifact, mapping, variant and provenance arguments."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, required=True, help='optimized APK that was already built')
    parser.add_argument('--mapping', type=Path, help='R8 mapping.txt from the same build; omitted for D8 output')
    parser.add_argument('--variant', required=True, help='variant name as keyed in the budget config')
    parser.add_argument('--source-sha', required=True, help='40-hex commit the APK was built from')
    parser.add_argument('--config', type=Path, default=DEFAULT_CONFIG)
    parser.add_argument('--report-dir', type=Path)
    parser.add_argument('--top', type=int, default=25)
    args = parser.parse_args(argv)
    if not re.fullmatch(r'[0-9a-f]{40}', args.source_sha):
        parser.error('--source-sha must be a full 40-hex commit')
    if args.top < 1:
        parser.error('--top must be positive')
    return args


def run(args):
    """Measure, report and enforce one variant's budget; return the process exit code."""
    budget = load_config(args.config, args.variant)
    if not args.apk.is_file():
        raise BudgetInputError(f'{args.apk}: APK not found')
    methods, dex_files, markers, app_metadata = read_apk(args.apk)
    if not methods:
        raise BudgetInputError(f'{args.apk}: no method has a code_item')
    compiler = artifact_compiler(budget['kind'], markers, args.mapping is not None)
    by_insns = sorted(methods, key=lambda item: -item['insns_code_units'])[:args.top]
    by_registers = sorted(methods, key=lambda item: -item['registers'])[:args.top]
    flagged = [item for item in methods if item['insns_code_units'] > budget['max_insns_code_units']
               or item['registers'] > budget['max_registers']]
    named = by_insns + by_registers + flagged
    identities = {}
    header = None
    if compiler == 'D8':
        for item in named:
            identities[(item['dex'], item['class'], item['name'], item['proto'])] = source_identity(item)
    else:
        wanted = {item['class'][1:-1].replace('/', '.') for item in named}
        header, classes, runs = read_mapping(args.mapping, wanted)
        require_bound_mapping([marker for dex_markers in markers.values() for tool, marker in dex_markers
                               if tool == 'R8'], header)
        require_variant_shape(budget['kind'], classes)
        original_to_residual = {original: residual for residual, original in classes.items()}
        for item in named:
            residual_class = item['class'][1:-1].replace('/', '.')
            identities[(item['dex'], item['class'], item['name'], item['proto'])] = original_identity(
                item, classes, original_to_residual, runs.get((residual_class, item['name']), []))
    report = build_report(args, budget, (methods, dex_files, markers, app_metadata, compiler), header, identities)
    summary = markdown_summary(report)
    if args.report_dir:
        args.report_dir.mkdir(parents=True, exist_ok=True)
        (args.report_dir / f'dex-method-sizes-{args.variant}.json').write_text(
            json.dumps(report, indent=2) + '\n', encoding='utf-8')
        (args.report_dir / f'dex-method-sizes-{args.variant}.md').write_text(summary, encoding='utf-8')
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a', encoding='utf-8') as stream:
            stream.write(summary)
    print(summary)
    for row in report['violations']:
        print(f'error: {row["original"]} [{row["residual"]} in {row["dex"]}] has '
              f'{row["insns_code_units"]} code units / {row["registers"]} registers; budget '
              f'{budget["max_insns_code_units"]} / {budget["max_registers"]}. Split the method or '
              'review a new budget (docs/performance.md#dex-method-size-budgets).', file=sys.stderr)
    return EXIT_VIOLATION if report['violations'] else 0


def main(argv=None):
    """Command-line entry point: input errors and budget violations both fail."""
    args = parse_args(argv)
    try:
        return run(args)
    except BudgetInputError as error:
        print(f'error: {error}', file=sys.stderr)
        return EXIT_INPUT


if __name__ == '__main__':
    sys.exit(main())
