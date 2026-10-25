package com.dataintegration.console.datasource.jdbc;

import com.dataintegration.console.datasource.DataSourceCapabilities;
import com.dataintegration.console.datasource.DataSourceType;
import com.dataintegration.console.datasource.Props;

import java.util.Map;
import java.util.Set;

public class MySqlDataSourcePlugin extends JdbcDataSourcePlugin {

    @Override
    public DataSourceType type() {
        return DataSourceType.MYSQL;
    }

    @Override
    public DataSourceCapabilities capabilities() {
        return new DataSourceCapabilities("MySQL", JDBC_CONNECTOR, JDBC_CONNECTOR, true, true, true, 8, false, true);
    }

    @Override
    public SqlDialect dialect() {
        return SqlDialect.MYSQL;
    }

    /** Database is optional for MySQL, since jobs can address tables as {@code db.table}. */
    @Override
    public String buildUrl(Map<String, String> props) {
        String host = Props.required(props, "host");
        int port = Props.port(props, 3306);
        String database = Props.optional(props, "database");
        return withParams("jdbc:mysql://" + host + ":" + port + "/" + (database == null ? "" : database), props.get("params"));
    }

    private static final Set<String> SYSTEM = Set.of("information_schema", "mysql", "performance_schema", "sys");

    @Override
    protected boolean namespaceIsCatalog() {
        return true;
    }

    @Override
    protected boolean isSystemNamespace(String name) {
        return SYSTEM.contains(name.toLowerCase());
    }

    @Override
    protected String primaryKeysSql() {
        return "select table_name, column_name from information_schema.key_column_usage"
                + " where table_schema = ? and constraint_name = 'PRIMARY' order by table_name, ordinal_position";
    }

    @Override
    protected String driverClass() {
        return "com.mysql.cj.jdbc.Driver";
    }

    @Override
    protected Map<String, String> timeoutProperties(int seconds) {
        String millis = String.valueOf(seconds * 1000);
        return Map.of("connectTimeout", millis, "socketTimeout", millis);
    }
}
