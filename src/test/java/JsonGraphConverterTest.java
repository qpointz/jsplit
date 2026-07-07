import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    private static boolean containsNestedStructure(Map<String, Object> payload) {
        for (Object value : payload.values()) {
            if (value instanceof Map<?, ?>) {
                return true;
            }
            if (value instanceof List<?> list) {
                for (Object element : list) {
                    if (element instanceof Map<?, ?> || element instanceof List<?>) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // --- split() tests ---

    @Test
    void split_sampleDocument_entitiesAndRelations() throws Exception {
        Graph graph = converter.split(parse(SAMPLE_JSON));

        assertEquals(5, graph.getEntities().size());
        assertEquals(12, graph.getRelations().size());
        assertTrue(graph.getEntities().containsKey(graph.getRootId()));

        UUID customerId = findChildId(graph, graph.getRootId(), MetadataType.PROPERTY, "customer");
        UUID addressId = findChildId(graph, customerId, MetadataType.PROPERTY, "address");
        UUID bookId = findChildId(graph, graph.getRootId(), MetadataType.ARRAY, "items", 0);
        UUID penId = findChildId(graph, graph.getRootId(), MetadataType.ARRAY, "items", 1);

        assertRelation(graph, graph.getRootId(), customerId);
        assertRelation(graph, customerId, addressId);
        assertRelation(graph, graph.getRootId(), bookId);
        assertRelation(graph, graph.getRootId(), penId);
    }

    @Test
    void split_sampleDocument_flatPayload() throws Exception {
        Graph graph = converter.split(parse(SAMPLE_JSON));
        JsonNode rootPayload = graph.getEntities().get(graph.getRootId()).payloadAsObjectNode();

        assertEquals("123", rootPayload.get("number").asText());
        assertFalse(rootPayload.has("customer"));
        assertFalse(rootPayload.has("items"));
        assertEquals(3, rootPayload.get("tags").size());
        assertEquals("a", rootPayload.get("tags").get(0).asText());
        assertEquals("b", rootPayload.get("tags").get(1).asText());
        assertEquals("c", rootPayload.get("tags").get(2).asText());
    }

    @Test
    void split_flatObject() throws Exception {
        JsonNode original = parse("{\"name\":\"John\",\"age\":30}");
        Graph graph = converter.split(original);

        assertEquals(1, graph.getEntities().size());
        assertEquals(2, graph.getRelations().size());
        assertEquals(original, graph.getEntities().get(graph.getRootId()).payloadAsObjectNode());
    }

    @Test
    void split_emptyObject() throws Exception {
        JsonNode original = parse("{}");
        Graph graph = converter.split(original);

        assertEquals(1, graph.getEntities().size());
        assertEquals(0, graph.getRelations().size());
        assertTrue(graph.getEntities().get(graph.getRootId()).getPayload().isEmpty());
    }

    @Test
    void split_nestedObject_createsRelation() throws Exception {
        JsonNode original = parse("{\"child\":{\"value\":1}}");
        Graph graph = converter.split(original);

        assertEquals(2, graph.getEntities().size());
        assertEquals(2, graph.getRelations().size());
        assertTrue(graph.getEntities().get(graph.getRootId()).getPayload().isEmpty());

        UUID childId = findChildId(graph, graph.getRootId(), MetadataType.PROPERTY, "child");
        assertRelation(graph, graph.getRootId(), childId);
        assertEquals(1, graph.getEntities().get(childId).getPayload().get("value"));

        RelationMetadata property = graph.getRelations().stream()
                .map(Relation::getMetadata)
                .filter(m -> m.getType() == MetadataType.PROPERTY)
                .findFirst()
                .orElseThrow();
        assertEquals("child", property.getKey());
    }

    @Test
    void split_arrayOfObjects_replacesOnlyObjects() throws Exception {
        JsonNode original = parse("{\"items\":[{\"a\":1},\"plain\",42]}");
        Graph graph = converter.split(original);

        assertEquals(2, graph.getEntities().size());
        assertEquals(4, graph.getRelations().size());
        assertFalse(graph.getEntities().get(graph.getRootId()).getPayload().containsKey("items"));

        assertTrue(graph.getRelations().stream().anyMatch(r ->
                r.getMetadata().getType() == MetadataType.ARRAY
                        && r.getMetadata().getPath().get(0) == 0));
        assertTrue(graph.getRelations().stream().anyMatch(r ->
                r.getMetadata().getType() == MetadataType.ARRAY_VALUE
                        && r.getMetadata().getPath().get(0) == 1
                        && "plain".equals(r.getMetadata().getValue())));
        assertTrue(graph.getRelations().stream().anyMatch(r ->
                r.getMetadata().getType() == MetadataType.ARRAY_VALUE
                        && r.getMetadata().getPath().get(0) == 2
                        && Integer.valueOf(42).equals(r.getMetadata().getValue())));
    }

    @Test
    void split_doesNotMutateInput() throws Exception {
        JsonNode original = parse(SAMPLE_JSON);
        JsonNode snapshot = original.deepCopy();

        converter.split(original);

        assertEquals(snapshot, original);
    }

    @Test
    void split_payloadContainsNoNestedObjects() throws Exception {
        Graph graph = converter.split(parse(SAMPLE_JSON));

        for (Entity entity : graph.getEntities().values()) {
            assertFalse(containsNestedStructure(entity.getPayload()));
        }
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

        assertTrue(graph.getEntities().containsKey(graph.getRootId()));
        assertEquals("object", graph.getEntities().get(graph.getRootId()).getType());
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

        Graph graph = new Graph(
                rootId,
                Map.of(
                        rootId, new Entity(rootId, "object", Map.of()),
                        childId, new Entity(childId, "object", Map.of("city", "Bern"))),
                List.of(
                        new Relation(rootId, childId, RelationMetadata.property("address", 0)),
                        new Relation(childId, null, RelationMetadata.field("city", 0))));

        JsonNode result = converter.assemble(graph);
        assertEquals("Bern", result.get("address").get("city").asText());
    }

    @Test
    void assemble_arrayOfObjects() throws Exception {
        UUID rootId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();

        Graph graph = new Graph(
                rootId,
                Map.of(
                        rootId, new Entity(rootId, "object", Map.of()),
                        itemId, new Entity(itemId, "object", Map.of("name", "Book", "price", 10))),
                List.of(
                        new Relation(rootId, itemId, RelationMetadata.array("items", 0, List.of(0))),
                        new Relation(itemId, null, RelationMetadata.field("name", 0)),
                        new Relation(itemId, null, RelationMetadata.field("price", 1))));

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
    void assemble_noStructuralRefsInOutput() throws Exception {
        JsonNode original = parse(SAMPLE_JSON);
        JsonNode restored = converter.assemble(converter.split(original));

        assertFalse(restored.toString().contains("$node"));
        assertEquals(original, restored);
    }

    @Test
    void assemble_throwsOnMissingEntity() {
        UUID rootId = UUID.randomUUID();
        UUID missingId = UUID.randomUUID();

        Graph graph = new Graph(
                rootId,
                Map.of(rootId, new Entity(rootId, "object", Map.of())),
                List.of(new Relation(rootId, missingId, RelationMetadata.property("child", 0))));

        assertThrows(IllegalArgumentException.class, () -> converter.assemble(graph));
    }

    @Test
    void graph_jsonSerializable() throws Exception {
        Graph graph = converter.split(parse(SAMPLE_JSON));

        String json = mapper.writeValueAsString(graph);
        Graph restored = mapper.readValue(json, Graph.class);

        assertEquals(graph, restored);
        assertEquals(parse(SAMPLE_JSON), converter.assemble(restored));
    }

    @Test
    void graph_jsonSerializesPayloadAndValueAsPlainJson() throws Exception {
        Graph graph = converter.split(parse("""
                {"number":"123","items":["plain",42]}
                """));

        String json = mapper.writeValueAsString(graph);

        assertFalse(json.contains("\"nodeType\""), () -> json);
        assertFalse(json.contains("\"containerNode\""), () -> json);
        assertTrue(json.contains("\"number\":\"123\""), () -> json);
        assertTrue(json.contains("\"plain\""), () -> json);
    }

    @Test
    void relationMetadata_jsonRoundTrip() throws Exception {
        RelationMetadata metadata = mapper.readValue(
                "{\"kind\":\"arrayValue\",\"key\":\"items\",\"order\":2,\"path\":[1],\"value\":\"plain\"}",
                RelationMetadata.class);

        assertEquals(MetadataType.ARRAY_VALUE, metadata.getType());
        assertEquals("items", metadata.getKey());
        assertEquals(2, metadata.getOrder());
        assertEquals(List.of(1), metadata.getPath());
        assertEquals("plain", metadata.getValue());
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

    private static UUID findChildId(Graph graph, UUID parentId, MetadataType type, String key) {
        return graph.getRelations().stream()
                .filter(r -> r.getParentId().equals(parentId))
                .filter(r -> r.getMetadata().getType() == type)
                .filter(r -> key.equals(r.getMetadata().getKey()))
                .map(Relation::getChildId)
                .findFirst()
                .orElseThrow();
    }

    private static UUID findChildId(Graph graph, UUID parentId, MetadataType type, String key, int index) {
        return graph.getRelations().stream()
                .filter(r -> r.getParentId().equals(parentId))
                .filter(r -> r.getMetadata().getType() == type)
                .filter(r -> key.equals(r.getMetadata().getKey()))
                .filter(r -> r.getMetadata().getPath().get(0) == index)
                .map(Relation::getChildId)
                .findFirst()
                .orElseThrow();
    }

    private static void assertRelation(Graph graph, UUID parentId, UUID childId) {
        assertTrue(graph.getRelations().stream()
                .anyMatch(r -> r.getParentId().equals(parentId) && childId.equals(r.getChildId())));
    }
}
