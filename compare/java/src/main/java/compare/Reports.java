package compare;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class Reports {
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private Reports() {}

    static void writeAll(List<Row> rows, Path htmlPath, Map<String, String> metadata) throws IOException {
        if (htmlPath.getParent() != null) {
            Files.createDirectories(htmlPath.getParent());
        }
        writeHtml(rows, htmlPath, metadata);
        writeCsv(rows, withSuffix(htmlPath, ".csv"));
        writeJson(rows, withSuffix(htmlPath, ".json"), metadata);
    }

    static void writeCsv(List<Row> rows, Path path) throws IOException {
        BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
        try {
            writer.write(joinCsv(Row.COLUMNS));
            writer.newLine();
            for (Row row : rows) {
                Map<String, String> map = row.asMap();
                String[] values = new String[Row.COLUMNS.length];
                for (int i = 0; i < Row.COLUMNS.length; i++) {
                    values[i] = csv(map.get(Row.COLUMNS[i]));
                }
                writer.write(joinCsv(values));
                writer.newLine();
            }
        } finally {
            writer.close();
        }
    }

    static void writeJson(List<Row> rows, Path path, Map<String, String> metadata) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("metadata", metadata);
        payload.put("summary", counts(rows));
        List<Map<String, String>> rowMaps = new ArrayList<Map<String, String>>();
        for (Row row : rows) {
            rowMaps.add(row.asMap());
        }
        payload.put("rows", rowMaps);
        MAPPER.writeValue(path.toFile(), payload);
    }

    static void writeHtml(List<Row> rows, Path path, Map<String, String> metadata) throws IOException {
        Map<String, Integer> counts = counts(rows);
        LinkedHashSet<String> groups = new LinkedHashSet<String>();
        for (Row row : rows) {
            groups.add(row.group);
        }

        StringBuilder cards = new StringBuilder();
        for (String status : Status.ORDER) {
            Integer count = counts.get(status);
            int n = count == null ? 0 : count.intValue();
            String color = Status.COLORS.get(status);
            cards.append("<button class=\"card\" data-status=\"").append(status).append("\">")
                    .append("<b style=\"color:").append(color).append("\">").append(n).append("</b>")
                    .append("<span>").append(status.replace('_', ' ')).append("</span></button>");
        }

        StringBuilder body = new StringBuilder();
        for (Row row : rows) {
            List<String> values = new ArrayList<String>();
            values.add(row.group);
            values.add(row.key);
            values.add(row.path);
            values.add(row.status);
            values.add(row.jsonValue);
            values.add(row.xmlMappedValue);
            values.add(row.jsonPath);
            values.add(row.xmlPath);
            values.add(row.detail);
            String raw = MAPPER.writeValueAsString(values);
            String haystack = String.join(" ", values).toLowerCase(Locale.ROOT);
            String color = Status.COLORS.get(row.status);
            body.append("<tr data-status=\"").append(esc(row.status)).append("\" data-group=\"")
                    .append(esc(row.group)).append("\" data-hay=\"").append(esc(haystack))
                    .append("\" data-values='").append(esc(raw)).append("'>")
                    .append("<td>").append(esc(row.group)).append("</td>")
                    .append("<td class=\"mono\">").append(esc(row.key)).append("</td>")
                    .append("<td class=\"mono\">").append(esc(row.path)).append("</td>")
                    .append("<td><span class=\"tag\" style=\"color:").append(color)
                    .append(";border-color:").append(color).append("\">")
                    .append(esc(row.status)).append("</span></td>")
                    .append("<td class=\"mono value\">").append(empty(row.jsonValue)).append("</td>")
                    .append("<td class=\"mono value\">").append(empty(row.xmlMappedValue)).append("</td>")
                    .append("<td class=\"mono path\">").append(esc(row.jsonPath)).append("</td>")
                    .append("<td class=\"mono path\">").append(esc(row.xmlPath)).append("</td>")
                    .append("<td class=\"detail\">").append(esc(row.detail)).append("</td></tr>");
        }

        StringBuilder options = new StringBuilder();
        for (String group : groups) {
            options.append("<option value=\"").append(esc(group)).append("\">")
                    .append(esc(group)).append("</option>");
        }

        String title = metadata.get("name");
        String html = "<!doctype html>\n"
                + "<html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width\">\n"
                + "<title>" + esc(title) + " comparison</title><style>" + CSS + "</style></head><body>\n"
                + "<header><h1>JSON ↔ XML comparison — " + esc(title) + "</h1>\n"
                + "<div class=\"meta\">JSON: " + esc(metadata.get("json"))
                + " · XML: " + esc(metadata.get("xml"))
                + " · Mapping: " + esc(metadata.get("mapping"))
                + " · " + rows.size() + " comparisons"
                + " · Generated: " + esc(metadata.get("generated")) + "</div></header>\n"
                + "<main><div class=\"cards\">" + cards + "</div><div class=\"toolbar\">\n"
                + "<input id=\"q\" type=\"search\" placeholder=\"Filter path, value, status…\">\n"
                + "<select id=\"group\"><option value=\"*\">All groups</option>" + options + "</select>\n"
                + "<button id=\"problems\">Only problems</button><button id=\"all\">Reset</button>\n"
                + "<button id=\"csv\">Download filtered CSV</button>"
                + "<span class=\"count\"><b id=\"shown\">0</b> shown</span>\n"
                + "</div><div class=\"wrap\"><table><thead><tr><th>Group</th><th>Key</th><th>Path</th>\n"
                + "<th>Status</th><th>JSON value</th><th>XML mapped value</th><th>JSON path</th>\n"
                + "<th>XML path</th><th>Detail</th></tr></thead><tbody>" + body
                + "</tbody></table></div>\n"
                + "</main><script>" + JS + "</script></body></html>";
        Files.write(path, html.getBytes(StandardCharsets.UTF_8));
    }

    static void writeBatchIndex(List<Map<String, Object>> results, Path path) throws IOException {
        StringBuilder table = new StringBuilder();
        for (Map<String, Object> result : results) {
            @SuppressWarnings("unchecked")
            Map<String, Integer> counts = (Map<String, Integer>) result.get("counts");
            int problems = 0;
            for (String status : Status.PROBLEMS) {
                Integer n = counts.get(status);
                if (n != null) {
                    problems += n.intValue();
                }
            }
            table.append("<tr><td><a href=\"").append(esc(String.valueOf(result.get("report"))))
                    .append("\">").append(esc(String.valueOf(result.get("name")))).append("</a></td>")
                    .append("<td>").append(result.get("total")).append("</td>")
                    .append("<td>").append(nz(counts.get(Status.MISMATCH))).append("</td>")
                    .append("<td>").append(problems).append("</td>")
                    .append("<td>").append(nz(counts.get(Status.MATCH))).append("</td>")
                    .append("<td>").append(nz(counts.get(Status.NORMALIZED_MATCH))).append("</td></tr>");
        }
        String html = "<!doctype html><html><head><meta charset=\"utf-8\"><title>Comparison batch</title>\n"
                + "<style>body{font:14px system-ui;background:#0b0f17;color:#e5e7eb;padding:30px}\n"
                + "table{border-collapse:collapse;width:100%}th,td{padding:10px;border-bottom:1px solid #273044;\n"
                + "text-align:left}a{color:#60a5fa}</style></head><body><h1>JSON ↔ XML comparison batch</h1>\n"
                + "<table><tr><th>Pair</th><th>Comparisons</th><th>Mismatches</th><th>All problems</th>\n"
                + "<th>Matches</th><th>Normalized matches</th></tr>"
                + table + "</table></body></html>";
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Files.write(path, html.getBytes(StandardCharsets.UTF_8));
    }

    static Map<String, Integer> counts(List<Row> rows) {
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        for (String status : Status.ORDER) {
            counts.put(status, Integer.valueOf(0));
        }
        for (Row row : rows) {
            Integer n = counts.get(row.status);
            counts.put(row.status, Integer.valueOf((n == null ? 0 : n.intValue()) + 1));
        }
        return counts;
    }

    static int problemCount(Map<String, Integer> counts) {
        int total = 0;
        for (String status : Status.PROBLEMS) {
            Integer n = counts.get(status);
            if (n != null) {
                total += n.intValue();
            }
        }
        return total;
    }

    private static Path withSuffix(Path html, String suffix) {
        String name = html.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot < 0 ? name : name.substring(0, dot);
        Path parent = html.getParent();
        Path file = java.nio.file.Paths.get(base + suffix);
        return parent == null ? file : parent.resolve(file);
    }

    private static String csv(String value) {
        if (value == null) {
            value = "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private static String joinCsv(String[] values) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(values[i]);
        }
        return builder.toString();
    }

    private static String esc(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String empty(String value) {
        return value == null || value.isEmpty() ? "—" : esc(value);
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value.intValue();
    }

    private static final String CSS =
            ":root{color-scheme:dark;--bg:#0b0f17;--panel:#121824;--line:#273044;--text:#e5e7eb;--muted:#8993a7}"
            + "*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);"
            + "font:13px/1.45 Inter,ui-sans-serif,system-ui,sans-serif}header{padding:20px 24px;"
            + "border-bottom:1px solid var(--line);background:#0e1420;position:sticky;top:0;z-index:5}"
            + "h1{font-size:19px;margin:0 0 5px}.meta{color:var(--muted);font-size:12px}"
            + "main{padding:18px 24px 50px}.cards{display:flex;gap:8px;flex-wrap:wrap;margin-bottom:12px}"
            + ".card{background:var(--panel);color:var(--text);border:1px solid var(--line);"
            + "border-radius:9px;padding:9px 12px;text-align:left;cursor:pointer}.card.off{opacity:.3}"
            + ".card b{font-size:20px;display:block}.card span{font-size:10px;color:var(--muted)}"
            + ".toolbar{display:flex;gap:8px;margin:12px 0}.toolbar input,.toolbar select,.toolbar button{"
            + "background:var(--panel);border:1px solid var(--line);color:var(--text);border-radius:7px;padding:8px 10px}"
            + ".toolbar input{min-width:310px}.toolbar .count{color:var(--muted);padding:8px}"
            + ".wrap{overflow:auto;border:1px solid var(--line);border-radius:10px}"
            + "table{border-collapse:collapse;width:100%;background:var(--panel)}th,td{padding:8px 10px;"
            + "border-bottom:1px solid var(--line);text-align:left;vertical-align:top}th{position:sticky;top:0;"
            + "background:#192131;color:var(--muted);font-size:10px;letter-spacing:.05em;text-transform:uppercase}"
            + "tbody tr:hover{background:#172033}.mono{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;"
            + "font-size:12px}.value{max-width:320px;overflow-wrap:anywhere}"
            + ".path{color:#aeb8cd;max-width:230px;overflow-wrap:anywhere}"
            + ".detail{color:var(--muted);max-width:260px;overflow-wrap:anywhere}"
            + ".tag{border:1px solid;border-radius:12px;font-size:10px;font-weight:700;padding:2px 6px;white-space:nowrap}";

    private static final String JS =
            "const rows=[...document.querySelectorAll(\"tbody tr\")];"
            + "const active=new Set([...document.querySelectorAll(\".card\")].map(x=>x.dataset.status));"
            + "function filter(){"
            + "  const q=document.querySelector(\"#q\").value.toLowerCase();"
            + "  const group=document.querySelector(\"#group\").value;let shown=0;"
            + "  for(const row of rows){"
            + "    const visible=active.has(row.dataset.status)&&(group===\"*\"||row.dataset.group===group)"
            + "      &&(!q||row.dataset.hay.includes(q));"
            + "    row.hidden=!visible;if(visible)shown++;"
            + "  } document.querySelector(\"#shown\").textContent=shown;"
            + "}"
            + "document.querySelectorAll(\".card\").forEach(card=>card.onclick=()=>{"
            + "  const status=card.dataset.status;"
            + "  if(active.has(status)){active.delete(status);card.classList.add(\"off\")}"
            + "  else{active.add(status);card.classList.remove(\"off\")} filter();"
            + "});"
            + "document.querySelector(\"#q\").oninput=filter;"
            + "document.querySelector(\"#group\").onchange=filter;"
            + "document.querySelector(\"#problems\").onclick=()=>{"
            + "  active.clear();[\"MISMATCH\",\"MISSING_IN_JSON\",\"MISSING_IN_XML\","
            + "  \"UNMATCHED_JSON_ROW\",\"UNMATCHED_XML_ROW\"].forEach(x=>active.add(x));"
            + "  document.querySelectorAll(\".card\").forEach(x=>x.classList.toggle(\"off\",!active.has(x.dataset.status)));"
            + "  filter();"
            + "};"
            + "document.querySelector(\"#all\").onclick=()=>{"
            + "  document.querySelectorAll(\".card\").forEach(x=>{active.add(x.dataset.status);x.classList.remove(\"off\")});"
            + "  document.querySelector(\"#q\").value=\"\";document.querySelector(\"#group\").value=\"*\";filter();"
            + "};"
            + "document.querySelector(\"#csv\").onclick=()=>{"
            + "  const header=[\"group\",\"key\",\"path\",\"status\",\"jsonValue\",\"xmlMappedValue\",\"jsonPath\",\"xmlPath\",\"detail\"];"
            + "  const quote=v=>`\"${String(v).replaceAll('\"','\"\"')}\"`;"
            + "  const data=[header,...rows.filter(x=>!x.hidden).map(x=>JSON.parse(x.dataset.values))];"
            + "  const blob=new Blob([data.map(r=>r.map(quote).join(\",\")).join(\"\\n\")],{type:\"text/csv\"});"
            + "  const a=document.createElement(\"a\");a.href=URL.createObjectURL(blob);a.download=\"filtered-report.csv\";a.click();"
            + "};filter();";
}
