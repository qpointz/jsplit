import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * A single node in the graph, corresponding to one JSON object from the original document.
 *
 * <p>The payload holds scalar fields and primitive-only arrays. Field order and all
 * nested structure are encoded in {@link Relation} metadata on the parent entity.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Entity {
    private UUID id;
    private String type;
    private ObjectNode payload;
}
