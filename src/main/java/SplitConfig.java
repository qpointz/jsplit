import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Configures smart split: which objects stay inline, and optional relation semantics.
 *
 * <p>Load from YAML via {@link #fromYaml(String)} or build programmatically via {@link #builder()}.
 */
public final class SplitConfig {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private final Set<SplitPath> levelKeep;
    private final Set<SplitPath> subtreeKeep;
    private final List<SemanticRule> semanticRules;

    private SplitConfig(Set<SplitPath> levelKeep, Set<SplitPath> subtreeKeep, List<SemanticRule> semanticRules) {
        this.levelKeep = Set.copyOf(levelKeep);
        this.subtreeKeep = Set.copyOf(subtreeKeep);
        this.semanticRules = List.copyOf(semanticRules);
    }

    public static SplitConfig empty() {
        return new SplitConfig(Set.of(), Set.of(), List.of());
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Loads split configuration from YAML.
     *
     * <pre>{@code
     * keep:
     *   level:
     *     - a.b
     *   subtree:
     *     - a.c
     * relations:
     *   - verb: owned-by
     *     path: a.b.c[]
     * }</pre>
     */
    public static SplitConfig fromYaml(String yaml) {
        try {
            SplitConfigYaml document = YAML.readValue(yaml, SplitConfigYaml.class);
            return fromDocument(document);
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid split config YAML", e);
        }
    }

    public static SplitConfig fromYaml(InputStream input) {
        try {
            SplitConfigYaml document = YAML.readValue(input, SplitConfigYaml.class);
            return fromDocument(document);
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid split config YAML", e);
        }
    }

    private static SplitConfig fromDocument(SplitConfigYaml document) {
        Builder builder = builder();
        if (document.getKeep() != null) {
            for (String path : document.getKeep().getLevel()) {
                builder.keepSubobject(SplitPath.parse(path));
            }
            for (String path : document.getKeep().getSubtree()) {
                builder.keepSubtree(SplitPath.parse(path));
            }
        }
        for (SplitConfigYaml.RelationRule rule : document.getRelations()) {
            if (rule.getVerb() == null || rule.getVerb().isBlank()) {
                throw new IllegalArgumentException("Relation rule requires verb");
            }
            if (rule.getPath() != null && !rule.getPath().isBlank()) {
                builder.semantic(SplitPath.parse(rule.getPath()), rule.getVerb(), rule.getProperties());
            }
            if (rule.getPaths() != null) {
                for (String path : rule.getPaths()) {
                    if (path != null && !path.isBlank()) {
                        builder.semantic(SplitPath.parse(path), rule.getVerb(), rule.getProperties());
                    }
                }
            }
        }
        return builder.build();
    }

    public boolean shouldKeepSubobject(SplitPath path) {
        return levelKeep.contains(path);
    }

    public boolean shouldKeepSubtree(SplitPath path) {
        return subtreeKeep.contains(path);
    }

    public boolean isUnderSubtreeKeep(SplitPath path) {
        for (SplitPath root : subtreeKeep) {
            if (path.startsWith(root)) {
                return true;
            }
        }
        return false;
    }

    public Optional<SemanticRule> semanticAt(SplitPath actualPath) {
        for (SemanticRule rule : semanticRules) {
            if (rule.getPath().matches(actualPath)) {
                return Optional.of(rule);
            }
        }
        return Optional.empty();
    }

    public static final class Builder {
        private final Set<SplitPath> levelKeep = new HashSet<>();
        private final Set<SplitPath> subtreeKeep = new HashSet<>();
        private final List<SemanticRule> semanticRules = new ArrayList<>();

        public Builder keepSubobject(String... properties) {
            return keepSubobject(SplitPath.of(properties));
        }

        public Builder keepSubobject(SplitPath path) {
            levelKeep.add(path);
            return this;
        }

        public Builder keepSubtree(String... properties) {
            return keepSubtree(SplitPath.of(properties));
        }

        public Builder keepSubtree(SplitPath path) {
            subtreeKeep.add(path);
            return this;
        }

        public Builder semantic(String pathExpression, String verb) {
            return semantic(SplitPath.parse(pathExpression), verb, null);
        }

        public Builder semantic(SplitPath path, String verb) {
            return semantic(path, verb, null);
        }

        public Builder semantic(SplitPath path, String verb, Map<String, Object> properties) {
            semanticRules.add(new SemanticRule(path, verb, properties));
            return this;
        }

        public SplitConfig build() {
            return new SplitConfig(levelKeep, subtreeKeep, semanticRules);
        }
    }
}
