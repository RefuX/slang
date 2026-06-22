package org.shaderslang.wasm.reflection;

import org.shaderslang.wasm.enums.Stage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.shaderslang.wasm.reflection.JsonUtil.*;

/**
 * One entry point's reflection (mirrors {@code EntryPointReflection} in SlangShaderSharp /
 * {@code slang::EntryPointReflection}): its name, stage, parameters, and (for a compute entry
 * point) thread group size.
 */
public final class EntryPointReflection {
    private final String name;
    private final Stage stage;
    private final List<VariableLayoutReflection> parameters;
    private final int[] threadGroupSize;
    private final boolean usesAnySampleRateInput;

    private EntryPointReflection(
            String name,
            Stage stage,
            List<VariableLayoutReflection> parameters,
            int[] threadGroupSize,
            boolean usesAnySampleRateInput) {
        this.name = name;
        this.stage = stage;
        this.parameters = parameters;
        this.threadGroupSize = threadGroupSize;
        this.usesAnySampleRateInput = usesAnySampleRateInput;
    }

    static EntryPointReflection fromJson(Map<String, Object> json) {
        if (json == null) return null;

        String name = getString(json, "name");
        Stage stage = ReflectionMapping.stage(getString(json, "stage"));

        List<VariableLayoutReflection> parameters = new ArrayList<>();
        for (Object paramObj : getArray(json, "parameters")) {
            parameters.add(VariableLayoutReflection.fromJson(asObject(paramObj)));
        }

        // [x, y, z], present only for a compute entry point.
        int[] threadGroupSize = {1, 1, 1};
        List<Object> threadGroupSizeJson = getArray(json, "threadGroupSize");
        for (int i = 0; i < threadGroupSizeJson.size() && i < 3; i++) {
            threadGroupSize[i] = ((Number) threadGroupSizeJson.get(i)).intValue();
        }

        boolean usesAnySampleRateInput = getBoolean(json, "usesAnySampleRateInput", false);

        return new EntryPointReflection(name, stage, parameters, threadGroupSize, usesAnySampleRateInput);
    }

    /** This entry point's name. */
    public String name() {
        return name;
    }

    /** This entry point's pipeline stage. */
    public Stage stage() {
        return stage;
    }

    /** This entry point's parameters (its function signature, with binding info). */
    public List<VariableLayoutReflection> parameters() {
        return parameters;
    }

    /** {@code [x, y, z]} thread group size, for a {@link Stage#COMPUTE} entry point. {@code [1, 1, 1]} otherwise. */
    public int[] threadGroupSize() {
        return threadGroupSize;
    }

    /** True if this (fragment) entry point reads any per-sample (as opposed to per-pixel) input. */
    public boolean usesAnySampleRateInput() {
        return usesAnySampleRateInput;
    }
}
