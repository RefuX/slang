package org.shaderslang.wasm.reflection;

import org.shaderslang.wasm.enums.ScalarType;
import org.shaderslang.wasm.enums.TypeKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.shaderslang.wasm.reflection.JsonUtil.*;

/**
 * An un-laid-out type (mirrors {@code TypeReflection} in SlangShaderSharp / {@code
 * slang::TypeReflection}): no binding/offset information, just the type's shape. Appears
 * nested under a {@link TypeLayoutReflection}'s {@link TypeLayoutReflection#resultType()}
 * (e.g. the element type of a {@code StructuredBuffer<T>}) and recursively within that —
 * a resource's result type can itself be a struct, vector, etc.
 */
public final class TypeReflection {
    private final TypeKind kind;
    private final String name;
    private final ScalarType scalarType;
    private final int rowCount;
    private final int columnCount;
    private final long elementCount;
    private final TypeReflection elementType;
    private final List<TypeReflection> fields;
    private final List<String> fieldNames;

    private TypeReflection(
            TypeKind kind,
            String name,
            ScalarType scalarType,
            int rowCount,
            int columnCount,
            long elementCount,
            TypeReflection elementType,
            List<TypeReflection> fields,
            List<String> fieldNames) {
        this.kind = kind;
        this.name = name;
        this.scalarType = scalarType;
        this.rowCount = rowCount;
        this.columnCount = columnCount;
        this.elementCount = elementCount;
        this.elementType = elementType;
        this.fields = fields;
        this.fieldNames = fieldNames;
    }

    static TypeReflection fromJson(Map<String, Object> json) {
        if (json == null) return null;

        TypeKind kind = ReflectionMapping.typeKind(getString(json, "kind"));
        String name = getString(json, "name");
        ScalarType scalarType = json.containsKey("scalarType")
                ? ReflectionMapping.scalarType(getString(json, "scalarType"))
                : null;
        int rowCount = (int) getLong(json, "rowCount", 0);
        int columnCount = (int) getLong(json, "columnCount", 0);
        long elementCount = getLong(json, "elementCount", -1);

        Map<String, Object> elementTypeJson = getObject(json, "elementType");
        if (elementTypeJson == null) elementTypeJson = getObject(json, "resultType");
        TypeReflection elementType = fromJson(elementTypeJson);

        List<TypeReflection> fields = new ArrayList<>();
        List<String> fieldNames = new ArrayList<>();
        for (Object fieldObj : getArray(json, "fields")) {
            Map<String, Object> field = asObject(fieldObj);
            fieldNames.add(getString(field, "name", ""));
            fields.add(fromJson(getObject(field, "type")));
        }

        return new TypeReflection(
                kind, name, scalarType, rowCount, columnCount, elementCount, elementType,
                fields, fieldNames);
    }

    /** The kind of type this is (struct, array, scalar, vector, matrix, resource, ...). */
    public TypeKind kind() {
        return kind;
    }

    /** The type's name, if it has one (e.g. a struct's declared name). May be {@code null}. */
    public String name() {
        return name;
    }

    /** The scalar element type, for {@link TypeKind#SCALAR} (and the leaves of vectors/matrices). */
    public ScalarType scalarType() {
        return scalarType;
    }

    /** Row count, for {@link TypeKind#MATRIX}. 0 if not a matrix. */
    public int rowCount() {
        return rowCount;
    }

    /** Column count, for {@link TypeKind#MATRIX}. 0 if not a matrix. */
    public int columnCount() {
        return columnCount;
    }

    /** Element count, for {@link TypeKind#ARRAY} or {@link TypeKind#VECTOR}. -1 if unbounded/unknown/not applicable. */
    public long elementCount() {
        return elementCount;
    }

    /** Element type, for {@link TypeKind#ARRAY}, {@link TypeKind#VECTOR}, {@link TypeKind#MATRIX}, or a resource's result type. */
    public TypeReflection elementType() {
        return elementType;
    }

    /** Field types, for {@link TypeKind#STRUCT}. Parallel to {@link #fieldNames()}. */
    public List<TypeReflection> fields() {
        return fields;
    }

    /** Field names, for {@link TypeKind#STRUCT}. Parallel to {@link #fields()}. */
    public List<String> fieldNames() {
        return fieldNames;
    }
}
