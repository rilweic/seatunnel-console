package com.dataintegration.console.datasource.http;

public record HttpPreviewResponse(int status, long elapsedMillis, String body) {}
