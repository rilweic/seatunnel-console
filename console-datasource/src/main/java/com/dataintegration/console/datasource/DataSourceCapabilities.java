package com.dataintegration.console.datasource;

/**
 * What a datasource type can do, declared by its plugin so the wizard, the datasource page, health
 * checks and validation ask instead of comparing type names (docs/design.md §6.1).
 *
 * @param label name shown to users, e.g. {@code PostgreSQL}
 * @param sourceConnector SeaTunnel source connector the job wizard generates, e.g. {@code Jdbc};
 *     null when the wizard cannot read from this type (it may still be used in scripts)
 * @param sinkConnector SeaTunnel sink connector the job wizard generates; null when the wizard
 *     cannot write to this type
 * @param namespaces tables sit in a database or schema that jobs pick first (MySQL, PostgreSQL);
 *     false when they sit directly under the datasource (Elasticsearch indices) or there are none
 * @param multiTable one wizard job may read several of its tables
 * @param countable rows can be counted, for row-count validation
 * @param maxParallelism highest parallelism a wizard job reading from it may use
 * @param lowerCaseNames table names must be lower case (Elasticsearch indices)
 * @param jdbcDriver SeaTunnel workers need this type's JDBC driver, checked by the cluster diagnostics
 */
public record DataSourceCapabilities(
        String label,
        String sourceConnector,
        String sinkConnector,
        boolean namespaces,
        boolean multiTable,
        boolean countable,
        int maxParallelism,
        boolean lowerCaseNames,
        boolean jdbcDriver) {

    public boolean wizardSource() {
        return sourceConnector != null;
    }

    public boolean wizardTarget() {
        return sinkConnector != null;
    }
}
