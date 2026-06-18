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

// ── Session ───────────────────────────────────────────────────────────────────

typedef uint32_t SlangWasmSession;

// Create a compile session configured for one target format. `targetFormat` is
// a SlangCompileTarget enum value (e.g. SLANG_SPIRV). `profile` may be NULL or
// empty to accept the target's default profile. Returns 0 on failure.
SlangWasmSession slang_wasm_session_create(
    uint32_t targetFormat,
    const char* profile,
    uint32_t profileLen);

// Release a session and all associated resources. The handle must not be used
// after this call.
void slang_wasm_session_destroy(SlangWasmSession session);

// ── Compilation ───────────────────────────────────────────────────────────────

typedef uint32_t SlangWasmResult;

// Compile `source` as module `moduleName`, find entry point `entryName`, link,
// and produce target code plus reflection JSON. Never propagates a C++ exception
// across the boundary: internal aborts are caught and returned as a failed result
// with diagnostics text, leaving the session alive and reusable.
// Returns 0 if a result object could not be allocated at all.
SlangWasmResult slang_wasm_compile(
    SlangWasmSession session,
    const char* moduleName,
    uint32_t moduleNameLen,
    const char* source,
    uint32_t sourceLen,
    const char* entryName,
    uint32_t entryNameLen);

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
