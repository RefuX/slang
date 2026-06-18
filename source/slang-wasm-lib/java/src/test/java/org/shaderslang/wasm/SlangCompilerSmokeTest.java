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
 * Tests require the WASM artifact to be built first:
 *   cmake --build --preset slang-wasm-lib
 *
 * Locate the artifact via the system property {@code slang.wasm.path} (set by
 * build.gradle from the project property or the SLANG_WASM_PATH env var). If the
 * file does not exist the tests are skipped rather than failed, so a plain
 * {@code ./gradlew test} does not break when the WASM build has not been run.
 */
class SlangCompilerSmokeTest {

    private static Path wasmPath;

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

            // SPIR-V magic number: 0x07230203 (little-endian)
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

    // Reflection JSON shape ─────────────────────────────────────────

    @Test
    void reflectionJsonContainsEntryPoint() throws Exception {
        try (var slang = SlangCompiler.forSpirvFromWasm(wasmPath)) {
            CompileResult result = slang.compile("reflect", TRIVIAL_SHADER, "main");

            assertTrue(result.succeeded(),
                    "Expected compilation to succeed. Diagnostics:\n" + result.diagnostics());

            String json = result.reflectionJson();
            assertFalse(json.isEmpty(), "reflectionJson() must not be empty on success");

            // Validate the outer shape: must be a JSON object.
            assertTrue(json.trim().startsWith("{"),
                    "reflectionJson() must start with '{', got: " + json.substring(0, Math.min(80, json.length())));
            assertTrue(json.trim().endsWith("}"),
                    "reflectionJson() must end with '}'");

            // The entry point name must appear somewhere in the JSON tree.
            assertTrue(json.contains("\"main\""),
                    "reflectionJson() must contain the entry point name \"main\"");

            System.out.println("reflectionJson length: " + json.length() + " chars");
        }
    }

    // Broken shader: failure result + instance survives ─────────────

    @Test
    void brokenShaderFailsAndInstanceSurvives() throws Exception {
        // Use a single SlangCompiler instance for both calls — the point is to
        // verify that a failed compile leaves the session alive and reusable.
        try (var slang = SlangCompiler.forSpirvFromWasm(wasmPath)) {

            // First compile: deliberately broken source (undefined symbol).
            CompileResult bad = slang.compile("broken",
                    "void main() { undefinedFunction(); }", "main");

            assertFalse(bad.succeeded(),
                    "Expected compilation of broken shader to fail");
            assertFalse(bad.diagnostics().isEmpty(),
                    "Expected non-empty diagnostics on failure");
            System.out.println("Bad-shader diagnostics (expected):\n" + bad.diagnostics());

            // Second compile on the same instance: the session must still work.
            CompileResult good = slang.compile("recovery", TRIVIAL_SHADER, "main");

            assertTrue(good.succeeded(),
                    "Expected recovery compile to succeed after a prior failure. "
                    + "Diagnostics:\n" + good.diagnostics());
            assertTrue(good.code().length > 0,
                    "Expected non-empty SPIR-V from recovery compile");
        }
    }

    // Stack depth stress ─────────────────────────────────────────────

    @Test
    void stackDepthStressShader() throws Exception {
        // A shader with multiple levels of helper-function nesting exercises the
        // AST/IR recursion stack and confirms the 8 MB shadow stack is sufficient.
        String nested =
            "float level5(float x) { return x * 2.0f; }\n" +
            "float level4(float x) { return level5(x + 1.0f); }\n" +
            "float level3(float x) { return level4(x + 1.0f); }\n" +
            "float level2(float x) { return level3(x + 1.0f); }\n" +
            "float level1(float x) { return level2(x + 1.0f); }\n" +
            "RWStructuredBuffer<float> output;\n" +
            "[shader(\"compute\")] [numthreads(1,1,1)]\n" +
            "void main() { output[0] = level1(0.0f); }";

        try (var slang = SlangCompiler.forSpirvFromWasm(wasmPath)) {
            CompileResult result = slang.compile("nested", nested, "main");

            assertTrue(result.succeeded(),
                    "Expected nested shader to compile successfully. "
                    + "Diagnostics:\n" + result.diagnostics());
            assertTrue(result.code().length > 0,
                    "Expected non-empty SPIR-V from nested shader");
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
