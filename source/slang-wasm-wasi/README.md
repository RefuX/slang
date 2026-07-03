# slang-wasm-wasi

A WebAssembly build of the [Slang](https://shader-slang.com) shader compiler, callable from any
WASI-compatible runtime with **no dependency on Emscripten or a native Slang installation**.
The module is a plain WASI reactor exporting a flat C ABI.
This is currently a WASIp1 project, due to this there is a fair amount of work the consumer must do to use it.

---

## Consuming the module

`slang-wasm-wasi.wasm` is a plain WASI Preview 1 reactor module with a flat C ABI — it has no
runtime preference baked in. Any host capable of instantiating a WASI Preview 1 module and calling
its exports can use it: a JVM via a pure-Java runtime, a browser via a WASIp1 polyfill, Wasmtime,
wasmer, Node, Go, Rust, Python, and so on. Because this is WASIp1 (not the newer Component Model /
WASIp2), there is no automatically-generated host binding for any of these — the consumer has to do
the following itself, regardless of language or runtime:

1. Parse and instantiate the module, providing WASI Preview 1 imports.
2. Call the exported `_initialize` function once, before any other export — this is the standard
   WASI **reactor** convention (a library-style module with no `main`, as opposed to a WASI
   **command** module that runs once and exits).
3. Write UTF-8 input (module names, source text, …) into the module's linear memory via
   `slang_wasm_alloc` the memory object the runtime exposes, then call whichever C ABI export is
   needed, reading results back out of linear memory the same way.

The [C ABI reference](#c-abi-reference) below documents every export; [`slang-wasm-wasi.h`](slang-wasm-wasi.h)
is the authoritative signature/ownership reference.

### Example consumer: Java via Endive

The [slang-wasm-endive](https://github.com/RefuX/slang-wasm-endive) repository is a worked example
of the steps above — it is **not** the only way to use the module, just a reference implementation
showing the pattern end to end. It drives the module via [Endive](https://github.com/bytecodealliance/endive), a pure-JVM WebAssembly runtime,
so it needs no native Slang install and no JNI.

### Running the example's tests

The [slang-wasm-endive](https://github.com/RefuX/slang-wasm-endive) example's tests cover
compilation, the builder/CompileRequest API, modules, specialization, typed and declaration
reflection, structured diagnostics, and disassembly — exercising the full C ABI through that one
example consumer.

```bash
# From a clone of https://github.com/RefuX/slang-wasm-endive
./gradlew test

# Point at a specific WASM artifact (default: build.wasi/Release/bin/slang-wasm-wasi.wasm)
./gradlew test -Pslang.wasm.path=/path/to/slang-wasm-wasi.wasm

# Or via env var
SLANG_WASM_PATH=/path/to/slang-wasm-wasi.wasm ./gradlew test
```

---

## Building the WASM module

See [Building Slang From Source § WASI-SDK Build](../../docs/building.md#wasi-sdk-build-slang-wasm-wasi)
for the canonical prerequisites and build steps.

One detail specific to this module: at configure time CMake checks whether `include/slang.h` is
newer than the generated file (or whether the generated file exists at all — it is gitignored, not
committed). If either is true, it automatically runs the [enum binding generator](#enum-binding-generator)
to (re)create the C++ metadata blob.

### Enum binding generator

`tools/generate-slang-bindings.py` reads `include/slang.h` and emits the following artefacts:

| Output                         | Default | Purpose                                                                                                                                                                                                                                                                                          |
| ------------------------------ | ------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `slang-wasm-enum-metadata.cpp` | always  | C++ static JSON blob baked into the WASM module, exported via `slang_wasm_enum_metadata_ptr/len`. Dynamic-language runtimes (Go, Python, Rust) call these two exports at startup to resolve enum integer values without hardcoding them. Generated at configure time; gitignored, not committed. |
| `<java-out>/*.java`            | opt-in  | One Java enum per Slang enum, with integer values baked in from `slang.h`. Provides compile-time type safety and IDE autocomplete. Only emitted when `--java-out` is supplied.                                                                                                                   |


---

## C ABI reference

The module is a WASI reactor. Call `_initialize` once after instantiation, then call any of the
exports below. All pointers are 32-bit offsets into linear memory; all strings are UTF-8 byte
ranges passed as `(ptr, len)` pairs; all objects are opaque `uint32_t` handles. The full, current
signatures and ownership contracts are documented in
[`slang-wasm-wasi.h`](slang-wasm-wasi.h) — that header, not this table, is the source of truth;
this is an index into it, grouped by capability:

| Group                       | Exports                                                                                                                                                          |
| --------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Memory                      | `slang_wasm_alloc`, `slang_wasm_free`                                                                                                                            |
| Session                     | `slang_wasm_session_create`/`slang_wasm_session_create2`, `slang_wasm_session_destroy`                                                                           |
| Session descriptor builders | `slang_wasm_target_list_*`, `slang_wasm_macro_list_*`, `slang_wasm_path_list_*`, `slang_wasm_options_*`                                                          |
| Enum metadata               | `slang_wasm_enum_metadata_ptr/len`, `slang_wasm_target_from_string`, `slang_wasm_stage_from_string`                                                              |
| Modules                     | `slang_wasm_session_load_module`, `slang_wasm_module_destroy`, `slang_wasm_module_entry_point_*`                                                                 |
| Compilation                 | `slang_wasm_compile`, `slang_wasm_compile_entry_point`, `slang_wasm_compile_module`                                                                              |
| Result accessors            | `slang_wasm_result_succeeded`, `slang_wasm_result_code_*`, `slang_wasm_result_reflection_json_*`, `slang_wasm_result_diagnostics_*`, `slang_wasm_result_destroy` |
| Precompiled IR              | `slang_wasm_module_serialize`, `slang_wasm_session_load_module_ir`                                                                                               |
| Specialization              | `slang_wasm_spec_args_*`, `slang_wasm_compile_specialized_entry_point`                                                                                           |
| Declaration reflection      | `slang_wasm_module_decl_reflection_json`                                                                                                                         |
| Disassembly                 | `slang_wasm_module_disassemble`                                                                                                                                  |
| Build info                  | `slang_wasm_version`                                                                                                                                             |

---

## Status

Capabilities implemented today: compile-from-string, full session control (multiple targets,
preprocessor macros, search paths, compiler options), module handles with independent
multi-entry-point compilation, typed reflection (binding layout and module-level declaration trees),
precompiled IR round-tripping, generic specialization, and disassembly with structured diagnostics.

Not yet implemented: explicit interface type conformance for dynamic dispatch
(`ISession::createTypeConformanceComponentType`) — a separable feature with its own API surface,
left for when a concrete need for it arises. A WASM Component Model / WIT binding is a longer-term
direction rather than near-term work, once Component Model has broader support in runtimes.
