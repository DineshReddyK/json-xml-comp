package compare;

import com.fasterxml.jackson.databind.JsonNode;
import org.w3c.dom.Attr;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Reports JSON leaves and XML values that no mapping entry reads. Mirrors the
 * coverage sweep in compare_json_xml.py.
 */
final class Coverage {
    static final String GROUP = "Coverage";

    private Coverage() {}

    static List<Row> rows(JsonNode jsonRoot, Element xmlRoot, JsonNode mapping) {
        Set<String> jsonSubtrees = new HashSet<String>();
        Set<String> jsonExact = new HashSet<String>();
        mappedJsonPaths(mapping, jsonSubtrees, jsonExact);
        Set<String> xmlPaths = mappedXmlPaths(mapping);

        // Repeats collapse onto one row per shape, so a 12-row collection with
        // 6 undeclared fields yields 6 rows, not 72.
        Map<String, List<String>> unmappedJson = new TreeMap<String, List<String>>();
        for (Map.Entry<String, String> leaf : jsonLeaves(jsonRoot).entrySet()) {
            String shape = shape(leaf.getKey());
            if (!jsonIsRead(shape, jsonSubtrees, jsonExact)) {
                add(unmappedJson, shape, leaf.getValue());
            }
        }
        Map<String, List<String>> unmappedXml = new TreeMap<String, List<String>>();
        for (String[] leaf : xmlLeaves(xmlRoot)) {
            if (!xmlPaths.contains(leaf[0])) {
                add(unmappedXml, leaf[0], leaf[1]);
            }
        }

        List<Row> rows = new ArrayList<Row>();
        for (Map.Entry<String, List<String>> entry : unmappedJson.entrySet()) {
            rows.add(row(Status.UNMAPPED_IN_JSON, entry.getKey(), entry.getValue()));
        }
        for (Map.Entry<String, List<String>> entry : unmappedXml.entrySet()) {
            rows.add(row(Status.UNMAPPED_IN_XML, entry.getKey(), entry.getValue()));
        }
        return rows;
    }

    private static Row row(String status, String path, List<String> values) {
        boolean json = Status.UNMAPPED_IN_JSON.equals(status);
        String side = json ? "JSON" : "XML";
        String occurrences = values.size() == 1 ? "" : ", " + values.size() + " occurrences";
        return new Row(
                GROUP, "", path, status,
                json ? values.get(0) : "",
                json ? "" : values.get(0),
                json ? path : "",
                json ? "" : path,
                "present in " + side + ", not declared in the mapping" + occurrences
        );
    }

    /**
     * Collects the JSON paths the mapping reads. A scalar field may point at an
     * object, so anything below it counts as read; collection base paths are
     * deliberately excluded, since mapping a collection does not mean every
     * field inside its rows is compared.
     */
    private static void mappedJsonPaths(JsonNode mapping, Set<String> subtrees, Set<String> exact) {
        for (JsonNode field : mapping.path("fields")) {
            for (String path : Paths.asPaths(field.get("json"))) {
                subtrees.add(shape(path));
            }
        }
        for (JsonNode collection : mapping.path("collections")) {
            for (String rawBase : Paths.asPaths(collection.get("json"))) {
                String base = shape(rawBase);
                if (!base.endsWith("[*]")) {
                    base = base + "[*]";
                }
                for (String path : collectionFieldPaths(collection, "json")) {
                    exact.add(base + "." + shape(path));
                }
            }
        }
    }

    private static Set<String> mappedXmlPaths(JsonNode mapping) {
        Set<String> paths = new HashSet<String>();
        for (JsonNode field : mapping.path("fields")) {
            for (String path : Paths.asPaths(field.get("xml"))) {
                paths.add(canonical(path));
            }
        }
        for (JsonNode collection : mapping.path("collections")) {
            for (String rawBase : Paths.asPaths(collection.get("xml"))) {
                String base = canonical(rawBase);
                for (String path : collectionFieldPaths(collection, "xml")) {
                    paths.add(join(base, canonical(path)));
                }
            }
        }
        return paths;
    }

    private static List<String> collectionFieldPaths(JsonNode collection, String side) {
        List<String> paths = new ArrayList<String>(
                Paths.asPaths(collection.path("join").get(side)));
        for (JsonNode field : collection.path("fields")) {
            paths.addAll(Paths.asPaths(field.get(side)));
        }
        return paths;
    }

    private static boolean jsonIsRead(String path, Set<String> subtrees, Set<String> exact) {
        if (exact.contains(path) || subtrees.contains(path)) {
            return true;
        }
        for (String root : subtrees) {
            if (path.startsWith(root + ".") || path.startsWith(root + "[")) {
                return true;
            }
        }
        return false;
    }

    /** Yields every leaf with real array indices in the path. */
    private static Map<String, String> jsonLeaves(JsonNode root) {
        Map<String, String> leaves = new LinkedHashMap<String, String>();
        Deque<Object[]> stack = new ArrayDeque<Object[]>();
        stack.push(new Object[] { root, "" });
        while (!stack.isEmpty()) {
            Object[] frame = stack.pop();
            JsonNode node = (JsonNode) frame[0];
            String prefix = (String) frame[1];
            if (node.isObject() && node.size() > 0) {
                List<Object[]> children = new ArrayList<Object[]>();
                Iterator<String> names = node.fieldNames();
                while (names.hasNext()) {
                    String name = names.next();
                    children.add(new Object[] {
                            node.get(name), prefix.isEmpty() ? name : prefix + "." + name });
                }
                pushReversed(stack, children);
            } else if (node.isArray() && node.size() > 0) {
                List<Object[]> children = new ArrayList<Object[]>();
                for (int i = 0; i < node.size(); i++) {
                    children.add(new Object[] { node.get(i), prefix + "[" + i + "]" });
                }
                pushReversed(stack, children);
            } else {
                // Empty containers are leaves too, otherwise they vanish.
                leaves.put(prefix, Paths.display(node));
            }
        }
        return leaves;
    }

    /** Yields every attribute and every element carrying direct text. */
    private static List<String[]> xmlLeaves(Element root) {
        List<String[]> leaves = new ArrayList<String[]>();
        Deque<Object[]> stack = new ArrayDeque<Object[]>();
        stack.push(new Object[] { root, "" });
        while (!stack.isEmpty()) {
            Object[] frame = stack.pop();
            Element element = (Element) frame[0];
            String prefix = (String) frame[1];
            NamedNodeMap attrs = element.getAttributes();
            for (int i = 0; i < attrs.getLength(); i++) {
                Attr attr = (Attr) attrs.item(i);
                leaves.add(new String[] {
                        join(prefix, "@" + Paths.localName(attr)), attr.getValue() });
            }
            String text = directText(element);
            if (!text.isEmpty() && !prefix.isEmpty()) {
                leaves.add(new String[] { prefix, text });
            }
            List<Object[]> children = new ArrayList<Object[]>();
            NodeList nodes = element.getChildNodes();
            for (int i = 0; i < nodes.getLength(); i++) {
                Node child = nodes.item(i);
                if (child.getNodeType() == Node.ELEMENT_NODE) {
                    children.add(new Object[] {
                            child, join(prefix, Paths.localName(child)) });
                }
            }
            pushReversed(stack, children);
        }
        return leaves;
    }

    private static void pushReversed(Deque<Object[]> stack, List<Object[]> frames) {
        for (int i = frames.size() - 1; i >= 0; i--) {
            stack.push(frames.get(i));
        }
    }

    private static String directText(Element element) {
        StringBuilder text = new StringBuilder();
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.TEXT_NODE
                    || child.getNodeType() == Node.CDATA_SECTION_NODE) {
                text.append(child.getNodeValue());
            }
        }
        return text.toString().trim();
    }

    private static void add(Map<String, List<String>> found, String path, String value) {
        List<String> values = found.get(path);
        if (values == null) {
            values = new ArrayList<String>();
            found.put(path, values);
        }
        values.add(value);
    }

    private static String shape(String path) {
        return path.replaceAll("\\[(?:\\d+|\\*)\\]", "[*]");
    }

    private static String canonical(String path) {
        int start = 0;
        while (start < path.length() && (path.charAt(start) == '.' || path.charAt(start) == '/')) {
            start++;
        }
        return path.substring(start);
    }

    private static String join(String base, String leaf) {
        if (base.isEmpty() || ".".equals(base)) {
            return leaf;
        }
        return base + "/" + leaf;
    }
}
