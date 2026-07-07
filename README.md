# jsplit

A standalone Java utility that converts hierarchical JSON documents into a flat graph representation and back again, with lossless round-trip equality.

Structure is encoded in **entities** (scalar data) and **relations** (placement metadata), not in nested payload shape.

## Requirements

- Java 21
- [Jackson Databind](https://github.com/FasterXML/jackson-databind) (only runtime dependency)
- [Lombok](https://projectlombok.org/) (compile-time only, for model boilerplate)
- No Spring, no persistence layer

## Build and run

Requires a Java 21 JDK. Set `JAVA_HOME` if your default `java` is not version 21:

```powershell
$env:JAVA_HOME = "C:\Program Files\Zulu\zulu-21"
.\gradlew.bat test
.\gradlew.bat run
```

| Task | Description |
|------|-------------|
| `.\gradlew.bat test` | Run unit tests (split, assemble, round-trip) |
| `.\gradlew.bat run` | Run the demo `main()` and verify `original.equals(restored)` |

## API

The converter exposes exactly two public methods:

```java
JsonGraphConverter converter = new JsonGraphConverter();

Graph graph = converter.split(originalJson);   // JSON → graph
ObjectNode restored = converter.assemble(graph); // graph → JSON
```

The invariant:

```java
assert original.equals(converter.assemble(converter.split(original)));
```

## Graph model

```
Graph
├── rootId: UUID
├── entities: Map<UUID, Entity>
└── relations: List<Relation>

Entity
├── id: UUID
├── type: String                    // always "object"
└── payload: Map<String, Object>    // scalars + primitive-only arrays

Relation
├── parentId: UUID
├── childId: UUID | null
└── metadata: RelationMetadata

RelationMetadata
├── type: MetadataType     // serialized as JSON "kind"
├── key: String
├── order: int
├── path: List<Integer>    // ARRAY, ARRAY_VALUE
└── value: Object          // ARRAY_VALUE only (String, Number, Boolean, List, …)
```

`MetadataType`: `FIELD`, `PROPERTY`, `ARRAY`, `ARRAY_VALUE`.

`Graph`, `Entity`, `Relation`, and `RelationMetadata` are Lombok `@Data` classes. Payload and value use plain Java types so REST APIs serialize them as normal JSON without custom Jackson handling.

Use {@link Entity#payloadAsObjectNode()} and {@link RelationMetadata#valueAsJsonNode()} when tree access is needed. {@link JsonTrees} converts between maps/objects and Jackson nodes inside the converter.

## Entity payload rules

An entity `payload` contains **only**:

- scalar properties (string, number, boolean, null)
- arrays whose elements are **all primitives** (e.g. `"tags": ["a","b","c"]`)

Nested objects and arrays containing objects are **removed** from the payload. Their placement is recorded in relations.

### Example root payload after split

```json
{
  "number": "123",
  "tags": ["a", "b", "c"]
}
```

## Relation metadata

`Relation.metadata` is a single `RelationMetadata` type with a `MetadataType` discriminator (JSON field `kind`):

| MetadataType | JSON kind | meaningful fields |
|--------------|-----------|-------------------|
| `FIELD` | `field` | `key`, `order` |
| `PROPERTY` | `property` | `key`, `order` |
| `ARRAY` | `array` | `key`, `order`, `path` |
| `ARRAY_VALUE` | `arrayValue` | `key`, `order`, `path`, `value` |

Unused properties are omitted from JSON (`null`).

## Split algorithm

Depth-first traversal:

1. Each JSON **object** becomes one `Entity`.
2. **Scalar** fields and **primitive-only arrays** are stored in the entity payload; a `field` relation records `key` and `order`.
3. **Nested object** fields are removed from the payload; a `property` relation records `key`, `order`, and `childId`.
4. **Arrays containing objects or nested arrays** are removed from the payload; each element becomes an `array` or `arrayValue` relation with `path` indices.
5. The root entity's UUID becomes `graph.rootId`.

**Input is never modified.**

## Assemble algorithm

Starting from `graph.rootId`:

1. Load the entity's scalar payload and all relations where `parentId` matches.
2. Merge payload fields and relation-derived fields by `order`.
3. For each array property, rebuild the array from `array` and `arrayValue` relations grouped by `key` and `path`.
4. Recursively rebuild child entities referenced by relations.

Property order and array order from the original document are preserved.

## Project layout

```
jsplit/
├── src/main/java/JsonGraphConverter.java
├── src/main/java/Graph.java
├── src/main/java/Entity.java
├── src/main/java/Relation.java
├── src/main/java/RelationMetadata.java
├── src/main/java/JsonTrees.java
├── src/main/java/MetadataType.java
├── src/test/java/JsonGraphConverterTest.java
├── build.gradle.kts
└── settings.gradle.kts
```

Production code is split across the converter and Lombok-annotated model classes.

## Tests

`JsonGraphConverterTest` covers:

- **split()** — flat payloads, relation metadata, input immutability, no nested objects in payloads
- **assemble()** — reconstruction from split graphs and hand-built graphs, order preservation, missing-entity errors
- **round-trip** — nested arrays, mixed primitive/object arrays, full sample document
