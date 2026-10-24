package com.dataintegration.console.server.web;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataintegration.console.common.PageResult;

/** Converts between request paging parameters, MyBatis-Plus pages and {@link PageResult}. */
public final class Pages {

    public static final int MAX_SIZE = 200;

    private Pages() {}

    public static <T> Page<T> of(Integer page, Integer size) {
        long p = page == null || page < 1 ? 1 : page;
        long s = size == null || size < 1 ? 20 : Math.min(size, MAX_SIZE);
        return Page.of(p, s);
    }

    public static <T> PageResult<T> result(Page<T> page) {
        return new PageResult<>(page.getRecords(), page.getTotal(), page.getCurrent(), page.getSize());
    }
}
