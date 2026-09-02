package compare;

import java.util.LinkedHashMap;
import java.util.Map;

final class Row {
    final String group;
    final String key;
    final String path;
    final String status;
    final String jsonValue;
    final String xmlMappedValue;
    final String jsonPath;
    final String xmlPath;
    final String detail;

    Row(String group, String key, String path, String status,
        String jsonValue, String xmlMappedValue, String jsonPath, String xmlPath, String detail) {
        this.group = group;
        this.key = key;
        this.path = path;
        this.status = status;
        this.jsonValue = jsonValue;
        this.xmlMappedValue = xmlMappedValue;
        this.jsonPath = jsonPath;
        this.xmlPath = xmlPath;
        this.detail = detail == null ? "" : detail;
    }

    Map<String, String> asMap() {
        Map<String, String> map = new LinkedHashMap<String, String>();
        map.put("group", group);
        map.put("key", key);
        map.put("path", path);
        map.put("status", status);
        map.put("jsonValue", jsonValue);
        map.put("xmlMappedValue", xmlMappedValue);
        map.put("jsonPath", jsonPath);
        map.put("xmlPath", xmlPath);
        map.put("detail", detail);
        return map;
    }

    static final String[] COLUMNS = {
        "group", "key", "path", "status", "jsonValue", "xmlMappedValue", "jsonPath", "xmlPath", "detail"
    };
}
