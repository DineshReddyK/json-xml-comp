package compare;

import com.fasterxml.jackson.databind.JsonNode;
import org.w3c.dom.Element;
import org.w3c.dom.ls.DOMImplementationLS;
import org.w3c.dom.ls.LSSerializer;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

final class Engine {
    private static final Set<String> TRUE_VALUES =
            new HashSet<String>(Arrays.asList("1", "true", "yes", "y", "on"));
    private static final Set<String> FALSE_VALUES =
            new HashSet<String>(Arrays.asList("0", "false", "no", "n", "off"));

    private Engine() {}

    static List<Row> compareDocuments(JsonNode jsonRoot, Element xmlRoot, JsonNode mapping) {
        JsonNode enums = mapping.path("enums");
        List<Row> rows = new ArrayList<Row>();
        for (JsonNode field : mapping.path("fields")) {
            rows.add(makeRow(field, text(field, "group", "Fields"), "", jsonRoot, xmlRoot, enums));
        }
        for (JsonNode collection : mapping.path("collections")) {
            rows.addAll(compareCollection(jsonRoot, xmlRoot, collection, enums));
        }
        return rows;
    }

    static Row makeRow(JsonNode field, String group, String key,
                       JsonNode jsonParent, Element xmlParent, JsonNode enums) {
        Object[] jsonHit = Paths.jsonValue(jsonParent, field.get("json"));
        Object[] xmlHit = Paths.xmlValue(xmlParent, field.get("xml"));
        String[] compared = compare(field, jsonHit[0], xmlHit[0], enums);
        return new Row(
                group, key, field.path("path").asText(), compared[0],
                Paths.display(jsonHit[0]), Paths.display(xmlHit[0]),
                String.valueOf(jsonHit[1]), String.valueOf(xmlHit[1]), compared[1]
        );
    }

    static String[] compare(JsonNode field, Object jsonData, Object xmlData, JsonNode enums) {
        boolean jsonMissing = Paths.absent(jsonData);
        boolean xmlMissing = Paths.absent(xmlData);
        if (jsonMissing && xmlMissing) {
            return new String[] { Status.OPTIONAL_MISSING, "absent on both sides" };
        }
        if (jsonMissing) {
            String status = bool(field, "optional_json") ? Status.OPTIONAL_MISSING : Status.MISSING_IN_JSON;
            return new String[] { status, "value is absent in JSON" };
        }
        if (xmlMissing) {
            String status = bool(field, "optional_xml") ? Status.OPTIONAL_MISSING : Status.MISSING_IN_XML;
            return new String[] { status, "value is absent in XML" };
        }

        String valueType = text(field, "type", "string");
        if ("id".equals(valueType)) {
            return new String[] { Status.ID_PAIR, "different identifier spaces; paired, not value-compared" };
        }
        if ("exists".equals(valueType)) {
            return new String[] { Status.MATCH, "present on both sides" };
        }

        String enumName = field.hasNonNull("enum") ? field.get("enum").asText() : null;
        Object expectedJson = jsonData;
        if (enumName != null) {
            JsonNode table = enums.path(enumName);
            String jsonText = Paths.display(jsonData).toLowerCase(Locale.ROOT);
            JsonNode mapped = null;
            java.util.Iterator<String> names = table.fieldNames();
            while (names.hasNext()) {
                String key = names.next();
                if (key.toLowerCase(Locale.ROOT).equals(jsonText)) {
                    mapped = table.get(key);
                    break;
                }
            }
            if (mapped != null && !mapped.isMissingNode()) {
                expectedJson = mapped;
            }
        }

        if ("number".equals(valueType)) {
            BigDecimal left = decimalValue(expectedJson);
            BigDecimal right = decimalValue(xmlData);
            if (left == null || right == null) {
                return new String[] { Status.MISMATCH, "one value is not a valid number" };
            }
            BigDecimal tolerance = new BigDecimal(text(field, "tolerance", "0"));
            if (left.subtract(right).abs().compareTo(tolerance) <= 0) {
                if (Paths.display(jsonData).equals(Paths.display(xmlData))) {
                    return new String[] { Status.MATCH, "" };
                }
                return new String[] {
                    Status.NORMALIZED_MATCH,
                    "numeric values are equal within tolerance " + tolerance
                };
            }
            return new String[] { Status.MISMATCH, "numeric delta: " + left.subtract(right) };
        }

        if ("bool".equals(valueType)) {
            Boolean left = boolValue(expectedJson);
            Boolean right = boolValue(xmlData);
            if (left == null || right == null) {
                return new String[] { Status.MISMATCH, "one value is not a valid boolean" };
            }
            if (left.equals(right)) {
                if (Paths.display(jsonData).equals(Paths.display(xmlData))) {
                    return new String[] { Status.MATCH, "" };
                }
                return new String[] {
                    Status.NORMALIZED_MATCH,
                    quote(Paths.display(jsonData)) + " equals " + quote(Paths.display(xmlData))
                };
            }
            return new String[] { Status.MISMATCH, "boolean values differ: " + left + " vs " + right };
        }

        if ("date".equals(valueType) || "datetime".equals(valueType)) {
            ParsedDate left = datetimeValue(expectedJson);
            ParsedDate right = datetimeValue(xmlData);
            if (left == null || right == null) {
                return new String[] { Status.MISMATCH, "one value is not a valid ISO date/datetime" };
            }
            boolean equal = "date".equals(valueType)
                    ? left.toLocalDate().equals(right.toLocalDate())
                    : left.toInstant().equals(right.toInstant());
            if (equal) {
                if (Paths.display(jsonData).equals(Paths.display(xmlData))) {
                    return new String[] { Status.MATCH, "" };
                }
                return new String[] { Status.NORMALIZED_MATCH, valueType + " values are equivalent" };
            }
            return new String[] { Status.MISMATCH, valueType + " values differ" };
        }

        String left = Paths.display(expectedJson);
        String right = Paths.display(xmlData);
        if (left.equals(right)) {
            if (Paths.display(jsonData).equals(right)) {
                return new String[] { Status.MATCH, "" };
            }
            return new String[] { Status.NORMALIZED_MATCH, "mapped through enum " + enumName };
        }

        String normalizedLeft = left.trim();
        String normalizedRight = right.trim();
        if (bool(field, "ignore_whitespace")) {
            normalizedLeft = normalizedLeft.replaceAll("\\s+", "");
            normalizedRight = normalizedRight.replaceAll("\\s+", "");
        }
        if (bool(field, "ignore_case") || enumName != null) {
            normalizedLeft = normalizedLeft.toLowerCase(Locale.ROOT);
            normalizedRight = normalizedRight.toLowerCase(Locale.ROOT);
        }
        if (normalizedLeft.equals(normalizedRight)) {
            String reason = enumName != null
                    ? "mapped through enum " + enumName
                    : "equal after normalization";
            return new String[] { Status.NORMALIZED_MATCH, reason };
        }
        if (enumName != null) {
            return new String[] {
                Status.MISMATCH,
                "enum " + enumName + " maps JSON to " + quote(left) + ", XML contains " + quote(right)
            };
        }
        return new String[] { Status.MISMATCH, "" };
    }

    private static List<Row> compareCollection(JsonNode jsonRoot, Element xmlRoot,
                                              JsonNode collection, JsonNode enums) {
        List<Row> rows = new ArrayList<Row>();
        String group = collection.path("name").asText();
        JsonNode join = collection.path("join");
        String keyLabel = text(collection, "key_label", "key");
        String joinJson = join.path("json").asText();
        String joinXml = join.path("xml").asText();

        Map<String, JsonNode> jsonByKey = new HashMap<String, JsonNode>();
        for (JsonNode item : Paths.jsonValues(jsonRoot, collection.path("json").asText())) {
            Object[] hit = Paths.jsonValue(item, join.get("json"));
            if (Paths.absent(hit[0])) {
                rows.add(new Row(group, "", keyLabel, Status.UNMATCHED_JSON_ROW,
                        Paths.display(item), "", joinJson, joinXml,
                        "JSON collection row has no join key"));
            } else {
                String key = Paths.display(hit[0]);
                if (jsonByKey.containsKey(key)) {
                    throw new IllegalArgumentException(group + ": duplicate JSON join key '" + key + "'");
                }
                jsonByKey.put(key, item);
            }
        }

        Map<String, Element> xmlByKey = new HashMap<String, Element>();
        for (Element item : Paths.xmlElements(xmlRoot, collection.path("xml").asText())) {
            Object[] hit = Paths.xmlValue(item, join.get("xml"));
            if (Paths.absent(hit[0])) {
                rows.add(new Row(group, "", keyLabel, Status.UNMATCHED_XML_ROW,
                        "", serialize(item), joinJson, joinXml,
                        "XML collection row has no join key"));
            } else {
                String key = Paths.display(hit[0]);
                if (xmlByKey.containsKey(key)) {
                    throw new IllegalArgumentException(group + ": duplicate XML join key '" + key + "'");
                }
                xmlByKey.put(key, item);
            }
        }

        Set<String> keys = new TreeSet<String>();
        keys.addAll(jsonByKey.keySet());
        keys.addAll(xmlByKey.keySet());
        for (String key : keys) {
            JsonNode jsonItem = jsonByKey.get(key);
            Element xmlItem = xmlByKey.get(key);
            if (jsonItem == null) {
                rows.add(new Row(group, key, keyLabel, Status.UNMATCHED_XML_ROW,
                        "", key, joinJson, joinXml, "row exists only in XML"));
                continue;
            }
            if (xmlItem == null) {
                rows.add(new Row(group, key, keyLabel, Status.UNMATCHED_JSON_ROW,
                        key, "", joinJson, joinXml, "row exists only in JSON"));
                continue;
            }
            rows.add(new Row(group, key, keyLabel, Status.MATCH, key, key, joinJson, joinXml,
                    "collection row joined by business key"));
            for (JsonNode field : collection.path("fields")) {
                rows.add(makeRow(field, group, key, jsonItem, xmlItem, enums));
            }
        }
        return rows;
    }

    private static BigDecimal decimalValue(Object value) {
        String raw = Paths.display(value).replace(",", "").replace("$", "").trim();
        if (raw.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Boolean boolValue(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof JsonNode && ((JsonNode) value).isBoolean()) {
            return Boolean.valueOf(((JsonNode) value).booleanValue());
        }
        String normalized = Paths.display(value).trim().toLowerCase(Locale.ROOT);
        if (TRUE_VALUES.contains(normalized)) {
            return Boolean.TRUE;
        }
        if (FALSE_VALUES.contains(normalized)) {
            return Boolean.FALSE;
        }
        return null;
    }

    private static ParsedDate datetimeValue(Object value) {
        String raw = Paths.display(value).trim();
        if (raw.isEmpty()) {
            return null;
        }
        String iso = raw.replace("Z", "+00:00");
        try {
            if (raw.length() == 10) {
                return new ParsedDate(LocalDate.parse(raw), null, null);
            }
            try {
                OffsetDateTime odt = OffsetDateTime.parse(iso);
                return new ParsedDate(null, null, odt);
            } catch (DateTimeParseException e) {
                return new ParsedDate(null, LocalDateTime.parse(iso), null);
            }
        } catch (DateTimeParseException e) {
            try {
                return new ParsedDate(LocalDate.parse(raw.substring(0, 10)), null, null);
            } catch (Exception e2) {
                return null;
            }
        }
    }

    private static boolean bool(JsonNode node, String field) {
        return node.has(field) && node.get(field).asBoolean(false);
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || value.isMissingNode() ? fallback : value.asText();
    }

    private static String quote(String value) {
        return "'" + value + "'";
    }

    private static String serialize(Element element) {
        try {
            DOMImplementationLS ls = (DOMImplementationLS) element.getOwnerDocument()
                    .getImplementation().getFeature("LS", "3.0");
            LSSerializer serializer = ls.createLSSerializer();
            serializer.getDomConfig().setParameter("xml-declaration", Boolean.FALSE);
            return serializer.writeToString(element);
        } catch (Exception e) {
            return Paths.localName(element);
        }
    }

    private static final class ParsedDate {
        final LocalDate date;
        final LocalDateTime local;
        final OffsetDateTime offset;

        ParsedDate(LocalDate date, LocalDateTime local, OffsetDateTime offset) {
            this.date = date;
            this.local = local;
            this.offset = offset;
        }

        LocalDate toLocalDate() {
            if (date != null) {
                return date;
            }
            if (offset != null) {
                return offset.toLocalDate();
            }
            return local.toLocalDate();
        }

        Instant toInstant() {
            if (offset != null) {
                return offset.toInstant();
            }
            if (local != null) {
                return local.toInstant(ZoneOffset.UTC);
            }
            return date.atStartOfDay().toInstant(ZoneOffset.UTC);
        }
    }
}
