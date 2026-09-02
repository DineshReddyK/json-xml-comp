package compare;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.w3c.dom.Attr;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Paths {
    static final Object MISSING = new Object();
    private static final Pattern JSON_STEP = Pattern.compile("([^.()\\[\\]]+)|\\[(\\*|-?\\d+)\\]");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Paths() {}

    static List<String> asPaths(JsonNode node) {
        List<String> paths = new ArrayList<String>();
        if (node == null || node.isNull() || node.isMissingNode()) {
            return paths;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                paths.add(item.asText());
            }
        } else {
            paths.add(node.asText());
        }
        return paths;
    }

    static List<JsonNode> jsonValues(JsonNode data, String path) {
        List<Object> steps = new ArrayList<Object>();
        Matcher matcher = JSON_STEP.matcher(path);
        while (matcher.find()) {
            String token = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            if (!"*".equals(token) && token.replaceFirst("^-", "").matches("\\d+")) {
                steps.add(Integer.valueOf(token));
            } else {
                steps.add(token);
            }
        }

        List<JsonNode> values = new ArrayList<JsonNode>();
        if (data != null) {
            values.add(data);
        }
        for (Object step : steps) {
            List<JsonNode> next = new ArrayList<JsonNode>();
            for (JsonNode value : values) {
                if (value == null || value.isNull() || value.isMissingNode()) {
                    continue;
                }
                if ("*".equals(step) && value.isArray()) {
                    for (JsonNode item : value) {
                        next.add(item);
                    }
                } else if (step instanceof Integer && value.isArray()) {
                    int index = ((Integer) step).intValue();
                    int size = value.size();
                    if (index < 0) {
                        index = size + index;
                    }
                    if (index >= 0 && index < size) {
                        next.add(value.get(index));
                    }
                } else if (step instanceof String && value.isObject() && value.has((String) step)) {
                    next.add(value.get((String) step));
                }
            }
            values = next;
        }
        return values;
    }

    static Object[] jsonValue(JsonNode data, JsonNode pathNode) {
        List<String> paths = asPaths(pathNode);
        for (String path : paths) {
            List<JsonNode> values = jsonValues(data, path);
            if (!values.isEmpty()) {
                return new Object[] { values.get(0), path };
            }
        }
        return new Object[] { MISSING, paths.isEmpty() ? "" : paths.get(0) };
    }

    static String[] splitXmlPath(String path) {
        if (path.startsWith("@")) {
            return new String[] { ".", path.substring(1) };
        }
        String attribute = null;
        if (path.contains("/@")) {
            int at = path.lastIndexOf("/@");
            attribute = path.substring(at + 2);
            path = path.substring(0, at);
        }
        if (path.startsWith("//")) {
            path = "." + path;
        } else if (!path.startsWith(".")) {
            path = "./" + path;
        }
        return new String[] { path.isEmpty() ? "." : path, attribute };
    }

    static List<Element> xmlElements(Element node, String path) {
        String elementPath = splitXmlPath(path)[0];
        return findElements(node, elementPath);
    }

    static Object[] xmlValue(Element node, JsonNode pathNode) {
        List<String> paths = asPaths(pathNode);
        for (String path : paths) {
            String[] split = splitXmlPath(path);
            List<Element> found = findElements(node, split[0]);
            if (found.isEmpty()) {
                continue;
            }
            Element first = found.get(0);
            if (split[1] == null) {
                String text = textContentDirect(first);
                return new Object[] { text.isEmpty() ? Boolean.TRUE : text, path };
            }
            String attr = attribute(first, split[1]);
            if (attr != null) {
                return new Object[] { attr, path };
            }
        }
        return new Object[] { MISSING, paths.isEmpty() ? "" : paths.get(0) };
    }

    static String display(Object value) {
        if (value == MISSING || value == null) {
            return "";
        }
        if (value instanceof Boolean) {
            return ((Boolean) value).booleanValue() ? "true" : "false";
        }
        if (value instanceof JsonNode) {
            JsonNode node = (JsonNode) value;
            if (node.isNull() || node.isMissingNode()) {
                return "";
            }
            if (node.isBoolean()) {
                return node.booleanValue() ? "true" : "false";
            }
            if (node.isNumber()) {
                return node.numberValue().toString();
            }
            if (node.isTextual()) {
                return node.asText();
            }
            if (node.isObject() || node.isArray()) {
                try {
                    return MAPPER.writeValueAsString(node);
                } catch (Exception e) {
                    return node.toString();
                }
            }
            return node.asText();
        }
        return String.valueOf(value);
    }

    static boolean absent(Object value) {
        if (value == MISSING || value == null) {
            return true;
        }
        if (value instanceof JsonNode) {
            JsonNode node = (JsonNode) value;
            if (node.isNull() || node.isMissingNode()) {
                return true;
            }
        }
        return display(value).isEmpty();
    }

    static String localName(Node node) {
        String local = node.getLocalName();
        if (local != null) {
            return local;
        }
        String name = node.getNodeName();
        int colon = name.indexOf(':');
        return colon < 0 ? name : name.substring(colon + 1);
    }

    private static String attribute(Element element, String name) {
        if (element.hasAttribute(name)) {
            return element.getAttribute(name);
        }
        NamedNodeMap attrs = element.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Attr attr = (Attr) attrs.item(i);
            if (name.equals(localName(attr))) {
                return attr.getValue();
            }
        }
        return null;
    }

    private static String textContentDirect(Element element) {
        StringBuilder text = new StringBuilder();
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.TEXT_NODE || child.getNodeType() == Node.CDATA_SECTION_NODE) {
                text.append(child.getNodeValue());
            }
        }
        return text.toString().trim();
    }

    private static List<Element> findElements(Element node, String elementPath) {
        if (".".equals(elementPath)) {
            return Collections.singletonList(node);
        }
        boolean descendants = false;
        String path = elementPath;
        if (path.startsWith(".//")) {
            descendants = true;
            path = path.substring(3);
        } else if (path.startsWith("./")) {
            path = path.substring(2);
        }
        List<Element> current = Collections.singletonList(node);
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            List<Element> next = new ArrayList<Element>();
            boolean deep = descendants && i == 0;
            for (Element element : current) {
                if (deep) {
                    collectDescendants(element, part, next);
                } else {
                    collectChildren(element, part, next);
                }
            }
            current = next;
            descendants = false;
        }
        return current;
    }

    private static void collectChildren(Element parent, String name, List<Element> out) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE && name.equals(localName(child))) {
                out.add((Element) child);
            }
        }
    }

    private static void collectDescendants(Element parent, String name, List<Element> out) {
        NodeList all = parent.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Node child = all.item(i);
            if (name.equals(localName(child))) {
                out.add((Element) child);
            }
        }
    }
}
