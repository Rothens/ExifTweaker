package me.rothens.gpsexif.util;

import java.io.IOException;
import java.io.PushbackReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Reads a large JSON document one array element at a time, so files of hundreds of megabytes (e.g. a Google
 * location history) never have to be in memory at once. The elements of a top-level array, and of the arrays that
 * are values of the top-level object's keys, are handed over one by one, parsed like {@link MiniJson} does (maps,
 * lists, strings, doubles, booleans, null). Everything else at the top level is skipped.
 */
public final class JsonElements {

    private final PushbackReader in;
    private long position;

    private JsonElements(Reader reader) {
        this.in = new PushbackReader(reader, 2);
    }

    /**
     * Calls {@code consumer} with the key of the array (or {@code null} for a top-level array) and each element.
     *
     * @throws IOException if reading fails or the document isn't valid JSON
     */
    public static void forEach(Reader reader, BiConsumer<String, Object> consumer) throws IOException {
        JsonElements parser = new JsonElements(reader);
        int c = parser.peekNonSpace();
        if (c == '[') {
            parser.array(null, consumer);
        } else if (c == '{') {
            parser.read();
            if (parser.peekNonSpace() == '}') {
                parser.read();
                return;
            }
            while (true) {
                String key = parser.string();
                parser.expect(':');
                if (parser.peekNonSpace() == '[') {
                    parser.array(key, consumer);
                } else {
                    parser.value(); // small things at the top level
                }
                int next = parser.readNonSpace();
                if (next == '}') {
                    return;
                }
                if (next != ',') {
                    throw parser.error("Expected , or }");
                }
            }
        } else {
            throw parser.error("Expected { or [");
        }
    }

    private void array(String key, BiConsumer<String, Object> consumer) throws IOException {
        expect('[');
        if (peekNonSpace() == ']') {
            read();
            return;
        }
        while (true) {
            consumer.accept(key, value());
            int next = readNonSpace();
            if (next == ']') {
                return;
            }
            if (next != ',') {
                throw error("Expected , or ]");
            }
        }
    }

    private Object value() throws IOException {
        int c = peekNonSpace();
        return switch (c) {
            case '{' -> object();
            case '[' -> list();
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            case -1 -> throw error("Unexpected end");
            default -> number();
        };
    }

    private Map<String, Object> object() throws IOException {
        expect('{');
        Map<String, Object> map = new LinkedHashMap<>();
        if (peekNonSpace() == '}') {
            read();
            return map;
        }
        while (true) {
            String key = string();
            expect(':');
            map.put(key, value());
            int next = readNonSpace();
            if (next == '}') {
                return map;
            }
            if (next != ',') {
                throw error("Expected , or }");
            }
        }
    }

    private List<Object> list() throws IOException {
        expect('[');
        List<Object> list = new ArrayList<>();
        if (peekNonSpace() == ']') {
            read();
            return list;
        }
        while (true) {
            list.add(value());
            int next = readNonSpace();
            if (next == ']') {
                return list;
            }
            if (next != ',') {
                throw error("Expected , or ]");
            }
        }
    }

    private String string() throws IOException {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            int c = read();
            if (c == -1) {
                throw error("Unterminated string");
            }
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\') {
                int e = read();
                switch (e) {
                    case '"', '\\', '/' -> sb.append((char) e);
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        char[] hex = new char[4];
                        for (int i = 0; i < 4; i++) {
                            int h = read();
                            if (h == -1) {
                                throw error("Unterminated escape");
                            }
                            hex[i] = (char) h;
                        }
                        try {
                            sb.append((char) Integer.parseInt(new String(hex), 16));
                        } catch (NumberFormatException ex) {
                            throw error("Bad \\u escape");
                        }
                    }
                    default -> throw error("Bad escape");
                }
            } else {
                sb.append((char) c);
            }
        }
    }

    private Double number() throws IOException {
        StringBuilder sb = new StringBuilder();
        while (true) {
            int c = in.read();
            if (c != -1 && (Character.isDigit(c) || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E')) {
                sb.append((char) c);
                position++;
            } else {
                if (c != -1) {
                    in.unread(c);
                }
                break;
            }
        }
        try {
            return Double.parseDouble(sb.toString());
        } catch (NumberFormatException e) {
            throw error("Bad number '" + sb + "'");
        }
    }

    private Object literal(String word, Object value) throws IOException {
        for (int i = 0; i < word.length(); i++) {
            if (read() != word.charAt(i)) {
                throw error("Expected " + word);
            }
        }
        return value;
    }

    private void expect(char c) throws IOException {
        if (readNonSpace() != c) {
            throw error("Expected " + c);
        }
    }

    private int read() throws IOException {
        int c = in.read();
        if (c != -1) {
            position++;
        }
        return c;
    }

    private int readNonSpace() throws IOException {
        int c;
        do {
            c = read();
        } while (c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == '﻿');
        return c;
    }

    private int peekNonSpace() throws IOException {
        int c = readNonSpace();
        if (c != -1) {
            in.unread(c);
            position--;
        }
        return c;
    }

    private IOException error(String message) {
        return new IOException("Not valid JSON: " + message + " at character " + position);
    }
}
