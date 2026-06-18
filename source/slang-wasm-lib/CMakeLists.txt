# slang-wasm-lib — WASI reactor WebAssembly module.
#
# Only built when CMAKE_SYSTEM_NAME is WASI (i.e. the 'wasi' preset using
# wasi-sdk). The target is a WASI reactor: no main(), exports _initialize and
# a flat C ABI shim callable from any WASI-compatible WebAssembly runtime.
#
# Build sequence (from repo root):
#   cmake --workflow --preset generators --fresh
#   cmake --install build --config Release --prefix generators --component generators
#   export WASI_SDK_PATH=/path/to/wasi-sdk
#   cmake -DSLANG_GENERATORS_PATH=generators/bin --preset wasi
#   cmake --build --preset slang-wasm-lib
#
# See source/slang-wasm-lib/PLAN.md for the full design.

if(NOT CMAKE_SYSTEM_NAME STREQUAL "WASI")
    return()
endif()

# libunwind is not auto-linked by -fwasm-exceptions in wasi-sdk; the eh/
# sysroot subdirectory holds the EH-aware variant.
set(WASI_EH_LIB_DIR "${CMAKE_SYSROOT}/lib/wasm32-wasip1/eh")

add_executable(slang-wasm-lib slang-wasm-lib.cpp)

set_target_properties(
    slang-wasm-lib
    PROPERTIES
        SUFFIX ".wasm"
        RUNTIME_OUTPUT_DIRECTORY_RELEASE "${CMAKE_BINARY_DIR}/Release/bin"
        RUNTIME_OUTPUT_DIRECTORY_DEBUG "${CMAKE_BINARY_DIR}/Debug/bin"
        RUNTIME_OUTPUT_DIRECTORY_RELWITHDEBINFO "${CMAKE_BINARY_DIR}/RelWithDebInfo/bin"
        RUNTIME_OUTPUT_DIRECTORY_MINSIZEREL "${CMAKE_BINARY_DIR}/MinSizeRel/bin"
)

target_include_directories(
    slang-wasm-lib
    PRIVATE
        ${slang_SOURCE_DIR}/include
)

target_link_libraries(
    slang-wasm-lib
    PRIVATE
        slang
        core
        compiler-core
        slang-capability-defs
        slang-capability-lookup
        slang-fiddle-output
        slang-lookup-tables
        miniz
        lz4_static
)

target_link_options(
    slang-wasm-lib
    PRIVATE
        # Reactor: link crt1-reactor.o which provides _initialize (calls
        # __wasm_call_ctors) and emits no WASM start section. This matches
        # the pattern validated in phase0/spike.cpp.
        -mexec-model=reactor
        # Large stack: Slang's AST/IR passes recurse deeply. 8 MB avoids stack traps.
        -Wl,-z,stack-size=8388608
        # Export the C++ exception tag so the EH runtime is reachable from the host.
        -Wl,--export=__cpp_exception
        # Export the reactor init entry point.
        -Wl,--export=_initialize
        # Export the flat C ABI shim and allocator symbols.
        -Wl,--export=slang_wasm_alloc
        -Wl,--export=slang_wasm_free
        -Wl,--export=slang_wasm_session_create
        -Wl,--export=slang_wasm_session_destroy
        -Wl,--export=slang_wasm_compile
        -Wl,--export=slang_wasm_result_succeeded
        -Wl,--export=slang_wasm_result_code_ptr
        -Wl,--export=slang_wasm_result_code_len
        -Wl,--export=slang_wasm_result_reflection_json_ptr
        -Wl,--export=slang_wasm_result_reflection_json_len
        -Wl,--export=slang_wasm_result_diagnostics_ptr
        -Wl,--export=slang_wasm_result_diagnostics_len
        -Wl,--export=slang_wasm_result_destroy
        -Wl,--export=slang_wasm_version
        # libunwind is not auto-linked by -fwasm-exceptions in wasi-sdk.
        -L${WASI_EH_LIB_DIR}
        -lunwind
)
