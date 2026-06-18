// THIS FILE IS GENERATED — DO NOT EDIT.
// Source: include/slang.h
// Generator: source/slang-wasm-lib/tools/generate-slang-bindings.py
// Re-run: python source/slang-wasm-lib/tools/generate-slang-bindings.py  (or cmake --preset default)


package org.shaderslang.wasm.enums;

/** Optimisation level. Mirrors {@code SlangOptimizationLevel} in slang.h. */
public enum OptimizationLevel {
    /** Don't optimize at all. */
    NONE(0),
    /** Default optimization level: balance code quality and compilation time. */
    DEFAULT(1),
    /** Optimize aggressively. */
    HIGH(2),
    /** Include optimizations that may take a very long time, or may involve severe space-vs-speed tradeoffs */
    MAXIMAL(3);

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
