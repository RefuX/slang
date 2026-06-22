package org.shaderslang.wasm.reflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal recursive-descent JSON parser for the output of {@code spReflection_ToJson}.
 *
 * This is not a general-purpose JSON library: it exists only to parse reflection JSON
 * produced by {@code source/slang/slang-reflection-json.cpp}, a single, controlled
 * producer, so it skips features that producer never emits (surrogate pairs, exponent
 * notation edge cases beyond what Slang itself writes, comments, trailing commas).
 * Object keys parse into a {@code LinkedHashMap} so member order is preserved for
 * anything that wants to print it back out for debugging.
 */
final class Json {
    private final String text;
    private int pos;

    private Json(String text) {
        this.text = text;
    }

    /** Parse {@code text} as a single JSON value (object, array, string, number, boolean, or null). */
    static Object parse(String text) {
        Json parser = new Json(text);
        parser.skipWhitespace();
        Object value = parser.parseValue();
        parser.skipWhitespace();
        return value;
    }

    private Object parseValue() {
        skipWhitespace();
        char c = text.charAt(pos);
        switch (c) {
            case '{':
                return parseObject();
            case '[':
                return parseArray();
            case '"':
                return parseString();
            case 't':
                expect("true");
                return Boolean.TRUE;
            case 'f':
                expect("false");
                return Boolean.FALSE;
            case 'n':
                expect("null");
                return null;
            default:
                return parseNumber();
        }
    }

    private Map<String, Object> parseObject() {
        Map<String, Object> obj = new LinkedHashMap<>();
        pos++; // '{'
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            return obj;
        }
        while (true) {
            skipWhitespace();
            String key = parseString();
            skipWhitespace();
            expect(":");
            Object value = parseValue();
            obj.put(key, value);
            skipWhitespace();
            char c = text.charAt(pos++);
            if (c == '}') break;
            if (c != ',') throw malformed("expected ',' or '}'");
        }
        return obj;
    }

    private List<Object> parseArray() {
        List<Object> arr = new ArrayList<>();
        pos++; // '['
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            return arr;
        }
        while (true) {
            arr.add(parseValue());
            skipWhitespace();
            char c = text.charAt(pos++);
            if (c == ']') break;
            if (c != ',') throw malformed("expected ',' or ']'");
        }
        return arr;
    }

    private String parseString() {
        skipWhitespace();
        expect("\"");
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = text.charAt(pos++);
            if (c == '"') break;
            if (c == '\\') {
                char esc = text.charAt(pos++);
                switch (esc) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u':
                        sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                        pos += 4;
                        break;
                    default:
                        throw malformed("unknown escape \\" + esc);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private Object parseNumber() {
        int start = pos;
        if (peek() == '-') pos++;
        while (pos < text.length() && Character.isDigit(text.charAt(pos))) pos++;
        boolean isDouble = false;
        if (pos < text.length() && text.charAt(pos) == '.') {
            isDouble = true;
            pos++;
            while (pos < text.length() && Character.isDigit(text.charAt(pos))) pos++;
        }
        if (pos < text.length() && (text.charAt(pos) == 'e' || text.charAt(pos) == 'E')) {
            isDouble = true;
            pos++;
            if (text.charAt(pos) == '+' || text.charAt(pos) == '-') pos++;
            while (pos < text.length() && Character.isDigit(text.charAt(pos))) pos++;
        }
        String num = text.substring(start, pos);
        return isDouble ? (Object) Double.parseDouble(num) : (Object) Long.parseLong(num);
    }

    private char peek() {
        return text.charAt(pos);
    }

    private void expect(String literal) {
        if (!text.startsWith(literal, pos)) {
            throw malformed("expected \"" + literal + "\"");
        }
        pos += literal.length();
    }

    private void skipWhitespace() {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) pos++;
    }

    private RuntimeException malformed(String reason) {
        int contextStart = Math.max(0, pos - 20);
        int contextEnd = Math.min(text.length(), pos + 20);
        return new IllegalArgumentException(
                "Malformed reflection JSON at offset " + pos + ": " + reason
                + " — near \"" + text.substring(contextStart, contextEnd) + "\"");
    }
}
