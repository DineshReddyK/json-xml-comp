package compare;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Status {
    static final String MATCH = "MATCH";
    static final String NORMALIZED_MATCH = "NORMALIZED_MATCH";
    static final String MISMATCH = "MISMATCH";
    static final String MISSING_IN_JSON = "MISSING_IN_JSON";
    static final String MISSING_IN_XML = "MISSING_IN_XML";
    static final String OPTIONAL_MISSING = "OPTIONAL_MISSING";
    static final String ID_PAIR = "ID_PAIR";
    static final String UNMATCHED_JSON_ROW = "UNMATCHED_JSON_ROW";
    static final String UNMATCHED_XML_ROW = "UNMATCHED_XML_ROW";

    static final List<String> ORDER = Collections.unmodifiableList(Arrays.asList(
            MISMATCH, MISSING_IN_XML, MISSING_IN_JSON,
            UNMATCHED_JSON_ROW, UNMATCHED_XML_ROW,
            OPTIONAL_MISSING, ID_PAIR, NORMALIZED_MATCH, MATCH
    ));

    static final List<String> PROBLEMS = Collections.unmodifiableList(Arrays.asList(
            MISMATCH, MISSING_IN_JSON, MISSING_IN_XML, UNMATCHED_JSON_ROW, UNMATCHED_XML_ROW
    ));

    static final Map<String, String> COLORS;

    static {
        Map<String, String> colors = new LinkedHashMap<String, String>();
        colors.put(MATCH, "#16a34a");
        colors.put(NORMALIZED_MATCH, "#22c55e");
        colors.put(MISMATCH, "#dc2626");
        colors.put(MISSING_IN_JSON, "#d97706");
        colors.put(MISSING_IN_XML, "#9333ea");
        colors.put(OPTIONAL_MISSING, "#64748b");
        colors.put(ID_PAIR, "#0891b2");
        colors.put(UNMATCHED_JSON_ROW, "#db2777");
        colors.put(UNMATCHED_XML_ROW, "#ea580c");
        COLORS = Collections.unmodifiableMap(colors);
    }

    private Status() {}
}
