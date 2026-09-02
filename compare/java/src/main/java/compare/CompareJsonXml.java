package compare;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * JSON &lt;-&gt; XML comparator for valid, well-formed files.
 *
 * <pre>
 *   java -jar json-xml-compare.jar --json deal.json --xml deal.xml --out report.html
 *   java -jar json-xml-compare.jar --batch-dir ./data --out-dir ./reports
 *   java -jar json-xml-compare.jar --manifest pairs.csv --out-dir ./reports
 * </pre>
 *
 * Mapping defaults to {@code mapping.yaml} next to the jar / source tree.
 */
public final class CompareJsonXml {
    private static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        try {
            Cli cli = Cli.parse(args);
            JsonNode mapping = MappingLoader.load(cli.mapping);
            List<String[]> pairs;
            boolean batch;
            if (cli.manifest != null) {
                pairs = manifestPairs(cli.manifest);
                batch = true;
            } else if (cli.batchDir != null) {
                pairs = directoryPairs(cli.batchDir, cli.jsonGlob, cli.xmlExtension);
                batch = true;
            } else if (cli.json != null && cli.xml != null) {
                pairs = Arrays.asList(new String[][] {
                    { cli.json.toString(), cli.xml.toString(), stem(cli.json) }
                });
                batch = false;
            } else {
                System.err.println("use --json FILE --xml FILE, --batch-dir DIR, or --manifest FILE");
                return 2;
            }
            if (pairs.isEmpty()) {
                throw new IllegalArgumentException("no JSON/XML pairs found");
            }

            List<Map<String, Object>> batchResults = new ArrayList<Map<String, Object>>();
            boolean hasProblems = false;
            for (String[] pair : pairs) {
                Path jsonPath = Paths.get(pair[0]);
                Path xmlPath = Paths.get(pair[1]);
                String name = pair[2];
                Path output = batch
                        ? cli.outDir.resolve(name + ".html")
                        : cli.out;
                List<Row> rows = runPair(jsonPath, xmlPath, output, mapping, name);
                Map<String, Integer> counts = Reports.counts(rows);
                int problems = Reports.problemCount(counts);
                hasProblems |= problems > 0;
                System.out.println(name + ": " + rows.size() + " comparisons, "
                        + nz(counts.get(Status.MISMATCH)) + " mismatches, "
                        + problems + " problems -> " + output);
                Map<String, Object> result = new LinkedHashMap<String, Object>();
                result.put("name", name);
                result.put("report", output.getFileName().toString());
                result.put("total", Integer.valueOf(rows.size()));
                result.put("counts", counts);
                batchResults.add(result);
            }
            if (batch) {
                Files.createDirectories(cli.outDir);
                Path index = cli.outDir.resolve("index.html");
                Reports.writeBatchIndex(batchResults, index);
                System.out.println("batch index -> " + index);
            }
            return cli.failOnProblems && hasProblems ? 1 : 0;
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            return 2;
        } catch (Exception e) {
            System.err.println("error: " + e.getMessage());
            return 2;
        }
    }

    static List<Row> runPair(Path jsonPath, Path xmlPath, Path outputHtml,
                             JsonNode mapping, String name) throws Exception {
        JsonNode jsonRoot;
        try {
            jsonRoot = JSON.readTree(jsonPath.toFile());
        } catch (IOException e) {
            throw new IllegalArgumentException(jsonPath + ": invalid JSON: " + e.getMessage(), e);
        }

        Element xmlRoot;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            Document document = factory.newDocumentBuilder().parse(xmlPath.toFile());
            xmlRoot = document.getDocumentElement();
        } catch (SAXException e) {
            throw new IllegalArgumentException(xmlPath + ": invalid XML: " + e.getMessage(), e);
        }

        List<Row> rows = Engine.compareDocuments(jsonRoot, xmlRoot, mapping);
        Map<String, String> metadata = new LinkedHashMap<String, String>();
        metadata.put("name", name);
        metadata.put("json", jsonPath.toString());
        metadata.put("xml", xmlPath.toString());
        metadata.put("mapping", mapping.path("name").asText("mapping"));
        metadata.put("generated", OffsetDateTime.now(ZoneId.systemDefault())
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        Reports.writeAll(rows, outputHtml, metadata);
        return rows;
    }

    static List<String[]> manifestPairs(Path path) throws IOException {
        BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
        try {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                throw new IllegalArgumentException(path + ": manifest requires columns json,xml[,name]");
            }
            String[] header = headerLine.split(",", -1);
            int jsonIdx = indexOf(header, "json");
            int xmlIdx = indexOf(header, "xml");
            int nameIdx = indexOf(header, "name");
            if (jsonIdx < 0 || xmlIdx < 0) {
                throw new IllegalArgumentException(path + ": manifest requires columns json,xml[,name]");
            }
            List<String[]> pairs = new ArrayList<String[]>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                String[] cols = line.split(",", -1);
                Path json = Paths.get(cols[jsonIdx].trim());
                Path xml = Paths.get(cols[xmlIdx].trim());
                String name = nameIdx >= 0 && nameIdx < cols.length && !cols[nameIdx].trim().isEmpty()
                        ? cols[nameIdx].trim()
                        : stem(json);
                pairs.add(new String[] { json.toString(), xml.toString(), name });
            }
            return pairs;
        } finally {
            reader.close();
        }
    }

    static List<String[]> directoryPairs(Path dir, String jsonGlob, String xmlExtension) throws IOException {
        List<String[]> pairs = new ArrayList<String[]>();
        DirectoryStream<Path> stream = Files.newDirectoryStream(dir, jsonGlob);
        try {
            List<Path> files = new ArrayList<Path>();
            for (Path jsonPath : stream) {
                files.add(jsonPath);
            }
            java.util.Collections.sort(files);
            for (Path jsonPath : files) {
                String fileName = jsonPath.getFileName().toString();
                int dot = fileName.lastIndexOf('.');
                String base = dot < 0 ? fileName : fileName.substring(0, dot);
                Path xmlPath = jsonPath.resolveSibling(base + xmlExtension);
                if (!Files.exists(xmlPath)) {
                    System.err.println("skip " + jsonPath + ": expected XML partner " + xmlPath);
                    continue;
                }
                pairs.add(new String[] { jsonPath.toString(), xmlPath.toString(), base });
            }
        } finally {
            stream.close();
        }
        return pairs;
    }

    static Path defaultMappingPath() {
        try {
            URI uri = CompareJsonXml.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            Path loc = Paths.get(uri);
            Path start = Files.isRegularFile(loc) ? loc.getParent() : loc;
            Path cursor = start;
            for (int i = 0; i < 8 && cursor != null; i++) {
                Path candidate = cursor.resolve("mapping.yaml");
                if (Files.exists(candidate)) {
                    return candidate;
                }
                cursor = cursor.getParent();
            }
        } catch (Exception ignored) {
            // fall through
        }
        Path cwd = Paths.get("mapping.yaml");
        if (Files.exists(cwd)) {
            return cwd;
        }
        return Paths.get("mapping.yaml");
    }

    private static String stem(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    private static int indexOf(String[] header, String name) {
        for (int i = 0; i < header.length; i++) {
            if (name.equals(header[i].trim())) {
                return i;
            }
        }
        return -1;
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value.intValue();
    }

    static final class Cli {
        Path json;
        Path xml;
        Path out = Paths.get("comparison-report.html");
        Path batchDir;
        Path manifest;
        Path outDir = Paths.get("comparison-reports");
        String jsonGlob = "*.json";
        String xmlExtension = ".xml";
        Path mapping = defaultMappingPath();
        boolean failOnProblems;

        static Cli parse(String[] args) {
            Cli cli = new Cli();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if ("--json".equals(arg)) {
                    cli.json = Paths.get(next(args, ++i, arg));
                } else if ("--xml".equals(arg)) {
                    cli.xml = Paths.get(next(args, ++i, arg));
                } else if ("--out".equals(arg)) {
                    cli.out = Paths.get(next(args, ++i, arg));
                } else if ("--batch-dir".equals(arg)) {
                    cli.batchDir = Paths.get(next(args, ++i, arg));
                } else if ("--manifest".equals(arg)) {
                    cli.manifest = Paths.get(next(args, ++i, arg));
                } else if ("--out-dir".equals(arg)) {
                    cli.outDir = Paths.get(next(args, ++i, arg));
                } else if ("--json-glob".equals(arg)) {
                    cli.jsonGlob = next(args, ++i, arg);
                } else if ("--xml-extension".equals(arg)) {
                    cli.xmlExtension = next(args, ++i, arg);
                } else if ("--mapping".equals(arg)) {
                    cli.mapping = Paths.get(next(args, ++i, arg));
                } else if ("--fail-on-problems".equals(arg)) {
                    cli.failOnProblems = true;
                } else if ("-h".equals(arg) || "--help".equals(arg)) {
                    printHelp();
                    System.exit(0);
                } else {
                    throw new IllegalArgumentException("unknown argument: " + arg);
                }
            }
            return cli;
        }

        private static String next(String[] args, int index, String flag) {
            if (index >= args.length) {
                throw new IllegalArgumentException(flag + " requires a value");
            }
            return args[index];
        }

        private static void printHelp() {
            System.out.println("JSON <-> XML comparator for valid files.");
            System.out.println();
            System.out.println("  --json FILE            valid JSON input");
            System.out.println("  --xml FILE             valid XML input");
            System.out.println("  --out FILE             HTML report (default: comparison-report.html)");
            System.out.println("  --batch-dir DIR        pair *.json/*.xml by filename stem");
            System.out.println("  --manifest FILE        CSV columns: json,xml[,name]");
            System.out.println("  --out-dir DIR          batch output directory");
            System.out.println("  --json-glob GLOB       default *.json");
            System.out.println("  --xml-extension EXT   default .xml");
            System.out.println("  --mapping FILE         mapping.yaml or .json");
            System.out.println("  --fail-on-problems    exit 1 if mismatches/missing/unmatched rows exist");
        }
    }
}
