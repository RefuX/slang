# slangd-wasm-lib — WASI command WebAssembly build of Slang's language server.
#
# Only built when CMAKE_SYSTEM_NAME is WASI (i.e. the 'wasi' preset using
# wasi-sdk, the same preset slang-wasm-lib uses). Unlike slang-wasm-lib (a
# reactor exporting a flat C ABI for one-shot compile calls), this target is a
# plain WASI *command* module: it cross-compiles the same entry point as the
# native `slangd` (tools/slangd/main.cpp -> Slang::runLanguageServer), so its
# lifecycle matches the native binary's -- one continuous run reading/writing
# LSP JSON-RPC over stdio, not a library called repeatedly.
#
# Scope so far: get slang-language-server.cpp and its dependencies
# cross-compiling. No preopen wiring or editor-side host integration yet --
# this target exists to prove the WASI build itself works.
#
# Build sequence (from repo root), mirroring slang-wasm-lib's:
#   cmake --workflow --preset generators --fresh
#   cmake --install build --config Release --prefix generators --component generators
#   export WASI_SDK_PATH=/path/to/wasi-sdk
#   cmake -DSLANG_GENERATORS_PATH=generators/bin --preset wasi
#   cmake --build --preset wasi --target slangd-wasm-lib

if(NOT CMAKE_SYSTEM_NAME STREQUAL "WASI")
    return()
endif()

# libunwind is not auto-linked by -fwasm-exceptions in wasi-sdk; the eh/
# sysroot subdirectory holds the EH-aware variant. Same need as
# slang-wasm-lib: slang's internal abort path (SLANG_ASSERT / handleSignal)
# throws C++ exceptions that slang-language-server.cpp catches at its own
# request-handling boundaries.
set(WASI_EH_LIB_DIR "${CMAKE_SYSROOT}/lib/wasm32-wasip1/eh")

add_executable(slangd-wasm-lib "${slang_SOURCE_DIR}/tools/slangd/main.cpp")

set_target_properties(
    slangd-wasm-lib
    PROPERTIES
        SUFFIX ".wasm"
        RUNTIME_OUTPUT_DIRECTORY_RELEASE "${CMAKE_BINARY_DIR}/Release/bin"
        RUNTIME_OUTPUT_DIRECTORY_DEBUG "${CMAKE_BINARY_DIR}/Debug/bin"
        RUNTIME_OUTPUT_DIRECTORY_RELWITHDEBINFO "${CMAKE_BINARY_DIR}/RelWithDebInfo/bin"
        RUNTIME_OUTPUT_DIRECTORY_MINSIZEREL "${CMAKE_BINARY_DIR}/MinSizeRel/bin"
)

target_link_libraries(
    slangd-wasm-lib
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
        Threads::Threads
)

target_link_options(
    slangd-wasm-lib
    PRIVATE
        # Large stack: Slang's AST/IR passes recurse deeply. 8 MB avoids stack traps
        # (same rationale as slang-wasm-lib).
        -Wl,-z,stack-size=8388608
        # Export the C++ exception tag so the EH runtime is reachable from the host.
        -Wl,--export=__cpp_exception
        # libunwind is not auto-linked by -fwasm-exceptions in wasi-sdk.
        -L${WASI_EH_LIB_DIR}
        -lunwind
)
