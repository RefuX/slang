package org.shaderslang.wasm;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke tests for SlangCompiler against the real slang-wasm-lib.wasm artifact.
 *
 * These tests require the WASM artifact to be built first:
 *   cmake --build --preset slang-wasm-lib
 *
 * Locate the artifact via the system property {@code slang.wasm.path} (set by
 * build.gradle from the project property or the SLANG_WASM_PATH env var). If the
 * file does not exist the tests are skipped rather than failed, so a plain
 * {@code ./gradlew test} does not break when the WASM build has not been run.
 */
class SlangCompilerSmokeTest {

    private static Path wasmPath;

    // A trivial compute shader — the minimum shader that Slang should compile
    // to non-empty SPIR-V.
    private static final String TRIVIAL_SHADER =
            "[shader(\"compute\")] [numthreads(1,1,1)] void main() {}";

    @BeforeAll
    static void locateWasm() {
        String raw = System.getProperty("slang.wasm.path", "");
        wasmPath = Path.of(raw.isEmpty() ? "slang-wasm-lib.wasm" : raw);
        Assumptions.assumeTrue(
                Files.exists(wasmPath),
                "slang-wasm-lib.wasm not found at " + wasmPath.toAbsolutePath()
                + " — build it first with: cmake --build --preset slang-wasm-lib");
    }

    // SPIR-V smoke test ──────────────────────────────────────────────

    @Test
    void compileTrivialShaderToSpirv() throws Exception {
        try (var slang = SlangCompiler.forSpirvFromWasm(wasmPath)) {
            CompileResult result = slang.compile("hello", TRIVIAL_SHADER, "main");

            assertTrue(result.succeeded(),
                    "Expected compilation to succeed. Diagnostics:\n" + result.diagnostics());
            assertTrue(result.code().length > 0,
                    "Expected non-empty SPIR-V byte output");

            // SPIR-V magic number: 0x07230203 (little-endian in the byte stream)
            byte[] code = result.code();
            assertTrue(code.length >= 4, "SPIR-V output is shorter than 4 bytes");
            int magic = ((code[0] & 0xFF))
                      | ((code[1] & 0xFF) << 8)
                      | ((code[2] & 0xFF) << 16)
                      | ((code[3] & 0xFF) << 24);
            assertEquals(0x07230203, magic,
                    "First four bytes are not the SPIR-V magic number");
        }
    }

    // ── Version sanity check ──────────────────────────────────────────────────

    @Test
    void versionStringIsNonEmpty() throws Exception {
        try (var slang = SlangCompiler.forSpirvFromWasm(wasmPath)) {
            String ver = slang.version();
            assertFalse(ver.isEmpty(), "Expected a non-empty version string");
            System.out.println("slang-wasm-lib version: " + ver);
        }
    }
}
