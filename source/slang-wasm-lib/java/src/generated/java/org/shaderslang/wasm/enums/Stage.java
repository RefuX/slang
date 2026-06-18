// THIS FILE IS GENERATED — DO NOT EDIT.
// Source: include/slang.h
// Generator: source/slang-wasm-lib/tools/generate-slang-bindings.py
// Re-run: python source/slang-wasm-lib/tools/generate-slang-bindings.py  (or cmake --preset default)


package org.shaderslang.wasm.enums;

/** Pipeline stage. Mirrors {@code SlangStage} in slang.h. */
public enum Stage {
    NONE(0),
    VERTEX(1),
    HULL(2),
    DOMAIN(3),
    GEOMETRY(4),
    FRAGMENT(5),
    COMPUTE(6),
    RAY_GENERATION(7),
    INTERSECTION(8),
    ANY_HIT(9),
    CLOSEST_HIT(10),
    MISS(11),
    CALLABLE(12),
    MESH(13),
    AMPLIFICATION(14),
    DISPATCH(15);

    public final int value;

    Stage(int value) {
        this.value = value;
    }

    /** Return the Stage constant for the given integer value. */
    public static Stage fromValue(int v) {
        for (Stage t : values()) if (t.value == v) return t;
        throw new IllegalArgumentException("Unknown Stage value: " + v);
    }
}
