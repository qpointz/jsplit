import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Converts hierarchical JSON documents into a flat graph of entities linked by
 * parent-child relations, and reassembles them back into structurally identical JSON.
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
 * <p>The reserved placeholder key {@code "$node"} marks references to child entities
 * inside entity payloads. Input JSON that already uses this exact shape cannot be
 * distinguished from graph output.
 *
 * <p>Dependencies: Jackson Databind only ({@link JsonNode}, {@link ObjectNode},
 * {@link ArrayNode}). The input {@link JsonNode} is never mutated.
 */
public class JsonGraphConverter {

    /**
     * Splits a hierarchical JSON document into a graph.
     *
     * <p>Each JSON object in the tree becomes one {@link Entity}. Nested objects and
     * object elements inside arrays are replaced in the parent payload with
     * {@code {"$node": "<child-uuid>"}} placeholders, and a {@link Relation} is
     * recorded for each parent-child link. Primitive values and arrays of primitives
     * are copied unchanged.
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
     * <p>Starting at {@link Graph#rootId}, each {@code {"$node": "<uuid>"}} placeholder
     * in an entity payload is replaced by the recursively rebuilt child object.
     * Property and array order are preserved.
     *
     * @param graph a graph previously produced by {@link #split(JsonNode)}
     * @return the reconstructed JSON root object
     * @throws IllegalArgumentException if a {@code $node} reference points to a missing entity
     */
    public ObjectNode assemble(Graph graph) {
        return rebuild(graph.rootId, graph);
    }

    /**
     * Depth-first split of a single JSON object into an entity and its descendants.
     *
     * @return the UUID assigned to the entity created for {@code source}
     */
    private UUID processObject(ObjectNode source, Map<UUID, Entity> entities, List<Relation> relations) {
        UUID id = UUID.randomUUID();
        ObjectNode payload = source.deepCopy();

        Iterator<Map.Entry<String, JsonNode>> fields = source.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String key = field.getKey();
            JsonNode value = field.getValue();

            if (value.isObject()) {
                UUID childId = processObject((ObjectNode) value, entities, relations);
                payload.set(key, placeholder(childId));
                relations.add(new Relation(id, childId));
            } else if (value.isArray()) {
                ArrayNode payloadArray = (ArrayNode) payload.get(key);
                ArrayNode sourceArray = (ArrayNode) value;
                for (int i = 0; i < sourceArray.size(); i++) {
                    JsonNode element = sourceArray.get(i);
                    if (element.isObject()) {
                        UUID childId = processObject((ObjectNode) element, entities, relations);
                        payloadArray.set(i, placeholder(childId));
                        relations.add(new Relation(id, childId));
                    }
                }
            }
        }

        entities.put(id, new Entity(id, "object", payload));
        return id;
    }

    /**
     * Depth-first rebuild of a single entity and all entities it references.
     */
    private ObjectNode rebuild(UUID entityId, Graph graph) {
        Entity entity = graph.entities.get(entityId);
        if (entity == null) {
            throw new IllegalArgumentException("Unknown entity: " + entityId);
        }

        ObjectNode result = entity.payload.deepCopy();

        Iterator<Map.Entry<String, JsonNode>> fields = result.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String key = field.getKey();
            JsonNode value = field.getValue();

            if (isNodeRef(value)) {
                UUID refId = UUID.fromString(value.get("$node").asText());
                result.set(key, rebuild(refId, graph));
            } else if (value.isArray()) {
                ArrayNode array = (ArrayNode) value;
                for (int i = 0; i < array.size(); i++) {
                    JsonNode element = array.get(i);
                    if (isNodeRef(element)) {
                        UUID refId = UUID.fromString(element.get("$node").asText());
                        array.set(i, rebuild(refId, graph));
                    }
                }
            }
        }

        return result;
    }

    /** Creates a {@code {"$node": "<id>"}} placeholder for a child entity reference. */
    private static ObjectNode placeholder(UUID id) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("$node", id.toString());
        return node;
    }

    /**
     * Returns {@code true} if {@code node} is a strict {@code $node} reference:
     * a JSON object with exactly one textual {@code "$node"} field.
     */
    private static boolean isNodeRef(JsonNode node) {
        return node.isObject()
                && node.size() == 1
                && node.has("$node")
                && node.get("$node").isTextual();
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

        System.out.println("\n=== Graph statistics ===");
        System.out.println("rootId:    " + graph.rootId);
        System.out.println("entities:  " + graph.entities.size());
        System.out.println("relations: " + graph.relations.size());

        System.out.println("\n=== Entities ===");
        for (Entity entity : graph.entities.values()) {
            System.out.println("Entity " + entity.id);
            System.out.println("  type:    " + entity.type);
            System.out.println("  payload: " + mapper.writerWithDefaultPrettyPrinter().writeValueAsString(entity.payload));
        }

        System.out.println("=== Relations ===");
        for (Relation relation : graph.relations) {
            System.out.println(relation.parentId + " -> " + relation.childId);
        }

        ObjectNode restored = converter.assemble(graph);

        System.out.println("\n=== Reconstructed JSON ===");
        System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(restored));

        System.out.println("\n" + (original.equals(restored) ? "SUCCESS" : "FAILED"));
    }
}

/**
 * Flat graph representation of a formerly nested JSON document.
 *
 * @param rootId   UUID of the entity that represents the JSON root object
 * @param entities all entities keyed by their UUID
 * @param relations parent-to-child links recorded during {@link JsonGraphConverter#split(JsonNode)}
 */
class Graph {
    final UUID rootId;
    final Map<UUID, Entity> entities;
    final List<Relation> relations;

    Graph(UUID rootId, Map<UUID, Entity> entities, List<Relation> relations) {
        this.rootId = rootId;
        this.entities = entities;
        this.relations = relations;
    }
}

/**
 * A single node in the graph, corresponding to one JSON object from the original document.
 *
 * @param id      unique identifier assigned during split
 * @param type    entity kind; always {@code "object"} for JSON objects
 * @param payload deep copy of the JSON object, with nested objects replaced by
 *                {@code {"$node": "<uuid>"}} placeholders
 */
class Entity {
    final UUID id;
    final String type;
    final ObjectNode payload;

    Entity(UUID id, String type, ObjectNode payload) {
        this.id = id;
        this.type = type;
        this.payload = payload;
    }
}

/**
 * A directed parent-child link between two entities.
 *
 * @param parentId UUID of the entity that contained the nested object
 * @param childId  UUID of the entity created for that nested object
 */
class Relation {
    final UUID parentId;
    final UUID childId;

    Relation(UUID parentId, UUID childId) {
        this.parentId = parentId;
        this.childId = childId;
    }
}
