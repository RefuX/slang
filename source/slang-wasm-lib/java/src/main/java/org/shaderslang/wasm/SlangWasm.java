package org.shaderslang.wasm;

import run.endive.annotations.WasmModuleInterface;

/**
 * Anchor for the {@code @WasmModuleInterface} annotation processor.
 *
 * <p>At compile time the {@code run.endive} processor reads
 * {@code slang-wasm-lib.wasm} (resolved from the annotation processor path — see
 * {@code build.gradle}) and generates typed binding classes in this package:
 *
 * <ul>
 *   <li>{@code SlangWasm_ModuleExports} — a typed wrapper over every WASM export
 *       (e.g. {@code slangWasmCompile(int, ...)}), replacing the old hand-written
 *       {@code instance.export("slang_wasm_compile").apply(...)} string lookups.</li>
 *   <li>{@code SlangWasm_ModuleImports} / {@code SlangWasm_WasiSnapshotPreview1}
 *       — generated host-import scaffolding. Unused here: {@link SlangCompiler}
 *       supplies WASI via {@code run.endive:wasi}'s ready-made implementation.</li>
 * </ul>
 *
 * <p>This type is never instantiated; it exists only to carry the annotation.
 */
@WasmModuleInterface("slang-wasm-lib.wasm")
final class SlangWasm {
    private SlangWasm() {}
}
