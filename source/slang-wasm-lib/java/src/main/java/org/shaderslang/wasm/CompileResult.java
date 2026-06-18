package org.shaderslang.wasm;

/**
 * Immutable result of a {@link SlangCompiler#compile} call.
 *
 * All byte arrays are copies taken at compile time; they remain valid after
 * the result is returned and are independent of the compiler instance lifetime.
 */
public final class CompileResult {

    private final boolean succeeded;
    private final byte[] code;
    private final String reflectionJson;
    private final String diagnostics;

    CompileResult(boolean succeeded, byte[] code, String reflectionJson, String diagnostics) {
        this.succeeded = succeeded;
        this.code = code;
        this.reflectionJson = reflectionJson;
        this.diagnostics = diagnostics;
    }

    /** Returns true if compilation produced target code. */
    public boolean succeeded() { return succeeded; }

    /**
     * Compiled target code bytes (e.g. SPIR-V words). Non-empty only when
     * {@link #succeeded()} is true.
     */
    public byte[] code() { return code; }

    /**
     * Reflection data serialized as a JSON string. Non-empty only when
     * {@link #succeeded()} is true and the layout was serializable.
     */
    public String reflectionJson() { return reflectionJson; }

    /**
     * Concatenated diagnostic messages (warnings and/or errors). May be
     * non-empty even on success (warnings). Always non-empty on failure.
     */
    public String diagnostics() { return diagnostics; }
}
