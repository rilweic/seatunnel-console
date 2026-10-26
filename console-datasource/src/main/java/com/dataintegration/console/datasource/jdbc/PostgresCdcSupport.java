package com.dataintegration.console.datasource.jdbc;

import com.dataintegration.console.datasource.cdc.CdcCheck;
import com.dataintegration.console.datasource.cdc.CdcCheck.Level;
import com.dataintegration.console.datasource.cdc.CdcSupport;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * What Postgres-CDC needs: logical decoding, a replication role, a free slot, full row images of
 * the tables, and a publication the account may create or that exists already (docs/design.md
 * §12 P2, Q8, Q29 and Q30). A job's slot and publication stay on the database while it is stopped
 * and are dropped with the job.
 */
final class PostgresCdcSupport implements CdcSupport {

    /** Slot and publication names are made by the console; anything else is refused rather than quoted. */
    private static final Pattern NAME = Pattern.compile("[a-z_][a-z0-9_]{0,62}");

    private final PostgreSqlDataSourcePlugin plugin;

    PostgresCdcSupport(PostgreSqlDataSourcePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public List<CdcCheck> check(Map<String, String> props, Scope scope) {
        try (Connection connection = plugin.open(props)) {
            List<CdcCheck> checks = new ArrayList<>();
            String walLevel = single(connection, "show wal_level");
            checks.add("logical".equals(walLevel)
                    ? CdcCheck.ok("wal_level", walLevel, "已开启逻辑复制")
                    : new CdcCheck("wal_level", Level.ERROR, walLevel, "wal_level 必须是 logical，实时同步才能读到变更",
                            "ALTER SYSTEM SET wal_level = logical;  -- 需要重启 PostgreSQL"));
            boolean superuser = "true".equals(single(connection, "select rolsuper::text from pg_roles where rolname = current_user"));
            boolean replication = "true".equals(single(connection, "select rolreplication::text from pg_roles where rolname = current_user"));
            String user = props.get("username");
            checks.add(superuser || replication
                    ? CdcCheck.ok("复制权限", superuser ? "超级用户" : "REPLICATION", "账号可以建立复制连接")
                    : new CdcCheck("复制权限", Level.ERROR, "无", "账号没有 REPLICATION 权限", "ALTER ROLE \"" + user + "\" REPLICATION;"));
            checks.add(slots(connection, scope.replicationName()));
            if (!scope.tables().isEmpty()) {
                checks.add(replicaIdentity(connection, scope));
                checks.add(publication(connection, scope, superuser));
            }
            return checks;
        } catch (SQLException e) {
            return List.of(new CdcCheck("连接", Level.ERROR, null, "无法连接数据库：" + JdbcDataSourcePlugin.rootMessage(e), null));
        }
    }

    private static CdcCheck slots(Connection connection, String own) throws SQLException {
        int max = Integer.parseInt(single(connection, "show max_replication_slots"));
        int used = Integer.parseInt(single(connection, "select count(*)::text from pg_replication_slots"));
        boolean mine = own != null && "1".equals(query(connection,
                "select count(*)::text from pg_replication_slots where slot_name = ?", own));
        String actual = "已用 " + used + " / " + max;
        if (mine || used < max - 1) {
            return CdcCheck.ok("复制槽", actual, mine ? "任务的复制槽已存在" : "还有空闲的复制槽");
        }
        String fix = "ALTER SYSTEM SET max_replication_slots = " + (max + 10) + ";  -- 需要重启；或删除不用的复制槽";
        return used >= max
                ? new CdcCheck("复制槽", Level.ERROR, actual, "复制槽已经用完，新的实时任务无法启动", fix)
                : new CdcCheck("复制槽", Level.WARN, actual, "只剩 1 个空闲的复制槽", fix);
    }

    /** SeaTunnel refuses tables without REPLICA IDENTITY FULL by default (require-replica-identity-full). */
    private static CdcCheck replicaIdentity(Connection connection, Scope scope) throws SQLException {
        List<String> notFull = new ArrayList<>();
        for (String table : scope.tables()) {
            String identity = query(connection, "select c.relreplident::text from pg_class c join pg_namespace n on n.oid = c.relnamespace"
                    + " where n.nspname = ? and c.relname = ?", scope.namespace(), table);
            if (identity != null && !"f".equals(identity)) {
                notFull.add(table);
            }
        }
        if (notFull.isEmpty()) {
            return CdcCheck.ok("REPLICA IDENTITY", "FULL", "所选表都记录完整的旧行");
        }
        StringBuilder fix = new StringBuilder();
        notFull.forEach(t -> fix.append("ALTER TABLE ").append(qualified(scope.namespace(), t)).append(" REPLICA IDENTITY FULL;\n"));
        return new CdcCheck("REPLICA IDENTITY", Level.ERROR, "非 FULL：" + String.join("、", notFull),
                "这些表的 REPLICA IDENTITY 不是 FULL，更新和删除拿不到完整的旧行：" + String.join("、", notFull), fix.toString().trim());
    }

    /**
     * The job creates its own publication for its tables, which needs a superuser or the owner of
     * every table; otherwise a DBA creates it once under the job's name.
     */
    private static CdcCheck publication(Connection connection, Scope scope, boolean superuser) throws SQLException {
        String name = scope.replicationName();
        if (name != null && "1".equals(query(connection, "select count(*)::text from pg_publication where pubname = ?", name))) {
            return CdcCheck.ok("publication", name, "任务的 publication 已存在");
        }
        if (superuser) {
            return CdcCheck.ok("publication", "超级用户", "启动时自动创建 publication");
        }
        List<String> notOwned = new ArrayList<>();
        for (String table : scope.tables()) {
            String owner = query(connection, "select pg_get_userbyid(c.relowner) from pg_class c join pg_namespace n on n.oid = c.relnamespace"
                    + " where n.nspname = ? and c.relname = ?", scope.namespace(), table);
            if (owner != null && !owner.equals(single(connection, "select current_user"))) {
                notOwned.add(table);
            }
        }
        if (notOwned.isEmpty()) {
            return CdcCheck.ok("publication", "表的所有者", "启动时自动创建 publication");
        }
        String fix = "CREATE PUBLICATION " + (name == null ? "<任务的 publication 名>" : name) + " FOR TABLE "
                + String.join(", ", scope.tables().stream().map(t -> qualified(scope.namespace(), t)).toList()) + ";";
        return new CdcCheck("publication", Level.ERROR, "不是这些表的所有者：" + String.join("、", notOwned),
                "账号不是超级用户，也不是所有表的所有者，不能自动创建 publication。请 DBA 先建好", fix);
    }

    @Override
    public Replication replication(Map<String, String> props, String name) {
        try (Connection connection = plugin.open(props);
                PreparedStatement ps = connection.prepareStatement("select active, coalesce(pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn), 0)"
                        + " from pg_replication_slots where slot_name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Replication(name, rs.getBoolean(1), rs.getLong(2)) : null;
            }
        } catch (SQLException e) {
            return null;
        }
    }

    @Override
    public List<String> dropReplication(Map<String, String> props, String name) {
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("not a console replication name: " + name);
        }
        String dropSlot = "SELECT pg_drop_replication_slot('" + name + "');";
        String dropPublication = "DROP PUBLICATION IF EXISTS " + name + ";";
        List<String> left = new ArrayList<>();
        try (Connection connection = plugin.open(props); Statement st = connection.createStatement()) {
            try (ResultSet rs = st.executeQuery("select count(*) from pg_replication_slots where slot_name = '" + name + "'")) {
                if (rs.next() && rs.getInt(1) > 0) {
                    runOrKeep(st, dropSlot, left);
                }
            }
            runOrKeep(st, dropPublication, left);
        } catch (SQLException e) {
            return List.of(dropSlot, dropPublication);
        }
        return left;
    }

    private static void runOrKeep(Statement st, String sql, List<String> left) {
        try {
            st.execute(sql);
        } catch (SQLException e) {
            left.add(sql);
        }
    }

    private static String qualified(String schema, String table) {
        return SqlDialect.POSTGRESQL.qualified(schema, table);
    }

    private static String single(Connection connection, String sql) throws SQLException {
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static String query(Connection connection, String sql, String... args) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setString(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }
}
