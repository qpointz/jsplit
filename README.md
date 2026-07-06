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
├── payload: ObjectNode             // scalars + primitive-only arrays only
└── payloadFieldOrder: Map<String, Integer>  // original field index per payload key

Relation
├── parentId: UUID
├── childId: UUID | null            // null for inline array primitives
└── metadata: ObjectNode            // placement and optional inline value
```

`Graph`, `Entity`, and `Relation` are Lombok `@Data` classes with Jackson-compatible
constructors (`@NoArgsConstructor`, `@AllArgsConstructor`) for JSON serialization.

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

Relations carry arbitrary placement information in `metadata` (`ObjectNode`). Conventional `kind` values:

| kind | When | metadata fields |
|------|------|-----------------|
| `property` | nested object field | `key`, `order` |
| `array` | object element in array | `key`, `order`, `path` |
| `arrayValue` | primitive in mixed array | `key`, `order`, `path`, `value` |

- `key` — property name on the parent object
- `order` — original field index on the parent (preserves property order)
- `path` — array indices from the property's array root (e.g. `[0,0]` for `groups[0][0]`)
- `value` — inline primitive for `arrayValue` relations (`childId` is null)

### Example relations for the sample document

```
root -> customer   { "kind": "property", "key": "customer", "order": 1 }
root -> book       { "kind": "array", "key": "items", "order": 2, "path": [0] }
root -> pen        { "kind": "array", "key": "items", "order": 2, "path": [1] }
customer -> address { "kind": "property", "key": "address", "order": 1 }
```

## Split algorithm

Depth-first traversal:

1. Each JSON **object** becomes one `Entity`.
2. **Scalar** fields are copied into the entity payload with their original field index.
3. **Nested object** fields are removed from the payload; a `property` relation records `key`, `order`, and `childId`.
4. **Primitive-only arrays** stay in the payload.
5. **Arrays containing objects or nested arrays** are removed from the payload; each element becomes an `array` or `arrayValue` relation with `path` indices.
6. The root entity's UUID becomes `graph.rootId`.

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
├── src/test/java/JsonGraphConverterTest.java
├── build.gradle.kts
└── settings.gradle.kts
```

Production code is split across the converter and three Lombok-annotated model classes.

## Tests

`JsonGraphConverterTest` covers:

- **split()** — flat payloads, relation metadata, input immutability, no nested objects in payloads
- **assemble()** — reconstruction from split graphs and hand-built graphs, order preservation, missing-entity errors
- **round-trip** — nested arrays, mixed primitive/object arrays, full sample document
