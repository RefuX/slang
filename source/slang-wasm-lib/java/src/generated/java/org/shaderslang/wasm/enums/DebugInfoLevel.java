// THIS FILE IS GENERATED — DO NOT EDIT.
// Source: include/slang.h
// Generator: source/slang-wasm-lib/tools/generate-slang-bindings.py
// Re-run: python source/slang-wasm-lib/tools/generate-slang-bindings.py  (or cmake --preset default)


package org.shaderslang.wasm.enums;

/** Debug info verbosity. Mirrors {@code SlangDebugInfoLevel} in slang.h. */
public enum DebugInfoLevel {
    NONE(0), MINIMAL(1), STANDARD(2), MAXIMAL(3);

    public final int value;

    DebugInfoLevel(int value) {
        this.value = value;
    }

    /** Return the DebugInfoLevel constant for the given integer value. */
    public static DebugInfoLevel fromValue(int v) {
        for (DebugInfoLevel t : values()) if (t.value == v) return t;
        throw new IllegalArgumentException("Unknown DebugInfoLevel value: " + v);
    }
}
