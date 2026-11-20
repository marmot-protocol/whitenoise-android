"""Check Android translations and list untranslated work by priority."""

import argparse
from collections import Counter
from dataclasses import dataclass
import json
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
MAIN = Path("app/src/main/res")
PREVIEW = Path("app/src/preview/res")
LOCALE = re.compile(r"values-(?:[a-z]{2,3}(?:-r[A-Z]{2})?|b\+[A-Za-z0-9]+(?:\+[A-Za-z0-9]+)*)")
TYPES = {"string", "plurals", "string-array"}
QUANTITIES = {"zero", "one", "two", "few", "many", "other"}
# Android format strings use Java Formatter. Capture positions and conversions,
# excluding %% and %n, which consume no argument.
FORMAT = re.compile(r"%(?:(\d+)\$)?([-#+0,(<]*)(\d*(?:\.\d+)?)([tT][a-zA-Z]|[a-zA-Z%])")


@dataclass
class Entry:
    file: Path
    node: ET.Element

    @property
    def translatable(self):
        return self.node.get("translatable") != "false"


def resource_type(node):
    """Return the Android resource type, including typed <item> aliases."""
    return node.get("type") if node.tag == "item" else node.tag


def entries(paths):
    result = {}
    for path in sorted(paths):
        root = ET.parse(path).getroot()
        if root.tag != "resources":
            raise ValueError(f"{path}: expected <resources>")
        for node in root:
            if resource_type(node) not in TYPES:
                continue
            name = node.get("name")
            if not name or name in result:
                raise ValueError(f"{path}: missing or duplicate resource name: {name}")
            result[name] = Entry(path, node)
    return result


def arguments(node, *, strict=False):
    explicit = set()
    implicit = 0
    previous = None
    value = "".join(node.itertext())
    end = 0
    for match in FORMAT.finditer(value):
        if strict and "%" in value[end:match.start()]:
            raise ValueError("invalid percent sequence in formatted text")
        end = match.end()
        position, flags, width, conversion = match.groups()
        if conversion in {"%", "n"}:
            if strict and match.group() not in {"%%", "%n"}:
                raise ValueError("invalid percent sequence in formatted text")
            continue
        explicit_index = int(position) if position is not None else None
        if strict and explicit_index is not None and not 1 <= explicit_index <= 2_147_483_647:
            raise ValueError("invalid argument index in formatted text")
        if "<" in flags:
            index = previous
        elif explicit_index is not None:
            index = explicit_index
        else:
            implicit += 1
            index = implicit
        if strict and (index is None or index < 1 or flags.count("<") > 1):
            raise ValueError("invalid argument index in formatted text")
        explicit.add((index, flags.replace("<", ""), width, conversion))
        previous = index
    if strict and "%" in value[end:]:
        raise ValueError("invalid percent sequence in formatted text")
    return explicit


XLIFF_G = "{urn:oasis:names:tc:xliff:document:1.2}g"


def markup(node, *, ignore_wrapped_arguments=False):
    """Count child tags; optionally skip xliff:g wrappers around format arguments."""
    return Counter(
        child.tag for child in node.iter()
        if child is not node
        and not (ignore_wrapped_arguments and child.tag == XLIFF_G and arguments(child))
    )


def plural_problem(node):
    quantities = [item.get("quantity") for item in node]
    if (
        resource_type(node) == "plurals"
        and ("other" not in quantities
             or len(set(quantities)) != len(quantities)
             or set(quantities) - QUANTITIES
             or any(item.tag != "item" for item in node))
    ):
        return "expected unique Android plural quantities, including other"
    return None


def check_entry(source, target):
    original, translated = source.node, target.node
    kind = resource_type(original)
    if not source.translatable:
        return "source is marked non-translatable"
    if kind != resource_type(translated):
        return f"expected {kind}, found {resource_type(translated)}"
    if original.get("formatted", "true") != translated.get("formatted", "true"):
        return "formatted attribute differs from the source"
    if kind == "plurals":
        if problem := plural_problem(translated):
            return problem
        source_items = {item.get("quantity"): item for item in original}
        pairs = [(source_items.get(item.get("quantity"), source_items["other"]), item) for item in translated]
        # Languages differ on which forms spell out the count, so allow any English form's arguments.
        plural_arguments = set().union(*(arguments(item) for item in original))
        # Each form keeps its matching English form's arguments, except the count, which may be spelled out.
        integers = {
            argument[0] for item in original for argument in arguments(item)
            if argument[3] in {"d", "i"} and argument[0] is not None
        }
        if original.get("name") == "attachment_collection_limit_result":
            # ConversationImportedMediaSlots uses rejected (argument 3) as the quantity.
            optional_indices = {3}
        else:
            optional_indices = integers if len(integers) == 1 else set()
    elif kind == "string-array":
        if len(original) != len(translated) or any(item.tag != "item" for item in translated):
            return "array items differ from the source"
        pairs = zip(original, translated)
    else:
        pairs = [(original, translated)]
    for before, after in pairs:
        if not "".join(after.itertext()).strip():
            return "empty translation"
        if markup(before, ignore_wrapped_arguments=kind == "plurals") != markup(
            after, ignore_wrapped_arguments=kind == "plurals"
        ):
            return "markup tags differ from the source"
        if original.get("formatted") != "false":
            expected = plural_arguments if kind == "plurals" else arguments(before)
            try:
                actual = arguments(after, strict=bool(expected) or bool(arguments(after)))
            except ValueError as error:
                return str(error)
            if kind == "plurals":
                required = {argument[0] for argument in arguments(before)} - optional_indices
                ok = required <= {argument[0] for argument in actual} and actual <= expected
            else:
                ok = actual == expected
            if not ok:
                return "format arguments differ from the source"
    return None


def priorities(source, rules):
    required = {}
    for flow, selectors in rules.items():
        for selector in selectors:
            names = (
                [name for name in source if name.startswith(selector[:-1])]
                if selector.endswith("*")
                else [selector] if selector in source else []
            )
            if not names:
                raise ValueError(f"{flow}: no resource matches {selector}")
            for name in names:
                if not source[name].translatable:
                    continue
                if name in required:
                    raise ValueError(f"{name}: assigned to multiple launch flows")
                required[name] = flow
    return required


def content(node):
    return node.tag, node.attrib, node.text, [(content(child), child.tail) for child in node]


def signature(node):
    """Identify a resource by type and content, ignoring typed-alias spelling."""
    attributes = {key: value for key, value in node.attrib.items() if key != "type"}
    return resource_type(node), attributes, node.text, [(content(child), child.tail) for child in node]


def base_resources(base):
    return subprocess.check_output(
        ["git", "ls-tree", "-r", "--name-only", base, "--", str(MAIN)],
        cwd=ROOT, text=True,
    ).splitlines()


def shipped_locales(paths):
    return {
        Path(path).parent.name
        for path in paths
        if path.endswith(".xml") and LOCALE.fullmatch(Path(path).parent.name)
    }


def source_changes(base, paths, source):
    previous = {}
    for path in paths:
        if Path(path).parent != MAIN / "values" or not path.endswith(".xml"):
            continue
        contents = subprocess.check_output(["git", "show", f"{base}:{path}"], cwd=ROOT)
        for node in ET.fromstring(contents):
            if resource_type(node) in TYPES:
                previous[node.get("name")] = node
    return {
        name
        for name, entry in source.items()
        if entry.translatable and (
            name not in previous or signature(previous[name]) != signature(entry.node)
        )
    }


def locale_identity(tag):
    """Return one identity for equivalent qualifiers, such as fr-rCA and b+fr+CA."""
    parts = tag.removeprefix("b+").split("+") if tag.startswith("b+") else tag.replace("-r", "+", 1).split("+")
    return tuple(part.lower() for part in parts)


def scan(main, preview, rules, previously_shipped=()):
    source = entries((main / "values").glob("*.xml"))
    required = priorities(source, rules)
    broken = {name: plural_problem(entry.node) for name, entry in source.items() if plural_problem(entry.node)}
    catalogs = {}
    seen = set()
    present = shipped_locales(str(file) for file in main.glob("values-*/*.xml"))
    errors = [f"{source[name].file}: {name}: {problem}" for name, problem in broken.items()]
    errors += [
        f"{main / directory}: shipped locale was removed from the main resources"
        for directory in sorted(set(previously_shipped) - present)
    ]
    for root in (main, preview):
        for directory in sorted(root.glob("values-*")):
            if not directory.is_dir() or not LOCALE.fullmatch(directory.name):
                continue
            tag = directory.name.removeprefix("values-")
            localized = entries(directory.glob("*.xml"))
            if locale_identity(tag) in seen:
                errors.append(f"{directory}: locale already exists in the main resources")
                continue
            seen.add(locale_identity(tag))
            catalogs[tag] = localized
            for name, target in localized.items():
                problem = (
                    None if name in broken
                    else check_entry(source[name], target) if name in source else "unknown resource"
                )
                if problem:
                    errors.append(f"{target.file}: {name}: {problem}")
            if root == main:
                for name, entry in sorted(source.items()):
                    if entry.translatable and name not in localized:
                        errors.append(f"{directory}: {name}: missing from shipped locale")
    return source, required, catalogs, errors


def worklist_order(pair, required, rules):
    """Sort launch keys by priorities-file flow order, then explanation files and file name."""
    name, entry = pair
    flows = list(rules)
    flow = flows.index(required[name]) if name in required else len(flows)
    return name not in required, flow, entry.file.name.startswith("explanation"), entry.file.name, name


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--locale", help="Android qualifier after values-, such as ja or b+zh+Hant")
    parser.add_argument("--base", help="Git revision to compare English source with")
    args = parser.parse_args()
    try:
        rules = json.loads((ROOT / "docs/translation-priorities.json").read_text())
        paths = base_resources(args.base) if args.base else []
        source, required, catalogs, errors = scan(ROOT / MAIN, ROOT / PREVIEW, rules, shipped_locales(paths))
        changed = source_changes(args.base, paths, source) if args.base else set()
    except (OSError, ValueError, ET.ParseError, subprocess.CalledProcessError) as error:
        print(error, file=sys.stderr)
        return 1

    if args.locale:
        if not LOCALE.fullmatch(f"values-{args.locale}"):
            parser.error("use an Android locale qualifier, for example ja or b+zh+Hant")
        localized = catalogs.get(args.locale, {})
        for name, entry in sorted(source.items(), key=lambda pair: worklist_order(pair, required, rules)):
            if entry.translatable and (name not in localized or name in changed):
                tier = "1 launch" if name in required else (
                    "3 supplemental" if entry.file.name.startswith("explanation") else "2 next"
                )
                status = "missing" if name not in localized else "source changed"
                print(f"{tier}\t{required.get(name, entry.file.stem)}\t{entry.file.name}\t{name}\t{status}")
    else:
        for tag, localized in catalogs.items():
            missing = len(required.keys() - localized.keys())
            print(f"{tag}: {missing} launch keys missing")
        for name in sorted(changed):
            has = [tag for tag, localized in catalogs.items() if name in localized]
            lacks = [tag for tag in catalogs if tag not in has]
            print(
                f"English source changed: {source[name].file.name}/{name} "
                f"(in: {', '.join(has) or 'none'}; needs translation: {', '.join(lacks) or 'none'})"
            )
    for error in errors:
        print(error, file=sys.stderr)
    return bool(errors)


if __name__ == "__main__":
    sys.exit(main())
