package org.shaderslang.wasm;

import run.endive.wasm.Parser;
import run.endive.runtime.Instance;
import run.endive.runtime.Store;
import run.endive.wasi.WasiOptions;
import run.endive.wasi.WasiPreview1;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Thin Java wrapper around the {@code slang-wasm-lib.wasm} WASI reactor.
 *
 * <p>One instance owns one WASM module instance and one Slang session configured
 * for a single compile target. {@code SlangCompiler} is {@link AutoCloseable}:
 * use it in a try-with-resources block to ensure the session and WASM instance
 * are torn down deterministically.
 *
 * <p>Usage:
 * <pre>{@code
 * try (var slang = SlangCompiler.forSpirvFromWasm(Path.of("slang-wasm-lib.wasm"))) {
 *     CompileResult r = slang.compile("hello",
 *         "[shader(\"compute\")] [numthreads(1,1,1)] void main() {}",
 *         "main");
 *     if (r.succeeded()) {
 *         byte[] spirv = r.code();
 *     }
 * }
 * }</pre>
 *
 * <p>Thread safety: instances are not thread-safe. The underlying WASM module is
 * built single-threaded; do not share one instance across threads.
 */
public final class SlangCompiler implements AutoCloseable {

    // SlangCompileTarget enum values from include/slang.h
    public static final int TARGET_SPIRV = 6;
    public static final int TARGET_HLSL  = 2;
    public static final int TARGET_GLSL  = 1;

    private final Instance instance;
    private final long sessionHandle;

    private SlangCompiler(Instance instance, long sessionHandle) {
        this.instance = instance;
        this.sessionHandle = sessionHandle;
    }

    /**
     * Create a {@code SlangCompiler} targeting SPIR-V, loading the WASM module
     * from {@code wasmPath}.
     */
    public static SlangCompiler forSpirvFromWasm(Path wasmPath) throws IOException {
        return fromWasm(wasmPath, TARGET_SPIRV, "");
    }

    /**
     * Create a {@code SlangCompiler} from a WASM module file, configured for
     * {@code targetFormat} (a {@code SlangCompileTarget} integer). {@code profile}
     * may be empty to accept the target default.
     *
     * @throws IOException if the WASM file cannot be read or the module fails to
     *                     instantiate
     */
    public static SlangCompiler fromWasm(Path wasmPath, int targetFormat, String profile)
            throws IOException {

        var module = Parser.parse(wasmPath.toFile());

        var wasi = WasiPreview1.builder()
                .withOptions(WasiOptions.builder()
                        .withStdout(System.out)
                        .withStderr(System.err)
                        .build())
                .build();

        var store = new Store().addFunction(wasi.toHostFunctions());
        Instance inst = store.instantiate("slang-wasm-lib", module);

        // Reactor protocol: call _initialize before any other export.
        inst.export("_initialize").apply();

        // Create the Slang session for the requested target.
        byte[] profileUtf8 = profile.getBytes(StandardCharsets.UTF_8);
        long profilePtr = 0;
        if (profileUtf8.length > 0) {
            profilePtr = allocAndWrite(inst, profileUtf8);
        }

        long handle = inst.export("slang_wasm_session_create")
                .apply((long) targetFormat, profilePtr, (long) profileUtf8.length)[0];

        if (profilePtr != 0) {
            inst.export("slang_wasm_free").apply(profilePtr);
        }

        if (handle == 0) {
            throw new IOException(
                    "slang_wasm_session_create returned 0 — failed to create Slang session");
        }

        return new SlangCompiler(inst, handle);
    }

    /**
     * Compile {@code source} as module {@code moduleName} and link entry point
     * {@code entryPoint}. Never throws for compile errors: errors are captured in
     * the returned {@link CompileResult}.
     *
     * @throws RuntimeException if a fatal WASM-level error occurs (should not
     *                          happen under normal operation because the C shim
     *                          catches all C++ exceptions)
     */
    public CompileResult compile(String moduleName, String source, String entryPoint) {
        byte[] moduleNameUtf8 = moduleName.getBytes(StandardCharsets.UTF_8);
        byte[] sourceUtf8     = source.getBytes(StandardCharsets.UTF_8);
        byte[] entryUtf8      = entryPoint.getBytes(StandardCharsets.UTF_8);

        long modPtr   = allocAndWrite(instance, moduleNameUtf8);
        long srcPtr   = allocAndWrite(instance, sourceUtf8);
        long entryPtr = allocAndWrite(instance, entryUtf8);

        long resultHandle;
        try {
            resultHandle = instance.export("slang_wasm_compile").apply(
                    sessionHandle,
                    modPtr,   (long) moduleNameUtf8.length,
                    srcPtr,   (long) sourceUtf8.length,
                    entryPtr, (long) entryUtf8.length)[0];
        } finally {
            instance.export("slang_wasm_free").apply(modPtr);
            instance.export("slang_wasm_free").apply(srcPtr);
            instance.export("slang_wasm_free").apply(entryPtr);
        }

        if (resultHandle == 0) {
            return new CompileResult(false, new byte[0], "",
                    "slang_wasm_compile returned handle 0");
        }

        try {
            boolean ok = instance.export("slang_wasm_result_succeeded")
                    .apply(resultHandle)[0] != 0;

            byte[] code        = readWasmBytes("slang_wasm_result_code_ptr",
                                               "slang_wasm_result_code_len", resultHandle);
            String reflJson    = readWasmString("slang_wasm_result_reflection_json_ptr",
                                                "slang_wasm_result_reflection_json_len", resultHandle);
            String diagnostics = readWasmString("slang_wasm_result_diagnostics_ptr",
                                                "slang_wasm_result_diagnostics_len", resultHandle);

            return new CompileResult(ok, code, reflJson, diagnostics);
        } finally {
            instance.export("slang_wasm_result_destroy").apply(resultHandle);
        }
    }

    /**
     * Return the Slang build tag string (e.g. {@code "v2025.21"}) as reported by
     * the WASM module. Useful for sanity-checking the loaded build.
     */
    public String version() {
        long ptr = instance.export("slang_wasm_version").apply()[0];
        return instance.memory().readCString((int) ptr);
    }

    @Override
    public void close() {
        instance.export("slang_wasm_session_destroy").apply(sessionHandle);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Allocate space in WASM linear memory and copy {@code bytes} into it. */
    private static long allocAndWrite(Instance inst, byte[] bytes) {
        long ptr = inst.export("slang_wasm_alloc").apply((long) bytes.length)[0];
        if (ptr == 0) {
            throw new OutOfMemoryError("slang_wasm_alloc returned NULL for size " + bytes.length);
        }
        inst.memory().write((int) ptr, bytes);
        return ptr;
    }

    /** Read a byte array from WASM linear memory via a (ptr, len) export pair. */
    private byte[] readWasmBytes(String ptrExport, String lenExport, long handle) {
        int ptr = (int) instance.export(ptrExport).apply(handle)[0];
        int len = (int) instance.export(lenExport).apply(handle)[0];
        if (len == 0) return new byte[0];
        return instance.memory().readBytes(ptr, len);
    }

    /** Read a UTF-8 string from WASM linear memory via a (ptr, len) export pair. */
    private String readWasmString(String ptrExport, String lenExport, long handle) {
        int ptr = (int) instance.export(ptrExport).apply(handle)[0];
        int len = (int) instance.export(lenExport).apply(handle)[0];
        if (len == 0) return "";
        return instance.memory().readString(ptr, len);
    }
}
