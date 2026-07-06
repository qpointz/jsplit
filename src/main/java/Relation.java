import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * A directed link from a parent entity to a child entity or inline array value.
 *
 * <p>{@link #metadata} is an {@link ObjectNode} describing placement. Conventional
 * {@code kind} values:
 * <ul>
 *   <li>{@code property} — nested object field ({@code key}, {@code order})</li>
 *   <li>{@code array} — object at array slot ({@code key}, {@code order}, {@code path})</li>
 *   <li>{@code arrayValue} — primitive at array slot ({@code key}, {@code order}, {@code path}, {@code value})</li>
 * </ul>
 * {@code childId} is {@code null} for {@code arrayValue} relations.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Relation {
    private UUID parentId;
    private UUID childId;
    private ObjectNode metadata;
}
