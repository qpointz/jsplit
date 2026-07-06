import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Flat graph representation of a formerly nested JSON document.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Graph {
    private UUID rootId;
    private Map<UUID, Entity> entities;
    private List<Relation> relations;
}
