// THIS FILE IS GENERATED — DO NOT EDIT.
// Source: include/slang.h
// Generator: source/slang-wasm-lib/tools/generate-slang-bindings.py
// Re-run: python source/slang-wasm-lib/tools/generate-slang-bindings.py  (or cmake --preset default)


package org.shaderslang.wasm.enums;

/** Compile output format. Mirrors {@code SlangCompileTarget} in slang.h. */
public enum Target {
    TARGET_UNKNOWN(0),
    TARGET_NONE(1),
    GLSL(2),
    HLSL(5),
    SPIRV(6),
    SPIRV_ASM(7),
    DXBC(8),
    DXBC_ASM(9),
    DXIL(10),
    DXIL_ASM(11),
    /** The C language */
    C_SOURCE(12),
    /** C++ code for shader kernels. */
    CPP_SOURCE(13),
    /** Standalone binary executable (for hosting CPU/OS) */
    HOST_EXECUTABLE(14),
    /** A shared library/Dll for shader kernels (for hosting CPU/OS) */
    SHADER_SHARED_LIBRARY(15),
    /** A CPU target that makes the compiled shader code available to be run immediately */
    SHADER_HOST_CALLABLE(16),
    /** Cuda source */
    CUDA_SOURCE(17),
    /** PTX */
    PTX(18),
    /** Object code that contains CUDA functions. */
    CUDA_OBJECT_CODE(19),
    /** Object code that can be used for later linking (kernel/shader) */
    OBJECT_CODE(20),
    /** C++ code for host library or executable. */
    HOST_CPP_SOURCE(21),
    /** Host callable host code (ie non kernel/shader) */
    HOST_HOST_CALLABLE(22),
    /** C++ PyTorch binding code. */
    CPP_PYTORCH_BINDING(23),
    /** Metal shading language */
    METAL(24),
    /** Metal library */
    METAL_LIB(25),
    /** Metal library assembly */
    METAL_LIB_ASM(26),
    /** A shared library/Dll for host code (for hosting CPU/OS) */
    HOST_SHARED_LIBRARY(27),
    /** WebGPU shading language */
    WGSL(28),
    /** SPIR-V assembly via WebGPU shading language */
    WGSL_SPIRV_ASM(29),
    /** SPIR-V via WebGPU shading language */
    WGSL_SPIRV(30),
    /** Bytecode that can be interpreted by the Slang VM */
    HOST_VM(31),
    /** C++ header for shader kernels. */
    CPP_HEADER(32),
    /** Cuda header */
    CUDA_HEADER(33),
    /** Host object code */
    HOST_OBJECT_CODE(34),
    /** Host LLVM IR assembly */
    HOST_LLVM_IR(35),
    /** Host LLVM IR assembly (kernel/shader) */
    SHADER_LLVM_IR(36);

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
