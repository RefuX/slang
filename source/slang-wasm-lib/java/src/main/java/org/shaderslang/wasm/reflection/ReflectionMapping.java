package org.shaderslang.wasm.reflection;

import org.shaderslang.wasm.enums.ParameterCategory;
import org.shaderslang.wasm.enums.ScalarType;
import org.shaderslang.wasm.enums.Stage;
import org.shaderslang.wasm.enums.TypeKind;

import java.util.Map;

/**
 * The single place that maps the string spellings {@code source/slang/slang-reflection-json.cpp}
 * writes for a given enum onto the generated Java enum constant — e.g. {@code "constantBuffer"}
 * (written by {@code emitReflectionTypeInfoJSON}) maps to {@link TypeKind#CONSTANT_BUFFER}. Every
 * reflection model class resolves enums through here rather than re-deriving the mapping locally.
 */
final class ReflectionMapping {
    private ReflectionMapping() {}

    private static final Map<String, TypeKind> TYPE_KIND = Map.ofEntries(
            Map.entry("none", TypeKind.NONE),
            Map.entry("struct", TypeKind.STRUCT),
            Map.entry("array", TypeKind.ARRAY),
            Map.entry("matrix", TypeKind.MATRIX),
            Map.entry("vector", TypeKind.VECTOR),
            Map.entry("scalar", TypeKind.SCALAR),
            Map.entry("constantbuffer", TypeKind.CONSTANT_BUFFER),
            Map.entry("resource", TypeKind.RESOURCE),
            Map.entry("samplerstate", TypeKind.SAMPLER_STATE),
            Map.entry("texturebuffer", TypeKind.TEXTURE_BUFFER),
            Map.entry("shaderstoragebuffer", TypeKind.SHADER_STORAGE_BUFFER),
            Map.entry("parameterblock", TypeKind.PARAMETER_BLOCK),
            Map.entry("generictypeparameter", TypeKind.GENERIC_TYPE_PARAMETER),
            Map.entry("interface", TypeKind.INTERFACE),
            Map.entry("outputstream", TypeKind.OUTPUT_STREAM),
            Map.entry("meshoutput", TypeKind.MESH_OUTPUT),
            Map.entry("specialized", TypeKind.SPECIALIZED),
            Map.entry("feedback", TypeKind.FEEDBACK),
            Map.entry("pointer", TypeKind.POINTER),
            Map.entry("dynamicresource", TypeKind.DYNAMIC_RESOURCE));

    /**
     * Resolve a {@code "kind"} string to a {@link TypeKind}, lower-casing first since
     * the producer mixes camelCase ({@code "constantBuffer"}) and PascalCase
     * ({@code "GenericTypeParameter"}, {@code "DynamicResource"}, ...) inconsistently
     * across its emit sites. Returns {@link TypeKind#NONE} for an unrecognised string
     * (e.g. {@code "unknown"}, the producer's own fallback for an unhandled case)
     * rather than throwing — reflection parsing should degrade, not abort, on a type
     * shape this model hasn't been taught yet.
     */
    static TypeKind typeKind(String json) {
        if (json == null) return TypeKind.NONE;
        TypeKind kind = TYPE_KIND.get(json.toLowerCase());
        return kind != null ? kind : TypeKind.NONE;
    }

    /**
     * Resolve a {@code "scalarType"} string to a {@link ScalarType}. The producer's
     * spellings ({@code "float32"}, {@code "float_e4m3"}, ...) are exactly the generated
     * enum names lower-cased, so this is a direct {@code valueOf} after upper-casing,
     * with {@link ScalarType#NONE} as the fallback for an unrecognised string.
     */
    static ScalarType scalarType(String json) {
        if (json == null) return ScalarType.NONE;
        try {
            return ScalarType.valueOf(json.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ScalarType.NONE;
        }
    }

    /**
     * Resolve a {@code "stage"} string to a {@link Stage}. Same direct-uppercase mapping
     * as {@link #scalarType}, with {@link Stage#NONE} as the fallback.
     */
    static Stage stage(String json) {
        if (json == null) return Stage.NONE;
        try {
            return Stage.valueOf(json.toUpperCase());
        } catch (IllegalArgumentException e) {
            return Stage.NONE;
        }
    }

    private static final Map<String, ParameterCategory> PARAMETER_CATEGORY = Map.ofEntries(
            Map.entry("uniform", ParameterCategory.UNIFORM),
            Map.entry("constantBuffer", ParameterCategory.CONSTANT_BUFFER),
            Map.entry("shaderResource", ParameterCategory.SHADER_RESOURCE),
            Map.entry("unorderedAccess", ParameterCategory.UNORDERED_ACCESS),
            Map.entry("varyingInput", ParameterCategory.VARYING_INPUT),
            Map.entry("varyingOutput", ParameterCategory.VARYING_OUTPUT),
            Map.entry("samplerState", ParameterCategory.SAMPLER_STATE),
            Map.entry("pushConstantBuffer", ParameterCategory.PUSH_CONSTANT_BUFFER),
            Map.entry("descriptorTableSlot", ParameterCategory.DESCRIPTOR_TABLE_SLOT),
            Map.entry("specializationConstant", ParameterCategory.SPECIALIZATION_CONSTANT),
            Map.entry("mixed", ParameterCategory.MIXED),
            Map.entry("registerSpace", ParameterCategory.REGISTER_SPACE),
            Map.entry("generic", ParameterCategory.GENERIC),
            Map.entry("rayPayload", ParameterCategory.RAY_PAYLOAD),
            Map.entry("hitAttributes", ParameterCategory.HIT_ATTRIBUTES),
            Map.entry("callablePayload", ParameterCategory.CALLABLE_PAYLOAD),
            Map.entry("shaderRecord", ParameterCategory.SHADER_RECORD));

    /**
     * Resolve a binding {@code "kind"} string to a {@link ParameterCategory}. A handful
     * of exotic categories the producer can emit (existential parameters, Metal-specific
     * argument buffer slots, subpass inputs) have no corresponding generated enum constant
     * yet; those fall back to {@link ParameterCategory#NONE} rather than throwing.
     */
    static ParameterCategory parameterCategory(String json) {
        if (json == null) return ParameterCategory.NONE;
        ParameterCategory category = PARAMETER_CATEGORY.get(json);
        return category != null ? category : ParameterCategory.NONE;
    }
}
