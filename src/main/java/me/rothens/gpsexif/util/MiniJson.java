package me.rothens.gpsexif.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader for ExifTool's {@code -j} output. Objects become {@link Map}s (in document order), arrays
 * {@link List}s, numbers {@link Double}s, plus {@link String}, {@link Boolean} and {@code null}.
 */
public final class MiniJson {

    private final String text;
    private int pos;

    private MiniJson(String text) {
        this.text = text;
    }

    /** @throws IllegalArgumentException if {@code text} isn't valid JSON */
    public static Object parse(String text) {
        MiniJson parser = new MiniJson(text);
        Object value = parser.value();
        parser.whitespace();
        if (parser.pos != text.length()) {
            throw parser.error("Unexpected trailing content");
        }
        return value;
    }

    private Object value() {
        whitespace();
        if (pos >= text.length()) {
            throw error("Unexpected end");
        }
        char c = text.charAt(pos);
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Map<String, Object> object() {
        Map<String, Object> map = new LinkedHashMap<>();
        pos++;
        whitespace();
        if (peek('}')) {
            pos++;
            return map;
        }
        while (true) {
            whitespace();
            if (!peek('"')) {
                throw error("Expected a key");
            }
            String key = string();
            whitespace();
            expect(':');
            map.put(key, value());
            whitespace();
            if (peek(',')) {
                pos++;
            } else {
                expect('}');
                return map;
            }
        }
    }

    private List<Object> array() {
        List<Object> list = new ArrayList<>();
        pos++;
        whitespace();
        if (peek(']')) {
            pos++;
            return list;
        }
        while (true) {
            list.add(value());
            whitespace();
            if (peek(',')) {
                pos++;
            } else {
                expect(']');
                return list;
            }
        }
    }

    private String string() {
        pos++; // opening quote
        StringBuilder sb = new StringBuilder();
        while (pos < text.length()) {
            char c = text.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (pos >= text.length()) {
                break;
            }
            char e = text.charAt(pos++);
            switch (e) {
                case '"', '\\', '/' -> sb.append(e);
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    if (pos + 4 > text.length()) {
                        throw error("Bad unicode escape");
                    }
                    try {
                        sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                    } catch (NumberFormatException ex) {
                        throw error("Bad unicode escape");
                    }
                    pos += 4;
                }
                default -> throw error("Bad escape \\" + e);
            }
        }
        throw error("Unterminated string");
    }

    private Double number() {
        int start = pos;
        while (pos < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0) {
            pos++;
        }
        try {
            return Double.parseDouble(text.substring(start, pos));
        } catch (NumberFormatException e) {
            pos = start;
            throw error("Unexpected character '" + text.charAt(start) + "'");
        }
    }

    private Object literal(String word, Object value) {
        if (!text.startsWith(word, pos)) {
            throw error("Unexpected token");
        }
        pos += word.length();
        return value;
    }

    private void whitespace() {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
            pos++;
        }
    }

    private boolean peek(char c) {
        return pos < text.length() && text.charAt(pos) == c;
    }

    private void expect(char c) {
        if (!peek(c)) {
            throw error("Expected '" + c + "'");
        }
        pos++;
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException(message + " at position " + pos);
    }
}
