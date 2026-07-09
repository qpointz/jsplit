import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Converts hierarchical JSON documents into a flat graph of entities linked by
 * relations, and reassembles them back into structurally identical JSON.
 *
 * <p>Entity payloads are {@link Map Maps} of plain Java values for clean REST serialization.
 * Jackson tree types are used only inside the converter pipeline.
 *
 * <p>The converter exposes split and assemble operations. Use {@link SplitConfig} to control
 * which object hierarchies stay inline and to annotate relations with semantic verbs.
 *
 * <p>Round-trip equality is guaranteed when the root is a JSON object:
 * <pre>{@code original.equals(converter.assemble(converter.split(original)))}</pre>
 *
 * <p>Dependencies: Jackson Databind only. The input {@link JsonNode} is never mutated.
 */
public class JsonGraphConverter {

    /**
     * Splits a hierarchical JSON document into a graph using default rules (every object becomes an entity).
     */
    public Graph split(JsonNode root) {
        return split(root, SplitConfig.empty());
    }

    /**
     * Splits a hierarchical JSON document into a graph.
     *
     * @param root the JSON root; must be an {@link ObjectNode}
     * @param config smart-split and semantic configuration
     * @return a graph whose {@link Graph#rootId} points to the root entity
     * @throws IllegalArgumentException if {@code root} is not a JSON object
     */
    public Graph split(JsonNode root, SplitConfig config) {
        if (!root.isObject()) {
            throw new IllegalArgumentException("Root must be a JSON object");
        }

        Map<UUID, Entity> entities = new HashMap<>();
        List<Relation> relations = new ArrayList<>();
        UUID rootId = processObject((ObjectNode) root, SplitPath.root(), entities, relations, config);
        return new Graph(rootId, entities, relations);
    }

    /**
     * Reassembles a JSON document from a graph produced by {@link #split(JsonNode)}.
     */
    public ObjectNode assemble(Graph graph) {
        return rebuild(graph.getRootId(), graph);
    }

    private UUID processObject(
            ObjectNode source,
            SplitPath path,
            Map<UUID, Entity> entities,
            List<Relation> relations,
            SplitConfig config) {
        UUID id = UUID.randomUUID();
        Map<String, Object> payload = new LinkedHashMap<>();
        processFields(source, payload, id, path, List.of(), entities, relations, config);
        entities.put(id, new Entity(id, "object", payload));
        return id;
    }

    private Map<String, Object> processInlinedObject(
            ObjectNode source,
            SplitPath path,
            UUID ownerId,
            List<String> scope,
            Map<UUID, Entity> entities,
            List<Relation> relations,
            SplitConfig config) {
        Map<String, Object> payload = new LinkedHashMap<>();
        processFields(source, payload, ownerId, path, scope, entities, relations, config);
        return payload;
    }

    private void processFields(
            ObjectNode source,
            Map<String, Object> payload,
            UUID ownerId,
            SplitPath path,
            List<String> scope,
            Map<UUID, Entity> entities,
            List<Relation> relations,
            SplitConfig config) {
        int order = 0;
        Iterator<Map.Entry<String, JsonNode>> fields = source.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String key = field.getKey();
            JsonNode value = field.getValue();
            SplitPath childPath = path.append(key);

            if (value.isObject()) {
                if (config.shouldKeepSubtree(childPath)) {
                    payload.put(key, JsonTrees.mapFromObjectNode((ObjectNode) value));
                    relations.add(new Relation(ownerId, null, RelationMetadata.field(key, order, scopeOrNull(scope))));
                } else if (config.shouldKeepSubobject(childPath)) {
                    Map<String, Object> nested = processInlinedObject(
                            (ObjectNode) value, childPath, ownerId, appendScope(scope, key), entities, relations, config);
                    payload.put(key, nested);
                    relations.add(new Relation(ownerId, null, RelationMetadata.field(key, order, scopeOrNull(scope))));
                } else {
                    UUID childId = processObject((ObjectNode) value, childPath, entities, relations, config);
                    relations.add(applySemantics(
                            childPath,
                            new Relation(ownerId, childId, RelationMetadata.property(key, order, scopeOrNull(scope))),
                            config));
                }
            } else if (value.isArray()) {
                if (isPrimitiveOnlyArray((ArrayNode) value)) {
                    payload.put(key, JsonTrees.objectFromJsonNode(value.deepCopy()));
                    if (scope.isEmpty()) {
                        relations.add(new Relation(ownerId, null, RelationMetadata.field(key, order, null)));
                    }
                } else {
                    processArray(ownerId, key, (ArrayNode) value, order, List.of(), childPath, scope, entities, relations, config);
                }
            } else {
                payload.put(key, JsonTrees.objectFromJsonNode(value.deepCopy()));
                if (scope.isEmpty()) {
                    relations.add(new Relation(ownerId, null, RelationMetadata.field(key, order, null)));
                }
            }
            order++;
        }
    }

    private void processArray(
            UUID parentId,
            String key,
            ArrayNode array,
            int order,
            List<Integer> pathPrefix,
            SplitPath jsonPath,
            List<String> scope,
            Map<UUID, Entity> entities,
            List<Relation> relations,
            SplitConfig config) {
        for (int i = 0; i < array.size(); i++) {
            JsonNode element = array.get(i);
            List<Integer> path = appendPath(pathPrefix, i);
            SplitPath elementPath = jsonPath.appendArrayIndex(i);

            if (element.isObject()) {
                UUID childId = processObject((ObjectNode) element, elementPath, entities, relations, config);
                relations.add(applySemantics(
                        jsonPath.appendArrayWildcard(),
                        new Relation(parentId, childId, RelationMetadata.array(key, order, path, scopeOrNull(scope))),
                        config));
            } else if (element.isArray()) {
                processArray(parentId, key, (ArrayNode) element, order, path, elementPath, scope, entities, relations, config);
            } else {
                relations.add(new Relation(
                        parentId,
                        null,
                        RelationMetadata.arrayValue(
                                key, order, path, JsonTrees.objectFromJsonNode(element.deepCopy()), scopeOrNull(scope))));
            }
        }
    }

    private static Relation applySemantics(SplitPath path, Relation relation, SplitConfig config) {
        config.semanticAt(path).ifPresent(rule -> {
            relation.setVerb(rule.getVerb());
            if (rule.getProperties() != null) {
                relation.setProperties(new LinkedHashMap<>(rule.getProperties()));
            }
        });
        return relation;
    }

    private ObjectNode rebuild(UUID entityId, Graph graph) {
        Entity entity = graph.getEntities().get(entityId);
        if (entity == null) {
            throw new IllegalArgumentException("Unknown entity: " + entityId);
        }

        List<Relation> childRelations = graph.getRelations().stream()
                .filter(r -> r.getParentId().equals(entityId))
                .toList();

        ObjectNode result = rebuildAtScope(entity, childRelations, List.of(), graph);
        applyScopedRelations(result, entity, childRelations, graph);
        return result;
    }

    private ObjectNode rebuildAtScope(
            Entity entity,
            List<Relation> relations,
            List<String> scope,
            Graph graph) {
        List<Relation> scopedRelations = relations.stream()
                .filter(r -> scopeEquals(r.getMetadata().getScope(), scope))
                .toList();

        int maxOrder = scopedRelations.stream()
                .mapToInt(r -> r.getMetadata().getOrder())
                .max()
                .orElse(-1);

        ObjectNode result = JsonNodeFactory.instance.objectNode();
        Map<Integer, String> placedArrayKeys = new HashMap<>();

        for (int o = 0; o <= maxOrder; o++) {
            final int fieldOrder = o;

            Relation fieldRelation = scopedRelations.stream()
                    .filter(r -> r.getMetadata().getType() == MetadataType.FIELD
                            && r.getMetadata().getOrder() == fieldOrder)
                    .findFirst()
                    .orElse(null);
            if (fieldRelation != null) {
                String key = fieldRelation.getMetadata().getKey();
                result.set(key, JsonTrees.jsonNodeFromObject(entity.getPayload().get(key)));
                continue;
            }

            Relation propertyRelation = scopedRelations.stream()
                    .filter(r -> r.getMetadata().getType() == MetadataType.PROPERTY
                            && r.getMetadata().getOrder() == fieldOrder)
                    .findFirst()
                    .orElse(null);
            if (propertyRelation != null) {
                String key = propertyRelation.getMetadata().getKey();
                result.set(key, rebuild(propertyRelation.getChildId(), graph));
                continue;
            }

            String arrayKey = scopedRelations.stream()
                    .filter(r -> isArrayPlacement(r.getMetadata()) && r.getMetadata().getOrder() == fieldOrder)
                    .map(r -> r.getMetadata().getKey())
                    .findFirst()
                    .orElse(null);
            if (arrayKey != null && !placedArrayKeys.containsValue(arrayKey)) {
                placedArrayKeys.put(fieldOrder, arrayKey);
                result.set(arrayKey, buildArray(arrayKey, scopedRelations, graph));
            }
        }

        return result;
    }

    private void applyScopedRelations(
            ObjectNode result,
            Entity entity,
            List<Relation> relations,
            Graph graph) {
        Map<List<String>, List<Relation>> grouped = new LinkedHashMap<>();
        for (Relation relation : relations) {
            List<String> scope = relation.getMetadata().getScope();
            if (scope == null || scope.isEmpty()) {
                continue;
            }
            grouped.computeIfAbsent(scope, ignored -> new ArrayList<>()).add(relation);
        }

        for (Map.Entry<List<String>, List<Relation>> entry : grouped.entrySet()) {
            ObjectNode target = navigateToScope(result, entity, entry.getKey());
            ObjectNode nested = rebuildAtScope(entity, entry.getValue(), entry.getKey(), graph);
            nested.fields().forEachRemaining(field -> target.set(field.getKey(), field.getValue()));
        }
    }

    private static ObjectNode navigateToScope(ObjectNode result, Entity entity, List<String> scope) {
        ObjectNode current = result;
        for (String key : scope) {
            JsonNode existing = current.get(key);
            if (existing == null || !existing.isObject()) {
                Object value = entity.getPayload().get(key);
                current.set(key, value != null ? JsonTrees.jsonNodeFromObject(value) : JsonNodeFactory.instance.objectNode());
            }
            current = (ObjectNode) current.get(key);
        }
        return current;
    }

    private ArrayNode buildArray(String key, List<Relation> relations, Graph graph) {
        List<Relation> arrayRelations = relations.stream()
                .filter(r -> key.equals(r.getMetadata().getKey()))
                .filter(r -> isArrayPlacement(r.getMetadata()))
                .toList();

        return (ArrayNode) buildAtPath(arrayRelations, List.of(), graph);
    }

    private JsonNode buildAtPath(List<Relation> relations, List<Integer> pathPrefix, Graph graph) {
        int maxIndex = -1;
        for (Relation relation : relations) {
            List<Integer> path = readPath(relation.getMetadata());
            if (path.size() <= pathPrefix.size()) {
                continue;
            }
            if (!startsWith(path, pathPrefix)) {
                continue;
            }
            maxIndex = Math.max(maxIndex, path.get(pathPrefix.size()));
        }

        if (maxIndex < 0) {
            return JsonNodeFactory.instance.arrayNode();
        }

        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        for (int i = 0; i <= maxIndex; i++) {
            List<Integer> childPath = appendPath(pathPrefix, i);

            boolean hasNested = relations.stream()
                    .anyMatch(r -> {
                        List<Integer> path = readPath(r.getMetadata());
                        return startsWith(path, childPath) && path.size() > childPath.size();
                    });

            if (hasNested) {
                array.add(buildAtPath(relations, childPath, graph));
            } else {
                Relation leaf = relations.stream()
                        .filter(r -> readPath(r.getMetadata()).equals(childPath))
                        .findFirst()
                        .orElse(null);
                if (leaf == null) {
                    array.addNull();
                } else if (leaf.getMetadata().getType() == MetadataType.ARRAY) {
                    array.add(rebuild(leaf.getChildId(), graph));
                } else if (leaf.getMetadata().getType() == MetadataType.ARRAY_VALUE) {
                    array.add(JsonTrees.jsonNodeFromObject(leaf.getMetadata().getValue()));
                }
            }
        }
        return array;
    }

    private static boolean isArrayPlacement(RelationMetadata metadata) {
        return metadata.getType() == MetadataType.ARRAY
                || metadata.getType() == MetadataType.ARRAY_VALUE;
    }

    private static boolean isPrimitiveOnlyArray(ArrayNode array) {
        for (JsonNode element : array) {
            if (element.isObject() || element.isArray()) {
                return false;
            }
        }
        return true;
    }

    private static List<Integer> readPath(RelationMetadata metadata) {
        List<Integer> path = metadata.getPath();
        return path != null ? path : List.of();
    }

    private static List<Integer> appendPath(List<Integer> prefix, int index) {
        List<Integer> path = new ArrayList<>(prefix);
        path.add(index);
        return path;
    }

    private static boolean startsWith(List<Integer> path, List<Integer> prefix) {
        if (path.size() < prefix.size()) {
            return false;
        }
        for (int i = 0; i < prefix.size(); i++) {
            if (!path.get(i).equals(prefix.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static List<String> appendScope(List<String> scope, String key) {
        List<String> next = new ArrayList<>(scope);
        next.add(key);
        return next;
    }

    private static List<String> scopeOrNull(List<String> scope) {
        return scope == null || scope.isEmpty() ? null : List.copyOf(scope);
    }

    private static boolean scopeEquals(List<String> actual, List<String> expected) {
        if (actual == null || actual.isEmpty()) {
            return expected.isEmpty();
        }
        return actual.equals(expected);
    }

    /**
     * Demo entry point: splits a sample document, prints graph details, reassembles,
     * and verifies {@code original.equals(restored)}.
     */
    public static void main(String[] args) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String json = """
                {
                  "number":"123",
                  "customer":{
                      "name":"John",
                      "address":{
                          "city":"Bern"
                      }
                  },
                  "items":[
                      {
                          "name":"Book",
                          "price":10
                      },
                      {
                          "name":"Pen",
                          "price":2
                      }
                  ],
                  "tags":["a","b","c"]
                }
                """;

        JsonNode original = mapper.readTree(json);
        JsonGraphConverter converter = new JsonGraphConverter();

        System.out.println("=== Original JSON ===");
        System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(original));

        Graph graph = converter.split(original);
        System.out.println("\n=== Graph JSON ===");
        System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(graph));

        System.out.println("\n=== Graph statistics ===");
        System.out.println("rootId:    " + graph.getRootId());
        System.out.println("entities:  " + graph.getEntities().size());
        System.out.println("relations: " + graph.getRelations().size());

        System.out.println("\n=== Entities ===");
        graph.getEntities().values().stream()
                .sorted(Comparator.comparing(e -> e.getId().toString()))
                .forEach(entity -> {
                    System.out.println("Entity " + entity.getId());
                    System.out.println("  type:    " + entity.getType());
                    try {
                        System.out.println("  payload: "
                                + mapper.writerWithDefaultPrettyPrinter().writeValueAsString(entity.getPayload()));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });

        System.out.println("\n=== Relations ===");
        for (Relation relation : graph.getRelations()) {
            String child = relation.getChildId() != null ? relation.getChildId().toString() : "null";
            System.out.println(relation.getParentId() + " -> " + child + "  "
                    + mapper.writeValueAsString(relation.getMetadata()));
        }

        ObjectNode restored = converter.assemble(graph);

        System.out.println("\n=== Reconstructed JSON ===");
        System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(restored));

        System.out.println("\n" + (original.equals(restored) ? "SUCCESS" : "FAILED"));
    }
}
