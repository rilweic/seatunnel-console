package com.dataintegration.console.datasource.jdbc;

import com.dataintegration.console.datasource.cdc.CdcCheck;
import com.dataintegration.console.datasource.cdc.CdcCheck.Level;
import com.dataintegration.console.datasource.cdc.CdcSupport;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What MySQL-CDC needs from the server and the account: the binlog on, in ROW format with full row
 * images, and the right to read it (docs/design.md §12 P2, Q8 and Q30).
 */
final class MySqlCdcSupport implements CdcSupport {

    /** A job stopped longer than this may find the binlog it has to resume from purged. */
    static final Duration SHORT_RETENTION = Duration.ofDays(3);

    private final MySqlDataSourcePlugin plugin;

    MySqlCdcSupport(MySqlDataSourcePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public List<CdcCheck> check(Map<String, String> props, Scope scope) {
        try (Connection connection = plugin.open(props)) {
            Map<String, String> vars = variables(connection);
            List<CdcCheck> checks = new ArrayList<>();
            checks.add(expect(vars, "log_bin", "ON", "binlog 已开启",
                    "binlog 没有开启，实时同步读不到变更",
                    "在 my.cnf 的 [mysqld] 下加上 log-bin=mysql-bin 和 server-id=1，然后重启 MySQL"));
            checks.add(expect(vars, "binlog_format", "ROW", "binlog 格式是 ROW",
                    "binlog 格式必须是 ROW，否则读不到每一行的变化",
                    "SET GLOBAL binlog_format = 'ROW';  -- 并在 my.cnf 里写上 binlog_format=ROW，重启后也生效"));
            checks.add(expect(vars, "binlog_row_image", "FULL", "binlog 记录完整的行",
                    "binlog_row_image 必须是 FULL，否则更新只记录改动的列",
                    "SET GLOBAL binlog_row_image = 'FULL';  -- 并在 my.cnf 里写上 binlog_row_image=FULL"));
            checks.add(retention(vars));
            checks.add(privileges(connection, props.get("username"), scope.namespace()));
            return checks;
        } catch (SQLException e) {
            return List.of(new CdcCheck("连接", Level.ERROR, null, "无法连接数据库：" + JdbcDataSourcePlugin.rootMessage(e), null));
        }
    }

    private static Map<String, String> variables(Connection connection) throws SQLException {
        Map<String, String> vars = new HashMap<>();
        try (Statement st = connection.createStatement();
                ResultSet rs = st.executeQuery("show global variables where variable_name in "
                        + "('log_bin', 'binlog_format', 'binlog_row_image', 'binlog_expire_logs_seconds', 'expire_logs_days')")) {
            while (rs.next()) {
                vars.put(rs.getString(1).toLowerCase(Locale.ROOT), rs.getString(2));
            }
        }
        return vars;
    }

    private static CdcCheck expect(Map<String, String> vars, String name, String wanted, String okMessage, String problem, String fix) {
        String actual = vars.get(name);
        return wanted.equalsIgnoreCase(actual)
                ? CdcCheck.ok(name, actual, okMessage)
                : new CdcCheck(name, Level.ERROR, actual, problem, fix);
    }

    /** How long the binlog is kept: 8.0 counts seconds, 5.7 days; 0 means forever. */
    private static CdcCheck retention(Map<String, String> vars) {
        long seconds = parse(vars.get("binlog_expire_logs_seconds"));
        if (seconds == 0) {
            seconds = parse(vars.get("expire_logs_days")) * 86400;
        }
        String actual = seconds == 0 ? "不过期" : seconds / 3600 + " 小时";
        if (seconds == 0 || seconds >= SHORT_RETENTION.toSeconds()) {
            return CdcCheck.ok("binlog 保留时间", actual, "binlog 保留时间足够");
        }
        return new CdcCheck("binlog 保留时间", Level.WARN, actual,
                "binlog 只保留 " + actual + "：任务停止超过这个时间后，要恢复的位置已被清理，只能全新启动（重新全量）",
                "SET GLOBAL binlog_expire_logs_seconds = 604800;  -- 7 天，并写进 my.cnf");
    }

    private static long parse(String value) {
        try {
            return value == null ? 0 : Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** REPLICATION SLAVE and CLIENT on *.*, and SELECT on *.* or on the job's database. */
    private static CdcCheck privileges(Connection connection, String user, String namespace) throws SQLException {
        List<String> grants = new ArrayList<>();
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery("show grants for current_user()")) {
            while (rs.next()) {
                grants.add(rs.getString(1).toUpperCase(Locale.ROOT).replace("`", "").replace("\"", ""));
            }
        }
        List<String> missing = new ArrayList<>();
        for (String privilege : List.of("REPLICATION SLAVE", "REPLICATION CLIENT")) {
            if (!granted(grants, privilege, "*.*")) {
                missing.add(privilege);
            }
        }
        if (!granted(grants, "SELECT", "*.*") && (namespace == null || !granted(grants, "SELECT", namespace.toUpperCase(Locale.ROOT) + ".*"))) {
            missing.add("SELECT");
        }
        if (missing.isEmpty()) {
            return CdcCheck.ok("账号权限", "已有复制和读取权限", "账号有读取 binlog 和表的权限");
        }
        return new CdcCheck("账号权限", Level.ERROR, "缺少 " + String.join("、", missing),
                "账号缺少 " + String.join("、", missing) + " 权限",
                "GRANT SELECT, REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO '" + user + "'@'%';");
    }

    private static boolean granted(List<String> grants, String privilege, String on) {
        return grants.stream().anyMatch(g -> g.contains(" ON " + on + " ")
                && (g.startsWith("GRANT ALL PRIVILEGES") || g.matches("^GRANT .*\\b" + privilege + "\\b.* ON .*")));
    }
}
