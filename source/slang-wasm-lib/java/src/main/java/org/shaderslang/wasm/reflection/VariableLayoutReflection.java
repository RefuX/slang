package org.shaderslang.wasm.reflection;

import org.shaderslang.wasm.enums.ParameterCategory;

import java.util.List;
import java.util.Map;

import static org.shaderslang.wasm.reflection.JsonUtil.*;

/**
 * A named, laid-out member (mirrors {@code VariableLayoutReflection} in SlangShaderSharp /
 * {@code slang::VariableLayoutReflection}): a name, a {@link TypeLayoutReflection}, and binding
 * information. Used both for {@link ShaderReflection#parameters()} (top-level shader parameters)
 * and {@link TypeLayoutReflection#fields()} (struct fields with offsets).
 *
 * <p>The reflection JSON's {@code "binding"} object has a different shape depending on the
 * parameter category: a {@code uniform} member reports an {@code offset}/{@code size}/
 * {@code elementStride} (its place within a constant buffer's byte layout), while a resource
 * binding (e.g. {@code constantBuffer}, {@code shaderResource}) reports an {@code index}/
 * {@code space} (its descriptor slot). {@link #bindingIndex()} and {@link #offset()} therefore
 * read different JSON keys depending on {@link #bindingCategory()} — see each method's doc.
 */
public final class VariableLayoutReflection {
    private final String name;
    private final TypeLayoutReflection typeLayout;
    private final ParameterCategory bindingCategory;
    private final long bindingIndex;
    private final long bindingSpace;
    private final long offset;
    private final long size;

    private VariableLayoutReflection(
            String name,
            TypeLayoutReflection typeLayout,
            ParameterCategory bindingCategory,
            long bindingIndex,
            long bindingSpace,
            long offset,
            long size) {
        this.name = name;
        this.typeLayout = typeLayout;
        this.bindingCategory = bindingCategory;
        this.bindingIndex = bindingIndex;
        this.bindingSpace = bindingSpace;
        this.offset = offset;
        this.size = size;
    }

    static VariableLayoutReflection fromJson(Map<String, Object> json) {
        if (json == null) return null;

        String name = getString(json, "name");
        TypeLayoutReflection typeLayout = TypeLayoutReflection.fromJson(getObject(json, "type"));

        // A member with more than one binding category (rare — e.g. a parameter that is both
        // a uniform and a resource) emits a "bindings" array instead of a single "binding"
        // object; this model exposes only the first, which covers every case the gate and the
        // SlangShaderSharp-mirroring goal of this phase actually need.
        Map<String, Object> binding = getObject(json, "binding");
        if (binding == null) {
            List<Object> bindings = getArray(json, "bindings");
            if (!bindings.isEmpty()) {
                binding = asObject(bindings.get(0));
            }
        }

        ParameterCategory bindingCategory = ParameterCategory.NONE;
        long bindingIndex = 0;
        long bindingSpace = 0;
        long offset = 0;
        long size = -1;
        if (binding != null) {
            bindingCategory = ReflectionMapping.parameterCategory(getString(binding, "kind"));
            bindingSpace = getLong(binding, "space", 0);
            if (bindingCategory == ParameterCategory.UNIFORM) {
                offset = getLong(binding, "offset", 0);
                size = getLong(binding, "size", -1);
            } else {
                bindingIndex = getLong(binding, "index", 0);
                size = getLong(binding, "count", 1);
            }
        }

        return new VariableLayoutReflection(
                name, typeLayout, bindingCategory, bindingIndex, bindingSpace, offset, size);
    }

    /** This member's declared name. May be {@code null} for an unnamed result/return value. */
    public String name() {
        return name;
    }

    /** This member's type, with layout. */
    public TypeLayoutReflection typeLayout() {
        return typeLayout;
    }

    /** Field layouts, if {@link #typeLayout()} is a struct. Equivalent to {@code typeLayout().fields()}. */
    public List<VariableLayoutReflection> fields() {
        return typeLayout == null ? List.of() : typeLayout.fields();
    }

    /**
     * The binding category this member was assigned (e.g. {@code CONSTANT_BUFFER} for a
     * {@code ConstantBuffer<T>} parameter, {@code UNIFORM} for a field inside one).
     * {@code NONE} if this member has no binding info at all.
     */
    public ParameterCategory bindingCategory() {
        return bindingCategory;
    }

    /**
     * The descriptor binding index (register/slot), for a resource-category member (e.g.
     * {@code CONSTANT_BUFFER}, {@code SHADER_RESOURCE}). 0 for a {@code UNIFORM} member — use
     * {@link #offset()} for that case instead.
     */
    public long bindingIndex() {
        return bindingIndex;
    }

    /** The descriptor set / register space. 0 if not applicable or unspecified. */
    public long bindingSpace() {
        return bindingSpace;
    }

    /**
     * The byte offset within an enclosing constant buffer, for a {@code UNIFORM}-category
     * member (e.g. a struct field). 0 for a resource-category member — use
     * {@link #bindingIndex()} for that case instead.
     */
    public long offset() {
        return offset;
    }

    /**
     * For a {@code UNIFORM}-category member, its byte size. For a resource-category member,
     * its descriptor count (1 unless it's an array of resources). -1 if no binding info.
     */
    public long size() {
        return size;
    }
}
