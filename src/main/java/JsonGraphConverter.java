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
 * <p>The converter exposes two operations:
 * <ul>
 *   <li>{@link #split(JsonNode)} — decompose a nested JSON tree into a {@link Graph}</li>
 *   <li>{@link #assemble(Graph)} — rebuild the original JSON from a graph</li>
 * </ul>
 *
 * <p>Round-trip equality is guaranteed when the root is a JSON object:
 * <pre>{@code original.equals(converter.assemble(converter.split(original)))}</pre>
 *
 * <p>Dependencies: Jackson Databind only. The input {@link JsonNode} is never mutated.
 */
public class JsonGraphConverter {

    /**
     * Splits a hierarchical JSON document into a graph.
     *
     * @param root the JSON root; must be an {@link ObjectNode}
     * @return a graph whose {@link Graph#rootId} points to the root entity
     * @throws IllegalArgumentException if {@code root} is not a JSON object
     */
    public Graph split(JsonNode root) {
        if (!root.isObject()) {
            throw new IllegalArgumentException("Root must be a JSON object");
        }

        Map<UUID, Entity> entities = new HashMap<>();
        List<Relation> relations = new ArrayList<>();
        UUID rootId = processObject((ObjectNode) root, entities, relations);
        return new Graph(rootId, entities, relations);
    }

    /**
     * Reassembles a JSON document from a graph produced by {@link #split(JsonNode)}.
     *
     * @param graph a graph previously produced by {@link #split(JsonNode)}
     * @return the reconstructed JSON root object
     * @throws IllegalArgumentException if a relation references a missing entity
     */
    public ObjectNode assemble(Graph graph) {
        return rebuild(graph.getRootId(), graph);
    }

    private UUID processObject(ObjectNode source, Map<UUID, Entity> entities, List<Relation> relations) {
        UUID id = UUID.randomUUID();
        Map<String, Object> payload = new LinkedHashMap<>();

        int order = 0;
        Iterator<Map.Entry<String, JsonNode>> fields = source.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String key = field.getKey();
            JsonNode value = field.getValue();

            if (value.isObject()) {
                UUID childId = processObject((ObjectNode) value, entities, relations);
                relations.add(new Relation(id, childId, RelationMetadata.property(key, order)));
            } else if (value.isArray()) {
                if (isPrimitiveOnlyArray((ArrayNode) value)) {
                    payload.put(key, JsonTrees.objectFromJsonNode(value.deepCopy()));
                    relations.add(new Relation(id, null, RelationMetadata.field(key, order)));
                } else {
                    processArray(id, key, (ArrayNode) value, order, List.of(), entities, relations);
                }
            } else {
                payload.put(key, JsonTrees.objectFromJsonNode(value.deepCopy()));
                relations.add(new Relation(id, null, RelationMetadata.field(key, order)));
            }
            order++;
        }

        entities.put(id, new Entity(id, "object", payload));
        return id;
    }

    private void processArray(
            UUID parentId,
            String key,
            ArrayNode array,
            int order,
            List<Integer> pathPrefix,
            Map<UUID, Entity> entities,
            List<Relation> relations) {
        for (int i = 0; i < array.size(); i++) {
            JsonNode element = array.get(i);
            List<Integer> path = appendPath(pathPrefix, i);

            if (element.isObject()) {
                UUID childId = processObject((ObjectNode) element, entities, relations);
                relations.add(new Relation(parentId, childId, RelationMetadata.array(key, order, path)));
            } else if (element.isArray()) {
                processArray(parentId, key, (ArrayNode) element, order, path, entities, relations);
            } else {
                relations.add(new Relation(parentId, null, RelationMetadata.arrayValue(key, order, path, element.deepCopy())));
            }
        }
    }

    private ObjectNode rebuild(UUID entityId, Graph graph) {
        Entity entity = graph.getEntities().get(entityId);
        if (entity == null) {
            throw new IllegalArgumentException("Unknown entity: " + entityId);
        }

        List<Relation> childRelations = graph.getRelations().stream()
                .filter(r -> r.getParentId().equals(entityId))
                .toList();

        int maxOrder = childRelations.stream()
                .mapToInt(r -> r.getMetadata().getOrder())
                .max()
                .orElse(-1);

        ObjectNode result = JsonNodeFactory.instance.objectNode();
        Map<Integer, String> placedArrayKeys = new HashMap<>();

        for (int o = 0; o <= maxOrder; o++) {
            final int fieldOrder = o;

            Relation fieldRelation = childRelations.stream()
                    .filter(r -> r.getMetadata().getType() == MetadataType.FIELD
                            && r.getMetadata().getOrder() == fieldOrder)
                    .findFirst()
                    .orElse(null);
            if (fieldRelation != null) {
                String key = fieldRelation.getMetadata().getKey();
                result.set(key, JsonTrees.jsonNodeFromObject(entity.getPayload().get(key)));
                continue;
            }

            Relation propertyRelation = childRelations.stream()
                    .filter(r -> r.getMetadata().getType() == MetadataType.PROPERTY
                            && r.getMetadata().getOrder() == fieldOrder)
                    .findFirst()
                    .orElse(null);
            if (propertyRelation != null) {
                String key = propertyRelation.getMetadata().getKey();
                result.set(key, rebuild(propertyRelation.getChildId(), graph));
                continue;
            }

            String arrayKey = childRelations.stream()
                    .filter(r -> isArrayPlacement(r.getMetadata()) && r.getMetadata().getOrder() == fieldOrder)
                    .map(r -> r.getMetadata().getKey())
                    .findFirst()
                    .orElse(null);
            if (arrayKey != null && !placedArrayKeys.containsValue(arrayKey)) {
                placedArrayKeys.put(fieldOrder, arrayKey);
                result.set(arrayKey, buildArray(arrayKey, childRelations, graph));
            }
        }

        return result;
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
