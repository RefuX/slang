// THIS FILE IS GENERATED — DO NOT EDIT.
// Source: include/slang.h
// Generator: source/slang-wasm-lib/tools/generate-slang-bindings.py
// Re-run: python source/slang-wasm-lib/tools/generate-slang-bindings.py  (or cmake --preset default)


package org.shaderslang.wasm.enums;

/** Scalar element type. Mirrors {@code SlangScalarType} in slang.h. */
public enum ScalarType {
    NONE(0),
    VOID(1),
    BOOL(2),
    INT32(3),
    UINT32(4),
    INT64(5),
    UINT64(6),
    FLOAT16(7),
    FLOAT32(8),
    FLOAT64(9),
    INT8(10),
    UINT8(11),
    INT16(12),
    UINT16(13),
    INTPTR(14),
    UINTPTR(15),
    BFLOAT16(16),
    FLOAT_E4M3(17),
    FLOAT_E5M2(18);

    public final int value;

    ScalarType(int value) {
        this.value = value;
    }

    /** Return the ScalarType constant for the given integer value. */
    public static ScalarType fromValue(int v) {
        for (ScalarType t : values()) if (t.value == v) return t;
        throw new IllegalArgumentException("Unknown ScalarType value: " + v);
    }
}
