package com.dataintegration.console.common;

/** Error code ranges: 1xxx generic, 2xxx engine, 3xxx datasource, 4xxx scheduler. */
public final class ErrorCodes {

    public static final int INTERNAL_ERROR = 1000;
    public static final int INVALID_ARGUMENT = 1001;
    public static final int UNAUTHENTICATED = 1002;
    public static final int FORBIDDEN = 1003;
    /** Logged in with an initial or reset password; only the password change is allowed. */
    public static final int PASSWORD_CHANGE_REQUIRED = 1005;
    public static final int NOT_FOUND = 1004;
    public static final int CONFLICT = 1009;

    public static final int ENGINE_UNAVAILABLE = 2000;
    public static final int ENGINE_REQUEST_FAILED = 2001;
    /**
     * A request may have reached the engine but no answer came back (a timeout after sending, an
     * interrupt). Unlike {@link #ENGINE_UNAVAILABLE}, repeating a submission is not known to be safe.
     */
    public static final int ENGINE_RESPONSE_LOST = 2002;

    public static final int DATASOURCE_CONNECT_FAILED = 3000;

    public static final int SCHEDULER_UNAVAILABLE = 4000;
    public static final int SCHEDULER_REQUEST_FAILED = 4001;
    public static final int SCHEDULER_DISABLED = 4002;
    /** The XXL-JOB job a console job points at no longer exists, e.g. deleted in the admin UI. */
    public static final int SCHEDULER_JOB_MISSING = 4003;

    private ErrorCodes() {}
}
