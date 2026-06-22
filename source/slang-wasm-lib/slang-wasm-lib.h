// slang-wasm-lib.h — flat C ABI for the slang-wasm-lib WASI reactor.
//
// All pointers are 32-bit offsets into the module's linear memory. All strings
// are UTF-8 byte ranges passed as (ptr, len) pairs. All objects are opaque
// uint32_t handles backed by an internal table; callers never hold raw COM
// pointers and lifetimes are explicit.
//
// Ownership rules:
//   - Memory returned via slang_wasm_alloc is owned by the caller; free with
//     slang_wasm_free.
//   - Result handles are owned by the caller; free with slang_wasm_result_destroy.
//   - Session handles are owned by the caller; free with slang_wasm_session_destroy.
//   - Pointers returned by the result accessor functions (code_ptr, etc.) are
//     valid until slang_wasm_result_destroy is called on that result handle.

#pragma once

#include <stdint.h>

#ifdef __cplusplus
extern "C"
{
#endif

// ── Memory helpers ────────────────────────────────────────────────────────────

// Allocate `size` bytes in the module's linear memory. Returns NULL on failure.
// Exposed so the host can stage UTF-8 input buffers before calling exports.
void* slang_wasm_alloc(uint32_t size);

// Free a pointer previously returned by slang_wasm_alloc.
void slang_wasm_free(void* ptr);

// ── Enum metadata ─────────────────────────────────────────────────────────────

// Pointer and byte length of a static JSON blob of the form
// `{ "Target": {"SPIRV":6,...}, "Stage": {...}, ... }`, generated from
// include/slang.h by tools/generate-slang-bindings.py. Lets a runtime binding
// (Go, Python, …) resolve enum integer values without hardcoding them. Static
// storage in the module; no free required.
uint32_t slang_wasm_enum_metadata_ptr(void);
uint32_t slang_wasm_enum_metadata_len(void);

// Resolve a SlangCompileTarget by its lowercase string form (e.g. "spirv",
// "hlsl", "glsl", "wgsl"). Returns the integer enum value, or -1 if unrecognised.
int32_t slang_wasm_target_from_string(const char* name, uint32_t nameLen);

// Resolve a SlangStage by its lowercase string form (e.g. "vertex", "fragment",
// "compute"). Returns the integer enum value, or -1 if unrecognised.
int32_t slang_wasm_stage_from_string(const char* name, uint32_t nameLen);

// ── Session descriptor builders ───────────────────────────────────────────────
//
// Each builder accumulates entries for one piece of slang::SessionDesc and is
// consumed by slang_wasm_session_create2. A handle may be 0 (omitted) to leave
// the corresponding SessionDesc field at its default. Builders are owned by the
// caller and must be destroyed (or are consumed exactly once by
// slang_wasm_session_create2, which destroys them internally on return).

typedef uint32_t SlangWasmTargetList;

// Create an empty list of compile targets.
SlangWasmTargetList slang_wasm_target_list_create(void);

// Append one target: `format` is a SlangCompileTarget value, `profile` may be
// NULL/empty for the target default, `flags` is a SlangTargetFlags bitmask.
void slang_wasm_target_list_add(
    SlangWasmTargetList list,
    uint32_t format,
    const char* profile,
    uint32_t profileLen,
    uint32_t flags);

void slang_wasm_target_list_destroy(SlangWasmTargetList list);

typedef uint32_t SlangWasmMacroList;

// Create an empty list of preprocessor macro definitions.
SlangWasmMacroList slang_wasm_macro_list_create(void);

// Append one `#define name value` (value may be empty for a valueless define).
void slang_wasm_macro_list_add(
    SlangWasmMacroList list,
    const char* name,
    uint32_t nameLen,
    const char* value,
    uint32_t valueLen);

void slang_wasm_macro_list_destroy(SlangWasmMacroList list);

typedef uint32_t SlangWasmPathList;

// Create an empty list of module search paths.
SlangWasmPathList slang_wasm_path_list_create(void);

void slang_wasm_path_list_add(SlangWasmPathList list, const char* path, uint32_t pathLen);

void slang_wasm_path_list_destroy(SlangWasmPathList list);

typedef uint32_t SlangWasmOptions;

// Create an empty list of session-wide compiler option entries
// (slang::CompilerOptionEntry), keyed by SlangCompilerOptionName.
SlangWasmOptions slang_wasm_options_create(void);

void slang_wasm_options_add_string(
    SlangWasmOptions opts,
    uint32_t name,
    const char* val,
    uint32_t valLen);

void slang_wasm_options_add_int(SlangWasmOptions opts, uint32_t name, int32_t val);

void slang_wasm_options_destroy(SlangWasmOptions opts);

// ── Session ───────────────────────────────────────────────────────────────────

typedef uint32_t SlangWasmSession;

// Create a compile session configured for one target format. `targetFormat` is
// a SlangCompileTarget enum value (e.g. SLANG_SPIRV). `profile` may be NULL or
// empty to accept the target's default profile. Returns 0 on failure.
//
// Thin convenience wrapper over slang_wasm_session_create2 for the common
// single-target, no-macros, no-search-paths case.
SlangWasmSession slang_wasm_session_create(
    uint32_t targetFormat,
    const char* profile,
    uint32_t profileLen);

// Create a compile session from the full SessionDesc surface: one or more
// compile targets, preprocessor macros, module search paths, and session-wide
// compiler options. Any handle may be 0 to leave that part of the descriptor at
// its default (e.g. 0 targets is invalid and fails; 0 macros means none).
// Consumes (destroys) all four builder handles before returning, success or not.
// Returns 0 on failure.
SlangWasmSession slang_wasm_session_create2(
    SlangWasmTargetList targets,
    SlangWasmMacroList macros,
    SlangWasmPathList searchPaths,
    SlangWasmOptions sessionOptions);

// Release a session and all associated resources. The handle must not be used
// after this call.
void slang_wasm_session_destroy(SlangWasmSession session);

// ── Modules ───────────────────────────────────────────────────────────────────
//
// A module is parsed once and can then be queried for its defined entry points
// and compiled from independently of any other module loaded into the same
// session, unlike slang_wasm_compile (below) which loads, compiles, and
// discards a module in one call.

typedef uint32_t SlangWasmModule;

// Load `source` as module `name` into `session` (mirrors
// ISession::loadModuleFromSourceString). Returns 0 on failure. `diagPtrOut`/
// `diagLenOut` (each a pointer into the module's own linear memory, e.g. from
// slang_wasm_alloc) are always written — to a NULL/0 buffer if there were no
// diagnostics, or to a freshly heap-allocated UTF-8 buffer (owned by the
// caller; free with slang_wasm_free) otherwise. This is true on both success
// (warnings) and failure (errors), unlike slang_wasm_compile's diagnostics
// which are only retrievable via a result handle that does not exist when
// there is no module to return.
SlangWasmModule slang_wasm_session_load_module(
    SlangWasmSession session,
    const char* name,
    uint32_t nameLen,
    const char* source,
    uint32_t sourceLen,
    uint32_t* diagPtrOut,
    uint32_t* diagLenOut);

// Release a module handle. The handle must not be used after this call.
void slang_wasm_module_destroy(SlangWasmModule module);

// Number of entry points defined in the module (functions marked
// `[shader("...")]`), per IModule::getDefinedEntryPointCount.
uint32_t slang_wasm_module_entry_point_count(SlangWasmModule module);

// Pointer and byte length of the name of the entry point at `index`
// (0 <= index < slang_wasm_module_entry_point_count(module)). Valid until
// slang_wasm_module_destroy.
uint32_t slang_wasm_module_entry_point_name_ptr(SlangWasmModule module, uint32_t index);
uint32_t slang_wasm_module_entry_point_name_len(SlangWasmModule module, uint32_t index);

// ── Compilation ───────────────────────────────────────────────────────────────

typedef uint32_t SlangWasmResult;

// Compile `source` as module `moduleName`, find entry point `entryName`, link,
// and produce code for the target at `targetIndex` (its position in the
// SlangWasmTargetList the session was created with; 0 for a single-target
// session) plus reflection JSON. Never propagates a C++ exception across the
// boundary: internal aborts are caught and returned as a failed result with
// diagnostics text, leaving the session alive and reusable.
// Returns 0 if a result object could not be allocated at all.
SlangWasmResult slang_wasm_compile(
    SlangWasmSession session,
    const char* moduleName,
    uint32_t moduleNameLen,
    const char* source,
    uint32_t sourceLen,
    const char* entryName,
    uint32_t entryNameLen,
    uint32_t targetIndex);

// Compile entry point `entryName` from an already-loaded `module` (see
// slang_wasm_session_load_module), producing code for the target at
// `targetIndex`. Equivalent to slang_wasm_compile but reuses a module already
// parsed once, so multiple entry points from the same source can be compiled
// independently without re-parsing. Same never-throws contract as
// slang_wasm_compile.
SlangWasmResult slang_wasm_compile_entry_point(
    SlangWasmSession session,
    SlangWasmModule module,
    const char* entryName,
    uint32_t entryNameLen,
    uint32_t targetIndex);

// Compile all of `module`'s defined entry points together into one combined
// code blob for the target at `targetIndex` (mirrors
// IComponentType::getTargetCode, which — unlike getEntryPointCode — returns a
// single blob containing every entry point linked into the component, e.g. one
// SPIR-V module with both a vertex and a fragment entry point). Reflection JSON
// in the result covers the same combined layout. Same never-throws contract as
// slang_wasm_compile.
SlangWasmResult slang_wasm_compile_module(
    SlangWasmSession session,
    SlangWasmModule module,
    uint32_t targetIndex);

// ── Result accessors ──────────────────────────────────────────────────────────

// Returns 1 if compilation succeeded, 0 otherwise.
int32_t slang_wasm_result_succeeded(SlangWasmResult result);

// Pointer and byte length of the compiled target code blob (e.g. SPIR-V words).
// Valid until slang_wasm_result_destroy.
uint32_t slang_wasm_result_code_ptr(SlangWasmResult result);
uint32_t slang_wasm_result_code_len(SlangWasmResult result);

// Pointer and byte length of the reflection JSON string (null-terminated).
// Valid until slang_wasm_result_destroy.
uint32_t slang_wasm_result_reflection_json_ptr(SlangWasmResult result);
uint32_t slang_wasm_result_reflection_json_len(SlangWasmResult result);

// Pointer and byte length of concatenated diagnostic messages (null-terminated).
// Present on both success (warnings) and failure (errors). Valid until destroy.
uint32_t slang_wasm_result_diagnostics_ptr(SlangWasmResult result);
uint32_t slang_wasm_result_diagnostics_len(SlangWasmResult result);

// Release a result handle and all associated output buffers.
void slang_wasm_result_destroy(SlangWasmResult result);

// ── Diagnostics ───────────────────────────────────────────────────────────────

// Return the Slang build tag string (e.g. "v2025.21") as a null-terminated
// C string in static storage. Used by the host to verify it is talking to the
// expected build.
const char* slang_wasm_version(void);

#ifdef __cplusplus
} // extern "C"
#endif
