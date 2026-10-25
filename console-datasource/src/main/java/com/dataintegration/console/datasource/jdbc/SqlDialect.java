package com.dataintegration.console.datasource.jdbc;

/**
 * How a relational database names things in SQL and in SeaTunnel's Jdbc {@code table_path}, so
 * the job wizard can generate queries for any JDBC datasource without knowing which one it is.
 */
public interface SqlDialect {

    /** A quoted identifier, e.g. {@code `orders`} (MySQL) or {@code "orders"} (PostgreSQL). */
    String quote(String identifier);

    /**
     * SeaTunnel's {@code table_path} of a table: {@code namespace.table} where the namespace is the
     * database (MySQL), {@code database.schema.table} where it is a schema (PostgreSQL).
     *
     * @param database the datasource's database, used when the namespace is a schema
     */
    String tablePath(String database, String namespace, String table);

    default String qualified(String namespace, String table) {
        return quote(namespace) + "." + quote(table);
    }

    /** Backquoted identifiers; the namespace is the database. */
    SqlDialect MYSQL = new SqlDialect() {
        @Override
        public String quote(String identifier) {
            return "`" + identifier.replace("`", "``") + "`";
        }

        @Override
        public String tablePath(String database, String namespace, String table) {
            return namespace + "." + table;
        }
    };

    /** Double-quoted identifiers; the namespace is a schema inside the datasource's database. */
    SqlDialect POSTGRESQL = new SqlDialect() {
        @Override
        public String quote(String identifier) {
            return "\"" + identifier.replace("\"", "\"\"") + "\"";
        }

        @Override
        public String tablePath(String database, String namespace, String table) {
            return database + "." + namespace + "." + table;
        }
    };
}
