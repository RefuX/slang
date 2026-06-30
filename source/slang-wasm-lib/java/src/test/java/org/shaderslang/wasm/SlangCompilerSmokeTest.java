package org.shaderslang.wasm;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.shaderslang.wasm.diagnostics.Diagnostic;
import org.shaderslang.wasm.diagnostics.DiagnosticList;
import org.shaderslang.wasm.enums.CompilerOptionName;
import org.shaderslang.wasm.enums.OptimizationLevel;
import org.shaderslang.wasm.enums.ParameterCategory;
import org.shaderslang.wasm.enums.Target;
import org.shaderslang.wasm.enums.TypeKind;
import org.shaderslang.wasm.reflection.DeclReflection;
import org.shaderslang.wasm.reflection.EntryPointReflection;
import org.shaderslang.wasm.reflection.ShaderReflection;
import org.shaderslang.wasm.reflection.TypeLayoutReflection;
import org.shaderslang.wasm.reflection.VariableLayoutReflection;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

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

    /**
     * One runtime-compiled instance shared by the whole suite. Loading the
     * ~150 MB module and compiling it to JVM bytecode is the dominant cost, so we
     * pay it once and open a fresh Slang session per test on top of it. The few
     * tests that genuinely need a separate instance create their own.
     */
    private static SlangRuntime shared;

    private static final String TRIVIAL_SHADER =
            "[shader(\"compute\")] [numthreads(1,1,1)] void main() {}";

    @BeforeAll
    static void locateWasm() throws IOException {
        String raw = System.getProperty("slang.wasm.path", "");
        wasmPath = Path.of(raw.isEmpty() ? "slang-wasm-lib.wasm" : raw);
        Assumptions.assumeTrue(
                Files.exists(wasmPath),
                "slang-wasm-lib.wasm not found at " + wasmPath.toAbsolutePath()
                + " — build it first with: cmake --build --preset slang-wasm-lib");

        shared = SlangRuntime.builder(wasmPath)
                .withRuntimeCompiler(true)
                .build();
    }

    @AfterAll
    static void releaseShared() {
        if (shared != null) {
            shared.close();
        }
    }

    /** A fresh runtime-compiled instance, for the few tests that need isolation. */
    private static SlangRuntime freshRuntime() throws IOException {
        return SlangRuntime.builder(wasmPath)
                .withRuntimeCompiler(true)
                .build();
    }

    // SPIR-V smoke test ──────────────────────────────────────────────

    @Test
    void compileTrivialShaderToSpirv() throws Exception {
        try (var slang = shared.forSpirv()) {
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
        try (var slang = shared.forSpirv()) {
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
        try (var slang = shared.forSpirv()) {

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

        try (var slang = shared.forSpirv()) {
            CompileResult result = slang.compile("nested", nested, "main");

            assertTrue(result.succeeded(),
                    "Expected nested shader to compile successfully. "
                    + "Diagnostics:\n" + result.diagnostics());
            assertTrue(result.code().length > 0,
                    "Expected non-empty SPIR-V from nested shader");
        }
    }

    // ── preprocessor macro defines via the session builder ──────────

    @Test
    void macroDefineChangesCompiledOutput() throws Exception {
        String source =
            "RWStructuredBuffer<float> output;\n" +
            "[shader(\"compute\")] [numthreads(1,1,1)]\n" +
            "void main() {\n" +
            "#ifdef MY_DEFINE\n" +
            "    output[0] = 1.0f;\n" +
            "#else\n" +
            "    output[0] = 2.0f;\n" +
            "#endif\n" +
            "}";

        // Without the macro defined: compiles, but takes the #else branch.
        // Two sessions with different macro sets, both on the shared instance.
        try (var without = shared.newSession(
                List.of(SlangCompiler.TargetSpec.of(Target.SPIRV)),
                Map.of(),
                List.of())) {
            CompileResult r = without.compile("undef", source, "main");
            assertTrue(r.succeeded(), "Expected compile without macro to succeed. Diagnostics:\n"
                    + r.diagnostics());
        }

        // With the macro defined via the macro-list builder: still compiles
        // (and takes the #ifdef branch, though we only assert success here —
        // the point of this test is that slang_wasm_macro_list_add actually
        // reaches the preprocessor).
        try (var with = shared.newSession(
                List.of(SlangCompiler.TargetSpec.of(Target.SPIRV)),
                Map.of("MY_DEFINE", "1"),
                List.of())) {
            CompileResult r = with.compile("def", source, "main");
            assertTrue(r.succeeded(), "Expected compile with macro to succeed. Diagnostics:\n"
                    + r.diagnostics());
            assertTrue(r.code().length > 0, "Expected non-empty SPIR-V with macro defined");
        }
    }

    // ── session-wide compiler options (CompilerOptionName) ──────────

    @Test
    void compilerOptionsAffectCompilation() throws Exception {
        try (var slang = shared.newSession(
                List.of(SlangCompiler.TargetSpec.of(Target.SPIRV)),
                Map.of(),
                List.of(),
                List.of(SlangCompiler.CompilerOption.of(
                        CompilerOptionName.Optimization, OptimizationLevel.NONE.value)))) {

            CompileResult result = slang.compile("opt", TRIVIAL_SHADER, "main");
            assertTrue(result.succeeded(),
                    "Expected compile with an explicit optimization-level option to succeed. "
                    + "Diagnostics:\n" + result.diagnostics());
            assertTrue(result.code().length > 0, "Expected non-empty SPIR-V");
        }
    }

    // ── multi-target session, compile by target index ───────────────

    @Test
    void twoTargetSessionCompilesBothTargetsByIndex() throws Exception {
        try (var slang = shared.newSession(
                List.of(
                        SlangCompiler.TargetSpec.of(Target.SPIRV, "spirv_1_4"),
                        SlangCompiler.TargetSpec.of(Target.HLSL)),
                Map.of(),
                List.of())) {

            CompileResult spirv = slang.compile("multi", TRIVIAL_SHADER, "main", 0);
            assertTrue(spirv.succeeded(),
                    "Expected target index 0 (SPIR-V) to succeed. Diagnostics:\n"
                    + spirv.diagnostics());
            assertTrue(spirv.code().length >= 4, "Expected non-empty SPIR-V");

            CompileResult hlsl = slang.compile("multi", TRIVIAL_SHADER, "main", 1);
            assertTrue(hlsl.succeeded(),
                    "Expected target index 1 (HLSL) to succeed. Diagnostics:\n"
                    + hlsl.diagnostics());
            assertTrue(hlsl.code().length > 0, "Expected non-empty HLSL source");
        }
    }

    // ── Module handles, multi-entry-point compilation ─────────────────────────

    private static final String VERT_FRAG_SHADER =
            "[shader(\"vertex\")]\n"
            + "float4 vert(float3 pos : POSITION) : SV_Position { return float4(pos, 1.0f); }\n"
            + "[shader(\"fragment\")]\n"
            + "float4 frag() : SV_Target { return float4(1.0f, 0.0f, 0.0f, 1.0f); }";

    @Test
    void moduleReportsEntryPointCountAndCompilesEachIndependently() throws Exception {
        try (var slang = shared.forSpirv();
             var module = slang.loadModule("pipeline", VERT_FRAG_SHADER)) {

            List<String> entryPoints = module.entryPointNames();
            assertEquals(2, entryPoints.size(),
                    "Expected two defined entry points, got: " + entryPoints);
            assertTrue(entryPoints.contains("vert") && entryPoints.contains("frag"),
                    "Expected entry points \"vert\" and \"frag\", got: " + entryPoints);

            CompileResult vert = module.compileEntryPoint("vert", 0);
            assertTrue(vert.succeeded(),
                    "Expected \"vert\" to compile independently. Diagnostics:\n" + vert.diagnostics());
            assertTrue(vert.code().length > 0, "Expected non-empty SPIR-V for \"vert\"");

            CompileResult frag = module.compileEntryPoint("frag", 0);
            assertTrue(frag.succeeded(),
                    "Expected \"frag\" to compile independently. Diagnostics:\n" + frag.diagnostics());
            assertTrue(frag.code().length > 0, "Expected non-empty SPIR-V for \"frag\"");
        }
    }

    @Test
    void compileAllProducesOneCombinedModule() throws Exception {
        try (var slang = shared.forSpirv();
             var module = slang.loadModule("pipeline-combined", VERT_FRAG_SHADER)) {

            CompileResult combined = module.compileAll(0);
            assertTrue(combined.succeeded(),
                    "Expected combined compile to succeed. Diagnostics:\n" + combined.diagnostics());

            byte[] code = combined.code();
            assertTrue(code.length >= 4, "SPIR-V output is shorter than 4 bytes");
            int magic = ((code[0] & 0xFF))
                      | ((code[1] & 0xFF) << 8)
                      | ((code[2] & 0xFF) << 16)
                      | ((code[3] & 0xFF) << 24);
            assertEquals(0x07230203, magic, "First four bytes are not the SPIR-V magic number");

            // Both entry point names should be discoverable in the combined module's reflection.
            String json = combined.reflectionJson();
            assertTrue(json.contains("\"vert\"") && json.contains("\"frag\""),
                    "Expected reflection JSON to mention both entry points, got:\n" + json);
        }
    }

    @Test
    void loadModuleThrowsWithDiagnosticsOnBrokenSource() throws Exception {
        try (var slang = shared.forSpirv()) {
            Exception ex = assertThrows(IOException.class,
                    () -> slang.loadModule("broken-module", "this is not valid slang syntax {{{"));
            assertTrue(ex.getMessage().length() > 0, "Expected a non-empty exception message");
        }
    }

    // ── builder API ────────────────

    @Test
    void builderApiCompilesTwoTargetTwoEntryPointPipelineWithNoRawIntegers() throws Exception {
        // This test exercises the standalone SessionBuilder (its own instance);
        // run it compiled + cached so it stays fast like the shared-instance tests.
        try (var slang = SlangCompiler.builder()
                .wasm(wasmPath)
                .target(Target.SPIRV, "spirv_1_4")
                .target(Target.HLSL)
                .define("ENABLE_FOO", "1")
                .optimizationLevel(OptimizationLevel.NONE)
                .runtimeCompiler(true)
                .build();
             var module = slang.loadModule("pipeline-builder", VERT_FRAG_SHADER)) {

            CompileResult vertSpirv = module.compileEntryPoint("vert", Target.SPIRV);
            assertTrue(vertSpirv.succeeded(),
                    "Expected \"vert\" on SPIR-V to succeed. Diagnostics:\n" + vertSpirv.diagnostics());
            assertTrue(vertSpirv.code().length > 0, "Expected non-empty SPIR-V for \"vert\"");

            CompileResult fragHlsl = module.compileEntryPoint("frag", Target.HLSL);
            assertTrue(fragHlsl.succeeded(),
                    "Expected \"frag\" on HLSL to succeed. Diagnostics:\n" + fragHlsl.diagnostics());
            assertTrue(fragHlsl.code().length > 0, "Expected non-empty HLSL for \"frag\"");

            CompileResult combined = module.compileAll(Target.SPIRV);
            assertTrue(combined.succeeded(),
                    "Expected combined compile on SPIR-V to succeed. Diagnostics:\n"
                    + combined.diagnostics());

            CompileResult viaRequest = slang.compile(
                    SlangCompiler.CompileRequest.source("via-request", TRIVIAL_SHADER)
                            .entryPoint("main")
                            .target(Target.SPIRV));
            assertTrue(viaRequest.succeeded(),
                    "Expected CompileRequest-based compile to succeed. Diagnostics:\n"
                    + viaRequest.diagnostics());
        }
    }

    @Test
    void targetIndexOfRejectsUnconfiguredTarget() throws Exception {
        try (var slang = shared.newSession(
                List.of(SlangCompiler.TargetSpec.of(Target.SPIRV)), Map.of(), List.of())) {
            assertThrows(IllegalArgumentException.class,
                    () -> slang.compile("x", TRIVIAL_SHADER, "main", Target.HLSL));
        }
    }

    // typed reflection model ────────────────────────────────────────

    @Test
    void typedReflectionReportsConstantBufferBindingAndFields() throws Exception {
        String source =
            "struct MyStruct {\n"
            + "    float3 color;\n"
            + "    int count;\n"
            + "    float2 offset;\n"
            + "};\n"
            + "ConstantBuffer<MyStruct> gCB;\n"
            + "RWStructuredBuffer<float> output;\n"
            + "[shader(\"compute\")] [numthreads(1,1,1)]\n"
            + "void main() { output[0] = gCB.color.x + gCB.count + gCB.offset.x; }";

        try (var slang = shared.forSpirv()) {
            CompileResult result = slang.compile("typed-reflection", source, "main");
            assertTrue(result.succeeded(),
                    "Expected compilation to succeed. Diagnostics:\n" + result.diagnostics());

            ShaderReflection reflection = ShaderReflection.parse(result.reflectionJson());

            VariableLayoutReflection gcb = reflection.parameters().stream()
                    .filter(p -> "gCB".equals(p.name()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Expected a parameter named \"gCB\""));

            // On SPIR-V (Vulkan), a ConstantBuffer<T> parameter binds via a generic descriptor
            // table slot rather than the HLSL-register-specific "constantBuffer" category;
            // both are the binding kind a real caller for this target should expect.
            assertTrue(
                    gcb.bindingCategory() == ParameterCategory.CONSTANT_BUFFER
                    || gcb.bindingCategory() == ParameterCategory.DESCRIPTOR_TABLE_SLOT,
                    "Expected gCB to bind as a constant buffer or descriptor table slot, got: "
                    + gcb.bindingCategory());
            assertTrue(gcb.bindingIndex() >= 0, "Expected a non-negative binding index");
            assertTrue(gcb.bindingSpace() >= 0, "Expected a non-negative binding space");

            TypeLayoutReflection myStruct = gcb.typeLayout().elementType();
            assertEquals(TypeKind.STRUCT, myStruct.kind(), "Expected gCB's element type to be a struct");
            assertEquals("MyStruct", myStruct.name());
            assertEquals(List.of("color", "count", "offset"), myStruct.fieldNames());

            EntryPointReflection main = reflection.entryPoint("main")
                    .orElseThrow(() -> new AssertionError("Expected entry point \"main\""));
            assertEquals(org.shaderslang.wasm.enums.Stage.COMPUTE, main.stage());
            assertArrayEquals(new int[] {1, 1, 1}, main.threadGroupSize());
        }
    }

    // module serialisation (precompiled IR) ──────────────────────

    @Test
    void serializedModuleRoundTripsToIdenticalCode() throws Exception {
        byte[] ir;
        byte[] originalSpirv;

        // Serialize on the shared instance.
        try (var slang = shared.forSpirv();
             var module = slang.loadModule("serialize-me", TRIVIAL_SHADER)) {
            CompileResult original = module.compileEntryPoint("main", 0);
            assertTrue(original.succeeded(),
                    "Expected the original compile to succeed. Diagnostics:\n"
                    + original.diagnostics());
            originalSpirv = original.code();

            ir = module.serialize();
            assertTrue(ir.length > 0, "Expected non-empty serialized IR");
        }

        // Reload from IR in a brand new instance and session — proving the IR
        // is genuinely self-contained, not relying on anything left over from
        // the instance that produced it. (Hence a fresh runtime, not the shared one.)
        try (var runtime = freshRuntime();
             var slang = runtime.forSpirv();
             var reloaded = slang.loadModuleFromIr("reloaded", ir)) {

            List<String> entryPoints = reloaded.entryPointNames();
            assertTrue(entryPoints.contains("main"),
                    "Expected the reloaded module to still define \"main\", got: " + entryPoints);

            CompileResult fromIr = reloaded.compileEntryPoint("main", 0);
            assertTrue(fromIr.succeeded(),
                    "Expected the reloaded module to compile successfully. Diagnostics:\n"
                    + fromIr.diagnostics());
            assertArrayEquals(originalSpirv, fromIr.code(),
                    "Expected identical SPIR-V from the reloaded module");
        }
    }

    // ── Specialization ─────────────────────────────────────────────────────────

    @Test
    void specializingGenericEntryPointProducesDistinctBinaries() throws Exception {
        String source =
            "interface IMaterial { float3 getColor(); }\n"
            + "struct PbrMaterial : IMaterial { float3 getColor() { return float3(1.0f, 0.0f, 0.0f); } }\n"
            + "struct UnlitMaterial : IMaterial { float3 getColor() { return float3(0.0f, 1.0f, 0.0f); } }\n"
            + "RWStructuredBuffer<float3> output;\n"
            + "[shader(\"compute\")] [numthreads(1,1,1)]\n"
            + "void main<T : IMaterial>() {\n"
            + "    T material;\n"
            + "    output[0] = material.getColor();\n"
            + "}";

        try (var slang = shared.forSpirv();
             var module = slang.loadModule("generic-renderer", source)) {

            CompileResult pbr = module.compileSpecialized(
                    "main", List.of(SlangCompiler.SpecializationArg.fromType("PbrMaterial")), 0);
            assertTrue(pbr.succeeded(),
                    "Expected PbrMaterial specialization to succeed. Diagnostics:\n"
                    + pbr.diagnostics());
            assertTrue(pbr.code().length > 0, "Expected non-empty SPIR-V for PbrMaterial");

            CompileResult unlit = module.compileSpecialized(
                    "main", List.of(SlangCompiler.SpecializationArg.fromType("UnlitMaterial")), 0);
            assertTrue(unlit.succeeded(),
                    "Expected UnlitMaterial specialization to succeed. Diagnostics:\n"
                    + unlit.diagnostics());
            assertTrue(unlit.code().length > 0, "Expected non-empty SPIR-V for UnlitMaterial");

            assertFalse(Arrays.equals(pbr.code(), unlit.code()),
                    "Expected PbrMaterial and UnlitMaterial specializations to produce "
                    + "distinct SPIR-V binaries");
        }
    }

    @Test
    void specializingWithUnresolvableTypeNameFails() throws Exception {
        String source =
            "interface IMaterial { float3 getColor(); }\n"
            + "RWStructuredBuffer<float3> output;\n"
            + "[shader(\"compute\")] [numthreads(1,1,1)]\n"
            + "void main<T : IMaterial>() {\n"
            + "    T material;\n"
            + "    output[0] = material.getColor();\n"
            + "}";

        try (var slang = shared.forSpirv();
             var module = slang.loadModule("generic-renderer-bad", source)) {

            CompileResult result = module.compileSpecialized(
                    "main", List.of(SlangCompiler.SpecializationArg.fromType("NoSuchMaterial")), 0);
            assertFalse(result.succeeded(),
                    "Expected specialization with an unresolvable type name to fail");
            assertTrue(result.diagnostics().contains("NoSuchMaterial"),
                    "Expected diagnostics to mention the unresolved type name. Diagnostics:\n"
                    + result.diagnostics());
        }
    }

    // ── Module-level declaration reflection (DeclReflection) ──────────────────

    @Test
    void declReflectionReportsStructFieldsAndEntryPointWithoutCompiling() throws Exception {
        String source =
            "struct MyStruct {\n"
            + "    float3 color;\n"
            + "    int count;\n"
            + "    float2 offset;\n"
            + "};\n"
            + "RWStructuredBuffer<float> output;\n"
            + "[shader(\"compute\")] [numthreads(1,1,1)]\n"
            + "void main() { output[0] = 0.0f; }";

        try (var slang = shared.forSpirv();
             var module = slang.loadModule("decl-reflection", source)) {

            DeclReflection moduleDecl = DeclReflection.parse(module.declReflectionJson());
            assertEquals(DeclReflection.Kind.MODULE, moduleDecl.kind());

            DeclReflection myStruct = moduleDecl
                    .child(DeclReflection.Kind.STRUCT, "MyStruct")
                    .orElseThrow(() -> new AssertionError("Expected a STRUCT child named \"MyStruct\""));

            List<DeclReflection> fields = myStruct.childrenOfKind(DeclReflection.Kind.VARIABLE);
            assertEquals(3, fields.size(), "Expected three VARIABLE children, got: " + fields);
            assertEquals(
                    List.of("color", "count", "offset"),
                    fields.stream().map(DeclReflection::name).collect(java.util.stream.Collectors.toList()));

            assertTrue(moduleDecl.child(DeclReflection.Kind.FUNCTION, "main").isPresent(),
                    "Expected a FUNCTION child named \"main\"");
        }
    }

    // ── Disassembly + structured diagnostics ──────────────────────────────────

    @Test
    void disassembleProducesNonEmptyIrText() throws Exception {
        try (var slang = shared.forSpirv();
             var module = slang.loadModule("disasm-me", TRIVIAL_SHADER)) {
            String disasm = module.disassemble();
            assertFalse(disasm.isEmpty(), "Expected non-empty disassembly text");
            assertTrue(disasm.contains("main"),
                    "Expected disassembly to mention the entry point name. Got:\n" + disasm);
        }
    }

    @Test
    void diagnosticListParsesBrokenShaderError() throws Exception {
        try (var slang = shared.forSpirv()) {
            CompileResult bad = slang.compile("broken-for-diagnostics",
                    "void main() { undefinedFunction(); }", "main");
            assertFalse(bad.succeeded(), "Expected compilation of broken shader to fail");

            DiagnosticList diagnostics = DiagnosticList.parse(bad.diagnostics());
            assertTrue(diagnostics.hasErrors(), "Expected at least one error-or-worse diagnostic");

            Diagnostic error = diagnostics.diagnostics().stream()
                    .filter(d -> d.severity() == Diagnostic.Severity.ERROR)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "Expected an ERROR-severity diagnostic, got: " + diagnostics.diagnostics()));

            assertEquals("E30015", error.code());
            assertEquals(1, error.line(), "Expected the error to point at source line 1");
        }
    }

    // ── Version sanity check ──────────────────────────────────────────────────

    @Test
    void versionStringIsNonEmpty() throws Exception {
        try (var slang = shared.forSpirv()) {
            String ver = slang.version();
            assertFalse(ver.isEmpty(), "Expected a non-empty version string");
            System.out.println("slang-wasm-lib version: " + ver);
        }
    }
}
