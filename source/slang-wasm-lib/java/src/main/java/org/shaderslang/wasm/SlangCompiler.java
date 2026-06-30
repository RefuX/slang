package org.shaderslang.wasm;

import org.shaderslang.wasm.enums.CompilerOptionName;
import org.shaderslang.wasm.enums.DebugInfoLevel;
import org.shaderslang.wasm.enums.MatrixLayoutMode;
import org.shaderslang.wasm.enums.OptimizationLevel;
import org.shaderslang.wasm.enums.Target;
import org.shaderslang.wasm.enums.TargetFlags;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
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

    /**
     * A specialization argument for a generic shader parameter (mirrors
     * SlangShaderSharp's {@code SpecializationArg}): either a concrete type name
     * (resolved by name against the program's own layout, so it must be visible
     * from the module being specialized) or a constant expression. Passed to
     * {@link SlangModule#compileSpecialized}.
     */
    public static final class SpecializationArg {
        final boolean isType;
        final String value;

        private SpecializationArg(boolean isType, String value) {
            this.isType = isType;
            this.value = value;
        }

        /** A specialization argument naming a concrete type, e.g. {@code "PbrMaterial"}. */
        public static SpecializationArg fromType(String typeName) {
            return new SpecializationArg(true, typeName);
        }

        /** A specialization argument that is a constant expression, e.g. {@code "32"}. */
        public static SpecializationArg fromExpression(String expression) {
            return new SpecializationArg(false, expression);
        }
    }

    /**
     * Fluent builder for a {@code SlangCompiler} session (mirrors
     * {@code slang::SessionDesc}), as an alternative to the positional
     * {@link #fromWasm(Path, List, Map, List, List)} overload. Obtain via
     * {@link #builder()}.
     */
    public static final class SessionBuilder {
        private Path wasmPath;
        private final List<TargetSpec> targets = new ArrayList<>();
        private final Map<String, String> macros = new LinkedHashMap<>();
        private final List<String> searchPaths = new ArrayList<>();
        private final List<CompilerOption> options = new ArrayList<>();
        private boolean runtimeCompiler = false;

        private SessionBuilder() {}

        /** The WASM module file to load. Required before calling {@link #build()}. */
        public SessionBuilder wasm(Path wasmPath) {
            this.wasmPath = wasmPath;
            return this;
        }

        /** Add a compile target with no profile override and no flags. */
        public SessionBuilder target(Target format) {
            targets.add(TargetSpec.of(format));
            return this;
        }

        /** Add a compile target with an explicit profile, e.g. {@code "spirv_1_4"}. */
        public SessionBuilder target(Target format, String profile) {
            targets.add(TargetSpec.of(format, profile));
            return this;
        }

        /** Add a compile target built from a {@link TargetSpec} (profile and/or flags). */
        public SessionBuilder target(TargetSpec target) {
            targets.add(target);
            return this;
        }

        /** Define preprocessor macro {@code name} as {@code value} (empty for a valueless define). */
        public SessionBuilder define(String name, String value) {
            macros.put(name, value);
            return this;
        }

        /** Add a search path for resolving {@code #include}/{@code import}ed files. */
        public SessionBuilder searchPath(Path path) {
            searchPaths.add(path.toString());
            return this;
        }

        /** Add a raw session-wide {@link CompilerOption} entry not covered by a named method below. */
        public SessionBuilder option(CompilerOption option) {
            options.add(option);
            return this;
        }

        /**
         * Enable the endive runtime compiler, which compiles the WASM module to
         * JVM bytecode at instance creation (see {@link SlangRuntime.Builder#withRuntimeCompiler}).
         * This adds a one-time compilation cost when the session is built but
         * makes the actual shader compiles dramatically faster than the default
         * interpreter — worthwhile when the session will compile more than a couple
         * of shaders.
         */
        public SessionBuilder runtimeCompiler(boolean enabled) {
            this.runtimeCompiler = enabled;
            return this;
        }

        /** Set the optimization level ({@code CompilerOptionName.Optimization}). */
        public SessionBuilder optimizationLevel(OptimizationLevel level) {
            return option(CompilerOption.of(CompilerOptionName.Optimization, level.value));
        }

        /** Set the debug info level ({@code CompilerOptionName.DebugInformation}). */
        public SessionBuilder debugInfo(DebugInfoLevel level) {
            return option(CompilerOption.of(CompilerOptionName.DebugInformation, level.value));
        }

        /**
         * Set the default matrix layout ({@code CompilerOptionName.MatrixLayoutColumn} /
         * {@code MatrixLayoutRow} — Slang models these as two separate bool-valued options,
         * not one enum-valued one).
         */
        public SessionBuilder matrixLayout(MatrixLayoutMode mode) {
            switch (mode) {
                case COLUMN_MAJOR:
                    return option(CompilerOption.of(CompilerOptionName.MatrixLayoutColumn, 1));
                case ROW_MAJOR:
                    return option(CompilerOption.of(CompilerOptionName.MatrixLayoutRow, 1));
                default:
                    throw new IllegalArgumentException("Unsupported matrix layout: " + mode);
            }
        }

        /**
         * Instantiate the WASM module and create the configured session.
         *
         * @throws IllegalStateException if {@link #wasm} was never called or no
         *                                target was ever added
         * @throws IOException if the WASM file cannot be read, the module fails
         *                     to instantiate, or session creation fails
         */
        public SlangCompiler build() throws IOException {
            if (wasmPath == null) {
                throw new IllegalStateException("wasm(Path) is required");
            }
            if (targets.isEmpty()) {
                throw new IllegalStateException("at least one target(...) is required");
            }
            SlangRuntime runtime = SlangRuntime.builder(wasmPath)
                    .withRuntimeCompiler(runtimeCompiler)
                    .build();
            return openSession(runtime, targets, macros, searchPaths, options, true);
        }
    }

    /** Begin building a session with {@link SessionBuilder}. */
    public static SessionBuilder builder() {
        return new SessionBuilder();
    }

    private final SlangRuntime runtime;
    private final SlangWasm_ModuleExports wasm;
    private final int sessionHandle;
    private final List<Target> targetFormats;
    /** True when this session created its own {@link SlangRuntime} (standalone
     *  {@code fromWasm}/{@code builder} factories) and should release it on close;
     *  false when the runtime is shared (sessions opened via {@link SlangRuntime}). */
    private final boolean ownsRuntime;

    private SlangCompiler(SlangRuntime runtime, int sessionHandle, List<Target> targetFormats,
                          boolean ownsRuntime) {
        this.runtime = runtime;
        this.wasm = runtime.wasm();
        this.sessionHandle = sessionHandle;
        this.targetFormats = targetFormats;
        this.ownsRuntime = ownsRuntime;
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
        return openSession(SlangRuntime.load(wasmPath), targetFormat, profile, true);
    }

    /**
     * Open a single-target session on an already-initialized {@code runtime}.
     * When {@code ownsRuntime} is true the returned compiler releases the runtime
     * on {@link #close()}; when false the runtime is shared and outlives the
     * session.
     */
    static SlangCompiler openSession(SlangRuntime runtime, Target targetFormat, String profile,
                                     boolean ownsRuntime) throws IOException {
        SlangWasm_ModuleExports wasm = runtime.wasm();

        // Create the Slang session for the requested target.
        byte[] profileUtf8 = profile.getBytes(StandardCharsets.UTF_8);
        int profilePtr = 0;
        if (profileUtf8.length > 0) {
            profilePtr = allocAndWrite(wasm, profileUtf8);
        }

        int handle = wasm.slangWasmSessionCreate(targetFormat.value, profilePtr, profileUtf8.length);

        if (profilePtr != 0) {
            wasm.slangWasmFree(profilePtr);
        }

        if (handle == 0) {
            throw new IOException(
                    "slang_wasm_session_create returned 0 — failed to create Slang session");
        }

        return new SlangCompiler(runtime, handle, List.of(targetFormat), ownsRuntime);
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
        return openSession(SlangRuntime.load(wasmPath), targets, macros, searchPaths, options, true);
    }

    /**
     * Open a multi-target session on an already-initialized {@code runtime}.
     * See {@link #openSession(SlangRuntime, Target, String, boolean)} for the
     * meaning of {@code ownsRuntime}.
     */
    static SlangCompiler openSession(
            SlangRuntime runtime,
            List<TargetSpec> targets,
            Map<String, String> macros,
            List<String> searchPaths,
            List<CompilerOption> options,
            boolean ownsRuntime)
            throws IOException {

        if (targets.isEmpty()) {
            throw new IllegalArgumentException("at least one target is required");
        }

        SlangWasm_ModuleExports wasm = runtime.wasm();

        int targetList = wasm.slangWasmTargetListCreate();
        for (TargetSpec target : targets) {
            byte[] profileUtf8 = target.profile.getBytes(StandardCharsets.UTF_8);
            int profilePtr = profileUtf8.length > 0 ? allocAndWrite(wasm, profileUtf8) : 0;
            int flagsBits = 0;
            for (TargetFlags flag : target.flags) {
                flagsBits |= flag.value;
            }
            wasm.slangWasmTargetListAdd(
                    targetList, target.format.value,
                    profilePtr, profileUtf8.length,
                    flagsBits);
            if (profilePtr != 0) {
                wasm.slangWasmFree(profilePtr);
            }
        }

        int macroList = wasm.slangWasmMacroListCreate();
        for (var entry : macros.entrySet()) {
            byte[] nameUtf8 = entry.getKey().getBytes(StandardCharsets.UTF_8);
            byte[] valueUtf8 = entry.getValue().getBytes(StandardCharsets.UTF_8);
            int namePtr = allocAndWrite(wasm, nameUtf8);
            int valuePtr = valueUtf8.length > 0 ? allocAndWrite(wasm, valueUtf8) : 0;
            wasm.slangWasmMacroListAdd(
                    macroList, namePtr, nameUtf8.length,
                    valuePtr, valueUtf8.length);
            wasm.slangWasmFree(namePtr);
            if (valuePtr != 0) {
                wasm.slangWasmFree(valuePtr);
            }
        }

        int pathList = wasm.slangWasmPathListCreate();
        for (String path : searchPaths) {
            byte[] pathUtf8 = path.getBytes(StandardCharsets.UTF_8);
            int pathPtr = allocAndWrite(wasm, pathUtf8);
            wasm.slangWasmPathListAdd(pathList, pathPtr, pathUtf8.length);
            wasm.slangWasmFree(pathPtr);
        }

        int optionList = wasm.slangWasmOptionsCreate();
        for (CompilerOption option : options) {
            if (option.isString) {
                byte[] valUtf8 = option.stringValue.getBytes(StandardCharsets.UTF_8);
                int valPtr = allocAndWrite(wasm, valUtf8);
                wasm.slangWasmOptionsAddString(optionList, option.name.value, valPtr, valUtf8.length);
                wasm.slangWasmFree(valPtr);
            } else {
                wasm.slangWasmOptionsAddInt(optionList, option.name.value, option.intValue);
            }
        }

        int handle = wasm.slangWasmSessionCreate2(targetList, macroList, pathList, optionList);

        if (handle == 0) {
            throw new IOException(
                    "slang_wasm_session_create2 returned 0 — failed to create Slang session");
        }

        List<Target> formats = new ArrayList<>(targets.size());
        for (TargetSpec target : targets) {
            formats.add(target.format);
        }
        return new SlangCompiler(runtime, handle, formats, ownsRuntime);
    }

    /**
     * Position of {@code target} among the targets this session was created
     * with, for use as the {@code targetIndex} the C ABI expects. Lets callers
     * select a target by its {@link Target} value instead of a raw position.
     *
     * @throws IllegalArgumentException if {@code target} is not one of this
     *                                  session's configured targets, or appears
     *                                  more than once (ambiguous — use the
     *                                  {@code int targetIndex} overloads instead)
     */
    private int targetIndexOf(Target target) {
        int found = -1;
        for (int i = 0; i < targetFormats.size(); i++) {
            if (targetFormats.get(i) == target) {
                if (found != -1) {
                    throw new IllegalArgumentException(
                            "Target " + target + " appears more than once in this session; "
                            + "use the int targetIndex overload to disambiguate");
                }
                found = i;
            }
        }
        if (found == -1) {
            throw new IllegalArgumentException(
                    "Target " + target + " is not one of this session's configured targets: "
                    + targetFormats);
        }
        return found;
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

        int modPtr   = allocAndWrite(wasm, moduleNameUtf8);
        int srcPtr   = allocAndWrite(wasm, sourceUtf8);
        int entryPtr = allocAndWrite(wasm, entryUtf8);

        int resultHandle;
        try {
            resultHandle = wasm.slangWasmCompile(
                    sessionHandle,
                    modPtr,   moduleNameUtf8.length,
                    srcPtr,   sourceUtf8.length,
                    entryPtr, entryUtf8.length,
                    targetIndex);
        } finally {
            wasm.slangWasmFree(modPtr);
            wasm.slangWasmFree(srcPtr);
            wasm.slangWasmFree(entryPtr);
        }

        return readCompileResult(resultHandle, "slang_wasm_compile");
    }

    /**
     * Compile {@code source} as module {@code moduleName} and link entry point
     * {@code entryPoint}, producing code for {@code target} (resolved to its
     * position among this session's configured targets). Never throws for
     * compile errors: errors are captured in the returned {@link CompileResult}.
     *
     * @throws IllegalArgumentException if {@code target} is not one of this
     *                                  session's configured targets
     */
    public CompileResult compile(String moduleName, String source, String entryPoint, Target target) {
        return compile(moduleName, source, entryPoint, targetIndexOf(target));
    }

    /**
     * Compile a {@link CompileRequest} (a named-method alternative to the
     * positional-argument {@link #compile(String, String, String)} overloads).
     * Never throws for compile errors: errors are captured in the returned
     * {@link CompileResult}.
     */
    public CompileResult compile(CompileRequest request) {
        if (request.entryPoint == null) {
            throw new IllegalStateException(
                    "CompileRequest.entryPoint(...) must be called before compiling a single "
                    + "entry point; use loadModule(...).compileAll(...) to compile every entry "
                    + "point in a module");
        }
        return request.target != null
                ? compile(request.moduleName, request.source, request.entryPoint, request.target)
                : compile(request.moduleName, request.source, request.entryPoint, request.targetIndex);
    }

    /**
     * A named-method alternative to the positional {@code (moduleName, source,
     * entryPoint, target)} arguments of {@link #compile(String, String, String, Target)},
     * built fluently and passed to {@link #compile(CompileRequest)}.
     */
    public static final class CompileRequest {
        private final String moduleName;
        private final String source;
        private String entryPoint;
        private Target target;
        private int targetIndex = 0;

        private CompileRequest(String moduleName, String source) {
            this.moduleName = moduleName;
            this.source = source;
        }

        /** Begin a request to compile {@code source} as module {@code moduleName}. */
        public static CompileRequest source(String moduleName, String source) {
            return new CompileRequest(moduleName, source);
        }

        /** The entry point to link and compile. Required before calling {@link #compile(CompileRequest)}. */
        public CompileRequest entryPoint(String entryPoint) {
            this.entryPoint = entryPoint;
            return this;
        }

        /** Compile for {@code target} (resolved to its session position). Defaults to the session's first target. */
        public CompileRequest target(Target target) {
            this.target = target;
            return this;
        }

        /** Compile for the target at {@code targetIndex}. Defaults to 0 (the session's first target). */
        public CompileRequest target(int targetIndex) {
            this.target = null;
            this.targetIndex = targetIndex;
            return this;
        }
    }

    /** Read and destroy a {@code SlangWasmResult} handle, producing a {@link CompileResult}. */
    private CompileResult readCompileResult(int resultHandle, String sourceExportName) {
        if (resultHandle == 0) {
            return new CompileResult(false, new byte[0], "",
                    sourceExportName + " returned handle 0");
        }

        try {
            boolean ok = wasm.slangWasmResultSucceeded(resultHandle) != 0;

            int codePtr = wasm.slangWasmResultCodePtr(resultHandle);
            int codeLen = wasm.slangWasmResultCodeLen(resultHandle);
            byte[] code = codeLen == 0 ? new byte[0] : wasm.memory().readBytes(codePtr, codeLen);

            int reflPtr = wasm.slangWasmResultReflectionJsonPtr(resultHandle);
            int reflLen = wasm.slangWasmResultReflectionJsonLen(resultHandle);
            String reflJson = reflLen == 0 ? "" : wasm.memory().readString(reflPtr, reflLen);

            int diagPtr = wasm.slangWasmResultDiagnosticsPtr(resultHandle);
            int diagLen = wasm.slangWasmResultDiagnosticsLen(resultHandle);
            String diagnostics = diagLen == 0 ? "" : wasm.memory().readString(diagPtr, diagLen);

            return new CompileResult(ok, code, reflJson, diagnostics);
        } finally {
            wasm.slangWasmResultDestroy(resultHandle);
        }
    }

    /**
     * Parse {@code source} as module {@code moduleName} once, returning a handle
     * that can compile any number of its entry points independently without
     * re-parsing. Unlike {@link #compile}, which loads, compiles, and discards a
     * module in one call, the returned {@link SlangModule} stays loaded until
     * closed.
     *
     * @throws IOException if the module fails to load; the exception message
     *                      includes the diagnostics text
     */
    public SlangModule loadModule(String moduleName, String source) throws IOException {
        byte[] nameUtf8 = moduleName.getBytes(StandardCharsets.UTF_8);
        byte[] sourceUtf8 = source.getBytes(StandardCharsets.UTF_8);
        int namePtr = allocAndWrite(wasm, nameUtf8);
        int sourcePtr = allocAndWrite(wasm, sourceUtf8);

        // Two adjacent 4-byte out-param slots for the load's diagnostics
        // (ptr, len); slang_wasm_session_load_module writes into both,
        // regardless of whether the load succeeds.
        int diagOut = allocAndWrite(wasm, new byte[8]);
        int diagPtrAddr = diagOut;
        int diagLenAddr = diagOut + 4;

        int moduleHandle;
        try {
            moduleHandle = wasm.slangWasmSessionLoadModule(
                    sessionHandle,
                    namePtr,   nameUtf8.length,
                    sourcePtr, sourceUtf8.length,
                    diagPtrAddr, diagLenAddr);
        } finally {
            wasm.slangWasmFree(namePtr);
            wasm.slangWasmFree(sourcePtr);
        }

        String diagnostics = readAndFreeDiagOut(diagOut);

        if (moduleHandle == 0) {
            throw new IOException(
                    "slang_wasm_session_load_module returned 0 — failed to load module \""
                    + moduleName + "\". Diagnostics:\n" + diagnostics);
        }

        return new SlangModule(moduleHandle, diagnostics);
    }

    /**
     * Load a module from a precompiled IR blob (as produced by {@link
     * SlangModule#serialize()} in an earlier session), skipping re-parsing and
     * re-checking the original source. {@code moduleName} need not match the
     * name the module was originally loaded under.
     *
     * @throws IOException if the IR blob fails to load (e.g. it was produced
     *                      by an incompatible Slang build); the exception
     *                      message includes the diagnostics text
     */
    public SlangModule loadModuleFromIr(String moduleName, byte[] ir) throws IOException {
        byte[] nameUtf8 = moduleName.getBytes(StandardCharsets.UTF_8);
        int namePtr = allocAndWrite(wasm, nameUtf8);
        int irPtr = allocAndWrite(wasm, ir);

        int diagOut = allocAndWrite(wasm, new byte[8]);
        int diagPtrAddr = diagOut;
        int diagLenAddr = diagOut + 4;

        int moduleHandle;
        try {
            moduleHandle = wasm.slangWasmSessionLoadModuleIr(
                    sessionHandle,
                    namePtr, nameUtf8.length,
                    irPtr,   ir.length,
                    diagPtrAddr, diagLenAddr);
        } finally {
            wasm.slangWasmFree(namePtr);
            wasm.slangWasmFree(irPtr);
        }

        String diagnostics = readAndFreeDiagOut(diagOut);

        if (moduleHandle == 0) {
            throw new IOException(
                    "slang_wasm_session_load_module_ir returned 0 — failed to load module \""
                    + moduleName + "\" from IR. Diagnostics:\n" + diagnostics);
        }

        return new SlangModule(moduleHandle, diagnostics);
    }

    /**
     * Read the (ptr, len) diagnostics pair written by a load function at the two adjacent
     * 4-byte out-param slots starting at {@code diagOut}, then free both that scratch
     * allocation and (if non-null) the diagnostics buffer itself.
     */
    private String readAndFreeDiagOut(int diagOut) {
        int diagPtr = readI32(diagOut);
        int diagLen = readI32(diagOut + 4);
        wasm.slangWasmFree(diagOut);
        String diagnostics = diagLen > 0 ? wasm.memory().readString(diagPtr, diagLen) : "";
        if (diagPtr != 0) {
            wasm.slangWasmFree(diagPtr);
        }
        return diagnostics;
    }

    /**
     * A module parsed once via {@link #loadModule}, whose entry points can each
     * be compiled independently (or all together, into one combined blob) without
     * re-parsing the source. {@code AutoCloseable}: release with
     * {@link #close()} once no more entry points need compiling.
     */
    public final class SlangModule implements AutoCloseable {
        private final int handle;
        private final String diagnostics;

        private SlangModule(int handle, String diagnostics) {
            this.handle = handle;
            this.diagnostics = diagnostics;
        }

        /** Diagnostics (warnings) produced while loading this module. */
        public String diagnostics() {
            return diagnostics;
        }

        /** Names of the entry points (functions marked {@code [shader("...")]}) defined in this module. */
        public List<String> entryPointNames() {
            int count = wasm.slangWasmModuleEntryPointCount(handle);
            List<String> names = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int ptr = wasm.slangWasmModuleEntryPointNamePtr(handle, i);
                int len = wasm.slangWasmModuleEntryPointNameLen(handle, i);
                names.add(len == 0 ? "" : wasm.memory().readString(ptr, len));
            }
            return names;
        }

        /**
         * Compile entry point {@code entryPoint} from this module, producing code
         * for the target at {@code targetIndex}. Never throws for compile errors:
         * errors are captured in the returned {@link CompileResult}.
         */
        public CompileResult compileEntryPoint(String entryPoint, int targetIndex) {
            byte[] entryUtf8 = entryPoint.getBytes(StandardCharsets.UTF_8);
            int entryPtr = allocAndWrite(wasm, entryUtf8);
            int resultHandle;
            try {
                resultHandle = wasm.slangWasmCompileEntryPoint(
                        sessionHandle, handle, entryPtr, entryUtf8.length, targetIndex);
            } finally {
                wasm.slangWasmFree(entryPtr);
            }
            return readCompileResult(resultHandle, "slang_wasm_compile_entry_point");
        }

        /**
         * Compile entry point {@code entryPoint} from this module for
         * {@code target} (resolved to its position among this session's
         * configured targets). Never throws for compile errors.
         *
         * @throws IllegalArgumentException if {@code target} is not one of this
         *                                  session's configured targets
         */
        public CompileResult compileEntryPoint(String entryPoint, Target target) {
            return compileEntryPoint(entryPoint, targetIndexOf(target));
        }

        /**
         * Compile every entry point defined in this module together into one
         * combined code blob for the target at {@code targetIndex} (e.g. one
         * SPIR-V module containing both a vertex and a fragment entry point).
         * Never throws for compile errors: errors are captured in the returned
         * {@link CompileResult}.
         */
        public CompileResult compileAll(int targetIndex) {
            int resultHandle = wasm.slangWasmCompileModule(sessionHandle, handle, targetIndex);
            return readCompileResult(resultHandle, "slang_wasm_compile_module");
        }

        /**
         * Compile every entry point in this module together for {@code target}
         * (resolved to its position among this session's configured targets).
         *
         * @throws IllegalArgumentException if {@code target} is not one of this
         *                                  session's configured targets
         */
        public CompileResult compileAll(Target target) {
            return compileAll(targetIndexOf(target));
        }

        /**
         * Specialize entry point {@code entryPoint} from this module with
         * {@code args} (in argument-list order, matching the generic parameter
         * declaration order), then link and compile for the target at
         * {@code targetIndex}. Never throws for compile errors: errors are
         * captured in the returned {@link CompileResult} (including an
         * unresolvable type-name argument).
         */
        public CompileResult compileSpecialized(
                String entryPoint, List<SpecializationArg> args, int targetIndex) {
            byte[] entryUtf8 = entryPoint.getBytes(StandardCharsets.UTF_8);
            int entryPtr = allocAndWrite(wasm, entryUtf8);

            int argsHandle = wasm.slangWasmSpecArgsCreate();
            for (SpecializationArg arg : args) {
                byte[] valueUtf8 = arg.value.getBytes(StandardCharsets.UTF_8);
                int valuePtr = allocAndWrite(wasm, valueUtf8);
                if (arg.isType) {
                    wasm.slangWasmSpecArgsAddType(argsHandle, valuePtr, valueUtf8.length);
                } else {
                    wasm.slangWasmSpecArgsAddExpr(argsHandle, valuePtr, valueUtf8.length);
                }
                wasm.slangWasmFree(valuePtr);
            }

            int resultHandle;
            try {
                resultHandle = wasm.slangWasmCompileSpecializedEntryPoint(
                        sessionHandle, handle, entryPtr, entryUtf8.length,
                        argsHandle, targetIndex);
            } finally {
                wasm.slangWasmFree(entryPtr);
            }
            return readCompileResult(resultHandle, "slang_wasm_compile_specialized_entry_point");
        }

        /**
         * Specialize entry point {@code entryPoint} from this module with
         * {@code args}, for {@code target} (resolved to its position among this
         * session's configured targets).
         *
         * @throws IllegalArgumentException if {@code target} is not one of this
         *                                  session's configured targets
         */
        public CompileResult compileSpecialized(
                String entryPoint, List<SpecializationArg> args, Target target) {
            return compileSpecialized(entryPoint, args, targetIndexOf(target));
        }

        /**
         * Serialise this module's checked IR to bytes, for later reloading via
         * {@link SlangCompiler#loadModuleFromIr} (in this session or a later
         * one) without re-parsing or re-checking the original source.
         *
         * @throws IOException if serialization fails; the exception message
         *                      includes the diagnostics text
         */
        public byte[] serialize() throws IOException {
            int resultHandle = wasm.slangWasmModuleSerialize(handle);
            CompileResult result = readCompileResult(resultHandle, "slang_wasm_module_serialize");
            if (!result.succeeded()) {
                throw new IOException(
                        "slang_wasm_module_serialize failed. Diagnostics:\n" + result.diagnostics());
            }
            return result.code();
        }

        /**
         * Serialise this module's module-level declaration tree to JSON (every
         * struct, function, variable, enum, namespace, and generic declared at
         * module scope, recursively) — without compiling to any target. Parse
         * the result with {@link org.shaderslang.wasm.reflection.DeclReflection#parse}.
         *
         * @throws IOException if reflection fails; the exception message
         *                      includes the diagnostics text
         */
        public String declReflectionJson() throws IOException {
            int resultHandle = wasm.slangWasmModuleDeclReflectionJson(handle);
            CompileResult result =
                    readCompileResult(resultHandle, "slang_wasm_module_decl_reflection_json");
            if (!result.succeeded()) {
                throw new IOException(
                        "slang_wasm_module_decl_reflection_json failed. Diagnostics:\n"
                        + result.diagnostics());
            }
            return result.reflectionJson();
        }

        /**
         * Disassemble this module's checked IR to human-readable text.
         *
         * @throws IOException if disassembly fails; the exception message
         *                      includes the diagnostics text
         */
        public String disassemble() throws IOException {
            int resultHandle = wasm.slangWasmModuleDisassemble(handle);
            CompileResult result = readCompileResult(resultHandle, "slang_wasm_module_disassemble");
            if (!result.succeeded()) {
                throw new IOException(
                        "slang_wasm_module_disassemble failed. Diagnostics:\n"
                        + result.diagnostics());
            }
            // The C ABI reuses the diagnostics field to carry disassembly text
            // (see slang-wasm-lib.h) — translate that back to a sensible name here.
            return result.diagnostics();
        }

        @Override
        public void close() {
            wasm.slangWasmModuleDestroy(handle);
        }
    }

    /**
     * Return the Slang build tag string (e.g. {@code "v2025.21"}) as reported by
     * the WASM module. Useful for sanity-checking the loaded build.
     */
    public String version() {
        int ptr = wasm.slangWasmVersion();
        return wasm.memory().readCString(ptr);
    }

    @Override
    public void close() {
        wasm.slangWasmSessionDestroy(sessionHandle);
        if (ownsRuntime) {
            runtime.close();
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Allocate space in WASM linear memory and copy {@code bytes} into it. */
    private static int allocAndWrite(SlangWasm_ModuleExports wasm, byte[] bytes) {
        int ptr = wasm.slangWasmAlloc(bytes.length);
        if (ptr == 0) {
            throw new OutOfMemoryError("slang_wasm_alloc returned NULL for size " + bytes.length);
        }
        wasm.memory().write(ptr, bytes);
        return ptr;
    }

    /** Read a little-endian i32 out-param written by the WASM module at `addr`. */
    private int readI32(int addr) {
        byte[] b = wasm.memory().readBytes(addr, 4);
        return (b[0] & 0xFF) | ((b[1] & 0xFF) << 8) | ((b[2] & 0xFF) << 16) | ((b[3] & 0xFF) << 24);
    }
}
