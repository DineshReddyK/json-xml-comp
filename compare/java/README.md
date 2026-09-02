# Java port of compare_json_xml.py

Same CLI and the same `../mapping.yaml`. Java 8.

This box has a JRE but no Maven/`javac`. Use the bootstrap script:

```bash
./build.sh
java -jar target/json-xml-compare.jar --json ../../y1.fixed.json --xml ../../y2.fixed.xml --out report.html
```

If you do have Maven:

```bash
mvn -q package
java -jar target/json-xml-compare.jar --help
```

## Source layout

| File | Role |
| --- | --- |
| `CompareJsonXml.java` | CLI, pairing, batch driver |
| `MappingLoader.java` | loads and validates `mapping.yaml` / `.json` |
| `Paths.java` | JSON dot-path and XML XPath-like resolution |
| `Engine.java` | field comparison, enum translation, collection joins |
| `Coverage.java` | sweep for JSON/XML paths the mapping never reads |
| `Reports.java` | HTML / CSV / JSON output |
| `Theme.java` | report CSS + JS, generated from the Python constants |

`Theme.java` is generated from `REPORT_CSS` / `REPORT_JS` in
`../compare_json_xml.py`. Edit the styling there, then regenerate, otherwise
the two implementations drift.
