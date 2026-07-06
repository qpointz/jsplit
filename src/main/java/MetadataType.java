import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum MetadataType {
    FIELD("field"),
    PROPERTY("property"),
    ARRAY("array"),
    ARRAY_VALUE("arrayValue");

    private final String jsonName;

    MetadataType(String jsonName) {
        this.jsonName = jsonName;
    }

    @JsonValue
    public String jsonName() {
        return jsonName;
    }

    @JsonCreator
    public static MetadataType fromJson(String value) {
        for (MetadataType type : values()) {
            if (type.jsonName.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown metadata kind: " + value);
    }
}
