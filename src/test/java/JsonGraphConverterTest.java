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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    // --- SplitPath / SplitConfig ---

    @Test
    void splitPath_parse_propertyAndArrayWildcard() {
        SplitPath rule = SplitPath.parse("a.b.c[]");
        assertEquals("a.b.c[]", rule.toString());
        SplitPath actual = SplitPath.parse("a.b").append("c").appendArrayIndex(0);
        assertTrue(rule.matches(actual));
        assertTrue(SplitPath.parse("a.b.c[].d").matches(actual.append("d")));
    }

    @Test
    void splitPath_rejectsSubtreeSuffix() {
        assertThrows(IllegalArgumentException.class, () -> SplitPath.parse("a.c.*"));
    }

    @Test
    void splitConfig_fromYaml_keepAndSemanticRules() {
        SplitConfig config = SplitConfig.fromYaml("""
                keep:
                  level:
                    - a.b
                    - customer.address
                  subtree:
                    - a.c
                relations:
                  - verb: owned-by
                    path: a.b.c[]
                  - verb: depends-on
                    path: customer.address
                    properties:
                      system: crm
                """);

        assertTrue(config.shouldKeepSubobject(SplitPath.parse("a.b")));
        assertTrue(config.shouldKeepSubobject(SplitPath.parse("customer.address")));
        assertTrue(config.shouldKeepSubtree(SplitPath.parse("a.c")));
        assertTrue(config.semanticAt(SplitPath.parse("a.b.c[]")).map(SemanticRule::getVerb).orElse("").equals("owned-by"));
        assertEquals("crm", config.semanticAt(SplitPath.parse("customer.address"))
                .map(SemanticRule::getProperties)
                .map(p -> p.get("system"))
                .orElse(null));
    }

    @Test
    void splitConfig_fromYaml_multiplePathsPerRule() {
        SplitConfig config = SplitConfig.fromYaml("""
                relations:
                  - verb: owned-by
                    paths:
                      - a.b.c[]
                      - items[]
                """);

        assertEquals("owned-by", config.semanticAt(SplitPath.parse("a.b.c[]")).orElseThrow().getVerb());
        assertEquals("owned-by", config.semanticAt(SplitPath.parse("items").appendArrayIndex(0)).orElseThrow().getVerb());
    }

    @Test
    void splitConfig_fromYaml_rejectsInvalidYaml() {
        assertThrows(IllegalArgumentException.class, () -> SplitConfig.fromYaml("keep: [unclosed"));
    }

    // --- smart split ---

    @Test
    void split_smartSplit_keepsSubobject() throws Exception {
        String json = """
                {
                  "a": {
                    "b": {
                      "x": 1,
                      "c": [{ "d": "value" }]
                    }
                  }
                }
                """;
        SplitConfig config = SplitConfig.builder().keepSubobject("a", "b").build();
        Graph graph = converter.split(parse(json), config);

        assertEquals(3, graph.getEntities().size());
        UUID aEntity = findChildId(graph, graph.getRootId(), MetadataType.PROPERTY, "a");
        Entity entityA = graph.getEntities().get(aEntity);
        assertTrue(entityA.getPayload().get("b") instanceof Map<?, ?>);
        assertFalse(((Map<?, ?>) entityA.getPayload().get("b")).containsKey("c"));

        Relation arrayRelation = graph.getRelations().stream()
                .filter(r -> r.getParentId().equals(aEntity))
                .filter(r -> r.getMetadata().getType() == MetadataType.ARRAY)
                .findFirst()
                .orElseThrow();
        assertEquals(List.of("b"), arrayRelation.getMetadata().getScope());
    }

    @Test
    void split_smartSplit_roundTrip() throws Exception {
        JsonNode original = parse("""
                {
                  "a": {
                    "b": {
                      "x": 1,
                      "c": [{ "d": "value" }, { "d": "other" }]
                    }
                  }
                }
                """);
        SplitConfig config = SplitConfig.fromYaml("""
                keep:
                  level:
                    - a.b
                relations:
                  - verb: owned-by
                    path: a.b.c[]
                  - verb: depends-on
                    path: a.b.c[].d
                    properties:
                      required: true
                """);
        Graph graph = converter.split(original, config);
        assertEquals(original, converter.assemble(graph));
    }

    @Test
    void split_smartSplit_defaultUnchanged() throws Exception {
        Graph defaultGraph = converter.split(parse(SAMPLE_JSON));
        Graph emptyConfigGraph = converter.split(parse(SAMPLE_JSON), SplitConfig.empty());

        assertEquals(defaultGraph.getEntities().size(), emptyConfigGraph.getEntities().size());
        assertEquals(defaultGraph.getRelations().size(), emptyConfigGraph.getRelations().size());
    }

    @Test
    void split_smartSplit_customerAddress() throws Exception {
        SplitConfig config = SplitConfig.builder().keepSubobject("customer", "address").build();
        Graph graph = converter.split(parse(SAMPLE_JSON), config);

        UUID customerId = findChildId(graph, graph.getRootId(), MetadataType.PROPERTY, "customer");
        assertTrue(graph.getEntities().get(customerId).getPayload().get("address") instanceof Map<?, ?>);
        assertEquals(4, graph.getEntities().size());
    }

    @Test
    void split_smartSplit_keepsSubtree() throws Exception {
        String json = """
                {
                  "a": {
                    "b": 1,
                    "c": {
                      "d": { "e": 2 },
                      "f": [{ "g": 3 }]
                    }
                  }
                }
                """;
        SplitConfig config = SplitConfig.fromYaml("""
                keep:
                  subtree:
                    - a.c
                """);
        Graph graph = converter.split(parse(json), config);

        assertEquals(2, graph.getEntities().size());
        UUID aEntity = findChildId(graph, graph.getRootId(), MetadataType.PROPERTY, "a");
        Map<?, ?> payload = graph.getEntities().get(aEntity).getPayload();
        assertTrue(payload.get("c") instanceof Map<?, ?>);
        assertFalse(graph.getRelations().stream().anyMatch(r -> r.getMetadata().getType() == MetadataType.PROPERTY
                && "d".equals(r.getMetadata().getKey())));
        assertEquals(parse(json), converter.assemble(graph));
    }

    @Test
    void split_keepLevel_vs_subtree() throws Exception {
        String json = """
                {
                  "a": {
                    "c": {
                      "d": { "e": 2 }
                    }
                  }
                }
                """;
        Graph levelGraph = converter.split(parse(json), SplitConfig.fromYaml("""
                keep:
                  level:
                    - a.c
                """));
        Graph subtreeGraph = converter.split(parse(json), SplitConfig.fromYaml("""
                keep:
                  subtree:
                    - a.c
                """));

        assertTrue(levelGraph.getEntities().size() > subtreeGraph.getEntities().size());
        assertEquals(parse(json), converter.assemble(levelGraph));
        assertEquals(parse(json), converter.assemble(subtreeGraph));
    }

    // --- relation semantics ---

    @Test
    void split_semantic_attribution() throws Exception {
        JsonNode original = parse("""
                {
                  "a": {
                    "b": {
                      "c": [{ "d": "x" }]
                    }
                  }
                }
                """);
        SplitConfig config = SplitConfig.fromYaml("""
                keep:
                  level:
                    - a.b
                relations:
                  - verb: owned-by
                    path: a.b.c[]
                """);
        Graph graph = converter.split(original, config);

        Relation ownedBy = graph.getRelations().stream()
                .filter(r -> r.getMetadata().getType() == MetadataType.ARRAY)
                .filter(r -> "owned-by".equals(r.getVerb()))
                .findFirst()
                .orElseThrow();
        assertNotNull(ownedBy.getChildId());
        assertNull(ownedBy.getProperties());
    }

    @Test
    void split_semantic_withProperties() throws Exception {
        JsonNode original = parse("""
                {"customer":{"address":{"city":"Bern"}}}
                """);
        SplitConfig config = SplitConfig.fromYaml("""
                relations:
                  - verb: depends-on
                    path: customer.address
                    properties:
                      system: crm
                """);
        Graph graph = converter.split(original, config);

        Relation relation = graph.getRelations().stream()
                .filter(r -> r.getMetadata().getType() == MetadataType.PROPERTY)
                .filter(r -> "address".equals(r.getMetadata().getKey()))
                .findFirst()
                .orElseThrow();
        assertEquals("depends-on", relation.getVerb());
        assertEquals("crm", relation.getProperties().get("system"));
    }

    @Test
    void split_semantic_defaultEmpty() throws Exception {
        Graph graph = converter.split(parse(SAMPLE_JSON));
        for (Relation relation : graph.getRelations()) {
            assertNull(relation.getVerb());
            assertNull(relation.getProperties());
        }
    }

    @Test
    void relation_jsonOmitsSemantics() throws Exception {
        Graph graph = converter.split(parse("""
                {"a":{"b":{"c":[{"d":1}]}}}
                """), SplitConfig.fromYaml("""
                keep:
                  level:
                    - a.b
                relations:
                  - verb: owned-by
                    path: a.b.c[]
                """));

        String json = mapper.writeValueAsString(graph);
        assertTrue(json.contains("\"owned-by\""));
        assertFalse(json.contains("\"properties\":{}"));
    }

    @Test
    void assemble_ignoresSemantics() throws Exception {
        JsonNode original = parse("""
                {"a":{"b":{"c":[{"d":"x"}]}}}
                """);
        SplitConfig config = SplitConfig.fromYaml("""
                keep:
                  level:
                    - a.b
                relations:
                  - verb: owned-by
                    path: a.b.c[]
                  - verb: depends-on
                    path: a.b.c[].d
                    properties:
                      required: true
                """);
        Graph graph = converter.split(original, config);
        assertEquals(original, converter.assemble(graph));
    }

    @Test
    void assemble_scopedRelations() throws Exception {
        UUID rootId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();

        Graph graph = new Graph(
                rootId,
                Map.of(
                        rootId, new Entity(rootId, "object", Map.of("b", Map.of("x", 1))),
                        childId, new Entity(childId, "object", Map.of("d", "value"))),
                List.of(
                        new Relation(rootId, null, RelationMetadata.field("b", 0)),
                        new Relation(rootId, childId, RelationMetadata.array("c", 1, List.of(0), List.of("b"))),
                        new Relation(childId, null, RelationMetadata.field("d", 0))));

        JsonNode result = converter.assemble(graph);
        assertEquals("value", result.get("b").get("c").get(0).get("d").asText());
        assertEquals(1, result.get("b").get("x").asInt());
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
