package com.dataintegration.console.datasource.jdbc;

import com.dataintegration.console.datasource.DataSourceType;
import com.dataintegration.console.datasource.Props;

import java.util.Map;

public class PostgreSqlDataSourcePlugin extends JdbcDataSourcePlugin {

    @Override
    public DataSourceType type() {
        return DataSourceType.POSTGRESQL;
    }

    /** PostgreSQL connections are always bound to one database, so it is required. */
    @Override
    public String buildUrl(Map<String, String> props) {
        String host = Props.required(props, "host");
        int port = Props.port(props, 5432);
        String database = Props.required(props, "database");
        return withParams("jdbc:postgresql://" + host + ":" + port + "/" + database, props.get("params"));
    }

    @Override
    protected boolean namespaceIsCatalog() {
        return false;
    }

    @Override
    protected boolean isSystemNamespace(String name) {
        return name.startsWith("pg_") || name.equals("information_schema");
    }

    @Override
    protected String primaryKeysSql() {
        return "select kcu.table_name, kcu.column_name from information_schema.table_constraints tc"
                + " join information_schema.key_column_usage kcu on tc.constraint_name = kcu.constraint_name"
                + " and tc.table_schema = kcu.table_schema and tc.table_name = kcu.table_name"
                + " where tc.constraint_type = 'PRIMARY KEY' and tc.table_schema = ? order by kcu.table_name, kcu.ordinal_position";
    }

    @Override
    protected String driverClass() {
        return "org.postgresql.Driver";
    }

    @Override
    protected Map<String, String> timeoutProperties(int seconds) {
        String value = String.valueOf(seconds);
        return Map.of("connectTimeout", value, "socketTimeout", value, "loginTimeout", value);
    }
}
