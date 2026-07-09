import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;
import java.util.UUID;

/**
 * A directed link from a parent entity to a child entity or inline array value.
 *
 * <p>{@link #metadata} describes placement via {@link RelationMetadata} and {@link MetadataType}.
 * {@code childId} is {@code null} for {@link MetadataType#FIELD} and {@link MetadataType#ARRAY_VALUE}.
 *
 * <p>{@link #verb} and {@link #properties} carry optional semantic meaning. They are not used by
 * {@link JsonGraphConverter#assemble(Graph)} and are not populated unless configured at split time.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Relation {
    private UUID parentId;
    private UUID childId;
    private RelationMetadata metadata;
    private String verb;
    private Map<String, Object> properties;

    public Relation(UUID parentId, UUID childId, RelationMetadata metadata) {
        this(parentId, childId, metadata, null, null);
    }
}
