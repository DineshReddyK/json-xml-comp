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
        Map<String, Integer> groupCounts = new LinkedHashMap<String, Integer>();
        for (Row row : rows) {
            Integer n = groupCounts.get(row.group);
            groupCounts.put(row.group, Integer.valueOf((n == null ? 0 : n.intValue()) + 1));
        }

        int total = rows.size();
        int matched = 0;
        for (String status : Status.MATCHED) {
            matched += nz(counts.get(status));
        }
        int problems = problemCount(counts);
        String rate = total == 0 ? "0.0%"
                : String.format(Locale.ROOT, "%.1f%%", Double.valueOf(100.0 * matched / total));

        String[][] stats = {
                {"Total Comparisons", String.valueOf(total)},
                {"Matched", String.valueOf(matched)},
                {"Needs Attention", String.valueOf(problems)},
                {"Match Rate", rate},
        };
        StringBuilder statCards = new StringBuilder();
        for (String[] stat : stats) {
            statCards.append("<div class=\"stat\"><span class=\"label\">").append(esc(stat[0]))
                    .append("</span><span class=\"num\">").append(esc(stat[1]))
                    .append("</span></div>");
        }

        StringBuilder chips = new StringBuilder();
        for (String status : Status.ORDER) {
            chips.append("<button class=\"chip\" data-status=\"").append(status)
                    .append("\" style=\"--tone:").append(Status.COLORS.get(status)).append("\">")
                    .append("<b>").append(nz(counts.get(status))).append("</b>")
                    .append("<span>").append(status.replace('_', ' ')).append("</span></button>");
        }

        StringBuilder tabs = new StringBuilder();
        tabs.append("<button class=\"tab is-active\" data-group=\"*\">All Groups <em>")
                .append(total).append("</em></button>");
        for (Map.Entry<String, Integer> entry : groupCounts.entrySet()) {
            tabs.append("<button class=\"tab\" data-group=\"").append(esc(entry.getKey()))
                    .append("\">").append(esc(entry.getKey())).append(" <em>")
                    .append(entry.getValue()).append("</em></button>");
        }

        String views = "<button class=\"view is-active\" data-view=\"all\"><i class=\"dot\"></i>"
                + "All Rows (" + total + ")</button>"
                + "<button class=\"view\" data-view=\"matched\"><i class=\"dot\"></i>"
                + "Matched (" + matched + ")</button>"
                + "<button class=\"view\" data-view=\"problems\"><i class=\"dot\"></i>"
                + "Needs Attention (" + problems + ")</button>";

        String notice;
        if (problems > 0) {
            StringBuilder breakdown = new StringBuilder();
            for (String status : Status.PROBLEMS) {
                int n = nz(counts.get(status));
                if (n == 0) {
                    continue;
                }
                if (breakdown.length() > 0) {
                    breakdown.append(", ");
                }
                breakdown.append(n).append(' ')
                        .append(status.replace('_', ' ').toLowerCase(Locale.ROOT));
            }
            notice = "<div class=\"notice warn\"><b>Attention:</b> " + problems + " of " + total
                    + " comparisons need review (" + breakdown + "). "
                    + "Use the group tabs or status filters below to drill in.</div>";
        } else {
            notice = "<div class=\"notice ok\"><b>Clean run:</b> no mismatches and no "
                    + "unmatched rows across all mapped comparisons.</div>";
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
                    .append("<td class=\"mono\">").append(empty(row.key)).append("</td>")
                    .append("<td class=\"mono\">").append(esc(row.path)).append("</td>")
                    .append("<td><span class=\"pill\" style=\"background:").append(color)
                    .append("1a;color:").append(color).append(";border:1px solid ").append(color)
                    .append("40\">").append(esc(row.status)).append("</span></td>")
                    .append("<td class=\"mono value\">").append(empty(row.jsonValue)).append("</td>")
                    .append("<td class=\"mono value\">").append(empty(row.xmlMappedValue)).append("</td>")
                    .append("<td class=\"mono path\">").append(esc(row.jsonPath)).append("</td>")
                    .append("<td class=\"mono path\">").append(esc(row.xmlPath)).append("</td>")
                    .append("<td class=\"detail\">").append(esc(row.detail)).append("</td></tr>");
        }

        String title = metadata.get("name");
        String html = "<!doctype html>\n"
                + "<html lang=\"en\"><head><meta charset=\"utf-8\">\n"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\n"
                + "<title>JSON &#8596; XML Validation Report &mdash; " + esc(title) + "</title>\n"
                + "<style>" + Theme.CSS + "</style></head><body>\n"
                + "<div class=\"page\">\n"
                + "<header class=\"hero\">\n"
                + "  <h1>JSON &#8596; XML Validation Report</h1>\n"
                + "  <div class=\"sub\">Generated on " + esc(metadata.get("generated")) + "</div>\n"
                + "  <div class=\"meta\">Mapping: " + esc(metadata.get("mapping"))
                + "\n    &nbsp;|&nbsp; " + total + " mapped comparisons</div>\n"
                + "</header>\n"
                + "<nav class=\"tabs\">" + tabs + "</nav>\n"
                + "<section class=\"panel\">\n"
                + "  <h2>" + esc(title) + " &mdash; Field Comparison</h2>\n"
                + "  <div class=\"stats\">" + statCards + "</div>\n"
                + "  <div class=\"files\">\n"
                + "    <span><b>JSON</b> " + esc(metadata.get("json")) + "</span>\n"
                + "    <span><b>XML</b> " + esc(metadata.get("xml")) + "</span>\n"
                + "  </div>\n"
                + "</section>\n"
                + "<div class=\"views\">" + views + "</div>\n"
                + notice + "\n"
                + "<div class=\"chips\">" + chips + "</div>\n"
                + "<div class=\"toolbar\">\n"
                + "  <input id=\"q\" type=\"search\""
                + " placeholder=\"Filter by path, value, status or detail&hellip;\">\n"
                + "  <button id=\"csv\" class=\"primary\">Download filtered CSV</button>\n"
                + "  <button id=\"reset\">Reset filters</button>\n"
                + "  <span class=\"count\"><b id=\"shown\">0</b> of " + total + " rows shown</span>\n"
                + "</div>\n"
                + "<h3 class=\"rows-title\">Comparison Rows</h3>\n"
                + "<div class=\"wrap\"><table><thead><tr>\n"
                + "<th>Group</th><th>Key</th><th>Path</th><th>Status</th><th>JSON Value</th>\n"
                + "<th>XML Mapped Value</th><th>JSON Path</th><th>XML Path</th><th>Detail</th>\n"
                + "</tr></thead><tbody>" + body + "</tbody></table></div>\n"
                + "</div>\n"
                + "<script>" + Theme.JS + "</script></body></html>";
        Files.write(path, html.getBytes(StandardCharsets.UTF_8));
    }

    static void writeBatchIndex(List<Map<String, Object>> results, Path path) throws IOException {
        StringBuilder table = new StringBuilder();
        int totalRows = 0;
        int totalProblems = 0;
        for (Map<String, Object> result : results) {
            @SuppressWarnings("unchecked")
            Map<String, Integer> counts = (Map<String, Integer>) result.get("counts");
            int problems = problemCount(counts);
            int matched = 0;
            for (String status : Status.MATCHED) {
                matched += nz(counts.get(status));
            }
            totalProblems += problems;
            totalRows += ((Number) result.get("total")).intValue();
            String tone = problems > 0 ? "#dc2626" : "#16a34a";
            table.append("<tr><td><a href=\"").append(esc(String.valueOf(result.get("report"))))
                    .append("\">").append(esc(String.valueOf(result.get("name")))).append("</a></td>")
                    .append("<td>").append(result.get("total")).append("</td>")
                    .append("<td><b style=\"color:").append(tone).append("\">")
                    .append(nz(counts.get(Status.MISMATCH))).append("</b></td>")
                    .append("<td><b style=\"color:").append(tone).append("\">")
                    .append(problems).append("</b></td>")
                    .append("<td>").append(nz(counts.get(Status.MATCH))).append("</td>")
                    .append("<td>").append(nz(counts.get(Status.NORMALIZED_MATCH))).append("</td>")
                    .append("<td>").append(matched).append("</td></tr>");
        }

        String[][] stats = {
                {"Pairs Compared", String.valueOf(results.size())},
                {"Total Comparisons", String.valueOf(totalRows)},
                {"Needs Attention", String.valueOf(totalProblems)},
        };
        StringBuilder statCards = new StringBuilder();
        for (String[] stat : stats) {
            statCards.append("<div class=\"stat\"><span class=\"label\">").append(esc(stat[0]))
                    .append("</span><span class=\"num\">").append(esc(stat[1]))
                    .append("</span></div>");
        }
        String generated = java.time.OffsetDateTime.now()
                .truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();

        String html = "<!doctype html>\n"
                + "<html lang=\"en\"><head><meta charset=\"utf-8\">\n"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\n"
                + "<title>JSON &#8596; XML Validation Batch</title>\n"
                + "<style>" + Theme.CSS + "</style></head><body>\n"
                + "<div class=\"page\">\n"
                + "<header class=\"hero\">\n"
                + "  <h1>JSON &#8596; XML Validation Batch</h1>\n"
                + "  <div class=\"sub\">Generated on " + esc(generated) + "</div>\n"
                + "  <div class=\"meta\">" + results.size() + " file pairs &nbsp;|&nbsp; "
                + totalRows + " comparisons</div>\n"
                + "</header>\n"
                + "<section class=\"panel\">\n"
                + "  <h2>Batch Summary</h2>\n"
                + "  <div class=\"stats\">" + statCards + "</div>\n"
                + "</section>\n"
                + "<h3 class=\"rows-title\">Reports</h3>\n"
                + "<div class=\"wrap\"><table><thead><tr>\n"
                + "<th>Pair</th><th>Comparisons</th><th>Mismatches</th><th>Needs Attention</th>\n"
                + "<th>Match</th><th>Normalized Match</th><th>Matched Total</th>\n"
                + "</tr></thead><tbody>" + table + "</tbody></table></div>\n"
                + "</div></body></html>";
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
}
