package com.dataintegration.console.server.job.wizard;

import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.common.ErrorCodes;

import tools.jackson.databind.JsonNode;

/**
 * The JSON paths the HTTP wizard writes (§7.13): dot fields, quoted field names and array indices,
 * e.g. {@code $.data['order-id'][0]}. Anything else is refused instead of being skipped.
 */
final class HttpJsonPath {

    private HttpJsonPath() {}

    /** Throws when the path is not one the wizard accepts. */
    static void requireValid(String path) {
        read(null, path);
    }

    /**
     * The node at the path, or null when it is missing. A path without {@code $} is a field of the
     * root, as Jayway reads it.
     */
    static JsonNode read(JsonNode root, String path) {
        if (path == null || path.isBlank() || path.trim().equals("$")) {
            return root;
        }
        String text = path.trim();
        if (!text.startsWith("$")) {
            return root == null ? null : root.get(text);
        }
        JsonNode current = root;
        int position = 1;
        while (position < text.length()) {
            char token = text.charAt(position++);
            if (token == '.') {
                int start = position;
                if (position >= text.length() || !nameStart(text.charAt(position))) {
                    throw invalid(path);
                }
                position++;
                while (position < text.length() && namePart(text.charAt(position))) {
                    position++;
                }
                String name = text.substring(start, position);
                current = current == null ? null : current.get(name);
            } else if (token == '[') {
                if (position >= text.length()) {
                    throw invalid(path);
                }
                char quote = text.charAt(position);
                if (quote == '\'' || quote == '"') {
                    position++;
                    StringBuilder name = new StringBuilder();
                    boolean closed = false;
                    while (position < text.length()) {
                        char c = text.charAt(position++);
                        if (c == '\\') {
                            if (position >= text.length()) {
                                throw invalid(path);
                            }
                            name.append(text.charAt(position++));
                        } else if (c == quote) {
                            closed = true;
                            break;
                        } else {
                            name.append(c);
                        }
                    }
                    if (!closed || position >= text.length() || text.charAt(position++) != ']') {
                        throw invalid(path);
                    }
                    current = current == null ? null : current.get(name.toString());
                } else {
                    int start = position;
                    while (position < text.length() && Character.isDigit(text.charAt(position))) {
                        position++;
                    }
                    if (start == position || position >= text.length() || text.charAt(position++) != ']') {
                        throw invalid(path);
                    }
                    try {
                        int index = Integer.parseInt(text.substring(start, position - 1));
                        current = current == null ? null : current.get(index);
                    } catch (NumberFormatException e) {
                        throw invalid(path);
                    }
                }
            } else {
                throw invalid(path);
            }
        }
        return current;
    }

    private static boolean nameStart(char c) {
        return Character.isLetter(c) || c == '_' || c == '$';
    }

    private static boolean namePart(char c) {
        return nameStart(c) || Character.isDigit(c);
    }

    private static ConsoleException invalid(String path) {
        return new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "JSON 路径不合法：" + path);
    }
}
