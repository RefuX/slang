package org.shaderslang.wasm;

import run.endive.wasm.Parser;
import run.endive.runtime.Instance;
import run.endive.runtime.Store;
import run.endive.wasi.WasiOptions;
import run.endive.wasi.WasiPreview1;

import org.shaderslang.wasm.enums.CompilerOptionName;
import org.shaderslang.wasm.enums.Target;
import org.shaderslang.wasm.enums.TargetFlags;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /**
     * One session-wide compiler option entry (mirrors {@code slang::CompilerOptionEntry}):
     * a {@link CompilerOptionName} key paired with either a string or an int value,
     * matching the kind that option expects (see the per-constant doc comments on
     * {@link CompilerOptionName}, e.g. {@code Optimization} takes an
     * {@link org.shaderslang.wasm.enums.OptimizationLevel} int value, {@code
     * MatrixLayoutRow} takes a bool-as-int).
     */
    public static final class CompilerOption {
        final CompilerOptionName name;
        final boolean isString;
        final String stringValue;
        final int intValue;

        private CompilerOption(CompilerOptionName name, boolean isString, String stringValue, int intValue) {
            this.name = name;
            this.isString = isString;
            this.stringValue = stringValue;
            this.intValue = intValue;
        }

        public static CompilerOption of(CompilerOptionName name, String value) {
            return new CompilerOption(name, true, value, 0);
        }

        public static CompilerOption of(CompilerOptionName name, int value) {
            return new CompilerOption(name, false, null, value);
        }
    }

    /**
     * One entry in a multi-target session (mirrors {@code slang::TargetDesc}):
     * a {@link Target} format, an optional profile string (empty for the target
     * default), and a set of {@link TargetFlags} bits.
     */
    public static final class TargetSpec {
        final Target format;
        final String profile;
        final Set<TargetFlags> flags;

        private TargetSpec(Target format, String profile, Set<TargetFlags> flags) {
            this.format = format;
            this.profile = profile;
            this.flags = flags;
        }

        /** A target with no profile override and no flags. */
        public static TargetSpec of(Target format) {
            return new TargetSpec(format, "", EnumSet.noneOf(TargetFlags.class));
        }

        /** A target with an explicit profile (e.g. {@code "spirv_1_4"}) and no flags. */
        public static TargetSpec of(Target format, String profile) {
            return new TargetSpec(format, profile, EnumSet.noneOf(TargetFlags.class));
        }

        /** A target with an explicit profile and flag set; either may be empty. */
        public static TargetSpec of(Target format, String profile, Set<TargetFlags> flags) {
            return new TargetSpec(format, profile, flags);
        }
    }

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
        return fromWasm(wasmPath, Target.SPIRV, "");
    }

    /**
     * Create a {@code SlangCompiler} from a WASM module file, configured for
     * {@code targetFormat}. {@code profile} may be empty to accept the target
     * default.
     *
     * @throws IOException if the WASM file cannot be read or the module fails to
     *                     instantiate
     */
    public static SlangCompiler fromWasm(Path wasmPath, Target targetFormat, String profile)
            throws IOException {

        Instance inst = loadAndInitialize(wasmPath);

        // Create the Slang session for the requested target.
        byte[] profileUtf8 = profile.getBytes(StandardCharsets.UTF_8);
        long profilePtr = 0;
        if (profileUtf8.length > 0) {
            profilePtr = allocAndWrite(inst, profileUtf8);
        }

        long handle = inst.export("slang_wasm_session_create")
                .apply((long) targetFormat.value, profilePtr, (long) profileUtf8.length)[0];

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
     * Create a {@code SlangCompiler} from the full session descriptor surface:
     * one or more compile targets, preprocessor macro definitions, and module
     * search paths. {@code targets} must be non-empty; its order determines the
     * {@code targetIndex} accepted by {@link #compile(String, String, String, int)}.
     */
    public static SlangCompiler fromWasm(
            Path wasmPath,
            List<TargetSpec> targets,
            Map<String, String> macros,
            List<String> searchPaths)
            throws IOException {
        return fromWasm(wasmPath, targets, macros, searchPaths, List.of());
    }

    /**
     * Create a {@code SlangCompiler} from the full session descriptor surface,
     * additionally accepting session-wide {@link CompilerOption} entries (e.g.
     * optimization level, debug info, matrix layout). {@code targets} must be
     * non-empty; its order determines the {@code targetIndex} accepted by
     * {@link #compile(String, String, String, int)}.
     */
    public static SlangCompiler fromWasm(
            Path wasmPath,
            List<TargetSpec> targets,
            Map<String, String> macros,
            List<String> searchPaths,
            List<CompilerOption> options)
            throws IOException {

        if (targets.isEmpty()) {
            throw new IllegalArgumentException("at least one target is required");
        }

        Instance inst = loadAndInitialize(wasmPath);

        long targetList = inst.export("slang_wasm_target_list_create").apply()[0];
        for (TargetSpec target : targets) {
            byte[] profileUtf8 = target.profile.getBytes(StandardCharsets.UTF_8);
            long profilePtr = profileUtf8.length > 0 ? allocAndWrite(inst, profileUtf8) : 0;
            long flagsBits = 0;
            for (TargetFlags flag : target.flags) {
                flagsBits |= flag.value;
            }
            inst.export("slang_wasm_target_list_add").apply(
                    targetList, (long) target.format.value,
                    profilePtr, (long) profileUtf8.length,
                    flagsBits);
            if (profilePtr != 0) {
                inst.export("slang_wasm_free").apply(profilePtr);
            }
        }

        long macroList = inst.export("slang_wasm_macro_list_create").apply()[0];
        for (var entry : macros.entrySet()) {
            byte[] nameUtf8 = entry.getKey().getBytes(StandardCharsets.UTF_8);
            byte[] valueUtf8 = entry.getValue().getBytes(StandardCharsets.UTF_8);
            long namePtr = allocAndWrite(inst, nameUtf8);
            long valuePtr = valueUtf8.length > 0 ? allocAndWrite(inst, valueUtf8) : 0;
            inst.export("slang_wasm_macro_list_add").apply(
                    macroList, namePtr, (long) nameUtf8.length,
                    valuePtr, (long) valueUtf8.length);
            inst.export("slang_wasm_free").apply(namePtr);
            if (valuePtr != 0) {
                inst.export("slang_wasm_free").apply(valuePtr);
            }
        }

        long pathList = inst.export("slang_wasm_path_list_create").apply()[0];
        for (String path : searchPaths) {
            byte[] pathUtf8 = path.getBytes(StandardCharsets.UTF_8);
            long pathPtr = allocAndWrite(inst, pathUtf8);
            inst.export("slang_wasm_path_list_add").apply(pathList, pathPtr, (long) pathUtf8.length);
            inst.export("slang_wasm_free").apply(pathPtr);
        }

        long optionList = inst.export("slang_wasm_options_create").apply()[0];
        for (CompilerOption option : options) {
            if (option.isString) {
                byte[] valUtf8 = option.stringValue.getBytes(StandardCharsets.UTF_8);
                long valPtr = allocAndWrite(inst, valUtf8);
                inst.export("slang_wasm_options_add_string")
                        .apply(optionList, (long) option.name.value, valPtr, (long) valUtf8.length);
                inst.export("slang_wasm_free").apply(valPtr);
            } else {
                inst.export("slang_wasm_options_add_int")
                        .apply(optionList, (long) option.name.value, (long) option.intValue);
            }
        }

        long handle = inst.export("slang_wasm_session_create2")
                .apply(targetList, macroList, pathList, optionList)[0];

        if (handle == 0) {
            throw new IOException(
                    "slang_wasm_session_create2 returned 0 — failed to create Slang session");
        }

        return new SlangCompiler(inst, handle);
    }

    /** Parse, instantiate, and run the WASI reactor protocol's _initialize export. */
    private static Instance loadAndInitialize(Path wasmPath) throws IOException {
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
        return inst;
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
        return compile(moduleName, source, entryPoint, 0);
    }

    /**
     * Compile {@code source} as module {@code moduleName} and link entry point
     * {@code entryPoint}, producing code for the target at {@code targetIndex}
     * (its position among the targets the session was created with). Never
     * throws for compile errors: errors are captured in the returned
     * {@link CompileResult}.
     */
    public CompileResult compile(String moduleName, String source, String entryPoint, int targetIndex) {
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
                    entryPtr, (long) entryUtf8.length,
                    (long) targetIndex)[0];
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
