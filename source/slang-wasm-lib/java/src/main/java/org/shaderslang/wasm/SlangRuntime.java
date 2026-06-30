package org.shaderslang.wasm;

import run.endive.compiler.MachineFactoryCompiler;
import run.endive.runtime.Instance;
import run.endive.runtime.Store;
import run.endive.wasi.WasiOptions;
import run.endive.wasi.WasiPreview1;
import run.endive.wasm.Parser;
import run.endive.wasm.WasmModule;

import org.shaderslang.wasm.enums.Target;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * One loaded {@code slang-wasm-lib.wasm} instance, shared across any number of
 * {@link SlangCompiler} sessions.
 *
 * <p>Loading the module is the expensive part: parsing the ~150 MB artifact, and
 * — if the runtime compiler is enabled — eagerly compiling it to JVM bytecode.
 * {@code SlangCompiler}'s {@code fromWasm}/{@code builder} factories create one
 * {@code SlangRuntime} per session, which is fine for one-shot use. To compile
 * many shaders, load the runtime once and open a session per configuration:
 *
 * <pre>{@code
 * try (var runtime = SlangRuntime.builder(wasmPath)
 *         .withRuntimeCompiler(true)
 *         .build()) {
 *     try (var spirv = runtime.forSpirv()) {
 *         CompileResult r = spirv.compile("hello", source, "main");
 *     }
 *     // ... more sessions on the same instance, no re-parse / re-compile ...
 * }
 * }</pre>
 *
 * <p><b>Runtime compiler.</b> By default the WASM runs interpreted. Enabling the
 * compiler ({@link Builder#withRuntimeCompiler}) makes each shader compile far
 * faster (roughly an order of magnitude) but adds a one-time cost to
 * {@link Builder#build()} while the module is translated to JVM bytecode. A few
 * very large functions in the Slang compiler exceed what the bytecode emitter can
 * handle and are transparently left interpreted (the compiler logs a one-line
 * warning per such function); this is expected and harmless.
 *
 * <p>Not thread-safe: do not share one runtime (or its sessions) across threads.
 */
public final class SlangRuntime implements AutoCloseable {

    private final SlangWasm_ModuleExports wasm;

    private SlangRuntime(SlangWasm_ModuleExports wasm) {
        this.wasm = wasm;
    }

    SlangWasm_ModuleExports wasm() {
        return wasm;
    }

    /** Load and initialize the WASM module with default (interpreted) execution. */
    public static SlangRuntime load(Path wasmPath) throws IOException {
        return builder(wasmPath).build();
    }

    /** Begin configuring a runtime for {@code wasmPath}. */
    public static Builder builder(Path wasmPath) {
        return new Builder(wasmPath);
    }

    // ── Session factories (shared instance) ─────────────────────────────────────
    // Sessions opened here share this runtime: closing the returned SlangCompiler
    // tears down only its Slang session, not the WASM instance.

    /** Open a SPIR-V session on this runtime. */
    public SlangCompiler forSpirv() throws IOException {
        return newSession(Target.SPIRV, "");
    }

    /** Open a single-target session ({@code profile} may be empty for the default). */
    public SlangCompiler newSession(Target targetFormat, String profile) throws IOException {
        return SlangCompiler.openSession(this, targetFormat, profile, false);
    }

    /** Open a multi-target session (targets, macro defines, and search paths). */
    public SlangCompiler newSession(
            List<SlangCompiler.TargetSpec> targets,
            Map<String, String> macros,
            List<String> searchPaths) throws IOException {
        return newSession(targets, macros, searchPaths, List.of());
    }

    /** Open a multi-target session, additionally with session-wide compiler options. */
    public SlangCompiler newSession(
            List<SlangCompiler.TargetSpec> targets,
            Map<String, String> macros,
            List<String> searchPaths,
            List<SlangCompiler.CompilerOption> options) throws IOException {
        return SlangCompiler.openSession(this, targets, macros, searchPaths, options, false);
    }

    /**
     * Release this runtime. The endive WASM runtime has no explicit teardown
     * (the instance is reclaimed by GC once unreferenced), so this is currently
     * a no-op; it exists so callers can manage the runtime with try-with-resources.
     */
    @Override
    public void close() {
        // no-op: nothing to release deterministically today
    }

    /** Configures and builds a {@link SlangRuntime}. */
    public static final class Builder {
        private final Path wasmPath;
        private boolean runtimeCompiler = false;

        private Builder(Path wasmPath) {
            this.wasmPath = wasmPath;
        }

        /**
         * Enable the endive runtime compiler (compile the WASM to JVM bytecode at
         * {@link #build()} time). Off by default — the module runs interpreted.
         */
        public Builder withRuntimeCompiler(boolean enabled) {
            this.runtimeCompiler = enabled;
            return this;
        }

        public SlangRuntime build() throws IOException {
            return new SlangRuntime(instantiate(wasmPath, runtimeCompiler));
        }
    }

    // ── Internals ────────────────────────────────────────────────────────────

    /** Parse, instantiate (optionally with the runtime compiler), and run _initialize. */
    private static SlangWasm_ModuleExports instantiate(Path wasmPath, boolean useCompiler)
            throws IOException {

        WasmModule module = Parser.parse(wasmPath.toFile());

        Instance inst;
        if (useCompiler) {
            try {
                inst = buildInstance(module, true);
            } catch (Throwable t) {
                // Endive's runtime compiler can hard-fail on some modules (e.g. an
                // ASM frame-computation error on a function it can't translate),
                // which is not the same as the graceful per-function interpreter
                // fallback. Degrade to a fully interpreted instance rather than
                // failing to load the module at all.
                System.err.println(
                        "[slang-wasm-lib] runtime compiler failed (" + t
                        + "); falling back to the interpreter for this instance.");
                inst = buildInstance(module, false);
            }
        } else {
            inst = buildInstance(module, false);
        }

        // Typed view over the module's exports, generated from the wasm by the
        // @WasmModuleInterface processor (see SlangWasm).
        var wasm = new SlangWasm_ModuleExports(inst);

        // Reactor protocol: call _initialize before any other export.
        wasm._initialize();
        return wasm;
    }

    /** Instantiate the module, optionally driving execution through the runtime compiler. */
    private static Instance buildInstance(WasmModule module, boolean useCompiler) {
        var wasi = WasiPreview1.builder()
                .withOptions(WasiOptions.builder()
                        .withStdout(System.out)
                        .withStderr(System.err)
                        .build())
                .build();

        return new Store()
                .addFunction(wasi.toHostFunctions())
                .instantiate("slang-wasm-lib", importValues -> {
                    var b = Instance.builder(module).withImportValues(importValues);
                    if (useCompiler) {
                        // Eagerly compile to JVM bytecode. Functions the emitter
                        // can interpret-fallback are handled per-function; a hard
                        // failure is caught by the caller and degrades the whole
                        // instance to the interpreter.
                        b = b.withMachineFactory(MachineFactoryCompiler::compile);
                    }
                    return b.build();
                });
    }
}
