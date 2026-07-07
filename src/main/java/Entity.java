import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A single node in the graph, corresponding to one JSON object from the original document.
 *
 * <p>The payload holds scalar fields and primitive-only arrays as plain Java values
 * ({@link Map}, {@link String}, {@link Number}, {@link Boolean}, lists, etc.).
 * Use {@link #payloadAsObjectNode()} when Jackson tree access is needed.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Entity {
    private UUID id;
    private String type;
    private Map<String, Object> payload = new LinkedHashMap<>();

    public com.fasterxml.jackson.databind.node.ObjectNode payloadAsObjectNode() {
        return JsonTrees.objectNodeFromMap(payload);
    }

    public void setPayloadFromObjectNode(com.fasterxml.jackson.databind.node.ObjectNode node) {
        this.payload = JsonTrees.mapFromObjectNode(node);
    }
}
