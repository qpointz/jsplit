import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Placement metadata for a {@link Relation}.
 *
 * <p>All properties are present on this type; which fields are meaningful depends on {@link #type}:
 * <ul>
 *   <li>{@link MetadataType#FIELD} — {@code key}, {@code order}</li>
 *   <li>{@link MetadataType#PROPERTY} — {@code key}, {@code order}</li>
 *   <li>{@link MetadataType#ARRAY} — {@code key}, {@code order}, {@code path}</li>
 *   <li>{@link MetadataType#ARRAY_VALUE} — {@code key}, {@code order}, {@code path}, {@code value}</li>
 * </ul>
 *
 * <p>{@link #value} holds a plain JSON value ({@link String}, {@link Number}, {@link Boolean}, etc.).
 * Use {@link #valueAsJsonNode()} when Jackson tree access is needed.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RelationMetadata {

    @JsonProperty("kind")
    private MetadataType type;
    private String key;
    private int order;
    private List<Integer> path;
    private Object value;

    public static RelationMetadata field(String key, int order) {
        return new RelationMetadata(MetadataType.FIELD, key, order, null, null);
    }

    public static RelationMetadata property(String key, int order) {
        return new RelationMetadata(MetadataType.PROPERTY, key, order, null, null);
    }

    public static RelationMetadata array(String key, int order, List<Integer> path) {
        return new RelationMetadata(MetadataType.ARRAY, key, order, path, null);
    }

    public static RelationMetadata arrayValue(String key, int order, List<Integer> path, JsonNode value) {
        return new RelationMetadata(MetadataType.ARRAY_VALUE, key, order, path, JsonTrees.objectFromJsonNode(value));
    }

    public static RelationMetadata arrayValue(String key, int order, List<Integer> path, Object value) {
        return new RelationMetadata(MetadataType.ARRAY_VALUE, key, order, path, value);
    }

    public JsonNode valueAsJsonNode() {
        return JsonTrees.jsonNodeFromObject(value);
    }
}
