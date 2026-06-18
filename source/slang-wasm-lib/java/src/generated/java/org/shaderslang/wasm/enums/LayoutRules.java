// THIS FILE IS GENERATED — DO NOT EDIT.
// Source: include/slang.h
// Generator: source/slang-wasm-lib/tools/generate-slang-bindings.py
// Re-run: python source/slang-wasm-lib/tools/generate-slang-bindings.py  (or cmake --preset default)


package org.shaderslang.wasm.enums;

/** Layout rule set. Mirrors {@code SlangLayoutRules} in slang.h. */
public enum LayoutRules {
    DEFAULT(0),
    METAL_ARGUMENT_BUFFER_TIER_2(1),
    DEFAULT_STRUCTURED_BUFFER(2),
    DEFAULT_CONSTANT_BUFFER(3);

    public final int value;

    LayoutRules(int value) {
        this.value = value;
    }

    /** Return the LayoutRules constant for the given integer value. */
    public static LayoutRules fromValue(int v) {
        for (LayoutRules t : values()) if (t.value == v) return t;
        throw new IllegalArgumentException("Unknown LayoutRules value: " + v);
    }
}
