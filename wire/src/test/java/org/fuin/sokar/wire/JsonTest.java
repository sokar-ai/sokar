package org.fuin.sokar.wire;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Json}.
 */
class JsonTest {

    @Test
    void readsAnObject() {

        final Object value = Json.parse("{\"a\":1,\"b\":\"two\",\"c\":true,\"d\":null}");

        assertThat(value).isInstanceOf(Map.class);
        final Map<?, ?> map = (Map<?, ?>) value;
        assertThat(map.get("a")).isEqualTo(Double.valueOf(1));
        assertThat(map.get("b")).isEqualTo("two");
        assertThat(map.get("c")).isEqualTo(Boolean.TRUE);
        assertThat(map.containsKey("d")).isTrue();
        assertThat(map.get("d")).isNull();
    }

    @Test
    void readsNestedStructures() {

        final Map<?, ?> map = (Map<?, ?>) Json.parse("{\"list\":[1,[2],{\"x\":\"y\"}]}");

        assertThat((List<?>) map.get("list")).hasSize(3);
    }

    @Test
    void readsEscapes() {

        assertThat(Json.parse("\"a\\\"b\\\\c\\nd\\u0041\"")).isEqualTo("a\"b\\c\ndA");
    }

    @Test
    void readsEmptyContainers() {

        assertThat((Map<?, ?>) Json.parse("{}")).isEmpty();
        assertThat((List<?>) Json.parse("[]")).isEmpty();
    }

    @Test
    void ignoresWhitespace() {

        assertThat(((Map<?, ?>) Json.parse("  {\n  \"a\" : [ 1 , 2 ]\n}  ")).containsKey("a")).isTrue();
    }

    @Test
    void writesWhatItCanRead() {

        final Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", "a\"b");
        map.put("count", Integer.valueOf(3));
        map.put("on", Boolean.TRUE);
        map.put("items", List.of("x", "y"));
        map.put("nothing", null);

        final String json = Json.write(map);

        assertThat(json).isEqualTo("{\"name\":\"a\\\"b\",\"count\":3,\"on\":true,"
                + "\"items\":[\"x\",\"y\"],\"nothing\":null}");
        assertThat(Json.parse(json)).isInstanceOf(Map.class);
    }

    @Test
    void rejectsDuplicateKeys() {

        // A second value quietly winning is a bug in a file a fail-closed hook acts on.
        assertThatThrownBy(() -> Json.parse("{\"a\":1,\"a\":2}"))
                .isInstanceOf(JsonException.class)
                .hasMessageContaining("Duplicate key");
    }

    @Test
    void rejectsTrailingContent() {

        assertThatThrownBy(() -> Json.parse("{} {}"))
                .isInstanceOf(JsonException.class)
                .hasMessageContaining("Trailing content");
    }

    @Test
    void rejectsTruncatedInput() {

        assertThatThrownBy(() -> Json.parse("{\"a\":")).isInstanceOf(JsonException.class);
        assertThatThrownBy(() -> Json.parse("\"unterminated")).isInstanceOf(JsonException.class);
        assertThatThrownBy(() -> Json.parse("")).isInstanceOf(JsonException.class);
    }

    @Test
    void reportsWhereTheProblemIs() {

        assertThatThrownBy(() -> Json.parse("{\"a\" 1}"))
                .isInstanceOf(JsonException.class)
                .hasMessageContaining("at offset");
    }
}
