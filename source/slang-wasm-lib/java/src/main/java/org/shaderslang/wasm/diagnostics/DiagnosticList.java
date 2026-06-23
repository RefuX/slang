package org.shaderslang.wasm.diagnostics;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A parsed list of {@link Diagnostic}s, as produced by Slang's "rich" diagnostic renderer
 * (see {@code source/compiler-core/slang-rich-diagnostics-render.cpp}) — the format every
 * {@link org.shaderslang.wasm.CompileResult#diagnostics()} string is in. Replaces searching
 * that raw text by hand with structured severity/code/location access.
 *
 * <p>Each diagnostic in the raw text looks like:
 * <pre>{@code
 * error[E30015]: undefined identifier
 *  --> broken:1:15
 *   |
 * 1 | void main() { undefinedFunction(); }
 *   |               ^^^^^^^^^^^^^^^^^ undefined identifier 'undefinedFunction'.
 * --'
 * }</pre>
 * Only the header line ({@code <severity>[E<code>]: <message>}) and the immediately-following
 * location line ({@code --> <path>:<line>:<col>}) are parsed; the source snippet/underline lines
 * in between are not retained on {@link Diagnostic} (the message and location already identify
 * what and where — re-deriving the snippet from a {@link Diagnostic} alone is not this class's
 * job). Lines that don't start a recognised header (e.g. the wrapping
 * {@code "abort compilation: fatal error[...]"} message some failure paths add) are skipped
 * rather than mis-parsed.
 */
public final class DiagnosticList {
    private static final Pattern HEADER = Pattern.compile(
            "^(internal error|unknown error|fatal error|warning|error|note): (.*)$");
    private static final Pattern HEADER_WITH_CODE = Pattern.compile(
            "^(internal error|unknown error|fatal error|warning|error|note)\\[E(\\d+)\\]: (.*)$");
    private static final Pattern LOCATION = Pattern.compile("^\\s*-->\\s*(.+):(\\d+):(\\d+)\\s*$");

    private final List<Diagnostic> diagnostics;

    private DiagnosticList(List<Diagnostic> diagnostics) {
        this.diagnostics = diagnostics;
    }

    /** Parse {@code rawText} (a {@code diagnostics()} string) into a structured list. */
    public static DiagnosticList parse(String rawText) {
        List<Diagnostic> result = new ArrayList<>();
        String[] lines = rawText.split("\n", -1);

        for (int i = 0; i < lines.length; i++) {
            Diagnostic.Severity severity;
            String code;
            String message;

            Matcher withCode = HEADER_WITH_CODE.matcher(lines[i]);
            Matcher noCode = HEADER.matcher(lines[i]);
            if (withCode.matches()) {
                severity = severityOf(withCode.group(1));
                code = "E" + withCode.group(2);
                message = withCode.group(3);
            } else if (noCode.matches()) {
                severity = severityOf(noCode.group(1));
                code = "";
                message = noCode.group(2);
            } else {
                continue;
            }

            String filePath = "";
            int line = 0;
            int column = 0;
            if (i + 1 < lines.length) {
                Matcher loc = LOCATION.matcher(lines[i + 1]);
                if (loc.matches()) {
                    filePath = loc.group(1);
                    line = Integer.parseInt(loc.group(2));
                    column = Integer.parseInt(loc.group(3));
                }
            }

            result.add(new Diagnostic(severity, code, message, filePath, line, column));
        }

        return new DiagnosticList(result);
    }

    private static Diagnostic.Severity severityOf(String word) {
        switch (word) {
            case "note": return Diagnostic.Severity.NOTE;
            case "warning": return Diagnostic.Severity.WARNING;
            case "error": return Diagnostic.Severity.ERROR;
            case "fatal error": return Diagnostic.Severity.FATAL;
            // Internal/unrecognised severities collapse to FATAL — see Diagnostic.Severity's doc.
            default: return Diagnostic.Severity.FATAL;
        }
    }

    /** Every diagnostic parsed from the raw text, in source order. */
    public List<Diagnostic> diagnostics() {
        return diagnostics;
    }

    /** True if any parsed diagnostic is {@link Diagnostic.Severity#ERROR} or {@link Diagnostic.Severity#FATAL}. */
    public boolean hasErrors() {
        for (Diagnostic d : diagnostics) {
            if (d.severity() == Diagnostic.Severity.ERROR || d.severity() == Diagnostic.Severity.FATAL) {
                return true;
            }
        }
        return false;
    }
}
