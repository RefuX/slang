// THIS FILE IS GENERATED — DO NOT EDIT.
// Source: include/slang.h
// Generator: source/slang-wasm-lib/tools/generate-slang-bindings.py
// Re-run: python source/slang-wasm-lib/tools/generate-slang-bindings.py  (or cmake --preset default)


package org.shaderslang.wasm.enums;

/** Target flag bits. Mirrors {@code SlangTargetFlags} in slang.h. */
public enum TargetFlags {
    PARAMETER_BLOCKS_USE_REGISTER_SPACES(16),
    GENERATE_WHOLE_PROGRAM(256),
    /** When set, will dump out the IR between intermediate compilation steps. */
    DUMP_IR(512),
    GENERATE_SPIRV_DIRECTLY(1024);

    public final int value;

    TargetFlags(int value) {
        this.value = value;
    }

    /** Return the TargetFlags constant for the given integer value. */
    public static TargetFlags fromValue(int v) {
        for (TargetFlags t : values()) if (t.value == v) return t;
        throw new IllegalArgumentException("Unknown TargetFlags value: " + v);
    }
}
