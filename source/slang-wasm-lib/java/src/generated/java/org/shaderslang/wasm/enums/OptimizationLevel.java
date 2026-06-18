// THIS FILE IS GENERATED — DO NOT EDIT.
// Source: include/slang.h
// Generator: source/slang-wasm-lib/tools/generate-slang-bindings.py
// Re-run: python source/slang-wasm-lib/tools/generate-slang-bindings.py  (or cmake --preset default)


package org.shaderslang.wasm.enums;

/** Optimisation level. Mirrors {@code SlangOptimizationLevel} in slang.h. */
public enum OptimizationLevel {
    NONE(0), DEFAULT(1), HIGH(2), MAXIMAL(3);

    public final int value;

    OptimizationLevel(int value) {
        this.value = value;
    }

    /** Return the OptimizationLevel constant for the given integer value. */
    public static OptimizationLevel fromValue(int v) {
        for (OptimizationLevel t : values()) if (t.value == v) return t;
        throw new IllegalArgumentException("Unknown OptimizationLevel value: " + v);
    }
}
