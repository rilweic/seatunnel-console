package com.dataintegration.console.datasource.http;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record HttpPreviewRequest(String path, String method, Map<String, String> params, String body) {

    public HttpPreviewRequest {
        params = params == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(params));
    }
}
