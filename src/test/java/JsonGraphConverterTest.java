import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonGraphConverterTest {

    private static final String SAMPLE_JSON = """
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

    private ObjectMapper mapper;
    private JsonGraphConverter converter;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        converter = new JsonGraphConverter();
    }

    private JsonNode parse(String json) throws Exception {
        return mapper.readTree(json);
    }

    private void assertRoundTrip(JsonNode original) {
        Graph graph = converter.split(original);
        assertEquals(original, converter.assemble(graph));
    }

    private static ObjectNode placeholder(UUID id) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("$node", id.toString());
        return node;
    }

    private static boolean containsNodeRef(JsonNode node) {
        if (isNodeRef(node)) {
            return true;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                if (containsNodeRef(fields.next().getValue())) {
                    return true;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode element : node) {
                if (containsNodeRef(element)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isNodeRef(JsonNode node) {
        return node.isObject()
                && node.size() == 1
                && node.has("$node")
                && node.get("$node").isTextual();
    }

    // --- split() tests ---

    @Test
    void split_sampleDocument_entitiesAndRelations() throws Exception {
        Graph graph = converter.split(parse(SAMPLE_JSON));

        assertEquals(5, graph.entities.size());
        assertEquals(4, graph.relations.size());
        assertTrue(graph.entities.containsKey(graph.rootId));

        UUID customerId = UUID.fromString(graph.entities.get(graph.rootId).payload.get("customer").get("$node").asText());
        UUID addressId = UUID.fromString(graph.entities.get(customerId).payload.get("address").get("$node").asText());
        UUID bookId = UUID.fromString(graph.entities.get(graph.rootId).payload.get("items").get(0).get("$node").asText());
        UUID penId = UUID.fromString(graph.entities.get(graph.rootId).payload.get("items").get(1).get("$node").asText());

        assertRelation(graph, graph.rootId, customerId);
        assertRelation(graph, customerId, addressId);
        assertRelation(graph, graph.rootId, bookId);
        assertRelation(graph, graph.rootId, penId);
    }

    @Test
    void split_sampleDocument_payloadPlaceholders() throws Exception {
        Graph graph = converter.split(parse(SAMPLE_JSON));
        ObjectNode rootPayload = graph.entities.get(graph.rootId).payload;

        assertEquals("123", rootPayload.get("number").asText());
        assertTrue(rootPayload.get("customer").get("$node").isTextual());
        assertEquals(3, rootPayload.get("tags").size());
        assertEquals("a", rootPayload.get("tags").get(0).asText());
        assertEquals("b", rootPayload.get("tags").get(1).asText());
        assertEquals("c", rootPayload.get("tags").get(2).asText());
    }

    @Test
    void split_flatObject() throws Exception {
        JsonNode original = parse("{\"name\":\"John\",\"age\":30}");
        Graph graph = converter.split(original);

        assertEquals(1, graph.entities.size());
        assertEquals(0, graph.relations.size());
        assertEquals(original, graph.entities.get(graph.rootId).payload);
    }

    @Test
    void split_emptyObject() throws Exception {
        JsonNode original = parse("{}");
        Graph graph = converter.split(original);

        assertEquals(1, graph.entities.size());
        assertEquals(0, graph.relations.size());
        assertEquals(0, graph.entities.get(graph.rootId).payload.size());
    }

    @Test
    void split_nestedObject_createsRelation() throws Exception {
        JsonNode original = parse("{\"child\":{\"value\":1}}");
        Graph graph = converter.split(original);

        assertEquals(2, graph.entities.size());
        assertEquals(1, graph.relations.size());

        UUID childId = UUID.fromString(
                graph.entities.get(graph.rootId).payload.get("child").get("$node").asText());
        assertRelation(graph, graph.rootId, childId);
        assertEquals(1, graph.entities.get(childId).payload.get("value").asInt());
    }

    @Test
    void split_arrayOfObjects_replacesOnlyObjects() throws Exception {
        JsonNode original = parse("{\"items\":[{\"a\":1},\"plain\",42]}");
        Graph graph = converter.split(original);

        assertEquals(2, graph.entities.size());
        assertEquals(1, graph.relations.size());

        ArrayNode items = (ArrayNode) graph.entities.get(graph.rootId).payload.get("items");
        assertTrue(isNodeRef(items.get(0)));
        assertEquals("plain", items.get(1).asText());
        assertEquals(42, items.get(2).asInt());
    }

    @Test
    void split_doesNotMutateInput() throws Exception {
        JsonNode original = parse(SAMPLE_JSON);
        JsonNode snapshot = original.deepCopy();

        converter.split(original);

        assertEquals(snapshot, original);
        assertTrue(!containsNodeRef(original));
    }

    @Test
    void split_rejectsNonObjectRoot() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> converter.split(parse("[1,2,3]")));
        assertThrows(IllegalArgumentException.class, () -> converter.split(parse("\"hello\"")));
        assertThrows(IllegalArgumentException.class, () -> converter.split(parse("42")));
    }

    @Test
    void split_setsRootId() throws Exception {
        Graph graph = converter.split(parse(SAMPLE_JSON));

        assertTrue(graph.entities.containsKey(graph.rootId));
        assertEquals("object", graph.entities.get(graph.rootId).type);
    }

    // --- assemble() tests ---

    @Test
    void assemble_sampleDocument_fromSplitGraph() throws Exception {
        JsonNode original = parse(SAMPLE_JSON);
        Graph graph = converter.split(original);

        assertEquals(original, converter.assemble(graph));
    }

    @Test
    void assemble_nestedObjects() throws Exception {
        UUID rootId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();

        ObjectNode childPayload = mapper.createObjectNode().put("city", "Bern");
        ObjectNode rootPayload = mapper.createObjectNode();
        rootPayload.set("address", placeholder(childId));

        Graph graph = new Graph(
                rootId,
                Map.of(
                        rootId, new Entity(rootId, "object", rootPayload),
                        childId, new Entity(childId, "object", childPayload)),
                List.of(new Relation(rootId, childId)));

        JsonNode result = converter.assemble(graph);
        assertEquals("Bern", result.get("address").get("city").asText());
    }

    @Test
    void assemble_arrayOfObjects() throws Exception {
        UUID rootId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();

        ObjectNode itemPayload = mapper.createObjectNode()
                .put("name", "Book")
                .put("price", 10);
        ObjectNode rootPayload = mapper.createObjectNode();
        ArrayNode items = mapper.createArrayNode();
        items.add(placeholder(itemId));
        rootPayload.set("items", items);

        Graph graph = new Graph(
                rootId,
                Map.of(
                        rootId, new Entity(rootId, "object", rootPayload),
                        itemId, new Entity(itemId, "object", itemPayload)),
                List.of(new Relation(rootId, itemId)));

        JsonNode result = converter.assemble(graph);
        assertEquals("Book", result.get("items").get(0).get("name").asText());
        assertEquals(10, result.get("items").get(0).get("price").asInt());
    }

    @Test
    void assemble_preservesPropertyOrder() throws Exception {
        JsonNode original = parse("{\"z\":1,\"a\":2,\"m\":3}");
        JsonNode restored = converter.assemble(converter.split(original));

        Iterator<String> originalFields = original.fieldNames();
        Iterator<String> restoredFields = restored.fieldNames();
        while (originalFields.hasNext()) {
            assertEquals(originalFields.next(), restoredFields.next());
        }
    }

    @Test
    void assemble_preservesArrayOrder() throws Exception {
        JsonNode original = parse("{\"tags\":[\"c\",\"a\",\"b\"]}");
        JsonNode restored = converter.assemble(converter.split(original));

        assertEquals(original.get("tags"), restored.get("tags"));
    }

    @Test
    void assemble_noNodeRefsInOutput() throws Exception {
        JsonNode original = parse(SAMPLE_JSON);
        JsonNode restored = converter.assemble(converter.split(original));

        assertTrue(!containsNodeRef(restored));
    }

    @Test
    void assemble_throwsOnMissingEntity() {
        UUID rootId = UUID.randomUUID();
        UUID missingId = UUID.randomUUID();
        ObjectNode rootPayload = mapper.createObjectNode();
        rootPayload.set("child", placeholder(missingId));

        Graph graph = new Graph(
                rootId,
                Map.of(rootId, new Entity(rootId, "object", rootPayload)),
                List.of());

        assertThrows(IllegalArgumentException.class, () -> converter.assemble(graph));
    }

    // --- round-trip integration ---

    @Test
    void roundTrip_sampleDocument() throws Exception {
        assertRoundTrip(parse(SAMPLE_JSON));
    }

    @Test
    void roundTrip_nestedArrays() throws Exception {
        assertRoundTrip(parse("""
                {
                  "groups":[
                    [{"id":1},{"id":2}],
                    [{"id":3}]
                  ]
                }
                """));
    }

    @Test
    void roundTrip_mixedArray() throws Exception {
        assertRoundTrip(parse("""
                {
                  "items":[
                    {"name":"Book"},
                    "plain",
                    42,
                    {"name":"Pen"}
                  ]
                }
                """));
    }

    private static void assertRelation(Graph graph, UUID parentId, UUID childId) {
        assertTrue(graph.relations.stream()
                .anyMatch(r -> r.parentId.equals(parentId) && r.childId.equals(childId)));
    }
}
