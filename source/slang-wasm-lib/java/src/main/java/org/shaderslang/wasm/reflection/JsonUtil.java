package org.shaderslang.wasm.reflection;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Type-safe accessors over the {@code Map}/{@code List} tree {@link Json#parse} produces. */
final class JsonUtil {
    private JsonUtil() {}

    @SuppressWarnings("unchecked")
    static Map<String, Object> asObject(Object value) {
        return value == null ? null : (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    static List<Object> asArray(Object value) {
        return value == null ? Collections.emptyList() : (List<Object>) value;
    }

    static String getString(Map<String, Object> obj, String key) {
        Object v = obj.get(key);
        return v == null ? null : v.toString();
    }

    static String getString(Map<String, Object> obj, String key, String fallback) {
        String v = getString(obj, key);
        return v == null ? fallback : v;
    }

    static Map<String, Object> getObject(Map<String, Object> obj, String key) {
        return asObject(obj.get(key));
    }

    static List<Object> getArray(Map<String, Object> obj, String key) {
        return asArray(obj.get(key));
    }

    /**
     * Read an integer-shaped field. Slang's reflection JSON sometimes emits the sentinel
     * strings {@code "unbounded"} or {@code "unknown"} for a size/offset/count instead of a
     * number (see {@code emitReflectionSize} in slang-reflection-json.cpp); both map to {@code -1}
     * here, since callers cannot do arithmetic with either anyway.
     */
    static long getLong(Map<String, Object> obj, String key, long fallback) {
        Object v = obj.get(key);
        if (v == null) return fallback;
        if (v instanceof Number) return ((Number) v).longValue();
        return -1; // "unbounded" / "unknown"
    }

    static boolean getBoolean(Map<String, Object> obj, String key, boolean fallback) {
        Object v = obj.get(key);
        return v instanceof Boolean ? (Boolean) v : fallback;
    }
}
