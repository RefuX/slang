// THIS FILE IS GENERATED — DO NOT EDIT.
// Source: include/slang.h
// Generator: source/slang-wasm-lib/tools/generate-slang-bindings.py
// Re-run: python source/slang-wasm-lib/tools/generate-slang-bindings.py  (or cmake --preset default)


package org.shaderslang.wasm.enums;

/** Reflection type kind. Mirrors {@code SlangTypeKind} in slang.h. */
public enum TypeKind {
    NONE(0),
    STRUCT(1),
    ARRAY(2),
    MATRIX(3),
    VECTOR(4),
    SCALAR(5),
    CONSTANT_BUFFER(6),
    RESOURCE(7),
    SAMPLER_STATE(8),
    TEXTURE_BUFFER(9),
    SHADER_STORAGE_BUFFER(10),
    PARAMETER_BLOCK(11),
    GENERIC_TYPE_PARAMETER(12),
    INTERFACE(13),
    OUTPUT_STREAM(14),
    MESH_OUTPUT(15),
    SPECIALIZED(16),
    FEEDBACK(17),
    POINTER(18),
    DYNAMIC_RESOURCE(19),
    ENUM(20);

    public final int value;

    TypeKind(int value) {
        this.value = value;
    }

    /** Return the TypeKind constant for the given integer value. */
    public static TypeKind fromValue(int v) {
        for (TypeKind t : values()) if (t.value == v) return t;
        throw new IllegalArgumentException("Unknown TypeKind value: " + v);
    }
}
