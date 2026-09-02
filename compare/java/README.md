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
