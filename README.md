# jsplit

A standalone Java utility that converts hierarchical JSON documents into a flat graph representation and back again, with lossless round-trip equality.

## Requirements

- Java 21
- [Jackson Databind](https://github.com/FasterXML/jackson-databind) (only runtime dependency)
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
├── type: String          // always "object"
└── payload: ObjectNode   // flattened copy with $node placeholders

Relation
├── parentId: UUID
└── childId: UUID
```

## Split algorithm

Depth-first traversal of the input JSON tree:

1. Each JSON **object** becomes one `Entity` with a generated UUID.
2. The entity **payload** is a deep copy of that object.
3. When a property value is a nested **object**, the child is split recursively, the payload entry is replaced with `{"$node":"<child-uuid>"}`, and a `Relation(parent, child)` is recorded.
4. When an **array** contains object elements, each object element is replaced with `{"$node":"<uuid>"}`; primitive elements are left unchanged.
5. Primitive properties and arrays of primitives are copied as-is.
6. The root entity's UUID becomes `graph.rootId`.

**Input is never modified** — all changes happen on `deepCopy()` payloads.

### Example

Input:

```json
{
  "number": "123",
  "customer": {
    "name": "John",
    "address": { "city": "Bern" }
  },
  "items": [
    { "name": "Book", "price": 10 },
    { "name": "Pen", "price": 2 }
  ],
  "tags": ["a", "b", "c"]
}
```

After `split()`:

- **5 entities** — root, customer, address, and two items
- **4 relations** — root→customer, customer→address, root→book, root→pen
- Root payload keeps `"number"` and `"tags"` intact; nested objects become `$node` references

## Assemble algorithm

Starting from `graph.rootId`, depth-first reconstruction:

1. Load the entity payload (deep copy).
2. Wherever a value matches `{"$node":"<uuid>"}`, replace it with the recursively assembled child entity.
3. Return the rebuilt `ObjectNode`.

Property order and array order from the original document are preserved.

## Reserved key: `$node`

`"$node"` is a reserved placeholder name used inside entity payloads to reference child entities by UUID. UUIDs exist only inside the `Graph`; they do not appear in assembled output.

**Limitation:** input JSON that already contains a lone `{"$node":"<uuid>"}` object cannot be distinguished from graph output.

## Project layout

```
jsplit/
├── src/main/java/JsonGraphConverter.java   # converter, models, demo main()
├── src/test/java/JsonGraphConverterTest.java
├── build.gradle.kts
└── settings.gradle.kts
```

All production code lives in a single source file. Model classes (`Graph`, `Entity`, `Relation`) are package-private top-level classes in the same file.

## Tests

`JsonGraphConverterTest` covers:

- **split()** — entity/relation counts, payload placeholders, input immutability, error on non-object root
- **assemble()** — reconstruction from split graphs and hand-built graphs, order preservation, missing-entity errors
- **round-trip** — nested arrays, mixed primitive/object arrays, full sample document
