package org.shaderslang.wasm.diagnostics;

/**
 * One structured diagnostic message, as produced by parsing a {@link CompileResult#diagnostics()}
 * (or {@code SlangModule.declReflectionJson()}/etc. failure message) string via
 * {@link DiagnosticList#parse}.
 *
 * @see org.shaderslang.wasm.CompileResult#diagnostics()
 */
public final class Diagnostic {
    /**
     * Mirrors the four severities the plan's sketch names. Slang's own {@code Severity} enum
     * (see {@code source/compiler-core/slang-diagnostic-sink.h}) additionally has
     * {@code Disable}/{@code Internal}/an "unknown" fallback; {@link DiagnosticList#parse} maps
     * those onto the closest of these four ({@code Internal} and an unrecognised severity word
     * both become {@link #FATAL}) rather than growing this enum to mirror every internal severity
     * a caller is unlikely to ever need to distinguish.
     */
    public enum Severity {
        NOTE,
        WARNING,
        ERROR,
        FATAL
    }

    private final Severity severity;
    private final String code;
    private final String message;
    private final String filePath;
    private final int line;
    private final int column;

    Diagnostic(Severity severity, String code, String message, String filePath, int line, int column) {
        this.severity = severity;
        this.code = code;
        this.message = message;
        this.filePath = filePath;
        this.line = line;
        this.column = column;
    }

    /** This diagnostic's severity. */
    public Severity severity() {
        return severity;
    }

    /** This diagnostic's error code (e.g. {@code "E30015"}), or empty if it has none. */
    public String code() {
        return code;
    }

    /** The diagnostic message text. */
    public String message() {
        return message;
    }

    /** The source file path this diagnostic points at, or empty if it has no location. */
    public String filePath() {
        return filePath;
    }

    /** The 1-based source line this diagnostic points at, or 0 if it has no location. */
    public int line() {
        return line;
    }

    /** The 1-based source column this diagnostic points at, or 0 if it has no location. */
    public int column() {
        return column;
    }

    @Override
    public String toString() {
        return severity + (code.isEmpty() ? "" : "[" + code + "]") + ": " + message
                + (filePath.isEmpty() ? "" : " (" + filePath + ":" + line + ":" + column + ")");
    }
}
