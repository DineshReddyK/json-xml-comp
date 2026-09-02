#!/usr/bin/env bash
# Build json-xml-compare.jar without a system Maven/JDK install.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
SRC="$ROOT/src/main/java"
OUT="$ROOT/target/classes"
JAR="$ROOT/target/json-xml-compare.jar"
LIB="$ROOT/lib"
TOOLS="${TOOLS_DIR:-/tmp/json-xml-compare-tools}"
mkdir -p "$LIB" "$OUT" "$TOOLS"

fetch() {
  local url="$1" dest="$2"
  if [[ -f "$dest" && -s "$dest" ]]; then
    return 0
  fi
  echo "downloading $(basename "$dest")"
  python3 - "$url" "$dest" <<'PY'
import sys, urllib.request
url, dest = sys.argv[1], sys.argv[2]
req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 json-xml-compare-build"})
with urllib.request.urlopen(req) as fp, open(dest, "wb") as out:
    while True:
        chunk = fp.read(1024 * 1024)
        if not chunk:
            break
        out.write(chunk)
PY
}

CENTRAL="https://repo1.maven.org/maven2"
fetch "$CENTRAL/com/fasterxml/jackson/core/jackson-core/2.13.5/jackson-core-2.13.5.jar" "$LIB/jackson-core-2.13.5.jar"
fetch "$CENTRAL/com/fasterxml/jackson/core/jackson-annotations/2.13.5/jackson-annotations-2.13.5.jar" "$LIB/jackson-annotations-2.13.5.jar"
fetch "$CENTRAL/com/fasterxml/jackson/core/jackson-databind/2.13.5/jackson-databind-2.13.5.jar" "$LIB/jackson-databind-2.13.5.jar"
fetch "$CENTRAL/org/yaml/snakeyaml/1.33/snakeyaml-1.33.jar" "$LIB/snakeyaml-1.33.jar"

if ! command -v javac >/dev/null 2>&1; then
  JDK_TGZ="$TOOLS/OpenJDK11U-jdk_x64_linux.tar.gz"
  fetch "https://github.com/adoptium/temurin11-binaries/releases/download/jdk-11.0.24%2B8/OpenJDK11U-jdk_x64_linux_hotspot_11.0.24_8.tar.gz" "$JDK_TGZ"
  if [[ ! -x "$TOOLS/jdk/bin/javac" ]]; then
    rm -rf "$TOOLS/jdk-extract" "$TOOLS/jdk"
    mkdir -p "$TOOLS/jdk-extract"
    tar -xzf "$JDK_TGZ" -C "$TOOLS/jdk-extract"
    mv "$TOOLS/jdk-extract"/jdk-* "$TOOLS/jdk"
  fi
  export JAVA_HOME="$TOOLS/jdk"
  export PATH="$JAVA_HOME/bin:$PATH"
fi

echo "using javac: $(command -v javac)"
find "$OUT" -type f -name '*.class' -delete 2>/dev/null || true
javac -encoding UTF-8 -source 8 -target 8 -cp "$LIB/*" -d "$OUT" "$SRC"/compare/*.java

STAGE="$ROOT/target/stage"
rm -rf "$STAGE"
mkdir -p "$STAGE"
for jar in "$LIB"/*.jar; do
  unzip -qo "$jar" -d "$STAGE"
done
rm -rf "$STAGE/META-INF"
cp -R "$OUT"/. "$STAGE"/
printf 'Main-Class: compare.CompareJsonXml\n' > "$ROOT/target/MANIFEST.MF"
jar cfe "$JAR" compare.CompareJsonXml -C "$STAGE" .
echo "built $JAR"
echo "run: java -jar $JAR --help"
