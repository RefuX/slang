# slang-wasm-lib

A WebAssembly build of the [Slang](https://shader-slang.com) shader compiler, callable from the
JVM (or any WASI-compatible runtime) with **no dependency on Emscripten or a native Slang
installation**. The module is a plain WASI reactor exporting a flat C ABI; the Java wrapper drives
it via [Endive](https://github.com/bytecodealliance/endive), a pure-JVM WebAssembly runtime.

For the full design rationale and phased roadmap see [PLAN.md](PLAN.md).

---

## Prerequisites

| Tool | Version | Purpose |
|------|---------|---------|
| [wasi-sdk](https://github.com/WebAssembly/wasi-sdk/releases) | 33+ | Cross-compiler that produces the `.wasm` |
| CMake | 3.25+ | Build system |
| Ninja | any | Recommended generator |
| Python 3 | 3.8+ | Enum binding generator (standard library only) |
| Java | 11+ | Running the Java wrapper and tests |
| Gradle | 7+ | Java build tool |

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

---

## Java quick-start

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

### Target other formats

```java
// HLSL, GLSL, WGSL, Metal, etc. — pass the SlangCompileTarget integer directly.
// Use the generated Target enum so you never hardcode integers.
import org.shaderslang.wasm.enums.Target;

try (var slang = SlangCompiler.fromWasm(
        Path.of("slang-wasm-lib.wasm"),
        Target.HLSL.value,
        "")) {
    // ...
}
```

### Check the build version

```java
try (var slang = SlangCompiler.forSpirvFromWasm(wasmPath)) {
    System.out.println(slang.version()); // e.g. "v2025.21"
}
```

---

## Running the Java tests

The Java smoke tests cover compilation, reflection JSON, error recovery, and all generated enum
values. WASM-dependent tests skip automatically when the artifact has not been built yet.

```bash
# From source/slang-wasm-lib/java/
gradle test

# Point at a specific WASM artifact (default: build.wasi/Release/bin/slang-wasm-lib.wasm)
gradle test -Pslang.wasm.path=/path/to/slang-wasm-lib.wasm

# Or via env var
SLANG_WASM_PATH=/path/to/slang-wasm-lib.wasm gradle test
```

The enum binding tests (`EnumBindingsTest`) require **no WASM artifact** and always run.

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

### Enums generated (Phase 5)

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
ranges passed as `(ptr, len)` pairs; all objects are opaque `uint32_t` handles.

### Memory helpers

```c
void* slang_wasm_alloc(uint32_t size);   // allocate in linear memory
void  slang_wasm_free(void* ptr);        // free a slang_wasm_alloc'd pointer
```

Use these to write UTF-8 input strings into the module's linear memory before calling compile.

### Session

```c
// Create a session configured for one compile target.
// targetFormat: a SlangCompileTarget integer (use the generated enums).
// profile: target profile string (e.g. "spirv_1_4"), or empty for the default.
// Returns 0 on failure.
SlangWasmSession slang_wasm_session_create(
    uint32_t targetFormat,
    const char* profile, uint32_t profileLen);

// Destroy the session and free all associated resources.
void slang_wasm_session_destroy(SlangWasmSession session);
```

### Compilation

```c
// Compile source → target code + reflection JSON.
// Never throws across the boundary: internal Slang aborts are caught and
// returned as a failed result, leaving the session alive and reusable.
// Returns 0 only if the result object itself could not be allocated.
SlangWasmResult slang_wasm_compile(
    SlangWasmSession session,
    const char* moduleName, uint32_t moduleNameLen,
    const char* source,     uint32_t sourceLen,
    const char* entryName,  uint32_t entryNameLen);
```

### Result accessors

```c
int32_t  slang_wasm_result_succeeded(SlangWasmResult r);          // 1 = success

uint32_t slang_wasm_result_code_ptr(SlangWasmResult r);           // compiled code
uint32_t slang_wasm_result_code_len(SlangWasmResult r);

uint32_t slang_wasm_result_reflection_json_ptr(SlangWasmResult r); // reflection JSON
uint32_t slang_wasm_result_reflection_json_len(SlangWasmResult r);

uint32_t slang_wasm_result_diagnostics_ptr(SlangWasmResult r);    // warnings / errors
uint32_t slang_wasm_result_diagnostics_len(SlangWasmResult r);

void slang_wasm_result_destroy(SlangWasmResult r);                 // free result
```

All pointer/length pairs are valid until `slang_wasm_result_destroy` is called.

### Enum metadata

```c
// Static storage — no free required. Format:
// {"Target":{"SPIRV":6,...},"Stage":{"COMPUTE":6,...},...}
uint32_t slang_wasm_enum_metadata_ptr(void);
uint32_t slang_wasm_enum_metadata_len(void);
```

### Version

```c
// Null-terminated Slang build tag in static storage (e.g. "v2025.21").
const char* slang_wasm_version(void);
```

---

## What's coming

The following capabilities are planned in later phases (see [PLAN.md](PLAN.md) for details):

- **Phase 6** — Full `SessionDesc` control: preprocessor defines, optimisation level, debug info,
  matrix layout, SPIR-V version, module search paths; string-form target/stage helpers.
- **Phase 7** — Module handles and multi-entry-point compilation: load a module once, enumerate
  and compile each entry point independently.
- **Phase 8** — Java API enrichment: type-safe `CompileRequest` builder; all enums wired into the
  public API so raw integers are never needed.
- **Phase 9** — Typed reflection model: parse `reflectionJson()` into a structured Java object
  graph (`ShaderReflection`, `EntryPointReflection`, `VariableLayoutReflection`, …).
- **Phase 10** — Precompiled IR: serialise a module to bytes and reload it in a later session for
  build-time compilation workflows.
