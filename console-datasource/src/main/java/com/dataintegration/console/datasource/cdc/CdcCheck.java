package com.dataintegration.console.datasource.cdc;

/**
 * One condition a database must meet before a real-time job can read it (docs/design.md §12 P2,
 * 前置条件). The console only reports; {@code fix} is SQL or a setting for the DBA to apply.
 *
 * @param item what is checked, e.g. {@code binlog_format}
 * @param actual what the database has now
 * @param fix how to meet it; null when it is met
 */
public record CdcCheck(String item, Level level, String actual, String message, String fix) {

    public enum Level {
        OK,
        WARN,
        ERROR
    }

    public static CdcCheck ok(String item, String actual, String message) {
        return new CdcCheck(item, Level.OK, actual, message, null);
    }
}
