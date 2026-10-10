package com.spectrace.formulation.application;

import com.spectrace.platform.starter.error.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.function.Function;

/** Opaque keyset cursors: the base64url of the last returned key; pages are ordered by that key. */
public final class Cursors {

    private Cursors() {
    }

    public static int limit(Integer limit) {
        int value = limit == null ? 50 : limit;
        if (value < 1 || value > 100) {
            throw ApiException.invalid("limit must be between 1 and 100");
        }
        return value;
    }

    public static String after(String cursor) {
        if (cursor == null) {
            return "";
        }
        try {
            String key = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            if (key.isEmpty() || key.length() > 128) {
                throw new IllegalArgumentException();
            }
            return key;
        } catch (IllegalArgumentException exception) {
            throw ApiException.invalid("cursor is not a cursor returned by this API");
        }
    }

    /** Rows were fetched with {@code limit + 1} to know whether another page exists. */
    public static <R, T> Views.Page<T> page(List<R> rows, int limit, Function<R, String> key, Function<R, T> view) {
        boolean more = rows.size() > limit;
        List<R> visible = more ? rows.subList(0, limit) : rows;
        String next = more ? Base64.getUrlEncoder().withoutPadding()
                .encodeToString(key.apply(visible.getLast()).getBytes(StandardCharsets.UTF_8)) : null;
        return new Views.Page<>(visible.stream().map(view).toList(), next);
    }
}
