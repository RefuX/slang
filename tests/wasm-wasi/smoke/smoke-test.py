#!/usr/bin/env python3
"""
Functional smoke test for the slang-wasm-wasi module.

CI's wasi build previously only checked that slang-wasm-wasi.wasm exists on
disk -- a build-only gate that catches link/export drift but not runtime bugs
in the C ABI shim itself (memory ownership, session/module lifecycle, argument
marshaling). This script instantiates the module in wasmtime (the WASI
runtime already used to exercise the sibling Emscripten build's bindings via
Node, see ../../wasm/smoke/smoke-test.js) and drives the flat C ABI documented
in source/slang-wasm-wasi/slang-wasm-wasi.h end to end, across every exported
function: single- and multi-target sessions, module load/compile/serialize/
reload, specialization, reflection, disassembly, the failure/diagnostics
path, and handle-robustness (unknown and double-destroyed handles).

Usage:
    python3 smoke-test.py <path-to-slang-wasm-wasi.wasm> <path-to-slang-file> <entry-point-name>
"""

import json
import struct
import sys
from pathlib import Path

import wasmtime


class Abi:
    """Thin wrapper over the slang-wasm-wasi exports and linear memory.

    Every exported function takes `store` as its first wasmtime argument;
    routing all calls through `call()` keeps that detail in one place instead
    of repeated at each of the ~30 call sites below.
    """

    def __init__(self, store: wasmtime.Store, exports, memory: wasmtime.Memory):
        self.store = store
        self.exports = exports
        self.memory = memory

    def call(self, name: str, *args):
        return self.exports[name](self.store, *args)

    def alloc(self, data: bytes) -> tuple[int, int]:
        ptr = self.call("slang_wasm_alloc", len(data))
        if ptr == 0:
            raise RuntimeError(f"slang_wasm_alloc failed for {len(data)} bytes")
        self.memory.write(self.store, data, ptr)
        return ptr, len(data)

    def free(self, ptr: int) -> None:
        if ptr:
            self.call("slang_wasm_free", ptr)

    def read(self, ptr: int, length: int) -> bytes:
        return self.memory.read(self.store, ptr, ptr + length)

    def read_cstring(self, ptr: int, max_len: int) -> str:
        """Return the null-terminated UTF-8 string starting at `ptr`, reading at most `max_len` bytes."""
        return self.read(ptr, max_len).split(b"\0", 1)[0].decode("utf-8")

    def alloc_out_u32(self) -> int:
        """Allocate a 4-byte linear-memory slot for a `uint32_t*` out-parameter."""
        ptr, _ = self.alloc(struct.pack("<I", 0))
        return ptr

    def read_u32(self, ptr: int) -> int:
        return struct.unpack("<I", self.read(ptr, 4))[0]


def check(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def load_module(abi: Abi, session: int, name: str, source: str) -> tuple[int, str]:
    """Load `source` as `name` into `session`. Returns (module handle, diagnostics text)."""
    name_ptr, name_len = abi.alloc(name.encode("utf-8"))
    src_ptr, src_len = abi.alloc(source.encode("utf-8"))
    diag_ptr_out = abi.alloc_out_u32()
    diag_len_out = abi.alloc_out_u32()

    module = abi.call(
        "slang_wasm_session_load_module",
        session,
        name_ptr,
        name_len,
        src_ptr,
        src_len,
        diag_ptr_out,
        diag_len_out,
    )

    diag_ptr = abi.read_u32(diag_ptr_out)
    diag_len = abi.read_u32(diag_len_out)
    diagnostics = abi.read(diag_ptr, diag_len).decode("utf-8", errors="replace") if diag_len else ""

    abi.free(name_ptr)
    abi.free(src_ptr)
    abi.free(diag_ptr_out)
    abi.free(diag_len_out)
    abi.free(diag_ptr)  # freshly heap-allocated per slang_wasm_session_load_module's contract

    return module, diagnostics


def read_result(abi: Abi, result: int) -> tuple[bool, bytes, str]:
    """Read (succeeded, code bytes, diagnostics text) off a SlangWasmResult handle."""
    succeeded = bool(abi.call("slang_wasm_result_succeeded", result))
    code_ptr = abi.call("slang_wasm_result_code_ptr", result)
    code_len = abi.call("slang_wasm_result_code_len", result)
    code = bytes(abi.read(code_ptr, code_len)) if code_len else b""
    diag_ptr = abi.call("slang_wasm_result_diagnostics_ptr", result)
    diag_len = abi.call("slang_wasm_result_diagnostics_len", result)
    diagnostics = abi.read(diag_ptr, diag_len).decode("utf-8", errors="replace") if diag_len else ""
    return succeeded, code, diagnostics


def test_happy_path_and_module_lifecycle(abi: Abi, metadata: dict, source: str, entry_name: str) -> None:
    """slang_wasm_compile, then the load/compile_entry_point/compile_module/serialize/reload
    module lifecycle, cross-checked against slang_wasm_compile's output for the same source."""
    spirv_target = metadata["Target"]["SPIRV"]
    session = abi.call("slang_wasm_session_create", spirv_target, 0, 0)
    check(session != 0, "slang_wasm_session_create failed")

    name_ptr, name_len = abi.alloc(b"smoke_test_module")
    src_ptr, src_len = abi.alloc(source.encode("utf-8"))
    entry_ptr, entry_len = abi.alloc(entry_name.encode("utf-8"))

    direct_result = abi.call(
        "slang_wasm_compile", session, name_ptr, name_len, src_ptr, src_len, entry_ptr, entry_len, 0
    )
    check(direct_result != 0, "slang_wasm_compile returned no result object")
    direct_succeeded, direct_code, direct_diag = read_result(abi, direct_result)
    check(direct_succeeded, f"slang_wasm_compile failed:\n{direct_diag}")
    check(len(direct_code) > 0, "slang_wasm_compile produced an empty code blob")
    print(f"slang_wasm_compile: {len(direct_code)} bytes")

    module, load_diag = load_module(abi, session, "smoke_test_module2", source)
    check(module != 0, f"slang_wasm_session_load_module failed:\n{load_diag}")
    check(load_diag == "", f"unexpected diagnostics loading a known-good module:\n{load_diag}")

    entry_count = abi.call("slang_wasm_module_entry_point_count", module)
    check(entry_count == 1, f"expected 1 defined entry point, got {entry_count}")
    name_str_ptr = abi.call("slang_wasm_module_entry_point_name_ptr", module, 0)
    name_str_len = abi.call("slang_wasm_module_entry_point_name_len", module, 0)
    found_name = abi.read(name_str_ptr, name_str_len).decode("utf-8")
    check(found_name == entry_name, f"expected entry point name {entry_name!r}, got {found_name!r}")

    # slang_wasm_compile_entry_point is documented as "Equivalent to slang_wasm_compile"
    # for the same source/entry/target -- so on a deterministic backend the two code
    # blobs should be byte-identical.
    via_module_result = abi.call("slang_wasm_compile_entry_point", module, entry_ptr, entry_len, 0)
    via_module_succeeded, via_module_code, via_module_diag = read_result(abi, via_module_result)
    check(via_module_succeeded, f"slang_wasm_compile_entry_point failed:\n{via_module_diag}")
    check(
        via_module_code == direct_code,
        "slang_wasm_compile_entry_point produced different code than slang_wasm_compile "
        "for the same source/entry/target",
    )
    print("slang_wasm_compile_entry_point matches slang_wasm_compile: OK")

    combined_result = abi.call("slang_wasm_compile_module", module, 0)
    combined_succeeded, combined_code, combined_diag = read_result(abi, combined_result)
    check(combined_succeeded, f"slang_wasm_compile_module failed:\n{combined_diag}")
    check(len(combined_code) > 0, "slang_wasm_compile_module produced an empty code blob")
    print(f"slang_wasm_compile_module: {len(combined_code)} bytes")

    decl_result = abi.call("slang_wasm_module_decl_reflection_json", module)
    decl_succeeded, _, _ = read_result(abi, decl_result)
    check(decl_succeeded, "slang_wasm_module_decl_reflection_json failed")
    reflection_ptr = abi.call("slang_wasm_result_reflection_json_ptr", decl_result)
    reflection_len = abi.call("slang_wasm_result_reflection_json_len", decl_result)
    check(reflection_len > 0, "slang_wasm_module_decl_reflection_json produced empty JSON")
    reflection_json = json.loads(abi.read(reflection_ptr, reflection_len).decode("utf-8"))
    check(entry_name in json.dumps(reflection_json), f"reflection JSON does not mention {entry_name!r}")
    print(f"slang_wasm_module_decl_reflection_json: {reflection_len} bytes, parses as JSON")

    disasm_result = abi.call("slang_wasm_module_disassemble", module)
    disasm_succeeded, _, disasm_text = read_result(abi, disasm_result)
    check(disasm_succeeded, "slang_wasm_module_disassemble failed")
    check(len(disasm_text) > 20, "slang_wasm_module_disassemble produced suspiciously little text")
    print(f"slang_wasm_module_disassemble: {len(disasm_text)} chars")

    # IR serialize/reload round trip: the reloaded module must compile to the
    # exact same code as the original source-loaded module.
    serialize_result = abi.call("slang_wasm_module_serialize", module)
    serialize_succeeded, ir_blob, _ = read_result(abi, serialize_result)
    check(serialize_succeeded, "slang_wasm_module_serialize failed")
    check(len(ir_blob) > 0, "slang_wasm_module_serialize produced an empty IR blob")

    ir_name_ptr, ir_name_len = abi.alloc(b"smoke_test_module_reloaded")
    ir_ptr, ir_len = abi.alloc(ir_blob)
    diag_ptr_out = abi.alloc_out_u32()
    diag_len_out = abi.alloc_out_u32()
    reloaded_module = abi.call(
        "slang_wasm_session_load_module_ir",
        session,
        ir_name_ptr,
        ir_name_len,
        ir_ptr,
        ir_len,
        diag_ptr_out,
        diag_len_out,
    )
    check(reloaded_module != 0, "slang_wasm_session_load_module_ir failed")
    reload_diag_len = abi.read_u32(diag_len_out)
    check(reload_diag_len == 0, "unexpected diagnostics reloading a known-good IR blob")

    reloaded_compile_result = abi.call(
        "slang_wasm_compile_entry_point", reloaded_module, entry_ptr, entry_len, 0
    )
    reloaded_succeeded, reloaded_code, reloaded_diag = read_result(abi, reloaded_compile_result)
    check(reloaded_succeeded, f"compiling the IR-reloaded module failed:\n{reloaded_diag}")
    check(
        reloaded_code == direct_code,
        "IR round-trip produced different code than the original source compile",
    )
    print(f"IR serialize/reload round trip: {len(ir_blob)}-byte IR, recompiled code matches: OK")

    for handle in (
        direct_result,
        via_module_result,
        combined_result,
        decl_result,
        disasm_result,
        serialize_result,
        reloaded_compile_result,
    ):
        abi.call("slang_wasm_result_destroy", handle)
    abi.call("slang_wasm_module_destroy", module)
    abi.call("slang_wasm_module_destroy", reloaded_module)
    abi.call("slang_wasm_session_destroy", session)
    for ptr in (name_ptr, src_ptr, entry_ptr, ir_name_ptr, ir_ptr, diag_ptr_out, diag_len_out):
        abi.free(ptr)


def test_failure_path(abi: Abi, metadata: dict) -> None:
    """A deliberately broken shader must fail with a non-empty diagnostics string,
    proving the "never throws, always returns a result with diagnostics" contract."""
    spirv_target = metadata["Target"]["SPIRV"]
    session = abi.call("slang_wasm_session_create", spirv_target, 0, 0)
    check(session != 0, "slang_wasm_session_create failed")

    broken_source = "[shader(\"compute\")]\n[numthreads(1,1,1)]\nvoid computeMain() { int x = ; }\n"
    name_ptr, name_len = abi.alloc(b"broken_module")
    src_ptr, src_len = abi.alloc(broken_source.encode("utf-8"))
    entry_ptr, entry_len = abi.alloc(b"computeMain")

    result = abi.call(
        "slang_wasm_compile", session, name_ptr, name_len, src_ptr, src_len, entry_ptr, entry_len, 0
    )
    check(result != 0, "slang_wasm_compile returned no result object for a broken shader")
    succeeded, code, diagnostics = read_result(abi, result)
    check(not succeeded, "slang_wasm_compile reported success for a syntactically invalid shader")
    check(len(code) == 0, "a failed compile unexpectedly produced a non-empty code blob")
    check(len(diagnostics) > 0, "a failed compile produced no diagnostics text")
    print(f"failure path: compile failed as expected with {len(diagnostics)} chars of diagnostics")

    abi.call("slang_wasm_result_destroy", result)
    abi.call("slang_wasm_session_destroy", session)
    for ptr in (name_ptr, src_ptr, entry_ptr):
        abi.free(ptr)


def test_handle_robustness(abi: Abi, metadata: dict) -> None:
    """Unknown and double-destroyed handles must return safe sentinels, not trap the instance."""
    check(abi.call("slang_wasm_result_succeeded", 0) == 0, "result_succeeded(0) should be 0")
    check(abi.call("slang_wasm_result_code_len", 0) == 0, "result_code_len(0) should be 0")
    check(abi.call("slang_wasm_result_diagnostics_len", 0) == 0, "result_diagnostics_len(0) should be 0")
    check(abi.call("slang_wasm_module_entry_point_count", 0) == 0, "module_entry_point_count(0) should be 0")
    check(abi.call("slang_wasm_target_from_string", 0, 0) == -1, "target_from_string(NULL) should be -1")

    # slang_wasm_result_destroy/session_destroy on an unknown handle must be a no-op,
    # and destroying the same real handle twice must not double-free.
    bogus_handle = 0xFFFFFFFF
    abi.call("slang_wasm_result_destroy", bogus_handle)
    abi.call("slang_wasm_session_destroy", bogus_handle)

    spirv_target = metadata["Target"]["SPIRV"]
    throwaway_session = abi.call("slang_wasm_session_create", spirv_target, 0, 0)
    check(throwaway_session != 0, "slang_wasm_session_create failed")
    abi.call("slang_wasm_session_destroy", throwaway_session)
    abi.call("slang_wasm_session_destroy", throwaway_session)  # must not double-free
    print("handle robustness: unknown and double-destroyed handles handled safely")


def test_enum_resolvers(abi: Abi, metadata: dict) -> None:
    spirv_ptr, spirv_len = abi.alloc(b"spirv")
    check(
        abi.call("slang_wasm_target_from_string", spirv_ptr, spirv_len) == metadata["Target"]["SPIRV"],
        "target_from_string('spirv') mismatch",
    )
    abi.free(spirv_ptr)

    bogus_ptr, bogus_len = abi.alloc(b"not-a-real-target")
    check(
        abi.call("slang_wasm_target_from_string", bogus_ptr, bogus_len) == -1,
        "target_from_string(bogus) should be -1",
    )
    abi.free(bogus_ptr)

    compute_ptr, compute_len = abi.alloc(b"compute")
    check(
        abi.call("slang_wasm_stage_from_string", compute_ptr, compute_len) == metadata["Stage"]["COMPUTE"],
        "stage_from_string('compute') mismatch",
    )
    abi.free(compute_ptr)

    bogus_stage_ptr, bogus_stage_len = abi.alloc(b"not-a-real-stage")
    check(
        abi.call("slang_wasm_stage_from_string", bogus_stage_ptr, bogus_stage_len) == -1,
        "stage_from_string(bogus) should be -1",
    )
    abi.free(bogus_stage_ptr)
    print("enum resolvers: OK")


def test_multi_target_session(abi: Abi, metadata: dict, source: str, entry_name: str) -> None:
    """session_create2 plus every builder type (targets, macros, search paths, options),
    compiling the same module for two different targets by index."""
    targets = abi.call("slang_wasm_target_list_create")
    abi.call("slang_wasm_target_list_add", targets, metadata["Target"]["SPIRV"], 0, 0, 0)
    abi.call("slang_wasm_target_list_add", targets, metadata["Target"]["HLSL"], 0, 0, 0)

    macros = abi.call("slang_wasm_macro_list_create")
    macro_name_ptr, macro_name_len = abi.alloc(b"SMOKE_TEST_MACRO")
    macro_val_ptr, macro_val_len = abi.alloc(b"1")
    abi.call(
        "slang_wasm_macro_list_add", macros, macro_name_ptr, macro_name_len, macro_val_ptr, macro_val_len
    )

    paths = abi.call("slang_wasm_path_list_create")
    path_ptr, path_len = abi.alloc(b".")
    abi.call("slang_wasm_path_list_add", paths, path_ptr, path_len)

    options = abi.call("slang_wasm_options_create")
    abi.call(
        "slang_wasm_options_add_int",
        options,
        metadata["CompilerOptionName"]["Optimization"],
        metadata["OptimizationLevel"]["HIGH"],
    )

    session = abi.call("slang_wasm_session_create2", targets, macros, paths, options)
    check(session != 0, "slang_wasm_session_create2 failed")

    name_ptr, name_len = abi.alloc(b"multi_target_module")
    src_ptr, src_len = abi.alloc(source.encode("utf-8"))
    entry_ptr, entry_len = abi.alloc(entry_name.encode("utf-8"))

    spirv_result = abi.call(
        "slang_wasm_compile", session, name_ptr, name_len, src_ptr, src_len, entry_ptr, entry_len, 0
    )
    spirv_succeeded, spirv_code, spirv_diag = read_result(abi, spirv_result)
    check(spirv_succeeded, f"multi-target SPIRV compile (index 0) failed:\n{spirv_diag}")
    check(len(spirv_code) > 0, "multi-target SPIRV compile produced empty code")

    hlsl_result = abi.call(
        "slang_wasm_compile", session, name_ptr, name_len, src_ptr, src_len, entry_ptr, entry_len, 1
    )
    hlsl_succeeded, hlsl_code, hlsl_diag = read_result(abi, hlsl_result)
    check(hlsl_succeeded, f"multi-target HLSL compile (index 1) failed:\n{hlsl_diag}")
    hlsl_text = hlsl_code.decode("utf-8", errors="replace")
    check(entry_name in hlsl_text, f"HLSL output does not mention entry point {entry_name!r}")
    print(f"multi-target session: SPIRV {len(spirv_code)} bytes, HLSL {len(hlsl_code)} bytes")

    abi.call("slang_wasm_result_destroy", spirv_result)
    abi.call("slang_wasm_result_destroy", hlsl_result)
    abi.call("slang_wasm_session_destroy", session)
    for ptr in (
        name_ptr,
        src_ptr,
        entry_ptr,
        macro_name_ptr,
        macro_val_ptr,
        path_ptr,
    ):
        abi.free(ptr)


def test_specialization(abi: Abi, metadata: dict, generic_source: str) -> None:
    """Specializing the same generic entry point with two different values must
    produce two different code blobs, proving the specialization argument took effect."""
    spirv_target = metadata["Target"]["SPIRV"]
    session = abi.call("slang_wasm_session_create", spirv_target, 0, 0)
    check(session != 0, "slang_wasm_session_create failed")

    module, load_diag = load_module(abi, session, "generic_add", generic_source)
    check(module != 0, f"loading the generic specialization fixture failed:\n{load_diag}")

    entry_ptr, entry_len = abi.alloc(b"computeMain")

    def compile_specialized(value: str) -> bytes:
        args = abi.call("slang_wasm_spec_args_create")
        expr_ptr, expr_len = abi.alloc(value.encode("utf-8"))
        abi.call("slang_wasm_spec_args_add_expr", args, expr_ptr, expr_len)
        result = abi.call("slang_wasm_compile_specialized_entry_point", module, entry_ptr, entry_len, args, 0)
        succeeded, code, diagnostics = read_result(abi, result)
        check(succeeded, f"specialized compile (x={value}) failed:\n{diagnostics}")
        check(len(code) > 0, f"specialized compile (x={value}) produced empty code")
        abi.call("slang_wasm_result_destroy", result)
        abi.free(expr_ptr)
        return code

    code_3 = compile_specialized("3")
    code_4 = compile_specialized("4")
    check(code_3 != code_4, "specializing with different values produced identical code")
    print(f"specialization: x=3 -> {len(code_3)} bytes, x=4 -> {len(code_4)} bytes, differ: OK")

    abi.call("slang_wasm_module_destroy", module)
    abi.call("slang_wasm_session_destroy", session)
    abi.free(entry_ptr)


def main() -> int:
    if len(sys.argv) != 4:
        print(f"Usage: {sys.argv[0]} <slang-wasm-wasi.wasm> <slang-file> <entry-point>", file=sys.stderr)
        return 1

    wasm_path, slang_path, entry_name = sys.argv[1:4]

    with open(slang_path, "r", encoding="utf-8") as f:
        source = f.read()
    generic_source_path = Path(__file__).resolve().parent / "generic_add.slang"
    with open(generic_source_path, "r", encoding="utf-8") as f:
        generic_source = f.read()

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

    abi = Abi(store, exports, memory)

    version = abi.read_cstring(abi.call("slang_wasm_version"), 64)
    print(f"slang_wasm_version: {version}")

    # Resolve enum values from the module's own metadata rather than hardcoding them,
    # so this test tracks slang.h automatically.
    meta_ptr = abi.call("slang_wasm_enum_metadata_ptr")
    meta_len = abi.call("slang_wasm_enum_metadata_len")
    metadata = json.loads(abi.read(meta_ptr, meta_len).decode("utf-8"))

    tests = [
        (
            "happy path + module lifecycle",
            lambda: test_happy_path_and_module_lifecycle(abi, metadata, source, entry_name),
        ),
        ("failure path", lambda: test_failure_path(abi, metadata)),
        ("handle robustness", lambda: test_handle_robustness(abi, metadata)),
        ("enum resolvers", lambda: test_enum_resolvers(abi, metadata)),
        (
            "multi-target session",
            lambda: test_multi_target_session(abi, metadata, source, entry_name),
        ),
        ("specialization", lambda: test_specialization(abi, metadata, generic_source)),
    ]

    for name, test in tests:
        print(f"--- {name} ---")
        test()

    print("Smoke test completed successfully")
    return 0


if __name__ == "__main__":
    sys.exit(main())
