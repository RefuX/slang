// THIS FILE IS GENERATED — DO NOT EDIT.
// Source: include/slang.h
// Generator: source/slang-wasm-lib/tools/generate-slang-bindings.py
// Re-run: python source/slang-wasm-lib/tools/generate-slang-bindings.py  (or cmake --preset default)


package org.shaderslang.wasm.enums;

/** Downstream compiler. Mirrors {@code SlangPassThrough} in slang.h. */
public enum PassThrough {
    NONE(0), FXC(1), DXC(2), GLSLANG(3), SPIRV_DIS(4), CLANG(5), VISUAL_STUDIO(6), GCC(7), GENERIC_C_CPP(8), NVRTC(9), LLVM(10), SPIRV_OPT(11), METAL(12), TINT(13), SPIRV_LINK(14);

    public final int value;

    PassThrough(int value) {
        this.value = value;
    }

    /** Return the PassThrough constant for the given integer value. */
    public static PassThrough fromValue(int v) {
        for (PassThrough t : values()) if (t.value == v) return t;
        throw new IllegalArgumentException("Unknown PassThrough value: " + v);
    }
}
