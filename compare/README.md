# JSON ↔ XML comparison (valid files)

One script plus one mapping file. For well-formed JSON and XML — not the OCR
repair pipeline in the parent directory.

```
compare/
  compare_json_xml.py   Python engine
  mapping.yaml          field/enum/collection mapping (shared)
  java/                 Java 8 port of the same engine
```

## Run (Python)

From this directory:

```bash
pip install pyyaml    # only needed for .yaml mappings; .json needs nothing

python compare_json_xml.py --json ../y1.fixed.json --xml ../y2.fixed.xml --out report.html
```

From anywhere — mapping defaults to the file next to the script:

```bash
python /path/to/compare/compare_json_xml.py --json deal.json --xml deal.xml --out report.html
```

Batch (pairs `*.json` with `*.xml` by stem):

```bash
python compare_json_xml.py --batch-dir ./data --out-dir ./reports
```

Manifest CSV (`json,xml[,name]`):

```bash
python compare_json_xml.py --manifest pairs.csv --out-dir ./reports
```

A different mapping:

```bash
python compare_json_xml.py --json a.json --xml b.xml --mapping other.yaml
python compare_json_xml.py --json a.json --xml b.xml --mapping other.json
```

`--fail-on-problems` exits 1 if any mismatch / missing / unmatched row exists.

Each run writes `report.html`, `report.csv`, and `report.json`. Columns:
`path`, `status`, `jsonValue`, `xmlMappedValue`, plus resolved paths and a
status reason.

## Run (Java)

No system Maven needed. `build.sh` fetches a JDK into `/tmp` if `javac` is missing, then builds a fat jar:

```bash
cd java
./build.sh
java -jar target/json-xml-compare.jar \
  --json ../../y1.fixed.json \
  --xml ../../y2.fixed.xml \
  --mapping ../mapping.yaml \
  --out report.html
```

Same flags as the Python script: `--batch-dir`, `--manifest`, `--out-dir`,
`--fail-on-problems`. If `--mapping` is omitted, the jar walks up from its
location until it finds `mapping.yaml`.

## Mapping

Edit `mapping.yaml`. Field names are not hard-coded in the script. Schema is
documented at the top of that file: scalar `fields`, repeating `collections`
joined on a business key, and `enums` for JSON→XML value translation.
