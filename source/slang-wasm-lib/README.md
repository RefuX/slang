# slang-wasm-lib

A WebAssembly build of the [Slang](https://shader-slang.com) shader compiler, callable from the
JVM (or any WASI-compatible runtime) with **no dependency on Emscripten or a native Slang
installation**. The module is a plain WASI reactor exporting a flat C ABI.
This is currently a WASIp1 project, due to this there is a fair amount of work the consumer must do to use it.
An example Java project is provided, to show how to use the WASM module.

---

## Prerequisites

| Tool | Version | Purpose |
|------|---------|---------|
| [wasi-sdk](https://github.com/WebAssembly/wasi-sdk/releases) | 33+ | Cross-compiler that produces the `.wasm` |
| CMake | 3.25+ | Build system |
| Ninja | any | Recommended generator |
| Python 3 | 3.8+ | Enum binding generator (standard library only) |
| Java | 11+ | Running the example Java consumer and its tests (optional — only the `.wasm` itself is required to use this library from another runtime) |

A Gradle wrapper (`./gradlew`) is committed under `java/`, so a system Gradle install is not
required.

---

## Building the WASM module

### 1 — Build the native code generators (one-time)

Slang's build system generates some source files from a native host binary. This step
produces those generators on your build machine before cross-compiling to WASM:

```bash
cmake --workflow --preset generators --fresh
cmake --install build --config Release --prefix generators --component generators
```

### 2 — Configure for WASI

```bash
export WASI_SDK_PATH=/path/to/wasi-sdk

cmake -DSLANG_GENERATORS_PATH=generators/bin --preset wasi
```

At configure time CMake checks whether `include/slang.h` is newer than the committed generated
files. If it is, it automatically runs the [enum binding generator](#enum-binding-generator) to
keep the C++ metadata blob and the Java enum sources in sync.

### 3 — Build

```bash
cmake --build --preset slang-wasm-lib
```

Output: `build.wasi/Release/bin/slang-wasm-lib.wasm`

### Artifact size

The module is a whole-program-linked Slang build with the core module embedded
(`SLANG_EMBED_CORE_MODULE=ON`), so it lands in the same ~20 MB class as the Emscripten
`slang-wasm.wasm` build. If that size matters for your deployment (cold-start time, download
size), the usual WASM levers apply, in roughly this order of effort-to-payoff:

- **`wasm-opt -Oz`** (from [Binaryen](https://github.com/WebAssembly/binaryen)) as a post-build
  step — typically the single biggest win for the least effort, at the cost of an extra build
  step and (with aggressive flags) slightly harder-to-debug DWARF info.
- **LTO** (`-flto` on both compile and link) — smaller code at the cost of longer link times;
  most valuable for a release/distribution build, not worth it for local iteration.
- **`-Os`/`-Oz` instead of `-O2`** at the compiler level (already the default in the `wasi`
  preset's `CMAKE_C_FLAGS_INIT`/`CMAKE_CXX_FLAGS_INIT` — see `CMakePresets.json`) — trades a
  little runtime speed for smaller code, which is the right tradeoff for a module whose hot path
  is "compile a shader," not the shim's own code.

None of these are wired into the `wasi`/`slang-wasm-lib` presets by default, since they all trade
build time or debuggability for size and that tradeoff should be a deliberate choice by whoever is
packaging a release, not a default every local build pays for.

---

## Consuming the module

`slang-wasm-lib.wasm` is a plain WASI Preview 1 reactor module with a flat C ABI — it has no
runtime preference baked in. Any host capable of instantiating a WASI Preview 1 module and calling
its exports can use it: a JVM via a pure-Java runtime, a browser via a WASIp1 polyfill, Wasmtime,
wasmer, Node, Go, Rust, Python, and so on. Because this is WASIp1 (not the newer Component Model /
WASIp2), there is no automatically-generated host binding for any of these — the consumer has to do
the following itself, regardless of language or runtime:

1. Parse and instantiate the module, providing WASI Preview 1 imports. The module needs only a
   small import surface (e.g. `fd_write` for stderr output, `clock_*`, `random_get`) — no real
   filesystem access is required for the compile-from-string path, since the Slang core module is
   embedded in the binary rather than loaded from disk.
2. Call the exported `_initialize` function once, before any other export — this is the standard
   WASI **reactor** convention (a library-style module with no `main`, as opposed to a WASI
   **command** module that runs once and exits).
3. Write UTF-8 input (module names, source text, …) into the module's linear memory via
   `slang_wasm_alloc`/the memory object the runtime exposes, then call whichever C ABI export is
   needed, reading results back out of linear memory the same way.

The [C ABI reference](#c-abi-reference) below documents every export; [`slang-wasm-lib.h`](slang-wasm-lib.h)
is the authoritative signature/ownership reference.

### Example consumer: Java via Endive

This repository includes a Java project under `source/slang-wasm-lib/java/` purely as a worked
example of the steps above — it is **not** the only way to use the module, just a reference
implementation showing the pattern end to end. It drives the module via
[Endive](https://github.com/bytecodealliance/endive), a pure-JVM WebAssembly runtime, so it needs
no native Slang install and no JNI. A consumer in Go, Python, Rust, or a browser would follow the
same three steps above against whatever WASI-capable runtime is idiomatic there.

Add the Gradle project at `source/slang-wasm-lib/java/` to your build, or copy the sources. It
depends only on `run.endive:runtime:0.0.1` and `run.endive:wasi:0.0.1` from Maven Central.

### Compile a shader to SPIR-V

```java
import org.shaderslang.wasm.SlangCompiler;
import org.shaderslang.wasm.CompileResult;

try (var slang = SlangCompiler.forSpirvFromWasm(Path.of("slang-wasm-lib.wasm"))) {

    CompileResult result = slang.compile(
        "hello",                                               // module name
        "[shader(\"compute\")] [numthreads(1,1,1)] void main() {}",  // source
        "main");                                               // entry point

    if (result.succeeded()) {
        byte[] spirv = result.code();          // SPIR-V binary
        String refl  = result.reflectionJson(); // reflection as JSON
    } else {
        System.err.println(result.diagnostics());
    }
}
```

### A full session: multiple targets, macros, and compiler options

```java
import org.shaderslang.wasm.enums.Target;
import org.shaderslang.wasm.enums.OptimizationLevel;

try (var slang = SlangCompiler.builder()
        .wasm(Path.of("slang-wasm-lib.wasm"))
        .target(Target.SPIRV, "spirv_1_4")
        .target(Target.HLSL)
        .define("ENABLE_SHADOWS", "1")
        .optimizationLevel(OptimizationLevel.HIGH)
        .build()) {

    CompileResult spirv = slang.compile("hello", source, "main", Target.SPIRV);
    CompileResult hlsl  = slang.compile("hello", source, "main", Target.HLSL);
}
```

### Modules: load once, compile each entry point independently

```java
try (var slang = SlangCompiler.forSpirvFromWasm(wasmPath);
     var module = slang.loadModule("pipeline", vertAndFragSource)) {

    List<String> entryPoints = module.entryPointNames();      // ["vert", "frag"]

    CompileResult vert = module.compileEntryPoint("vert", Target.SPIRV);
    CompileResult frag = module.compileEntryPoint("frag", Target.SPIRV);
    CompileResult both = module.compileAll(Target.SPIRV);     // one combined SPIR-V module

    byte[] ir = module.serialize();                            // precompiled IR for later reuse
}

// Later, in this or another session — reload without re-parsing the source.
try (var slang = SlangCompiler.forSpirvFromWasm(wasmPath);
     var module = slang.loadModuleFromIr("pipeline", ir)) {
    CompileResult vert = module.compileEntryPoint("vert", Target.SPIRV);
}
```

### Generic specialization

```java
import org.shaderslang.wasm.SlangCompiler.SpecializationArg;

try (var slang = SlangCompiler.forSpirvFromWasm(wasmPath);
     var module = slang.loadModule("renderer", genericRendererSource)) {

    CompileResult pbr = module.compileSpecialized(
        "main", List.of(SpecializationArg.fromType("PbrMaterial")), Target.SPIRV);
}
```

### Typed reflection, declaration reflection, disassembly, and diagnostics

```java
import org.shaderslang.wasm.reflection.ShaderReflection;
import org.shaderslang.wasm.reflection.DeclReflection;
import org.shaderslang.wasm.diagnostics.DiagnosticList;

CompileResult result = slang.compile("hello", source, "main");

// Binding layout, type info, entry-point metadata — no JSON parsing.
ShaderReflection reflection = ShaderReflection.parse(result.reflectionJson());

// Structured errors instead of grepping the raw diagnostics string.
DiagnosticList diagnostics = DiagnosticList.parse(result.diagnostics());
if (diagnostics.hasErrors()) {
    diagnostics.diagnostics().forEach(System.err::println);
}

try (var module = slang.loadModule("hello", source)) {
    // Module-level decl tree (structs, functions, ...) — no compile required.
    DeclReflection decl = DeclReflection.parse(module.declReflectionJson());
    // Human-readable IR.
    String ir = module.disassemble();
}
```

### Check the build version

```java
try (var slang = SlangCompiler.forSpirvFromWasm(wasmPath)) {
    System.out.println(slang.version()); // e.g. "v2025.21"
}
```

---

## Running the example's tests

The Java example's tests cover compilation, the builder/`CompileRequest` API, modules,
specialization, typed and declaration reflection, structured diagnostics, and disassembly —
exercising the full C ABI through that one example consumer. They double as the regression suite
for the `.wasm` artifact itself, and skip automatically when the artifact has not been built yet.

```bash
# From source/slang-wasm-lib/java/
./gradlew test

# Point at a specific WASM artifact (default: build.wasi/Release/bin/slang-wasm-lib.wasm)
./gradlew test -Pslang.wasm.path=/path/to/slang-wasm-lib.wasm

# Or via env var
SLANG_WASM_PATH=/path/to/slang-wasm-lib.wasm ./gradlew test
```

### Faster startup in production: Endive's AOT compiler

This is specific to the Endive-based Java example, not a property of the `.wasm` module itself —
a consumer on a different runtime would look for that runtime's equivalent. Endive's interpreter
is correct but, like any bytecode interpreter, pays a JIT-style warm-up cost on first use against a
module this size (~20 MB class). For a long-running production service where that cold-start cost
matters, Endive ships a build-time **AOT compiler** plugin that compiles the `.wasm` to native JVM
bytecode ahead of time, so the first real call runs at steady-state speed instead of warming up
through the interpreter. Wire it in as a Gradle plugin dependency alongside
`run.endive:runtime`/`run.endive:wasi` (see the Endive documentation for the current artifact
coordinates and plugin configuration); the interpreter remains the right default for local
development and one-shot CLI usage, where the AOT compile step itself would dominate.

---

## Enum binding generator

`tools/generate-slang-bindings.py` reads `include/slang.h` and emits two generated artefacts
that are committed to the repository:

| Output | Purpose |
|--------|---------|
| `slang-wasm-enum-metadata.cpp` | C++ static JSON blob baked into the WASM module, exported via `slang_wasm_enum_metadata_ptr/len`. Dynamic-language runtimes (Go, Python, Rust) call these two exports at startup to resolve enum integer values without hardcoding them. |
| `java/src/generated/java/org/shaderslang/wasm/enums/*.java` | One Java enum per Slang enum, with integer values baked in from `slang.h`. Provides compile-time type safety and IDE autocomplete. |

Both files are committed so `./gradlew build` works without running CMake or Python first.
CMake regenerates them automatically at configure time when `slang.h` is newer than the C++ output.

### Running the generator manually

```bash
# From the repository root:
python3 source/slang-wasm-lib/tools/generate-slang-bindings.py

# With explicit paths:
python3 source/slang-wasm-lib/tools/generate-slang-bindings.py \
    --slang-h  include/slang.h \
    --cpp-out  source/slang-wasm-lib/slang-wasm-enum-metadata.cpp \
    --java-out source/slang-wasm-lib/java/src/generated/java/org/shaderslang/wasm/enums
```

### Enums generated

| Java class | Slang source enum | Example constant |
|------------|------------------|------------------|
| `Target` | `SlangCompileTarget` | `Target.SPIRV` (6) |
| `Stage` | `SlangStage` | `Stage.COMPUTE` (6) |
| `CompilerOptionName` | `slang::CompilerOptionName` | `CompilerOptionName.MacroDefine` (0) |
| `TypeKind` | `SlangTypeKind` | `TypeKind.STRUCT` (1) |
| `ScalarType` | `SlangScalarType` | `ScalarType.FLOAT32` (8) |
| `ResourceAccess` | `SlangResourceAccess` | `ResourceAccess.READ_WRITE` (2) |
| `ResourceShape` | `SlangResourceShape` | `ResourceShape.TEXTURE_2D` (2) |
| `ParameterCategory` | `SlangParameterCategory` | `ParameterCategory.UNIFORM` (8) |
| `LayoutRules` | `SlangLayoutRules` | `LayoutRules.DEFAULT` (0) |
| `MatrixLayoutMode` | `SlangMatrixLayoutMode` | `MatrixLayoutMode.COLUMN_MAJOR` (2) |
| `FloatingPointMode` | `SlangFloatingPointMode` | `FloatingPointMode.PRECISE` (2) |
| `OptimizationLevel` | `SlangOptimizationLevel` | `OptimizationLevel.DEFAULT` (1) |
| `DebugInfoLevel` | `SlangDebugInfoLevel` | `DebugInfoLevel.STANDARD` (2) |
| `PassThrough` | `SlangPassThrough` | `PassThrough.DXC` (2) |
| `TargetFlags` | anonymous `SLANG_TARGET_FLAG_*` | `TargetFlags.GENERATE_SPIRV_DIRECTLY` (1024) |

`DeclReflection.Kind` is a hand-written Java enum, not generated by this script — it
mirrors `slang::DeclReflection::Kind`, an 8-value enum that isn't part of the `slang.h` blocks this
generator scans for.

### Consuming the metadata from other languages

```go
// Go — no hardcoded integers; values come from the WASM itself
meta  := slang.LoadEnumMetadata()          // calls slang_wasm_enum_metadata_ptr/len
spirv := meta["Target"]["SPIRV"]           // → 6
```

```python
# Python — same approach
meta  = slang.load_enum_metadata()
spirv = meta["Target"]["SPIRV"]            # → 6
```

---

## C ABI reference

The module is a WASI reactor. Call `_initialize` once after instantiation, then call any of the
exports below. All pointers are 32-bit offsets into linear memory; all strings are UTF-8 byte
ranges passed as `(ptr, len)` pairs; all objects are opaque `uint32_t` handles. The full, current
signatures and ownership contracts are documented in
[`slang-wasm-lib.h`](slang-wasm-lib.h) — that header, not this table, is the source of truth;
this is an index into it, grouped by capability:

| Group | Exports |
|-------|---------|
| Memory | `slang_wasm_alloc`, `slang_wasm_free` |
| Session | `slang_wasm_session_create`/`slang_wasm_session_create2`, `slang_wasm_session_destroy` |
| Session descriptor builders | `slang_wasm_target_list_*`, `slang_wasm_macro_list_*`, `slang_wasm_path_list_*`, `slang_wasm_options_*` |
| Enum metadata | `slang_wasm_enum_metadata_ptr/len`, `slang_wasm_target_from_string`, `slang_wasm_stage_from_string` |
| Modules | `slang_wasm_session_load_module`, `slang_wasm_module_destroy`, `slang_wasm_module_entry_point_*` |
| Compilation | `slang_wasm_compile`, `slang_wasm_compile_entry_point`, `slang_wasm_compile_module` |
| Result accessors | `slang_wasm_result_succeeded`, `slang_wasm_result_code_*`, `slang_wasm_result_reflection_json_*`, `slang_wasm_result_diagnostics_*`, `slang_wasm_result_destroy` |
| Precompiled IR | `slang_wasm_module_serialize`, `slang_wasm_session_load_module_ir` |
| Specialization | `slang_wasm_spec_args_*`, `slang_wasm_compile_specialized_entry_point` |
| Declaration reflection | `slang_wasm_module_decl_reflection_json` |
| Disassembly | `slang_wasm_module_disassemble` |
| Build info | `slang_wasm_version` |

---

## Status

Capabilities implemented today: compile-from-string, full session control (multiple targets,
preprocessor macros, search paths, compiler options), module handles with independent
multi-entry-point compilation, a Java builder/typed-request API with generated enums throughout,
typed reflection (binding layout and module-level declaration trees), precompiled IR round-tripping,
generic specialization, and disassembly with structured diagnostics.

Not yet implemented: explicit interface type conformance for dynamic dispatch
(`ISession::createTypeConformanceComponentType`) — a separable feature with its own API surface,
left for when a concrete need for it arises. A WASM Component Model / WIT binding is a longer-term
direction rather than near-term work: it requires either Endive (or another runtime) to support the
Component Model, or a separate adapter layer, neither of which exists yet for the WASIp1-only
runtimes this module currently targets.
