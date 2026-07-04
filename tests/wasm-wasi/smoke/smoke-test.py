#!/usr/bin/env python3
"""
Functional smoke test for the slang-wasm-wasi module.

CI's wasi build previously only checked that slang-wasm-wasi.wasm exists on
disk -- a build-only gate that catches link/export drift but not runtime bugs
in the C ABI shim itself (memory ownership, session/module lifecycle, argument
marshaling). This script instantiates the module in wasmtime (the WASI
runtime already used to exercise the sibling Emscripten build's bindings via
Node, see ../../wasm/smoke/smoke-test.js) and drives the flat C ABI documented
in source/slang-wasm-wasi/slang-wasm-wasi.h end to end: allocate linear-memory
buffers, create a session, compile a real .slang source to SPIR-V, and check
that a non-empty code blob came back.

Usage:
    python3 smoke-test.py <path-to-slang-wasm-wasi.wasm> <path-to-slang-file> <entry-point-name>
"""

import json
import sys

import wasmtime


def read_cstring(memory: wasmtime.Memory, store: wasmtime.Store, ptr: int, max_len: int) -> str:
    """Return the null-terminated UTF-8 string starting at `ptr` in `memory`, reading at most `max_len` bytes."""
    raw = memory.read(store, ptr, ptr + max_len)
    return raw.split(b"\0", 1)[0].decode("utf-8")


def main() -> int:
    if len(sys.argv) != 4:
        print(f"Usage: {sys.argv[0]} <slang-wasm-wasi.wasm> <slang-file> <entry-point>", file=sys.stderr)
        return 1

    wasm_path, slang_path, entry_name = sys.argv[1:4]

    with open(slang_path, "r", encoding="utf-8") as f:
        source = f.read()

    print(f"Loading WASI module: {wasm_path}")
    # The module is built with -fwasm-exceptions (see slang-wasm-wasi's
    # CMakeLists.txt); wasmtime must have the exception-handling proposal
    # enabled to parse it at all.
    config = wasmtime.Config()
    config.wasm_exceptions = True
    engine = wasmtime.Engine(config)
    module = wasmtime.Module.from_file(engine, wasm_path)

    linker = wasmtime.Linker(engine)
    linker.define_wasi()

    store = wasmtime.Store(engine)
    wasi_config = wasmtime.WasiConfig()
    wasi_config.inherit_stdout()
    wasi_config.inherit_stderr()
    store.set_wasi(wasi_config)

    instance = linker.instantiate(store, module)
    exports = instance.exports(store)
    memory = exports["memory"]

    # Standard WASI reactor convention: call _initialize once before any other export.
    exports["_initialize"](store)
    print("Module initialized")

    version = read_cstring(memory, store, exports["slang_wasm_version"](store), 64)
    print(f"slang_wasm_version: {version}")

    def alloc(data: bytes) -> tuple[int, int]:
        ptr = exports["slang_wasm_alloc"](store, len(data))
        if ptr == 0:
            raise RuntimeError(f"slang_wasm_alloc failed for {len(data)} bytes")
        memory.write(store, data, ptr)
        return ptr, len(data)

    # Resolve the SPIRV target value from the module's own enum metadata
    # rather than hardcoding it, so this test tracks slang.h automatically.
    meta_ptr = exports["slang_wasm_enum_metadata_ptr"](store)
    meta_len = exports["slang_wasm_enum_metadata_len"](store)
    metadata = json.loads(memory.read(store, meta_ptr, meta_ptr + meta_len).decode("utf-8"))
    spirv_target = metadata["Target"]["SPIRV"]
    print(f"Resolved SPIRV target value: {spirv_target}")

    session = exports["slang_wasm_session_create"](store, spirv_target, 0, 0)
    if session == 0:
        print("Failed to create session", file=sys.stderr)
        return 1
    print("Session created")

    name_ptr, name_len = alloc(b"smoke_test_module")
    src_ptr, src_len = alloc(source.encode("utf-8"))
    entry_ptr, entry_len = alloc(entry_name.encode("utf-8"))

    result = exports["slang_wasm_compile"](
        store, session, name_ptr, name_len, src_ptr, src_len, entry_ptr, entry_len, 0
    )
    if result == 0:
        print("slang_wasm_compile returned no result object", file=sys.stderr)
        return 1

    succeeded = exports["slang_wasm_result_succeeded"](store, result)
    if not succeeded:
        diag_ptr = exports["slang_wasm_result_diagnostics_ptr"](store, result)
        diag_len = exports["slang_wasm_result_diagnostics_len"](store, result)
        diagnostics = memory.read(store, diag_ptr, diag_ptr + diag_len).decode("utf-8", errors="replace")
        print(f"Compilation failed:\n{diagnostics}", file=sys.stderr)
        return 1

    code_len = exports["slang_wasm_result_code_len"](store, result)
    print(f"Compiled successfully, code blob is {code_len} bytes")
    if code_len == 0:
        print("Compilation succeeded but produced an empty code blob", file=sys.stderr)
        return 1

    exports["slang_wasm_result_destroy"](store, result)
    exports["slang_wasm_session_destroy"](store, session)
    exports["slang_wasm_free"](store, name_ptr)
    exports["slang_wasm_free"](store, src_ptr)
    exports["slang_wasm_free"](store, entry_ptr)

    print("Smoke test completed successfully")
    return 0


if __name__ == "__main__":
    sys.exit(main())
