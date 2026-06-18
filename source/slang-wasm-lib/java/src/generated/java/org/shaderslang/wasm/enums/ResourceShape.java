// THIS FILE IS GENERATED — DO NOT EDIT.
// Source: include/slang.h
// Generator: source/slang-wasm-lib/tools/generate-slang-bindings.py
// Re-run: python source/slang-wasm-lib/tools/generate-slang-bindings.py  (or cmake --preset default)


package org.shaderslang.wasm.enums;

/** Resource shape. Mirrors {@code SlangResourceShape} in slang.h. */
public enum ResourceShape {
    RESOURCE_BASE_SHAPE_MASK(15),
    RESOURCE_NONE(0),
    TEXTURE_1D(1),
    TEXTURE_2D(2),
    TEXTURE_3D(3),
    TEXTURE_CUBE(4),
    TEXTURE_BUFFER(5),
    STRUCTURED_BUFFER(6),
    BYTE_ADDRESS_BUFFER(7),
    RESOURCE_UNKNOWN(8),
    ACCELERATION_STRUCTURE(9),
    TEXTURE_SUBPASS(10),
    RESOURCE_EXT_SHAPE_MASK(496),
    TEXTURE_FEEDBACK_FLAG(16),
    TEXTURE_SHADOW_FLAG(32),
    TEXTURE_ARRAY_FLAG(64),
    TEXTURE_MULTISAMPLE_FLAG(128),
    TEXTURE_COMBINED_FLAG(256);

    public final int value;

    ResourceShape(int value) {
        this.value = value;
    }

    /** Return the ResourceShape constant for the given integer value. */
    public static ResourceShape fromValue(int v) {
        for (ResourceShape t : values()) if (t.value == v) return t;
        throw new IllegalArgumentException("Unknown ResourceShape value: " + v);
    }
}
