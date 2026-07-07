import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Converts between Jackson tree types (used by {@link JsonGraphConverter}) and
 * plain Java types suitable for REST serialization ({@link Map}, {@link Object}).
 */
public final class JsonTrees {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonTrees() {
    }

    public static Map<String, Object> mapFromObjectNode(ObjectNode node) {
        if (node == null || node.isEmpty()) {
            return new LinkedHashMap<>();
        }
        return MAPPER.convertValue(node, new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {});
    }

    public static ObjectNode objectNodeFromMap(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return MAPPER.createObjectNode();
        }
        return MAPPER.valueToTree(map);
    }

    public static Object objectFromJsonNode(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return MAPPER.convertValue(node, Object.class);
    }

    public static JsonNode jsonNodeFromObject(Object value) {
        if (value == null) {
            return MAPPER.nullNode();
        }
        return MAPPER.valueToTree(value);
    }
}
