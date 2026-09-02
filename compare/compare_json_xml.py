#!/usr/bin/env python3
"""
JSON <-> XML comparator for valid, well-formed files.

Two files make up the tool: this script, and the mapping that describes how the
two schemas line up (mapping.yaml by default). No field names are hard-coded
here, so a different document type only needs a different mapping file.

Single pair:
    python compare_json_xml.py --json deal.json --xml deal.xml --out report.html

Directory batch (pairs *.json with *.xml by filename stem):
    python compare_json_xml.py --batch-dir ./data --out-dir ./reports

Explicit batch, from a CSV with the columns json,xml[,name]:
    python compare_json_xml.py --manifest pairs.csv --out-dir ./reports

Another mapping (.yaml needs PyYAML, .json needs nothing):
    python compare_json_xml.py --json a.json --xml b.xml --mapping other.yaml

Each run writes report.html plus report.csv and report.json next to it. The
report columns are path, status, jsonValue and xmlMappedValue, along with the
resolved path on each side and a reason for the status.
"""

from __future__ import annotations

import argparse
import csv
import html
import json
import re
import sys
import xml.etree.ElementTree as ET
from collections import Counter
from collections.abc import Iterator
from dataclasses import dataclass, asdict
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import Any




# =============================================================================
# PATH ACCESS
# =============================================================================

MISSING = object()
JSON_STEP = re.compile(r"([^.()\[\]]+)|\[(\*|-?\d+)\]")


def json_values(data: Any, path: str) -> list[Any]:
    steps: list[Any] = []
    for match in JSON_STEP.finditer(path):
        token = match.group(1) if match.group(1) is not None else match.group(2)
        steps.append(int(token) if token != "*" and str(token).lstrip("-").isdigit() else token)

    values = [data]
    for step in steps:
        next_values: list[Any] = []
        for value in values:
            if step == "*" and isinstance(value, list):
                next_values.extend(value)
            elif isinstance(step, int) and isinstance(value, list):
                if -len(value) <= step < len(value):
                    next_values.append(value[step])
            elif isinstance(step, str) and isinstance(value, dict) and step in value:
                next_values.append(value[step])
        values = next_values
    return values


def json_value(data: Any, path_or_paths: str | list[str]) -> tuple[Any, str]:
    paths = [path_or_paths] if isinstance(path_or_paths, str) else path_or_paths
    for path in paths:
        values = json_values(data, path)
        if values:
            return values[0], path
    return MISSING, paths[0] if paths else ""


def normalize_xml_names(root: ET.Element) -> None:
    """Remove namespace URIs in-place so mappings stay readable."""
    for element in root.iter():
        if isinstance(element.tag, str) and "}" in element.tag:
            element.tag = element.tag.rsplit("}", 1)[1]
        for key in list(element.attrib):
            if "}" in key:
                element.attrib[key.rsplit("}", 1)[1]] = element.attrib.pop(key)


def split_xml_path(path: str) -> tuple[str, str | None]:
    if path.startswith("@"):
        return ".", path[1:]
    attribute = None
    if "/@" in path:
        path, attribute = path.rsplit("/@", 1)
    if path.startswith("//"):
        path = "." + path
    elif not path.startswith("."):
        path = "./" + path
    return path or ".", attribute


def xml_elements(node: ET.Element, path: str) -> list[ET.Element]:
    element_path, _ = split_xml_path(path)
    return [node] if element_path == "." else node.findall(element_path)


def xml_value(node: ET.Element, path_or_paths: str | list[str]) -> tuple[Any, str]:
    paths = [path_or_paths] if isinstance(path_or_paths, str) else path_or_paths
    for path in paths:
        element_path, attribute = split_xml_path(path)
        found = [node] if element_path == "." else node.findall(element_path)
        if not found:
            continue
        if attribute is None:
            text = (found[0].text or "").strip()
            return (text if text else True), path
        if attribute in found[0].attrib:
            return found[0].attrib[attribute], path
    return MISSING, paths[0] if paths else ""


# =============================================================================
# COMPARISON
# =============================================================================

MATCH = "MATCH"
NORMALIZED_MATCH = "NORMALIZED_MATCH"
MISMATCH = "MISMATCH"
MISSING_IN_JSON = "MISSING_IN_JSON"
MISSING_IN_XML = "MISSING_IN_XML"
OPTIONAL_MISSING = "OPTIONAL_MISSING"
ID_PAIR = "ID_PAIR"
UNMATCHED_JSON_ROW = "UNMATCHED_JSON_ROW"
UNMATCHED_XML_ROW = "UNMATCHED_XML_ROW"
UNMAPPED_IN_JSON = "UNMAPPED_IN_JSON"
UNMAPPED_IN_XML = "UNMAPPED_IN_XML"

STATUS_ORDER = [
    MISMATCH,
    MISSING_IN_XML,
    MISSING_IN_JSON,
    UNMATCHED_JSON_ROW,
    UNMATCHED_XML_ROW,
    OPTIONAL_MISSING,
    ID_PAIR,
    NORMALIZED_MATCH,
    MATCH,
    UNMAPPED_IN_JSON,
    UNMAPPED_IN_XML,
]

# Statuses a reviewer has to act on, versus the ones that are already resolved.
PROBLEM_STATUSES = [
    MISMATCH,
    MISSING_IN_JSON,
    MISSING_IN_XML,
    UNMATCHED_JSON_ROW,
    UNMATCHED_XML_ROW,
]
MATCHED_STATUSES = [MATCH, NORMALIZED_MATCH]

# Coverage rows report data the mapping never looks at. They are informational:
# no comparison happened, so they count as neither matched nor problems.
COVERAGE_STATUSES = [UNMAPPED_IN_JSON, UNMAPPED_IN_XML]
COVERAGE_GROUP = "Coverage"

TRUE_VALUES = {"1", "true", "yes", "y", "on"}
FALSE_VALUES = {"0", "false", "no", "n", "off"}


@dataclass
class Row:
    group: str
    key: str
    path: str
    status: str
    jsonValue: str
    xmlMappedValue: str
    jsonPath: str
    xmlPath: str
    detail: str = ""


def display(value: Any) -> str:
    if value is MISSING or value is None:
        return ""
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, (dict, list)):
        return json.dumps(value, ensure_ascii=False, separators=(",", ":"))
    return str(value)


def absent(value: Any) -> bool:
    return value is MISSING or value is None or display(value) == ""


def decimal_value(value: Any) -> Decimal | None:
    try:
        return Decimal(display(value).replace(",", "").replace("$", "").strip())
    except InvalidOperation:
        return None


def bool_value(value: Any) -> bool | None:
    if isinstance(value, bool):
        return value
    normalized = display(value).strip().casefold()
    if normalized in TRUE_VALUES:
        return True
    if normalized in FALSE_VALUES:
        return False
    return None


def datetime_value(value: Any) -> datetime | None:
    raw = display(value).strip()
    if not raw:
        return None
    try:
        return datetime.fromisoformat(raw.replace("Z", "+00:00"))
    except ValueError:
        try:
            return datetime.strptime(raw, "%Y-%m-%d")
        except ValueError:
            return None


def compare(field: dict[str, Any], json_data: Any, xml_data: Any, enums: dict) -> tuple[str, str]:
    json_missing, xml_missing = absent(json_data), absent(xml_data)
    if json_missing and xml_missing:
        return OPTIONAL_MISSING, "absent on both sides"
    if json_missing:
        status = OPTIONAL_MISSING if field.get("optional_json") else MISSING_IN_JSON
        return status, "value is absent in JSON"
    if xml_missing:
        status = OPTIONAL_MISSING if field.get("optional_xml") else MISSING_IN_XML
        return status, "value is absent in XML"

    value_type = field.get("type", "string")
    if value_type == "id":
        return ID_PAIR, "different identifier spaces; paired, not value-compared"
    if value_type == "exists":
        return MATCH, "present on both sides"

    enum_name = field.get("enum")
    expected_json = json_data
    if enum_name:
        table = enums.get(enum_name, {})
        lookup = {str(key).casefold(): value for key, value in table.items()}
        expected_json = lookup.get(display(json_data).casefold(), json_data)

    if value_type == "number":
        left, right = decimal_value(expected_json), decimal_value(xml_data)
        if left is None or right is None:
            return MISMATCH, "one value is not a valid number"
        tolerance = Decimal(str(field.get("tolerance", 0)))
        if abs(left - right) <= tolerance:
            if display(json_data) == display(xml_data):
                return MATCH, ""
            return NORMALIZED_MATCH, f"numeric values are equal within tolerance {tolerance}"
        return MISMATCH, f"numeric delta: {left - right}"

    if value_type == "bool":
        left, right = bool_value(expected_json), bool_value(xml_data)
        if left is None or right is None:
            return MISMATCH, "one value is not a valid boolean"
        if left == right:
            return (
                (MATCH, "")
                if display(json_data) == display(xml_data)
                else (NORMALIZED_MATCH, f"{display(json_data)!r} equals {display(xml_data)!r}")
            )
        return MISMATCH, f"boolean values differ: {left} vs {right}"

    if value_type in {"date", "datetime"}:
        left, right = datetime_value(expected_json), datetime_value(xml_data)
        if left is None or right is None:
            return MISMATCH, "one value is not a valid ISO date/datetime"
        if value_type == "date":
            equal = left.date() == right.date()
        else:
            if left.tzinfo is not None:
                left = left.astimezone(timezone.utc).replace(tzinfo=None)
            if right.tzinfo is not None:
                right = right.astimezone(timezone.utc).replace(tzinfo=None)
            equal = left == right
        if equal:
            return (
                (MATCH, "")
                if display(json_data) == display(xml_data)
                else (NORMALIZED_MATCH, f"{value_type} values are equivalent")
            )
        return MISMATCH, f"{value_type} values differ"

    left, right = display(expected_json), display(xml_data)
    if left == right:
        # `left` is the enum-translated value, so an exact hit here is only a
        # plain MATCH when the two files literally agree.
        if display(json_data) == right:
            return MATCH, ""
        return NORMALIZED_MATCH, f"mapped through enum {enum_name}"

    normalized_left, normalized_right = left.strip(), right.strip()
    if field.get("ignore_whitespace"):
        normalized_left = re.sub(r"\s+", "", normalized_left)
        normalized_right = re.sub(r"\s+", "", normalized_right)
    if field.get("ignore_case") or enum_name:
        normalized_left = normalized_left.casefold()
        normalized_right = normalized_right.casefold()
    if normalized_left == normalized_right:
        reason = f"mapped through enum {enum_name}" if enum_name else "equal after normalization"
        return NORMALIZED_MATCH, reason
    if enum_name:
        return MISMATCH, f"enum {enum_name} maps JSON to {left!r}, XML contains {right!r}"
    return MISMATCH, ""


def make_row(
    field: dict[str, Any],
    group: str,
    key: str,
    json_parent: Any,
    xml_parent: ET.Element,
    enums: dict,
) -> Row:
    json_data, used_json_path = json_value(json_parent, field["json"])
    xml_data, used_xml_path = xml_value(xml_parent, field["xml"])
    status, detail = compare(field, json_data, xml_data, enums)
    return Row(
        group=group,
        key=key,
        path=field["path"],
        status=status,
        jsonValue=display(json_data),
        xmlMappedValue=display(xml_data),
        jsonPath=used_json_path,
        xmlPath=used_xml_path,
        detail=detail,
    )


def compare_documents(json_root: Any, xml_root: ET.Element, mapping: dict[str, Any]) -> list[Row]:
    enums = mapping.get("enums", {})
    rows = [
        make_row(field, field.get("group", "Fields"), "", json_root, xml_root, enums)
        for field in mapping.get("fields", [])
    ]

    for collection in mapping.get("collections", []):
        group = collection["name"]
        join = collection["join"]
        json_items = json_values(json_root, collection["json"])
        xml_items = xml_elements(xml_root, collection["xml"])

        json_by_key: dict[str, Any] = {}
        for item in json_items:
            key, _ = json_value(item, join["json"])
            if absent(key):
                rows.append(
                    Row(
                        group,
                        "",
                        collection.get("key_label", "key"),
                        UNMATCHED_JSON_ROW,
                        display(item),
                        "",
                        join["json"],
                        join["xml"],
                        "JSON collection row has no join key",
                    )
                )
            else:
                key_text = display(key)
                if key_text in json_by_key:
                    raise ValueError(f"{group}: duplicate JSON join key {key_text!r}")
                json_by_key[key_text] = item

        xml_by_key: dict[str, ET.Element] = {}
        for item in xml_items:
            key, _ = xml_value(item, join["xml"])
            if absent(key):
                rows.append(
                    Row(
                        group,
                        "",
                        collection.get("key_label", "key"),
                        UNMATCHED_XML_ROW,
                        "",
                        ET.tostring(item, encoding="unicode"),
                        join["json"],
                        join["xml"],
                        "XML collection row has no join key",
                    )
                )
            else:
                key_text = display(key)
                if key_text in xml_by_key:
                    raise ValueError(f"{group}: duplicate XML join key {key_text!r}")
                xml_by_key[key_text] = item

        for key in sorted(json_by_key.keys() | xml_by_key.keys()):
            json_item, xml_item = json_by_key.get(key), xml_by_key.get(key)
            if json_item is None:
                rows.append(
                    Row(
                        group,
                        key,
                        collection.get("key_label", "key"),
                        UNMATCHED_XML_ROW,
                        "",
                        key,
                        join["json"],
                        join["xml"],
                        "row exists only in XML",
                    )
                )
                continue
            if xml_item is None:
                rows.append(
                    Row(
                        group,
                        key,
                        collection.get("key_label", "key"),
                        UNMATCHED_JSON_ROW,
                        key,
                        "",
                        join["json"],
                        join["xml"],
                        "row exists only in JSON",
                    )
                )
                continue

            rows.append(
                Row(
                    group,
                    key,
                    collection.get("key_label", "key"),
                    MATCH,
                    key,
                    key,
                    join["json"],
                    join["xml"],
                    "collection row joined by business key",
                )
            )
            for field in collection.get("fields", []):
                rows.append(make_row(field, group, key, json_item, xml_item, enums))
    return rows


# =============================================================================
# COVERAGE SWEEP
# =============================================================================

INDEX_STEP = re.compile(r"\[(?:\d+|\*)\]")


def path_list(spec: Any) -> list[str]:
    """Mapping paths accept either one path or a list of alternatives."""
    if spec is None:
        return []
    return [path for path in (spec if isinstance(spec, list) else [spec]) if path]


def mapped_json_paths(mapping: dict[str, Any]) -> tuple[set[str], set[str]]:
    """Return (subtree_roots, exact_paths) that the mapping reads from JSON.

    A scalar field may point at an object, so anything below it counts as read.
    Collection base paths are deliberately excluded: mapping a collection does
    not mean every field inside its rows is compared.
    """
    subtrees: set[str] = set()
    exact: set[str] = set()
    for field in mapping.get("fields", []):
        subtrees.update(INDEX_STEP.sub("[*]", path) for path in path_list(field.get("json")))
    for collection in mapping.get("collections", []):
        for base in path_list(collection.get("json")):
            base = INDEX_STEP.sub("[*]", base)
            base = base if base.endswith("[*]") else base + "[*]"
            specs = [collection.get("join", {}).get("json")]
            specs += [field.get("json") for field in collection.get("fields", [])]
            for spec in specs:
                for path in path_list(spec):
                    exact.add(base + "." + INDEX_STEP.sub("[*]", path))
    return subtrees, exact


def mapped_xml_paths(mapping: dict[str, Any]) -> set[str]:
    """Return the attribute and element paths the mapping reads from XML."""
    paths: set[str] = set()
    for field in mapping.get("fields", []):
        paths.update(canonical_xml_path(path) for path in path_list(field.get("xml")))
    for collection in mapping.get("collections", []):
        for base in path_list(collection.get("xml")):
            base = canonical_xml_path(base)
            specs = [collection.get("join", {}).get("xml")]
            specs += [field.get("xml") for field in collection.get("fields", [])]
            for spec in specs:
                for path in path_list(spec):
                    paths.add(join_xml_path(base, canonical_xml_path(path)))
    return paths


def canonical_xml_path(path: str) -> str:
    return path.lstrip("./")


def join_xml_path(base: str, leaf: str) -> str:
    if not base or base == ".":
        return leaf
    return base + "/" + leaf


def json_leaves(node: Any, prefix: str = "") -> Iterator[tuple[str, Any]]:
    """Yield (path, value) for every leaf, with real array indices in the path."""
    if isinstance(node, dict) and node:
        for key, value in node.items():
            yield from json_leaves(value, f"{prefix}.{key}" if prefix else str(key))
    elif isinstance(node, list) and node:
        for index, value in enumerate(node):
            yield from json_leaves(value, f"{prefix}[{index}]")
    else:
        # Empty containers are leaves too, otherwise they vanish from coverage.
        yield prefix, node


def xml_leaves(root: ET.Element) -> Iterator[tuple[str, Any]]:
    """Yield (path, value) for every attribute and every element with text."""
    stack: list[tuple[ET.Element, str]] = [(root, "")]
    while stack:
        element, prefix = stack.pop()
        for name, value in element.attrib.items():
            yield join_xml_path(prefix, "@" + name), value
        text = (element.text or "").strip()
        if text and prefix:
            yield prefix, text
        for child in reversed(list(element)):
            stack.append((child, join_xml_path(prefix, child.tag)))


def coverage_rows(json_root: Any, xml_root: ET.Element, mapping: dict[str, Any]) -> list[Row]:
    """Report every JSON leaf and XML value that no mapping entry reads.

    Repeats collapse onto one row per shape (array indices become [*]) so a
    12-row collection with 6 undeclared fields yields 6 rows, not 72.
    """
    subtrees, exact = mapped_json_paths(mapping)
    xml_paths = mapped_xml_paths(mapping)

    def json_is_read(path: str) -> bool:
        if path in exact or path in subtrees:
            return True
        return any(path.startswith(root + ".") or path.startswith(root + "[") for root in subtrees)

    found: dict[tuple[str, str], list[Any]] = {}
    for path, value in json_leaves(json_root):
        shape = INDEX_STEP.sub("[*]", path)
        if not json_is_read(shape):
            found.setdefault((UNMAPPED_IN_JSON, shape), []).append(value)
    for path, value in xml_leaves(xml_root):
        if path not in xml_paths:
            found.setdefault((UNMAPPED_IN_XML, path), []).append(value)

    rows = []
    for (status, path), values in sorted(found.items(), key=lambda item: (item[0][0], item[0][1])):
        side = "JSON" if status == UNMAPPED_IN_JSON else "XML"
        occurrences = "" if len(values) == 1 else f", {len(values)} occurrences"
        rows.append(
            Row(
                group=COVERAGE_GROUP,
                key="",
                path=path,
                status=status,
                jsonValue=display(values[0]) if status == UNMAPPED_IN_JSON else "",
                xmlMappedValue=display(values[0]) if status == UNMAPPED_IN_XML else "",
                jsonPath=path if status == UNMAPPED_IN_JSON else "",
                xmlPath=path if status == UNMAPPED_IN_XML else "",
                detail=f"present in {side}, not declared in the mapping{occurrences}",
            )
        )
    return rows


# =============================================================================
# REPORT
# =============================================================================

STATUS_COLORS = {
    MATCH: "#16a34a",
    NORMALIZED_MATCH: "#22c55e",
    MISMATCH: "#dc2626",
    MISSING_IN_JSON: "#d97706",
    MISSING_IN_XML: "#9333ea",
    OPTIONAL_MISSING: "#64748b",
    ID_PAIR: "#0891b2",
    UNMATCHED_JSON_ROW: "#db2777",
    UNMATCHED_XML_ROW: "#ea580c",
    UNMAPPED_IN_JSON: "#4f46e5",
    UNMAPPED_IN_XML: "#0284c7",
}

REPORT_CSS = """
*{box-sizing:border-box}
body{margin:0;background:#eef1f8;color:#1f2937;
font:13.5px/1.5 "Segoe UI",Inter,system-ui,-apple-system,sans-serif}
.page{max-width:1560px;margin:0 auto;padding:18px 20px 60px}
.hero{background:linear-gradient(120deg,#7c4fe0 0%,#5b6ff0 55%,#3b82f6 100%);
border-radius:14px;padding:26px 24px;text-align:center;color:#fff;
box-shadow:0 10px 26px rgba(59,88,180,.28)}
.hero h1{margin:0 0 6px;font-size:27px;font-weight:700;letter-spacing:.2px}
.hero .sub{font-size:13px;opacity:.95}
.hero .meta{margin-top:8px;font-size:11.5px;opacity:.85}
.tabs{display:flex;gap:2px;flex-wrap:wrap;background:#fff;border:1px solid #dfe3ee;
border-radius:10px;margin:14px 0;padding:4px}
.tab{background:transparent;border:0;border-bottom:3px solid transparent;color:#5b6478;
font:inherit;font-weight:600;padding:10px 14px;cursor:pointer;white-space:nowrap;
border-radius:6px 6px 0 0}
.tab em{font-style:normal;font-weight:500;color:#98a1b5;font-size:11.5px}
.tab:hover{background:#f3f5fb}
.tab.is-active{color:#3b6fd4;border-bottom-color:#3b6fd4;background:#f3f7ff}
.panel{background:linear-gradient(120deg,#5b8bf0,#7b6ae8);border-radius:14px;padding:18px;
color:#fff;box-shadow:0 8px 20px rgba(59,88,180,.22)}
.panel h2{margin:0 0 14px;text-align:center;font-size:17px;font-weight:700}
.stats{display:grid;grid-template-columns:repeat(auto-fit,minmax(190px,1fr));gap:14px}
.stat{background:rgba(255,255,255,.16);border:1px solid rgba(255,255,255,.28);
border-radius:11px;padding:14px 16px}
.stat .label{display:block;font-size:11.5px;opacity:.92;margin-bottom:6px}
.stat .num{font-size:26px;font-weight:700}
.files{display:flex;gap:18px;flex-wrap:wrap;margin-top:14px;font-size:11.5px;opacity:.92}
.files b{opacity:.8;font-weight:600;margin-right:5px}
.views{display:flex;gap:20px;border-bottom:2px solid #e2e6f0;margin-top:18px;flex-wrap:wrap}
.view{background:none;border:0;border-bottom:3px solid transparent;color:#5b6478;
font:inherit;font-weight:600;padding:10px 2px;cursor:pointer;margin-bottom:-2px}
.view:hover{color:#1f2937}
.view.is-active{color:#3b6fd4;border-bottom-color:#3b6fd4}
.view.is-active[data-view="compared"]{color:#3b6fd4;border-bottom-color:#3b6fd4}
.view.is-active[data-view="matched"]{color:#15803d;border-bottom-color:#16a34a}
.view.is-active[data-view="problems"]{color:#dc2626;border-bottom-color:#dc2626}
.view.is-active[data-view="coverage"]{color:#4f46e5;border-bottom-color:#4f46e5}
.view .dot{display:inline-block;width:8px;height:8px;border-radius:50%;
margin-right:7px;background:#3b6fd4}
.view[data-view="matched"] .dot{background:#16a34a}
.view[data-view="problems"] .dot{background:#dc2626}
.view[data-view="coverage"] .dot{background:#4f46e5}
.toolbar{display:flex;gap:9px;align-items:center;flex-wrap:wrap;margin:12px 0}
.toolbar input,.toolbar button{font:inherit;border:1px solid #d3d9e8;background:#fff;
color:#1f2937;border-radius:8px;padding:9px 12px}
.toolbar input{min-width:320px}
.toolbar input:focus{outline:2px solid #b9ccf5;border-color:#7aa2e8}
.toolbar button{cursor:pointer;font-weight:600}
.toolbar button:hover{border-color:#7aa2e8}
.toolbar .primary{background:#3b6fd4;border-color:#3b6fd4;color:#fff}
.toolbar .count{color:#5b6478;font-size:12.5px}
.chips{display:flex;gap:7px;flex-wrap:wrap;margin:14px 0 2px}
.chip{display:flex;align-items:baseline;gap:6px;cursor:pointer;font:inherit;background:#fff;
border:1px solid #dfe3ee;border-left:4px solid var(--tone);border-radius:9px;padding:7px 11px}
.chip b{font-size:15px;color:var(--tone)}
.chip span{font-size:10.5px;color:#6b7385;letter-spacing:.03em}
.chip.off{opacity:.4}
.notice{border-radius:9px;padding:11px 14px;font-size:12.5px;margin:14px 0;border:1px solid}
.notice.warn{background:#fff8e6;border-color:#f3d99b;color:#8a5a00}
.notice.ok{background:#eafaf0;border-color:#b7e6c9;color:#166534}
.notice.info{background:#eef0ff;border-color:#c7cbf7;color:#3730a3}
.rows-title{color:#15803d;font-size:15px;margin:18px 0 9px;padding-bottom:7px;
border-bottom:2px solid #16a34a}
.wrap{background:#fff;border:1px solid #dfe3ee;border-radius:11px;overflow:auto;
box-shadow:0 2px 8px rgba(31,41,55,.05)}
table{border-collapse:collapse;width:100%}
thead th{position:sticky;top:0;background:linear-gradient(#1c9c53,#15803d);color:#fff;
text-align:left;font-size:10.5px;letter-spacing:.07em;text-transform:uppercase;padding:10px 11px}
tbody td{border-bottom:1px solid #eceff6;padding:9px 11px;vertical-align:top}
tbody tr:hover{background:#f2f7ff}
.mono{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-size:12px}
.value{max-width:330px;overflow-wrap:anywhere}
.path{color:#5f6b82;max-width:230px;overflow-wrap:anywhere}
.detail{color:#6b7385;max-width:270px;overflow-wrap:anywhere}
.pill{display:inline-block;border-radius:20px;font-size:10px;font-weight:700;
letter-spacing:.04em;padding:3px 9px;white-space:nowrap}
.dash{color:#aab2c4}
a{color:#3b6fd4}
"""

REPORT_JS = """
const rows=[...document.querySelectorAll("tbody tr")];
const chips=[...document.querySelectorAll(".chip")];
const ALL=chips.map(c=>c.dataset.status);
const MATCHED=["MATCH","NORMALIZED_MATCH"];
const PROBLEMS=["MISMATCH","MISSING_IN_JSON","MISSING_IN_XML",
  "UNMATCHED_JSON_ROW","UNMATCHED_XML_ROW"];
const COVERAGE=["UNMAPPED_IN_JSON","UNMAPPED_IN_XML"];
const COMPARED=ALL.filter(s=>!COVERAGE.includes(s));
const active=new Set(COMPARED);
const search=document.querySelector("#q");
let group="*";
function apply(){
  const term=search.value.trim().toLowerCase();
  let shown=0;
  for(const row of rows){
    const visible=active.has(row.dataset.status)
      &&(group==="*"||row.dataset.group===group)
      &&(!term||row.dataset.hay.includes(term));
    row.hidden=!visible;
    if(visible)shown++;
  }
  document.querySelector("#shown").textContent=shown;
  chips.forEach(c=>c.classList.toggle("off",!active.has(c.dataset.status)));
}
function setStatuses(list){active.clear();list.forEach(s=>active.add(s));apply();}
chips.forEach(chip=>chip.onclick=()=>{
  const status=chip.dataset.status;
  if(active.has(status))active.delete(status);else active.add(status);
  apply();
});
document.querySelectorAll(".tab").forEach(tab=>tab.onclick=()=>{
  document.querySelectorAll(".tab").forEach(t=>t.classList.remove("is-active"));
  tab.classList.add("is-active");
  group=tab.dataset.group;
  apply();
});
document.querySelectorAll(".view").forEach(view=>view.onclick=()=>{
  document.querySelectorAll(".view").forEach(v=>v.classList.remove("is-active"));
  view.classList.add("is-active");
  const mode=view.dataset.view;
  setStatuses(mode==="matched"?MATCHED:mode==="problems"?PROBLEMS
    :mode==="coverage"?COVERAGE:COMPARED);
});
search.oninput=apply;
document.querySelector("#reset").onclick=()=>{
  search.value="";group="*";
  document.querySelectorAll(".tab").forEach((t,i)=>t.classList.toggle("is-active",i===0));
  document.querySelectorAll(".view").forEach((v,i)=>v.classList.toggle("is-active",i===0));
  setStatuses(COMPARED);
};
document.querySelector("#csv").onclick=()=>{
  const header=["group","key","path","status","jsonValue","xmlMappedValue",
    "jsonPath","xmlPath","detail"];
  const quote=v=>'"'+String(v).split('"').join('""')+'"';
  const data=[header,...rows.filter(r=>!r.hidden).map(r=>JSON.parse(r.dataset.values))];
  const blob=new Blob([data.map(r=>r.map(quote).join(",")).join("\\n")],{type:"text/csv"});
  const link=document.createElement("a");
  link.href=URL.createObjectURL(blob);
  link.download="filtered-report.csv";
  link.click();
};
apply();
"""


def write_csv_report(rows: list[Row], path: Path) -> None:
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(asdict(rows[0]).keys()) if rows else [])
        if rows:
            writer.writeheader()
            writer.writerows(asdict(row) for row in rows)


def write_json_report(rows: list[Row], path: Path, metadata: dict[str, Any]) -> None:
    path.write_text(
        json.dumps(
            {
                "metadata": metadata,
                "summary": dict(Counter(row.status for row in rows)),
                "rows": [asdict(row) for row in rows],
            },
            indent=2,
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )


def write_html_report(rows: list[Row], path: Path, metadata: dict[str, Any]) -> None:
    counts = Counter(row.status for row in rows)
    group_counts = Counter(row.group for row in rows)
    groups = list(dict.fromkeys(row.group for row in rows))
    total = len(rows)
    matched = sum(counts.get(status, 0) for status in MATCHED_STATUSES)
    problems = sum(counts.get(status, 0) for status in PROBLEM_STATUSES)
    unmapped = sum(counts.get(status, 0) for status in COVERAGE_STATUSES)
    compared = total - unmapped
    rate = (100.0 * matched / compared) if compared else 0.0

    stats = [
        ("Comparisons", str(compared)),
        ("Matched", str(matched)),
        ("Needs Attention", str(problems)),
        ("Match Rate", "%.1f%%" % rate),
    ]
    if unmapped:
        stats.append(("Unmapped Fields", str(unmapped)))
    stat_cards = "".join(
        f'<div class="stat"><span class="label">{html.escape(label)}</span>'
        f'<span class="num">{html.escape(value)}</span></div>'
        for label, value in stats
    )

    chips = "".join(
        f'<button class="chip" data-status="{status}" style="--tone:{STATUS_COLORS[status]}">'
        f"<b>{counts.get(status, 0)}</b><span>{status.replace('_', ' ')}</span></button>"
        for status in STATUS_ORDER
    )

    tabs = f'<button class="tab is-active" data-group="*">All Groups <em>{total}</em></button>'
    tabs += "".join(
        f'<button class="tab" data-group="{html.escape(group)}">{html.escape(group)}'
        f" <em>{group_counts[group]}</em></button>"
        for group in groups
    )

    views = (
        f'<button class="view is-active" data-view="compared"><i class="dot"></i>'
        f"Compared ({compared})</button>"
        f'<button class="view" data-view="matched"><i class="dot"></i>'
        f"Matched ({matched})</button>"
        f'<button class="view" data-view="problems"><i class="dot"></i>'
        f"Needs Attention ({problems})</button>"
    )
    if unmapped:
        views += (
            f'<button class="view" data-view="coverage"><i class="dot"></i>'
            f"Unmapped ({unmapped})</button>"
        )

    if problems:
        breakdown = ", ".join(
            f"{counts[status]} {status.replace('_', ' ').lower()}"
            for status in PROBLEM_STATUSES
            if counts.get(status)
        )
        notice = (
            '<div class="notice warn"><b>Attention:</b> '
            f"{problems} of {compared} comparisons need review ({breakdown}). "
            "Use the group tabs or status filters below to drill in.</div>"
        )
    else:
        notice = (
            '<div class="notice ok"><b>Clean run:</b> no mismatches and no '
            "unmatched rows across all mapped comparisons.</div>"
        )
    if unmapped:
        notice += (
            '<div class="notice info"><b>Coverage:</b> '
            f"{counts.get(UNMAPPED_IN_JSON, 0)} JSON path(s) and "
            f"{counts.get(UNMAPPED_IN_XML, 0)} XML path(s) are not declared in the mapping, "
            "so they were never compared. See the Coverage group.</div>"
        )

    body = []
    for row in rows:
        raw = json.dumps(
            [
                row.group,
                row.key,
                row.path,
                row.status,
                row.jsonValue,
                row.xmlMappedValue,
                row.jsonPath,
                row.xmlPath,
                row.detail,
            ],
            ensure_ascii=False,
            separators=(",", ":"),
        )
        haystack = " ".join(json.loads(raw)).casefold()
        color = STATUS_COLORS[row.status]
        pill = (
            f'<span class="pill" style="background:{color}1a;color:{color};'
            f'border:1px solid {color}40">{row.status}</span>'
        )
        body.append(
            f'<tr data-status="{row.status}" data-group="{html.escape(row.group)}" '
            f'data-hay="{html.escape(haystack, quote=True)}" '
            f"data-values='{html.escape(raw, quote=True)}'>"
            f"<td>{html.escape(row.group)}</td>"
            f'<td class="mono">{html.escape(row.key) or dash()}</td>'
            f'<td class="mono">{html.escape(row.path)}</td>'
            f"<td>{pill}</td>"
            f'<td class="mono value">{html.escape(row.jsonValue) or dash()}</td>'
            f'<td class="mono value">{html.escape(row.xmlMappedValue) or dash()}</td>'
            f'<td class="mono path">{html.escape(row.jsonPath)}</td>'
            f'<td class="mono path">{html.escape(row.xmlPath)}</td>'
            f'<td class="detail">{html.escape(row.detail)}</td></tr>'
        )

    title = metadata["name"]
    document = f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>JSON &#8596; XML Validation Report &mdash; {html.escape(title)}</title>
<style>{REPORT_CSS}</style></head><body>
<div class="page">
<header class="hero">
  <h1>JSON &#8596; XML Validation Report</h1>
  <div class="sub">Generated on {html.escape(metadata["generated"])}</div>
  <div class="meta">Mapping: {html.escape(metadata["mapping"])}
    &nbsp;|&nbsp; {compared} mapped comparisons
    {f"&nbsp;|&nbsp; {unmapped} undeclared paths" if unmapped else ""}</div>
</header>
<nav class="tabs">{tabs}</nav>
<section class="panel">
  <h2>{html.escape(title)} &mdash; Field Comparison</h2>
  <div class="stats">{stat_cards}</div>
  <div class="files">
    <span><b>JSON</b> {html.escape(metadata["json"])}</span>
    <span><b>XML</b> {html.escape(metadata["xml"])}</span>
  </div>
</section>
<div class="views">{views}</div>
{notice}
<div class="chips">{chips}</div>
<div class="toolbar">
  <input id="q" type="search" placeholder="Filter by path, value, status or detail&hellip;">
  <button id="csv" class="primary">Download filtered CSV</button>
  <button id="reset">Reset filters</button>
  <span class="count"><b id="shown">0</b> of {total} rows shown</span>
</div>
<h3 class="rows-title">Comparison Rows</h3>
<div class="wrap"><table><thead><tr>
<th>Group</th><th>Key</th><th>Path</th><th>Status</th><th>JSON Value</th>
<th>XML Mapped Value</th><th>JSON Path</th><th>XML Path</th><th>Detail</th>
</tr></thead><tbody>{''.join(body)}</tbody></table></div>
</div>
<script>{REPORT_JS}</script></body></html>"""
    path.write_text(document, encoding="utf-8")


def dash() -> str:
    return '<span class="dash">&mdash;</span>'


def run_pair(
    json_path: Path,
    xml_path: Path,
    output_html: Path,
    mapping: dict[str, Any],
    name: str,
    coverage: bool = True,
) -> tuple[list[Row], dict[str, Any]]:
    try:
        with json_path.open(encoding="utf-8") as handle:
            json_root = json.load(handle)
    except json.JSONDecodeError as error:
        raise ValueError(
            f"{json_path}: invalid JSON at line {error.lineno}, column {error.colno}: {error.msg}"
        ) from error

    try:
        xml_root = ET.parse(xml_path).getroot()
    except ET.ParseError as error:
        raise ValueError(f"{xml_path}: invalid XML: {error}") from error

    normalize_xml_names(xml_root)
    rows = compare_documents(json_root, xml_root, mapping)
    if coverage:
        rows += coverage_rows(json_root, xml_root, mapping)
    metadata = {
        "name": name,
        "json": str(json_path),
        "xml": str(xml_path),
        "mapping": mapping.get("name", "mapping"),
        "generated": datetime.now().astimezone().isoformat(timespec="seconds"),
    }

    output_html.parent.mkdir(parents=True, exist_ok=True)
    write_html_report(rows, output_html, metadata)
    write_csv_report(rows, output_html.with_suffix(".csv"))
    write_json_report(rows, output_html.with_suffix(".json"), metadata)
    return rows, metadata


def write_batch_index(results: list[dict[str, Any]], path: Path) -> None:
    total_rows = sum(result["total"] for result in results)
    total_problems = 0
    table_rows = []
    for result in results:
        counts = result["counts"]
        problems = sum(counts.get(status, 0) for status in PROBLEM_STATUSES)
        total_problems += problems
        matched = sum(counts.get(status, 0) for status in MATCHED_STATUSES)
        tone = "#dc2626" if problems else "#16a34a"
        table_rows.append(
            f'<tr><td><a href="{html.escape(result["report"])}">'
            f'{html.escape(result["name"])}</a></td>'
            f'<td>{result["total"]}</td>'
            f'<td><b style="color:{tone}">{counts.get(MISMATCH, 0)}</b></td>'
            f'<td><b style="color:{tone}">{problems}</b></td>'
            f"<td>{counts.get(MATCH, 0)}</td>"
            f"<td>{counts.get(NORMALIZED_MATCH, 0)}</td>"
            f"<td>{matched}</td></tr>"
        )

    stat_cards = "".join(
        f'<div class="stat"><span class="label">{label}</span>'
        f'<span class="num">{value}</span></div>'
        for label, value in [
            ("Pairs Compared", str(len(results))),
            ("Total Comparisons", str(total_rows)),
            ("Needs Attention", str(total_problems)),
        ]
    )
    generated = datetime.now().astimezone().isoformat(timespec="seconds")
    path.write_text(
        f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>JSON &#8596; XML Validation Batch</title>
<style>{REPORT_CSS}</style></head><body>
<div class="page">
<header class="hero">
  <h1>JSON &#8596; XML Validation Batch</h1>
  <div class="sub">Generated on {html.escape(generated)}</div>
  <div class="meta">{len(results)} file pairs &nbsp;|&nbsp; {total_rows} comparisons</div>
</header>
<section class="panel">
  <h2>Batch Summary</h2>
  <div class="stats">{stat_cards}</div>
</section>
<h3 class="rows-title">Reports</h3>
<div class="wrap"><table><thead><tr>
<th>Pair</th><th>Comparisons</th><th>Mismatches</th><th>Needs Attention</th>
<th>Match</th><th>Normalized Match</th><th>Matched Total</th>
</tr></thead><tbody>{''.join(table_rows)}</tbody></table></div>
</div></body></html>""",
        encoding="utf-8",
    )
# =============================================================================
# CLI
# =============================================================================

DEFAULT_MAPPING_PATH = Path(__file__).resolve().parent / "mapping.yaml"


def load_mapping(path: Path) -> dict[str, Any]:
    """Read the mapping from YAML (needs PyYAML) or JSON (standard library)."""
    if not path.exists():
        raise ValueError(f"mapping file not found: {path}")

    text = path.read_text(encoding="utf-8")
    if path.suffix.lower() in {".yaml", ".yml"}:
        try:
            import yaml
        except ImportError as error:
            raise ValueError(
                f"{path}: reading a YAML mapping needs PyYAML (pip install pyyaml); "
                "a .json mapping works with the standard library alone"
            ) from error
        try:
            mapping = yaml.safe_load(text)
        except yaml.YAMLError as error:
            raise ValueError(f"{path}: invalid YAML: {error}") from error
    else:
        try:
            mapping = json.loads(text)
        except json.JSONDecodeError as error:
            raise ValueError(
                f"{path}: invalid JSON at line {error.lineno}, column {error.colno}: {error.msg}"
            ) from error

    validate_mapping(mapping, path)
    return mapping


def validate_mapping(mapping: Any, path: Path) -> None:
    """Fail on a broken mapping now, rather than on every row of the report."""
    if not isinstance(mapping, dict):
        raise ValueError(f"{path}: mapping must be a mapping/object at the top level")
    if not mapping.get("fields") and not mapping.get("collections"):
        raise ValueError(f"{path}: mapping defines neither 'fields' nor 'collections'")

    enums = mapping.get("enums") or {}
    valid_types = {"string", "number", "bool", "date", "datetime", "id", "exists"}
    problems: list[str] = []

    def check(field: Any, where: str) -> None:
        if not isinstance(field, dict):
            problems.append(f"{where}: each field must be a mapping/object")
            return
        for key in ("path", "json", "xml"):
            if not field.get(key):
                problems.append(f"{where}: missing '{key}'")
        field_type = field.get("type", "string")
        if field_type not in valid_types:
            problems.append(f"{where}: unknown type {field_type!r}, expected one of {sorted(valid_types)}")
        if field.get("enum") and field["enum"] not in enums:
            problems.append(f"{where}: enum {field['enum']!r} is not defined under 'enums'")

    for index, field in enumerate(mapping.get("fields") or []):
        label = field.get("path", index) if isinstance(field, dict) else index
        check(field, f"fields[{label}]")

    for index, collection in enumerate(mapping.get("collections") or []):
        if not isinstance(collection, dict):
            problems.append(f"collections[{index}]: must be a mapping/object")
            continue
        name = collection.get("name", index)
        for key in ("json", "xml"):
            if not collection.get(key):
                problems.append(f"collections[{name}]: missing '{key}'")
        join = collection.get("join") or {}
        if not join.get("json") or not join.get("xml"):
            problems.append(f"collections[{name}]: 'join' needs both a json and an xml key")
        for field in collection.get("fields") or []:
            label = field.get("path", "?") if isinstance(field, dict) else "?"
            check(field, f"collections[{name}].fields[{label}]")

    if problems:
        raise ValueError(f"{path}: invalid mapping:\n  - " + "\n  - ".join(problems))


def manifest_pairs(path: Path) -> list[tuple[Path, Path, str]]:
    with path.open(newline="", encoding="utf-8") as handle:
        rows = list(csv.DictReader(handle))
    required = {"json", "xml"}
    if not rows or not required.issubset(rows[0]):
        raise ValueError(f"{path}: manifest requires columns json,xml[,name]")
    return [
        (Path(row["json"]), Path(row["xml"]), row.get("name") or Path(row["json"]).stem)
        for row in rows
    ]


def directory_pairs(path: Path, json_glob: str, xml_extension: str) -> list[tuple[Path, Path, str]]:
    pairs = []
    for json_path in sorted(path.glob(json_glob)):
        xml_path = json_path.with_suffix(xml_extension)
        if not xml_path.exists():
            print(f"skip {json_path}: expected XML partner {xml_path}", file=sys.stderr)
            continue
        pairs.append((json_path, xml_path, json_path.stem))
    return pairs


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawTextHelpFormatter)
    parser.add_argument("--json", type=Path, help="valid JSON input")
    parser.add_argument("--xml", type=Path, help="valid XML input")
    parser.add_argument("--out", type=Path, default=Path("comparison-report.html"))
    parser.add_argument("--batch-dir", type=Path, help="pair *.json/*.xml by filename stem")
    parser.add_argument("--manifest", type=Path, help="CSV columns: json,xml[,name]")
    parser.add_argument("--out-dir", type=Path, default=Path("comparison-reports"))
    parser.add_argument("--json-glob", default="*.json")
    parser.add_argument("--xml-extension", default=".xml")
    parser.add_argument(
        "--mapping",
        type=Path,
        default=DEFAULT_MAPPING_PATH,
        help=f"mapping file, .yaml or .json (default: {DEFAULT_MAPPING_PATH.name})",
    )
    parser.add_argument(
        "--fail-on-problems",
        action="store_true",
        help="return exit code 1 for mismatch/missing/unmatched rows",
    )
    parser.add_argument(
        "--no-coverage",
        dest="coverage",
        action="store_false",
        help="skip the sweep that lists JSON/XML values the mapping never reads",
    )
    args = parser.parse_args(argv)

    try:
        mapping = load_mapping(args.mapping)
        if args.manifest:
            pairs = manifest_pairs(args.manifest)
            batch = True
        elif args.batch_dir:
            pairs = directory_pairs(args.batch_dir, args.json_glob, args.xml_extension)
            batch = True
        elif args.json and args.xml:
            pairs = [(args.json, args.xml, args.json.stem)]
            batch = False
        else:
            parser.error("use --json FILE --xml FILE, --batch-dir DIR, or --manifest FILE")

        if not pairs:
            raise ValueError("no JSON/XML pairs found")

        batch_results = []
        has_problems = False
        for json_path, xml_path, name in pairs:
            output = args.out_dir / f"{name}.html" if batch else args.out
            rows, _ = run_pair(json_path, xml_path, output, mapping, name, args.coverage)
            counts = Counter(row.status for row in rows)
            problem_count = sum(counts.get(status, 0) for status in PROBLEM_STATUSES)
            unmapped = sum(counts.get(status, 0) for status in COVERAGE_STATUSES)
            has_problems |= problem_count > 0
            print(
                f"{name}: {len(rows) - unmapped} comparisons, "
                f"{counts.get(MISMATCH, 0)} mismatches, {problem_count} problems, "
                f"{unmapped} unmapped -> {output}"
            )
            batch_results.append(
                {
                    "name": name,
                    "report": output.name,
                    "total": len(rows),
                    "counts": counts,
                }
            )

        if batch:
            args.out_dir.mkdir(parents=True, exist_ok=True)
            index = args.out_dir / "index.html"
            write_batch_index(batch_results, index)
            print(f"batch index -> {index}")
        return 1 if args.fail_on_problems and has_problems else 0
    except (OSError, ValueError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
