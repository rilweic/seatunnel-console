package com.dataintegration.console.server.job.wizard.source;

import java.util.List;
import java.util.Map;

/**
 * The HTTP source's part of a wizard form ({@code source.spec}, docs/design.md §7.13): one endpoint
 * and the fields confirmed from its response preview.
 *
 * @param contentField JSON path of the data array in the response
 * @param primaryKeys fields that identify a record; without them rows are only appended
 */
public record HttpSpec(
        String path,
        Method method,
        Map<String, String> params,
        String body,
        Paging paging,
        String contentField,
        List<Field> fields,
        List<String> primaryKeys) {

    public enum Method {
        GET,
        POST
    }

    /** @param jsonPath relative to a record, e.g. {@code $.meta.code}; null for a top-level field of that name */
    public record Field(String name, String type, String jsonPath, Integer precision, Integer scale) {

        public Field(String name, String type, String jsonPath) {
            this(name, type, jsonPath, null, null);
        }
    }

    public record Paging(
            PagingMode mode,
            String pageField,
            PageLocation pageLocation,
            Long startPageNumber,
            String pageSizeField,
            Integer pageSize,
            Long totalPageSize,
            String cursorField,
            String cursorResponseField) {}

    public enum PagingMode {
        NONE,
        PAGE_NUMBER,
        CURSOR
    }

    public enum PageLocation {
        PARAMS,
        BODY
    }
}
