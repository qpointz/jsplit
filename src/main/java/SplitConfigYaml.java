import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * YAML document shape for {@link SplitConfig#fromYaml(String)}.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SplitConfigYaml {

    private Keep keep = new Keep();
    private List<RelationRule> relations = new ArrayList<>();

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Keep {
        private List<String> level = new ArrayList<>();
        private List<String> subtree = new ArrayList<>();
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RelationRule {
        private String verb;
        private String path;
        private List<String> paths;
        private Map<String, Object> properties;
    }
}
