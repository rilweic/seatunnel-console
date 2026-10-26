package com.dataintegration.console.datasource;

import com.dataintegration.console.datasource.cdc.CdcSupport;
import com.dataintegration.console.datasource.meta.ColumnMeta;
import com.dataintegration.console.datasource.meta.TableMeta;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One implementation per {@link DataSourceType} (docs/design.md §6.1).
 *
 * <p>{@code props} always holds decrypted connection properties and must never be logged.
 */
public interface DataSourcePlugin {

    DataSourceType type();

    /** What this type can do, so callers never compare type names (docs/design.md §6.1). */
    DataSourceCapabilities capabilities();

    /**
     * Where the datasource points, shown in the datasource list, e.g. {@code db1:3306/shop}.
     *
     * @param config the saved properties without the secret ones
     */
    String address(Map<String, String> config);

    /** Property names stored encrypted and never returned to the browser, e.g. {@code password}. */
    Set<String> secretKeys();

    /** Rejects incomplete or malformed properties before they are saved or used. */
    void validate(Map<String, String> props);

    /** Opens a connection and reports whether it succeeded, without throwing. */
    ConnectionTestResult testConnection(Map<String, String> props);

    /**
     * Translates datasource properties into the option keys a given SeaTunnel connector expects,
     * e.g. MySQL for {@code Jdbc} gives url/driver/username/password. Used only at submit time.
     *
     * @param connector SeaTunnel connector identifier, such as {@code Jdbc} or {@code Elasticsearch}
     * @throws com.dataintegration.console.common.ConsoleException when this datasource type cannot
     *     back that connector
     */
    Map<String, Object> toConnectorOptions(String connector, Map<String, String> props);

    /**
     * Counts rows for validation.
     *
     * @throws com.dataintegration.console.common.ConsoleException with
     *     {@code DATASOURCE_CONNECT_FAILED} when the count cannot be taken
     */
    long count(Map<String, String> props, CountRequest request);

    /** CDC checks and replication resources; null when the type cannot be read by a real-time job. */
    default CdcSupport cdcSupport() {
        return null;
    }

    /** Databases (MySQL) or schemas (PostgreSQL) a job can choose from; empty when there is no such level (ES). */
    List<String> namespaces(Map<String, String> props);

    /** Tables (or ES indices) in a namespace, with their primary keys. */
    List<TableMeta> tables(Map<String, String> props, String namespace);

    /** Columns of one table (or the top-level fields of an ES index mapping), in table order. */
    List<ColumnMeta> columns(Map<String, String> props, String namespace, String table);
}
