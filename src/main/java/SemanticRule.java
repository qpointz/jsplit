import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Semantic attribution for a relation at a configured JSON path.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SemanticRule {
    private SplitPath path;
    private String verb;
    private Map<String, Object> properties;
}
