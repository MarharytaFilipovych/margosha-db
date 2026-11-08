package com.margosha.api.util;

import java.util.UUID;
import java.util.regex.Pattern;

public final class TraceIds {

    public static final String HEADER = "X-Trace-Id";
    public static final String MDC_KEY = "traceId";
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9-]{1,64}");

    private TraceIds() { }

    public static String fromHeader(String value) {
        return isValid(value) ? value : newId();
    }

    public static boolean isValid(String value) {
        return value != null && VALID.matcher(value).matches();
    }

    public static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
