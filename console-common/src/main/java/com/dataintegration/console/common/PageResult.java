package com.dataintegration.console.common;

import java.util.List;
import java.util.function.Function;

/** One page of a list endpoint. {@code page} is 1-based. */
public record PageResult<T>(List<T> items, long total, long page, long size) {

    public <R> PageResult<R> map(Function<T, R> mapper) {
        return new PageResult<>(items.stream().map(mapper).toList(), total, page, size);
    }
}
