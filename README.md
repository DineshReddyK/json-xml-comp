# JSON ↔ XML reconciliation for OCR-damaged, partial extracts

For **valid, well-formed** JSON/XML (no OCR repair), use
[`compare/`](compare/README.md) — one script + `mapping.yaml`.

This directory is the OCR/reference pipeline:

Repairs a malformed JSON/XML pair, maps the two field vocabularies onto each
other, and emits an interactive HTML report with `path, status, jsonValue,
xmlMappedValue` (plus the resolved paths on both sides and a reason for every
status). Built for the case where the JSON is a partial capture, both files are
OCR garbage, and the two systems use different field names and different ids.

```
y1.json  (partial deal JSON, OCR-damaged)   ─┐
                                             ├─ repair ─ map ─ compare ─ report
y2.xml   (ideal_security_master, OCR-damaged)┘
```

## Install & run

```bash
pip install -r requirements.txt          # only PyYAML is required

python -m recon.cli --json y1.json --xml y2.xml --out out
xdg-open out/y1.report.html
```

Outputs in `out/`:

| file | contents |
| --- | --- |
| `y1.report.html` | interactive report: status filters, search, sortable columns, repair log |
| `y1.report.csv` | same rows, flat, for Excel/diffing |
| `y1.report.json` | same rows plus summary counts and the repair log, for pipelines |
| `fixed/y1.fixed.json` | repaired, pretty-printed, strictly parseable JSON |
| `fixed/y2.fixed.xml` | repaired, namespace-stripped, indented XML |

### Many files

```bash
# pairs by filename stem: data/deal_a.json + data/deal_a.xml
python -m recon.cli --batch data/ --out out/

# explicit pairing (CSV with columns json,xml[,name])
python -m recon.cli --manifest pairs.csv --out out/

# different extension or a nested layout
python -m recon.cli --batch data/ --json-glob "**/*.json" --xml-ext ".xml" --out out/

# CI gate: non-zero exit if any row is a real MISMATCH
python -m recon.cli --batch data/ --out out/ --fail-on-mismatch
```

Batch mode also writes `out/index.html` with one row per pair (mismatch counts,
missing counts, repair counts) linking to each detailed report.

### As a library

```python
from pathlib import Path
from recon import load_mapping, repair_json_file, repair_xml_file, run_mapping, write_html

spec = load_mapping("mappings/ideal_deal.yaml")
data, jlog, fixed_json = repair_json_file("y1.json", spec["repair"]["json"])
root, xlog, fixed_xml = repair_xml_file("y2.xml", spec["repair"]["xml"])

result = run_mapping(spec, data, root)
result.json_repairs, result.xml_repairs = jlog.as_list(), xlog.as_list()
print(result.counts)                       # {'MISMATCH': 0, 'MATCH': 14, ...}
for row in result.rows:
    print(row.path, row.status, row.json_value, row.xml_value)
write_html(result, Path("out/report.html"), "y1")
```

## Statuses

| status | meaning |
| --- | --- |
| `MATCH` | equal after parsing only |
| `NORMALIZED_MATCH` | equal after type/enum/date/OCR-glyph normalisation (`false`↔`0`, `SemiAnnually`↔`S`, `LimitedTaxG0`↔`LTG`) |
| `PREFIX_MATCH` | one side is a truncated prefix of the other (the capture was cut off) |
| `FUZZY_MATCH` | near-identical; residual OCR noise above the field's `fuzzy` threshold |
| `MISMATCH` | both present, genuinely different — the only status that should ever need action |
| `MISSING_IN_JSON` | only in XML; expected when the JSON extract is partial |
| `MISSING_IN_XML` | only in JSON; nothing at the mapped XML path |
| `UNJOINED_FRAGMENT` | an OCR fragment with no join key; shown with a value preview for manual placement |
| `NOT_IN_XML_SCHEMA` | field marked `optional_xml`, i.e. this XML schema version has no counterpart |
| `MISSING_IN_BOTH` | absent on both sides |
| `ID_PAIR` | identifiers from different key spaces; paired by the mapping, never value-compared |

## The mapping file

Nothing about the two schemas is hard-coded in Python; everything lives in
`mappings/ideal_deal.yaml`. Point `--mapping` at your own file for other
document types.

```yaml
groups:                       # scalar fields
  - name: Series
    fields:
      - path: series.couponFrequency      # label in the report
        json: series[0].coupon.frequency  # or a list: first present wins
        xml: series_list/series/@coupon_freq_cd
        type: string                      # string number bool date datetime id exists
        enum: coupon_freq                 # json value -> xml value table

collections:                  # repeating rows, joined on a business key
  - name: Tranches vs maturities
    json: tranches[*]
    xml: maturities/maturity
    join: { json: name, xml: "@maturity_id" }
    fields:
      - path: quantity
        json: quantity
        xml: "@number_bonds"
        type: number
        tol: 0.00001
```

Per-field options: `tol` (numeric tolerance), `ci` (case-insensitive), `squash`
(ignore all whitespace), `fuzzy` (similarity threshold, enables `FUZZY_MATCH`),
`prefix` / `truncation_slack` (prefix matching), `optional_xml` (downgrade
`MISSING_IN_XML` to `NOT_IN_XML_SCHEMA`), `note` (free text in the report).

Rows are joined on a **business key**, not on position: `tranches[*].name` holds
the XML `maturity_id`, so the two files can list their children in any order and
a partial JSON still lines up. Unmatched keys are reported from both directions.

The mapping also drives the repair, since correct recovery needs schema
knowledge:

```yaml
repair:
  json:
    root_keys:   [series, tranches, products, ...]   # keys that only exist at the top level
    object_keys: [orderPeriod, coupon, ...]          # `"orderPeriod":1` -> `"orderPeriod": {`
    array_keys:  [tranches, ratings, ...]
    key_aliases: { "state TaxStatus": stateTaxStatus, "LongTerm": longTerm }
  xml:
    tag_aliases:  { "financial advisor": financial_advisor, "order types": order_types }
    attr_aliases: { add1_takedown: addl_takedown, series: { id: series_id } }  # flat or per-tag
    text_elements: [priority_rule]                   # elements whose text is real, not page noise
```

## How the repair works

`recon/repair_json.py` — a textual pre-clean followed by a tolerant
recursive-descent parser that resynchronises instead of raising. It handles what
this OCR actually produced:

* debugger annotations: `"trancheIds": [ size:17`, and orphan `size:1` lines
* `]` misread as a lone `1` / `1,` / `1.` on its own line; `{` misread as `1`
  after a known object key
* stray glyph lines (`I`, `l`, `|`), keys split by a space (`"state TaxStatus"`)
* strings left unterminated by a line break, and a file truncated mid-string
* **lost `]`/`}`**: a key that can only exist at the top level closes every open
  container, so `series` does not end up nested inside `orderPeriod`. Without
  this one rule, a single dropped bracket re-parents whole subtrees and every
  path in the report silently shifts.
* **lost `{`**: a key appearing directly inside an array of objects, or a key
  repeating inside the current element, starts a new element
* **displaced `}`**: a closer that lands before another key inside an array
  element is dropped and the element keeps accumulating
* a bare JSON fragment with no enclosing braces is wrapped in an object

`recon/repair_xml.py` — the document is re-emitted tag by tag, and every tag is
rebuilt from a tolerant attribute scan where attributes end at the next
` name="` rather than at a closing quote. That fixes:

* unterminated attribute quotes: `new_end_dttm="2026-08-19T11:30:00 new_end_timezone_cd="E"`
* line breaks inside values: `expected_pricing_dt="2026-08-\n19"`
* spaces in tag names (`<financial advisor .../>`, `<order types>`), `< /tag>`,
  `</maturities>>`
* `0` misread as `@`/`e`/`8`/`o`/`B` in boolean `*_ind` attributes — applied
  **only** to `*_ind`/`*_flag` attributes, so a cusip or a price is never touched
* stray page furniture between elements ("Chat"), `xmlns` noise, unclosed
  elements (auto-closed in document order)

Every fix is recorded with kind, detail, count and line, and shown in the
report's *Repair log* panel — nothing is silently changed.

## Results for the supplied pair

80 comparisons, 0 mismatches, 93 repairs applied. Everything unmatched is
explained: 14 fields present only in the XML (the JSON capture is partial), 5
tranche/product rows whose maturity ids are outside the XML excerpt, 2 OCR
fragments with no join key, 7 fields with no counterpart in this XML schema
version, and 2 residual OCR corruptions surfaced as `FUZZY_MATCH`
(`globalId`, 97.2% similar) and `PREFIX_MATCH` (`deal.name`, truncated capture).

## Tests

```bash
python -m pytest tests -q      # 11 tests: repair rules, comparators, paths, end-to-end
```

## Layout

```
recon/repair_json.py   OCR pre-clean + salvage JSON parser
recon/repair_xml.py    tag-by-tag XML rebuild
recon/paths.py         small JSON path (a.b[*].c) and XML path (a/b/@c) resolvers
recon/mapping.py       mapping load + validation (catches bad types/undefined enums early)
recon/compare.py       normalisers, comparators, join logic, statuses
recon/report.py        HTML / CSV / JSON / batch index writers
recon/cli.py           single pair, directory batch, manifest batch
mappings/ideal_deal.yaml   the whole schema mapping for this document type
```

## Adapting to a new document pair

1. Run once with a minimal mapping and open `out/fixed/*.fixed.{json,xml}` to see
   the real structure after repair.
2. Add fields to `groups:` / `collections:`; use a list for `json:`/`xml:` when
   OCR may have displaced a value out of its parent object.
3. Re-run. Anything still `MISMATCH` is either a mapping gap or a genuine data
   difference; `MISSING_IN_XML` in bulk usually means a wrong path prefix.
