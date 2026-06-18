// THIS FILE IS GENERATED — DO NOT EDIT.
// Source: include/slang.h
// Generator: source/slang-wasm-lib/tools/generate-slang-bindings.py
// Re-run: python source/slang-wasm-lib/tools/generate-slang-bindings.py  (or cmake --preset default)


package org.shaderslang.wasm.enums;

/** Compile output format. Mirrors {@code SlangCompileTarget} in slang.h. */
public enum Target {
    TARGET_UNKNOWN(0), TARGET_NONE(1), GLSL(2), HLSL(5), SPIRV(6), SPIRV_ASM(7), DXBC(8), DXBC_ASM(9), DXIL(10), DXIL_ASM(11), C_SOURCE(12), CPP_SOURCE(13), HOST_EXECUTABLE(14), SHADER_SHARED_LIBRARY(15), SHADER_HOST_CALLABLE(16), CUDA_SOURCE(17), PTX(18), CUDA_OBJECT_CODE(19), OBJECT_CODE(20), HOST_CPP_SOURCE(21), HOST_HOST_CALLABLE(22), CPP_PYTORCH_BINDING(23), METAL(24), METAL_LIB(25), METAL_LIB_ASM(26), HOST_SHARED_LIBRARY(27), WGSL(28), WGSL_SPIRV_ASM(29), WGSL_SPIRV(30), HOST_VM(31), CPP_HEADER(32), CUDA_HEADER(33), HOST_OBJECT_CODE(34), HOST_LLVM_IR(35), SHADER_LLVM_IR(36);

    public final int value;

    Target(int value) {
        this.value = value;
    }

    /** Return the Target constant for the given integer value. */
    public static Target fromValue(int v) {
        for (Target t : values()) if (t.value == v) return t;
        throw new IllegalArgumentException("Unknown Target value: " + v);
    }
}
