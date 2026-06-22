package org.shaderslang.wasm.reflection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.shaderslang.wasm.reflection.JsonUtil.*;

/**
 * The top-level reflection result for one compiled program (mirrors {@code ShaderReflection} in
 * SlangShaderSharp / {@code slang::ShaderReflection}). Parse it from
 * {@link org.shaderslang.wasm.CompileResult#reflectionJson()} via {@link #parse(String)} to get
 * binding layout, type information, and entry-point metadata without parsing JSON or knowing its
 * schema directly.
 */
public final class ShaderReflection {
    private final List<VariableLayoutReflection> parameters;
    private final List<EntryPointReflection> entryPoints;
    private final int bindlessSpaceIndex;

    private ShaderReflection(
            List<VariableLayoutReflection> parameters,
            List<EntryPointReflection> entryPoints,
            int bindlessSpaceIndex) {
        this.parameters = parameters;
        this.entryPoints = entryPoints;
        this.bindlessSpaceIndex = bindlessSpaceIndex;
    }

    /**
     * Parse {@code json} (the output of {@code spReflection_ToJson}, as returned by
     * {@link org.shaderslang.wasm.CompileResult#reflectionJson()}) into a typed reflection tree.
     *
     * @throws IllegalArgumentException if {@code json} is not well-formed
     */
    public static ShaderReflection parse(String json) {
        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) Json.parse(json);

        List<VariableLayoutReflection> parameters = new ArrayList<>();
        for (Object paramObj : getArray(root, "parameters")) {
            parameters.add(VariableLayoutReflection.fromJson(asObject(paramObj)));
        }

        List<EntryPointReflection> entryPoints = new ArrayList<>();
        for (Object epObj : getArray(root, "entryPoints")) {
            entryPoints.add(EntryPointReflection.fromJson(asObject(epObj)));
        }

        int bindlessSpaceIndex = (int) getLong(root, "bindlessSpaceIndex", -1);

        return new ShaderReflection(parameters, entryPoints, bindlessSpaceIndex);
    }

    /** Global shader parameters (uniforms, resources, constant buffers, ...) outside any entry point. */
    public List<VariableLayoutReflection> parameters() {
        return parameters;
    }

    /** Every entry point linked into this program. */
    public List<EntryPointReflection> entryPoints() {
        return entryPoints;
    }

    /** The named entry point, if one with that name was linked into this program. */
    public Optional<EntryPointReflection> entryPoint(String name) {
        return entryPoints.stream().filter(ep -> name.equals(ep.name())).findFirst();
    }

    /** The descriptor space reserved for bindless resource heaps, or -1 if not used. */
    public int bindlessSpaceIndex() {
        return bindlessSpaceIndex;
    }
}
