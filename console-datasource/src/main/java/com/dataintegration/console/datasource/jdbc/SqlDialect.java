package com.dataintegration.console.datasource.jdbc;

/**
 * How a relational database names things in SQL and in SeaTunnel's Jdbc options, so the job
 * wizard can generate configs for any JDBC datasource without knowing which one it is.
 */
public interface SqlDialect {

    /** A quoted identifier, e.g. {@code `orders`} (MySQL) or {@code "orders"} (PostgreSQL). */
    String quote(String identifier);

    /**
     * Whether a job's namespace is a schema inside the datasource's database (PostgreSQL) rather
     * than the database itself (MySQL).
     */
    boolean namespaceIsSchema();

    default String qualified(String namespace, String table) {
        return quote(namespace) + "." + quote(table);
    }

    /**
     * SeaTunnel's {@code table_path} of a table: {@code database.table}, or
     * {@code database.schema.table} where the namespace is a schema.
     *
     * @param database the datasource's database, used when the namespace is a schema
     */
    default String tablePath(String database, String namespace, String table) {
        return namespaceIsSchema() ? database + "." + namespace + "." + table : namespace + "." + table;
    }

    /** Backquoted identifiers; the namespace is the database. */
    SqlDialect MYSQL = new SqlDialect() {
        @Override
        public String quote(String identifier) {
            return "`" + identifier.replace("`", "``") + "`";
        }

        @Override
        public boolean namespaceIsSchema() {
            return false;
        }
    };

    /** Double-quoted identifiers; the namespace is a schema. */
    SqlDialect POSTGRESQL = new SqlDialect() {
        @Override
        public String quote(String identifier) {
            return "\"" + identifier.replace("\"", "\"\"") + "\"";
        }

        @Override
        public boolean namespaceIsSchema() {
            return true;
        }
    };
}
