package com.selfhealing.gateway.util;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JsonPointerTest {

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void movesNestedFieldToTopLevelAndPrunesEmptyParent() {
        Map<String, Object> payload = map("credentials", map("token", "JWT"));
        String[] from = JsonPointer.split("/credentials/token");
        String[] to = JsonPointer.split("/token");

        Object v = JsonPointer.get(payload, from);
        JsonPointer.set(payload, to, v);
        JsonPointer.delete(payload, from);

        assertThat(payload.get("token")).isEqualTo("JWT");
        assertThat(payload).doesNotContainKey("credentials");
    }

    @Test
    void movesTopLevelFieldIntoNewNestedObject() {
        Map<String, Object> payload = map("user_id", 7, "amount", 10);
        String[] from = JsonPointer.split("/user_id");
        String[] to = JsonPointer.split("/user_obj/user_id");

        Object v = JsonPointer.get(payload, from);
        JsonPointer.set(payload, to, v);
        JsonPointer.delete(payload, from);

        assertThat(payload).doesNotContainKey("user_id");
        assertThat(payload.get("amount")).isEqualTo(10);
        Object userObj = payload.get("user_obj");
        assertThat(userObj).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) userObj).get("user_id")).isEqualTo(7);
    }

    @Test
    void getReturnsNullForMissingPath() {
        Map<String, Object> payload = map("a", map("b", 1));
        assertThat(JsonPointer.get(payload, JsonPointer.split("/a/x"))).isNull();
        assertThat(JsonPointer.get(payload, JsonPointer.split("/z/y"))).isNull();
    }

    @Test
    void splitRejectsInvalidPointers() {
        assertThat(JsonPointer.split("")).isNull();
        assertThat(JsonPointer.split("no-leading-slash")).isNull();
        assertThat(JsonPointer.split(null)).isNull();
    }

    @Test
    void splitUnescapesRfc6901Tokens() {
        String[] tokens = JsonPointer.split("/a~1b/c~0d");
        assertThat(tokens).containsExactly("a/b", "c~d");
    }
}
