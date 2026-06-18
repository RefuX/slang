// THIS FILE IS GENERATED — DO NOT EDIT.
// Source: include/slang.h
// Generator: source/slang-wasm-lib/tools/generate-slang-bindings.py
// Re-run: python source/slang-wasm-lib/tools/generate-slang-bindings.py  (or cmake --preset default)


package org.shaderslang.wasm.enums;

/** Parameter binding category. Mirrors {@code SlangParameterCategory} in slang.h. */
public enum ParameterCategory {
    NONE(0),
    MIXED(1),
    CONSTANT_BUFFER(2),
    SHADER_RESOURCE(3),
    UNORDERED_ACCESS(4),
    VARYING_INPUT(5),
    VARYING_OUTPUT(6),
    SAMPLER_STATE(7),
    UNIFORM(8),
    DESCRIPTOR_TABLE_SLOT(9),
    SPECIALIZATION_CONSTANT(10),
    PUSH_CONSTANT_BUFFER(11),
    REGISTER_SPACE(12),
    GENERIC(13),
    RAY_PAYLOAD(14),
    HIT_ATTRIBUTES(15),
    CALLABLE_PAYLOAD(16),
    SHADER_RECORD(17);

    public final int value;

    ParameterCategory(int value) {
        this.value = value;
    }

    /** Return the ParameterCategory constant for the given integer value. */
    public static ParameterCategory fromValue(int v) {
        for (ParameterCategory t : values()) if (t.value == v) return t;
        throw new IllegalArgumentException("Unknown ParameterCategory value: " + v);
    }
}
