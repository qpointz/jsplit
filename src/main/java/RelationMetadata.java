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
 * <p>{@code scope} holds a property path inside an inlined sub-object on the owning entity
 * (e.g. {@code ["b"]} places the field under payload key {@code b}).
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
    private List<String> scope;
    private Object value;

    public static RelationMetadata field(String key, int order) {
        return field(key, order, null);
    }

    public static RelationMetadata field(String key, int order, List<String> scope) {
        return new RelationMetadata(MetadataType.FIELD, key, order, null, scope, null);
    }

    public static RelationMetadata property(String key, int order) {
        return property(key, order, null);
    }

    public static RelationMetadata property(String key, int order, List<String> scope) {
        return new RelationMetadata(MetadataType.PROPERTY, key, order, null, scope, null);
    }

    public static RelationMetadata array(String key, int order, List<Integer> path) {
        return array(key, order, path, null);
    }

    public static RelationMetadata array(String key, int order, List<Integer> path, List<String> scope) {
        return new RelationMetadata(MetadataType.ARRAY, key, order, path, scope, null);
    }

    public static RelationMetadata arrayValue(String key, int order, List<Integer> path, JsonNode value) {
        return arrayValue(key, order, path, JsonTrees.objectFromJsonNode(value), null);
    }

    public static RelationMetadata arrayValue(String key, int order, List<Integer> path, Object value) {
        return arrayValue(key, order, path, value, null);
    }

    public static RelationMetadata arrayValue(String key, int order, List<Integer> path, Object value, List<String> scope) {
        return new RelationMetadata(MetadataType.ARRAY_VALUE, key, order, path, scope, value);
    }

    public JsonNode valueAsJsonNode() {
        return JsonTrees.jsonNodeFromObject(value);
    }
}
