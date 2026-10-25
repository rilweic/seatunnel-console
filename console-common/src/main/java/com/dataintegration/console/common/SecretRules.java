package com.dataintegration.console.common;

import java.util.Locale;
import java.util.regex.Pattern;

/** Credential-like option and header names, shared by datasource validation and job config masking. */
public final class SecretRules {

    private static final Pattern SECRET_KEY =
            Pattern.compile("(?i).*(password|passwd|secret|token|credential|api_?key|access_?key).*");

    private SecretRules() {}

    public static boolean isSecretKey(String key) {
        return SECRET_KEY.matcher(key).matches();
    }

    public static boolean isSecretHeader(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        return lower.equals("authorization") || lower.equals("cookie") || lower.equals("x-api-key")
                || lower.contains("key") || lower.contains("token");
    }
}
