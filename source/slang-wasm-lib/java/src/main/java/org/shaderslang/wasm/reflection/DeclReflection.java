package org.shaderslang.wasm.reflection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.shaderslang.wasm.reflection.JsonUtil.*;

/**
 * A module-level declaration (mirrors {@code DeclReflection} in SlangShaderSharp / {@code
 * slang::DeclReflection}): a struct, function, variable, enum, namespace, or generic declared at
 * module scope, with its children recursively (a struct's fields, a namespace's members, ...).
 * Parse it from {@link org.shaderslang.wasm.SlangCompiler.SlangModule#declReflectionJson()} via
 * {@link #parse(String)} — unlike {@link ShaderReflection}, this requires no compile to a target;
 * it reflects the checked module itself.
 */
public final class DeclReflection {
    /**
     * The kind of declaration. Mirrors {@code slang::DeclReflection::Kind} exactly — note this is
     * narrower than SlangShaderSharp's sketch of this enum (no separate {@code CLASS},
     * {@code INTERFACE}, {@code TYPEDEF}, {@code GENERIC_TYPE_PARAM}, or {@code ENUM_CASE}
     * constant): the real reflection API folds interfaces and similar declarations into
     * {@link #UNSUPPORTED} or {@link #STRUCT} rather than giving each its own kind.
     */
    public enum Kind {
        UNSUPPORTED,
        STRUCT,
        FUNCTION,
        MODULE,
        GENERIC,
        VARIABLE,
        NAMESPACE,
        ENUM;

        static Kind fromJson(String json) {
            if (json == null) return UNSUPPORTED;
            switch (json) {
                case "struct": return STRUCT;
                case "function": return FUNCTION;
                case "module": return MODULE;
                case "generic": return GENERIC;
                case "variable": return VARIABLE;
                case "namespace": return NAMESPACE;
                case "enum": return ENUM;
                default: return UNSUPPORTED;
            }
        }
    }

    private final String name;
    private final Kind kind;
    private final List<DeclReflection> children;

    private DeclReflection(String name, Kind kind, List<DeclReflection> children) {
        this.name = name;
        this.kind = kind;
        this.children = children;
    }

    /**
     * Parse {@code json} (the output of {@code slang_wasm_module_decl_reflection_json}, as
     * returned by {@link org.shaderslang.wasm.SlangCompiler.SlangModule#declReflectionJson()})
     * into a typed declaration tree, rooted at the module itself ({@link Kind#MODULE}).
     *
     * @throws IllegalArgumentException if {@code json} is not well-formed
     */
    public static DeclReflection parse(String json) {
        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) Json.parse(json);
        return fromJson(root);
    }

    private static DeclReflection fromJson(Map<String, Object> json) {
        String name = getString(json, "name", "");
        Kind kind = Kind.fromJson(getString(json, "kind"));

        List<DeclReflection> children = new ArrayList<>();
        for (Object childObj : getArray(json, "children")) {
            children.add(fromJson(asObject(childObj)));
        }

        return new DeclReflection(name, kind, children);
    }

    /** This declaration's name. Empty for an unnamed declaration. */
    public String name() {
        return name;
    }

    /** The kind of declaration this is. */
    public Kind kind() {
        return kind;
    }

    /**
     * This declaration's children — a struct's fields, a namespace's members, the module's
     * top-level declarations, and so on. Empty for a leaf declaration (e.g. a simple variable).
     */
    public List<DeclReflection> children() {
        return children;
    }

    /** The first direct child with the given kind and name, if any. */
    public java.util.Optional<DeclReflection> child(Kind kind, String name) {
        return children.stream()
                .filter(c -> c.kind == kind && name.equals(c.name))
                .findFirst();
    }

    /** All direct children with the given kind. */
    public List<DeclReflection> childrenOfKind(Kind kind) {
        List<DeclReflection> result = new ArrayList<>();
        for (DeclReflection child : children) {
            if (child.kind == kind) result.add(child);
        }
        return result;
    }
}
