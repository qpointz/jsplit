import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * JSON location path for split configuration: property segments and {@code []} array wildcards.
 */
public final class SplitPath {

    private static final Object ARRAY_WILDCARD = new Object();

    private final List<Object> segments;

    private SplitPath(List<Object> segments) {
        this.segments = List.copyOf(segments);
    }

    public static SplitPath root() {
        return new SplitPath(List.of());
    }

    public static SplitPath parse(String expression) {
        String trimmed = expression.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Path expression must not be empty");
        }
        if (trimmed.endsWith(".*")) {
            throw new IllegalArgumentException("Subtree suffix .* belongs on keep rules, not in SplitPath: " + expression);
        }

        List<Object> parsed = new ArrayList<>();
        int i = 0;
        while (i < trimmed.length()) {
            if (trimmed.charAt(i) == '.') {
                i++;
                continue;
            }
            if (trimmed.startsWith("[]", i)) {
                parsed.add(ARRAY_WILDCARD);
                i += 2;
                continue;
            }
            int start = i;
            while (i < trimmed.length() && trimmed.charAt(i) != '.' && !trimmed.startsWith("[]", i)) {
                i++;
            }
            String segment = trimmed.substring(start, i).trim();
            if (!segment.isEmpty()) {
                parsed.add(segment);
            }
        }
        return new SplitPath(parsed);
    }

    public static SplitPath of(String... properties) {
        return new SplitPath(List.copyOf(Arrays.asList(properties)));
    }

    public SplitPath append(String key) {
        List<Object> next = new ArrayList<>(segments);
        next.add(key);
        return new SplitPath(next);
    }

    public SplitPath appendArrayWildcard() {
        List<Object> next = new ArrayList<>(segments);
        next.add(ARRAY_WILDCARD);
        return new SplitPath(next);
    }

    public SplitPath appendArrayIndex(int index) {
        List<Object> next = new ArrayList<>(segments);
        next.add(index);
        return new SplitPath(next);
    }

    public int size() {
        return segments.size();
    }

    public boolean isEmpty() {
        return segments.isEmpty();
    }

    /**
     * True when this path equals or extends {@code prefix} (array index matches wildcard in prefix).
     */
    public boolean startsWith(SplitPath prefix) {
        if (prefix.segments.size() > segments.size()) {
            return false;
        }
        for (int i = 0; i < prefix.segments.size(); i++) {
            if (!segmentMatches(prefix.segments.get(i), segments.get(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * True when this path pattern matches {@code actual} (wildcards match concrete array indices).
     */
    public boolean matches(SplitPath actual) {
        if (segments.size() != actual.segments.size()) {
            return false;
        }
        for (int i = 0; i < segments.size(); i++) {
            if (!segmentMatches(segments.get(i), actual.segments.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean segmentMatches(Object pattern, Object actual) {
        if (pattern == ARRAY_WILDCARD) {
            return actual instanceof Integer || actual == ARRAY_WILDCARD;
        }
        return Objects.equals(pattern, actual);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SplitPath other)) {
            return false;
        }
        return segments.equals(other.segments);
    }

    @Override
    public int hashCode() {
        return segments.hashCode();
    }

    @Override
    public String toString() {
        if (segments.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Object segment : segments) {
            if (segment == ARRAY_WILDCARD) {
                sb.append("[]");
            } else {
                if (!sb.isEmpty()) {
                    sb.append('.');
                }
                sb.append(segment);
            }
        }
        return sb.toString();
    }
}
