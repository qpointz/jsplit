import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;
import java.util.UUID;

/**
 * A single node in the graph, corresponding to one JSON object from the original document.
 *
 * <p>The payload contains only scalar fields and arrays whose elements are all primitives.
 * {@link #payloadFieldOrder} records the original property index for each payload key so
 * property order can be restored during {@link JsonGraphConverter#assemble(Graph)}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Entity {
    private UUID id;
    private String type;
    private ObjectNode payload;
    private Map<String, Integer> payloadFieldOrder;
}
