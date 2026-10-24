package com.dataintegration.console.server.job;

import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.datasource.CountRequest;
import com.dataintegration.console.server.datasource.DatasourceService;
import com.dataintegration.console.server.job.wizard.WizardService.Check;
import com.dataintegration.console.server.job.wizard.WizardService.Level;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Runs every Jdbc source query of a script job once under {@code where 1 = 0}, against the
 * datasource it names. SeaTunnel validates the same queries when it accepts a job but answers a
 * failure with a bare HTTP 500 (docs/design.md §4.3, point 10); this finds the actual database
 * error, both before saving (editor button) and after a rejected submission (run timeline).
 */
@Service
public class ScriptChecker {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final DatasourceService datasourceService;

    public ScriptChecker(DatasourceService datasourceService) {
        this.datasourceService = datasourceService;
    }

    /** @param template the job config with variables already rendered */
    public List<Check> check(HoconTemplate template) {
        List<Check> checks = new ArrayList<>();
        for (HoconTemplate.Block block : template.blocks("source")) {
            String name = "source." + block.connector();
            if (!"Jdbc".equalsIgnoreCase(block.connector())) {
                checks.add(new Check(Level.INFO, name + "：不是 Jdbc 连接器，不检查"));
                continue;
            }
            String datasourceId = block.string(HoconTemplate.DATASOURCE_KEY);
            if (datasourceId == null || datasourceId.isBlank()) {
                checks.add(new Check(Level.INFO, name + "：没有使用 datasource_id，无法检查"));
                continue;
            }
            String where = block.string("where_condition");
            List<Map<String, String>> entries = block.objects("table_list");
            if (entries.isEmpty()) {
                entries = List.of(Map.of(
                        "query", nullToEmpty(block.string("query")), "table_path", nullToEmpty(block.string("table_path"))));
            }
            for (Map<String, String> entry : entries) {
                probe(checks, name, datasourceId, entry.get("query"), entry.get("table_path"), where);
            }
        }
        if (checks.isEmpty()) {
            checks.add(new Check(Level.INFO, "配置里没有 source"));
        }
        return checks;
    }

    private void probe(List<Check> checks, String name, String datasourceId, String query, String tablePath, String where) {
        String base;
        String label;
        if (query != null && !query.isBlank()) {
            base = stripSemicolon(query);
            label = name + " 的查询";
        } else if (tablePath != null && !tablePath.isBlank()) {
            base = "select * from " + tablePath.trim();
            label = name + " 的表 " + tablePath.trim();
        } else {
            checks.add(new Check(Level.WARN, name + "：没有 query 也没有 table_path"));
            return;
        }
        // where_condition must start with "where" (SeaTunnel's own rule) and applies on top of the query.
        String filtered = where == null || where.isBlank() ? base : "select * from (" + base + ") t_where " + where.trim();
        try {
            datasourceService.count(datasourceId,
                    CountRequest.sql("select count(*) from (" + filtered + ") t_check where 1 = 0", TIMEOUT));
            checks.add(new Check(Level.INFO, label + " 可以执行"));
        } catch (ConsoleException e) {
            checks.add(new Check(Level.ERROR, label + " 执行失败：" + e.getMessage()));
        }
    }

    private static String stripSemicolon(String sql) {
        String s = sql.trim();
        while (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1).trim();
        }
        return s;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
