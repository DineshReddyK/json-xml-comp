package compare;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class MappingLoader {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> VALID_TYPES = new HashSet<String>(Arrays.asList(
            "string", "number", "bool", "date", "datetime", "id", "exists"
    ));

    private MappingLoader() {}

    static JsonNode load(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            throw new IllegalArgumentException("mapping file not found: " + path);
        }
        String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        JsonNode mapping;
        if (name.endsWith(".yaml") || name.endsWith(".yml")) {
            Object loaded = new Yaml().load(text);
            mapping = MAPPER.valueToTree(loaded);
        } else {
            mapping = MAPPER.readTree(text);
        }
        validate(mapping, path);
        return mapping;
    }

    static void validate(JsonNode mapping, Path path) {
        if (mapping == null || !mapping.isObject()) {
            throw new IllegalArgumentException(path + ": mapping must be a mapping/object at the top level");
        }
        if (!mapping.has("fields") && !mapping.has("collections")) {
            throw new IllegalArgumentException(path + ": mapping defines neither 'fields' nor 'collections'");
        }
        JsonNode enums = mapping.path("enums");
        List<String> problems = new ArrayList<String>();

        int index = 0;
        for (JsonNode field : mapping.path("fields")) {
            String label = field.isObject() ? field.path("path").asText(String.valueOf(index)) : String.valueOf(index);
            checkField(field, "fields[" + label + "]", enums, problems);
            index++;
        }
        index = 0;
        for (JsonNode collection : mapping.path("collections")) {
            if (!collection.isObject()) {
                problems.add("collections[" + index + "]: must be a mapping/object");
                continue;
            }
            String nameLabel = collection.path("name").asText(String.valueOf(index));
            if (blank(collection.get("json"))) {
                problems.add("collections[" + nameLabel + "]: missing 'json'");
            }
            if (blank(collection.get("xml"))) {
                problems.add("collections[" + nameLabel + "]: missing 'xml'");
            }
            JsonNode join = collection.path("join");
            if (blank(join.get("json")) || blank(join.get("xml"))) {
                problems.add("collections[" + nameLabel + "]: 'join' needs both a json and an xml key");
            }
            for (JsonNode field : collection.path("fields")) {
                String label = field.isObject() ? field.path("path").asText("?") : "?";
                checkField(field, "collections[" + nameLabel + "].fields[" + label + "]", enums, problems);
            }
            index++;
        }
        if (!problems.isEmpty()) {
            StringBuilder message = new StringBuilder(path.toString()).append(": invalid mapping:");
            for (String problem : problems) {
                message.append("\n  - ").append(problem);
            }
            throw new IllegalArgumentException(message.toString());
        }
    }

    private static void checkField(JsonNode field, String where, JsonNode enums, List<String> problems) {
        if (field == null || !field.isObject()) {
            problems.add(where + ": each field must be a mapping/object");
            return;
        }
        for (String key : Arrays.asList("path", "json", "xml")) {
            if (blank(field.get(key))) {
                problems.add(where + ": missing '" + key + "'");
            }
        }
        String type = field.has("type") ? field.get("type").asText("string") : "string";
        if (!VALID_TYPES.contains(type)) {
            problems.add(where + ": unknown type '" + type + "', expected one of " + sorted(VALID_TYPES));
        }
        if (field.hasNonNull("enum") && !enums.has(field.get("enum").asText())) {
            problems.add(where + ": enum '" + field.get("enum").asText() + "' is not defined under 'enums'");
        }
    }

    private static boolean blank(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode()
                || (node.isTextual() && node.asText().isEmpty())
                || (node.isArray() && node.size() == 0);
    }

    private static List<String> sorted(Set<String> values) {
        List<String> list = new ArrayList<String>(values);
        java.util.Collections.sort(list);
        return list;
    }
}
