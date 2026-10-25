package com.dataintegration.console.datasource.jdbc;

import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.common.ErrorCodes;
import com.dataintegration.console.datasource.ConnectionTestResult;
import com.dataintegration.console.datasource.CountRequest;
import com.dataintegration.console.datasource.meta.ColumnMeta;
import com.dataintegration.console.datasource.meta.TableMeta;
import com.dataintegration.console.datasource.DataSourcePlugin;
import com.dataintegration.console.datasource.Props;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Shared base for relational databases reached over JDBC. Properties: host, port, database,
 * username, password (secret) and optional params appended to the JDBC URL.
 */
public abstract class JdbcDataSourcePlugin implements DataSourcePlugin {

    /** SeaTunnel's generic JDBC connector (options verified against 3.0.0 JdbcCommonOptions). */
    public static final String JDBC_CONNECTOR = "Jdbc";

    private static final int CONNECT_TIMEOUT_SECONDS = 5;

    /** How this database quotes identifiers and forms SeaTunnel table paths. */
    public abstract SqlDialect dialect();

    @Override
    public Set<String> secretKeys() {
        return Set.of("password");
    }

    @Override
    public void validate(Map<String, String> props) {
        buildUrl(props);
        Props.required(props, "username");
        rejectSecretParams(props.get("params"));
    }

    /**
     * The params are stored and shown in plain text, so a password written there (e.g.
     * {@code password=...} or {@code sslpassword=...}) would leak; it belongs in the password field.
     */
    /** The same names counted as credentials in job configs (HoconTemplate). */
    private static final Pattern SECRET_PARAM =
            Pattern.compile(".*(password|passwd|secret|token|credential|api_?key|access_?key).*");

    static void rejectSecretParams(String params) {
        if (params == null || params.isBlank()) {
            return;
        }
        for (String pair : params.trim().replaceFirst("^\\?", "").split("[&;]")) {
            String raw = pair.split("=", 2)[0].trim();
            // Judged on the decoded name, so "%70assword" is caught like "password".
            String key;
            try {
                key = URLDecoder.decode(raw, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            } catch (IllegalArgumentException e) {
                key = raw.toLowerCase(Locale.ROOT);
            }
            if (SECRET_PARAM.matcher(key).matches() || key.equals("pwd")) {
                throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT,
                        "连接参数里不能写密码（" + key + "）：连接参数明文保存，密码请填在密码栏，会加密保存");
            }
        }
    }

    @Override
    public ConnectionTestResult testConnection(Map<String, String> props) {
        long start = System.currentTimeMillis();
        try {
            validate(props);
            Properties info = new Properties();
            info.setProperty("user", props.get("username"));
            if (props.get("password") != null) {
                info.setProperty("password", props.get("password"));
            }
            info.putAll(timeoutProperties(CONNECT_TIMEOUT_SECONDS));
            try (Connection connection = DriverManager.getConnection(buildUrl(props), info)) {
                connection.isValid(CONNECT_TIMEOUT_SECONDS);
            }
            return ConnectionTestResult.ok(System.currentTimeMillis() - start);
        } catch (SQLException e) {
            return ConnectionTestResult.failed(rootMessage(e), System.currentTimeMillis() - start);
        } catch (RuntimeException e) {
            return ConnectionTestResult.failed(e.getMessage(), System.currentTimeMillis() - start);
        }
    }

    @Override
    public long count(Map<String, String> props, CountRequest request) {
        if (request.sql() == null || request.sql().isBlank()) {
            throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "count SQL is empty");
        }
        try (Connection connection = open(props);
                Statement statement = connection.createStatement()) {
            connection.setReadOnly(true);
            statement.setQueryTimeout((int) Math.max(1, request.timeout().toSeconds()));
            try (ResultSet rs = statement.executeQuery(request.sql())) {
                if (!rs.next()) {
                    throw new ConsoleException(ErrorCodes.DATASOURCE_CONNECT_FAILED, "count SQL returned no rows");
                }
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new ConsoleException(ErrorCodes.DATASOURCE_CONNECT_FAILED, rootMessage(e));
        }
    }

    @Override
    public List<String> namespaces(Map<String, String> props) {
        try (Connection connection = open(props)) {
            DatabaseMetaData meta = connection.getMetaData();
            List<String> names = new ArrayList<>();
            try (ResultSet rs = namespaceIsCatalog() ? meta.getCatalogs() : meta.getSchemas()) {
                while (rs.next()) {
                    String name = rs.getString(namespaceIsCatalog() ? "TABLE_CAT" : "TABLE_SCHEM");
                    if (!isSystemNamespace(name)) {
                        names.add(name);
                    }
                }
            }
            names.sort(String::compareTo);
            return names;
        } catch (SQLException e) {
            throw failed(e);
        }
    }

    @Override
    public List<TableMeta> tables(Map<String, String> props, String namespace) {
        try (Connection connection = open(props)) {
            List<String> names = new ArrayList<>();
            try (ResultSet rs = connection.getMetaData()
                    .getTables(catalog(namespace), schema(namespace), "%", new String[] {"TABLE", "PARTITIONED TABLE"})) {
                while (rs.next()) {
                    names.add(rs.getString("TABLE_NAME"));
                }
            }
            Map<String, List<String>> keys = new LinkedHashMap<>();
            try (PreparedStatement ps = connection.prepareStatement(primaryKeysSql())) {
                ps.setString(1, namespace);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        keys.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(rs.getString(2));
                    }
                }
            }
            names.sort(String::compareTo);
            return names.stream().map(n -> new TableMeta(n, keys.getOrDefault(n, List.of()))).toList();
        } catch (SQLException e) {
            throw failed(e);
        }
    }

    @Override
    public List<ColumnMeta> columns(Map<String, String> props, String namespace, String table) {
        try (Connection connection = open(props)) {
            DatabaseMetaData meta = connection.getMetaData();
            List<String> keys = new ArrayList<>();
            try (ResultSet rs = meta.getPrimaryKeys(catalog(namespace), schema(namespace), table)) {
                while (rs.next()) {
                    keys.add(rs.getString("COLUMN_NAME"));
                }
            }
            List<ColumnMeta> columns = new ArrayList<>();
            try (ResultSet rs = meta.getColumns(catalog(namespace), schema(namespace), table, "%")) {
                while (rs.next()) {
                    String name = rs.getString("COLUMN_NAME");
                    int jdbcType = rs.getInt("DATA_TYPE");
                    String type = rs.getString("TYPE_NAME");
                    if (jdbcType == Types.VARCHAR || jdbcType == Types.CHAR || jdbcType == Types.NVARCHAR || jdbcType == Types.NCHAR) {
                        type = type + "(" + rs.getInt("COLUMN_SIZE") + ")";
                    }
                    boolean temporal = jdbcType == Types.DATE || jdbcType == Types.TIMESTAMP || jdbcType == Types.TIMESTAMP_WITH_TIMEZONE;
                    columns.add(new ColumnMeta(
                            name, type, rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls, keys.contains(name), temporal));
                }
            }
            if (columns.isEmpty()) {
                throw new ConsoleException(ErrorCodes.NOT_FOUND, "table not found: " + namespace + "." + table);
            }
            return columns;
        } catch (SQLException e) {
            throw failed(e);
        }
    }

    /** True when the namespace is a JDBC catalog (MySQL databases), false for a schema (PostgreSQL). */
    protected abstract boolean namespaceIsCatalog();

    protected abstract boolean isSystemNamespace(String name);

    /** Returns (table name, column name) of every primary key column in schema/database {@code ?}, in key order. */
    protected abstract String primaryKeysSql();

    private String catalog(String namespace) {
        return namespaceIsCatalog() ? namespace : null;
    }

    private String schema(String namespace) {
        return namespaceIsCatalog() ? null : namespace;
    }

    protected Connection open(Map<String, String> props) throws SQLException {
        validate(props);
        Properties info = new Properties();
        info.setProperty("user", props.get("username"));
        if (props.get("password") != null) {
            info.setProperty("password", props.get("password"));
        }
        info.putAll(timeoutProperties(CONNECT_TIMEOUT_SECONDS));
        return DriverManager.getConnection(buildUrl(props), info);
    }

    private static ConsoleException failed(SQLException e) {
        return new ConsoleException(ErrorCodes.DATASOURCE_CONNECT_FAILED, rootMessage(e));
    }

    @Override
    public Map<String, Object> toConnectorOptions(String connector, Map<String, String> props) {
        if (!JDBC_CONNECTOR.equals(connector)) {
            throw Props.unsupportedConnector(type(), connector);
        }
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("url", buildUrl(props));
        options.put("driver", driverClass());
        options.put("username", props.get("username"));
        if (props.get("password") != null) {
            options.put("password", props.get("password"));
        }
        return options;
    }

    /** JDBC URL including database and params, without credentials. */
    public abstract String buildUrl(Map<String, String> props);

    protected abstract String driverClass();

    /** Driver-specific connection properties that bound how long a connection attempt may hang. */
    protected abstract Map<String, String> timeoutProperties(int seconds);

    protected static String withParams(String base, String params) {
        if (params == null || params.isBlank()) {
            return base;
        }
        String trimmed = params.trim();
        return base + (trimmed.startsWith("?") ? trimmed : "?" + trimmed);
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }
}
