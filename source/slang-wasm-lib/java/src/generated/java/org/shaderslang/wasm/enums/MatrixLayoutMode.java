// THIS FILE IS GENERATED — DO NOT EDIT.
// Source: include/slang.h
// Generator: source/slang-wasm-lib/tools/generate-slang-bindings.py
// Re-run: python source/slang-wasm-lib/tools/generate-slang-bindings.py  (or cmake --preset default)


package org.shaderslang.wasm.enums;

/** Matrix storage order. Mirrors {@code SlangMatrixLayoutMode} in slang.h. */
public enum MatrixLayoutMode {
    MODE_UNKNOWN(0),
    ROW_MAJOR(1),
    COLUMN_MAJOR(2);

    public final int value;

    MatrixLayoutMode(int value) {
        this.value = value;
    }

    /** Return the MatrixLayoutMode constant for the given integer value. */
    public static MatrixLayoutMode fromValue(int v) {
        for (MatrixLayoutMode t : values()) if (t.value == v) return t;
        throw new IllegalArgumentException("Unknown MatrixLayoutMode value: " + v);
    }
}
