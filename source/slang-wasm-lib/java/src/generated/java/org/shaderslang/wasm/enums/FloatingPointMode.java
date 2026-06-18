// THIS FILE IS GENERATED — DO NOT EDIT.
// Source: include/slang.h
// Generator: source/slang-wasm-lib/tools/generate-slang-bindings.py
// Re-run: python source/slang-wasm-lib/tools/generate-slang-bindings.py  (or cmake --preset default)


package org.shaderslang.wasm.enums;

/** Floating-point mode. Mirrors {@code SlangFloatingPointMode} in slang.h. */
public enum FloatingPointMode {
    DEFAULT(0),
    FAST(1),
    PRECISE(2);

    public final int value;

    FloatingPointMode(int value) {
        this.value = value;
    }

    /** Return the FloatingPointMode constant for the given integer value. */
    public static FloatingPointMode fromValue(int v) {
        for (FloatingPointMode t : values()) if (t.value == v) return t;
        throw new IllegalArgumentException("Unknown FloatingPointMode value: " + v);
    }
}
