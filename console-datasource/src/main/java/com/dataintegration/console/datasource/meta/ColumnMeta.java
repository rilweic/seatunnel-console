package com.dataintegration.console.datasource.meta;

/**
 * A column as the database reports it.
 *
 * @param type database type name, with length for character types, e.g. {@code VARCHAR(64)}
 * @param temporal date, datetime or timestamp; the only kinds usable as an incremental field
 */
public record ColumnMeta(String name, String type, boolean nullable, boolean primaryKey, boolean temporal) {}
