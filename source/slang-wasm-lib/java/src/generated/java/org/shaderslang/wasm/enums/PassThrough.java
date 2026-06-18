// THIS FILE IS GENERATED — DO NOT EDIT.
// Source: include/slang.h
// Generator: source/slang-wasm-lib/tools/generate-slang-bindings.py
// Re-run: python source/slang-wasm-lib/tools/generate-slang-bindings.py  (or cmake --preset default)


package org.shaderslang.wasm.enums;

/** Downstream compiler. Mirrors {@code SlangPassThrough} in slang.h. */
public enum PassThrough {
    NONE(0),
    FXC(1),
    DXC(2),
    GLSLANG(3),
    SPIRV_DIS(4),
    /** Clang C/C++ compiler */
    CLANG(5),
    /** Visual studio C/C++ compiler */
    VISUAL_STUDIO(6),
    /** GCC C/C++ compiler */
    GCC(7),
    /** Generic C or C++ compiler, which is decided by the source type */
    GENERIC_C_CPP(8),
    /** NVRTC Cuda compiler */
    NVRTC(9),
    /** LLVM 'compiler' - includes LLVM and Clang */
    LLVM(10),
    /** SPIRV-opt */
    SPIRV_OPT(11),
    /** Metal compiler */
    METAL(12),
    /** Tint WGSL compiler */
    TINT(13),
    /** SPIRV-link */
    SPIRV_LINK(14);

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
