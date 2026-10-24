package com.dataintegration.console.server.job;

import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.common.ErrorCodes;
import com.dataintegration.console.datasource.CountRequest;
import com.dataintegration.console.server.datasource.DatasourceService;
import com.dataintegration.console.server.job.ValidationSpec.Mode;
import com.dataintegration.console.server.job.ValidationSpec.Side;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Counts source and target rows after a successful run and compares them (docs/design.md §10.3).
 * The counts are taken by the console directly against the datasources, independent of the
 * engine's own metrics. Both are only meaningful when the target rows of this run can be singled
 * out, e.g. the sink is cleared first or the target SQL filters on the same partition variable.
 */
@Service
public class RowCountValidator {

    public enum Status {
        PASSED,
        MISMATCH,
        ERROR
    }

    /**
     * @param sourceCount null when it could not be taken
     * @param sourceQuery what was counted on the source side, for display
     * @param targetQuery what was counted on the target side, for display
     */
    public record Result(
            Status status, Long sourceCount, Long targetCount, String message, String sourceQuery, String targetQuery) {}

    private final DatasourceService datasourceService;
    private final Duration timeout;

    public RowCountValidator(
            DatasourceService datasourceService, @Value("${console.validation.timeout:10m}") Duration timeout) {
        this.datasourceService = datasourceService;
        this.timeout = timeout;
    }

    /** Rejects a spec that could never run, so it is caught when the version is saved. */
    public void check(ValidationSpec spec, HoconTemplate template) {
        if (spec == null || !spec.enabled()) {
            return;
        }
        if (spec.perTable()) {
            for (ValidationSpec.TableCheck check : spec.tables()) {
                checkSide("source of " + check.name(), check.source(), template);
                checkSide("target of " + check.name(), check.target(), template);
            }
            return;
        }
        if (spec.source() == null || spec.target() == null) {
            throw invalid("validation needs both a source and a target side");
        }
        checkSide("source", spec.source(), template);
        checkSide("target", spec.target(), template);
        if (spec.target().mode() == Mode.AUTO) {
            throw invalid("validation target: AUTO is only available for the source side");
        }
    }

    public Result validate(ValidationSpec spec, HoconTemplate template, Map<String, String> variables, LocalDateTime scheduledTime) {
        if (spec.perTable()) {
            return validateTables(spec.tables(), template, variables, scheduledTime);
        }
        return validatePair(spec.source(), spec.target(), template, variables, scheduledTime);
    }

    /**
     * One count pair per table. Any mismatch makes the whole result MISMATCH, otherwise any failed
     * count makes it ERROR; counts are summed over the tables that could be counted.
     */
    private Result validateTables(
            List<ValidationSpec.TableCheck> checks, HoconTemplate template, Map<String, String> variables, LocalDateTime scheduledTime) {
        long sourceTotal = 0;
        long targetTotal = 0;
        List<String> mismatches = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        StringBuilder sourceQueries = new StringBuilder();
        StringBuilder targetQueries = new StringBuilder();
        for (ValidationSpec.TableCheck check : checks) {
            Result r = validatePair(check.source(), check.target(), template, variables, scheduledTime);
            if (r.sourceCount() != null) {
                sourceTotal += r.sourceCount();
            }
            if (r.targetCount() != null) {
                targetTotal += r.targetCount();
            }
            if (r.status() == Status.MISMATCH) {
                mismatches.add(check.name() + ": " + r.message());
            } else if (r.status() == Status.ERROR) {
                errors.add(check.name() + ": " + r.message());
            }
            sourceQueries.append(sourceQueries.isEmpty() ? "" : "; ").append(r.sourceQuery());
            targetQueries.append(targetQueries.isEmpty() ? "" : "; ").append(r.targetQuery());
        }
        Status status = !mismatches.isEmpty() ? Status.MISMATCH : !errors.isEmpty() ? Status.ERROR : Status.PASSED;
        String message = status == Status.PASSED
                ? checks.size() + " table(s) match, " + sourceTotal + " rows in total"
                : String.join("; ", mismatches.isEmpty() ? errors : concat(mismatches, errors));
        return new Result(status, sourceTotal, targetTotal, message, sourceQueries.toString(), targetQueries.toString());
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    private Result validatePair(Side sourceSide, Side targetSide, HoconTemplate template, Map<String, String> variables, LocalDateTime scheduledTime) {
        Counted source = count(sourceSide, template, variables, scheduledTime);
        Counted target = count(targetSide, template, variables, scheduledTime);
        if (source.error != null || target.error != null) {
            String message = (source.error == null ? "" : "source count failed: " + source.error)
                    + (source.error != null && target.error != null ? "; " : "")
                    + (target.error == null ? "" : "target count failed: " + target.error);
            return new Result(Status.ERROR, source.count, target.count, message, source.query, target.query);
        }
        long diff = target.count - source.count;
        return diff == 0
                ? new Result(Status.PASSED, source.count, target.count, "source and target both have " + source.count + " rows",
                        source.query, target.query)
                : new Result(Status.MISMATCH, source.count, target.count,
                        "source " + source.count + " rows, target " + target.count + " rows, difference " + (diff > 0 ? "+" : "") + diff,
                        source.query, target.query);
    }

    private record Counted(Long count, String query, String error) {}

    private Counted count(Side side, HoconTemplate template, Map<String, String> variables, LocalDateTime scheduledTime) {
        String datasourceId;
        String query;
        CountRequest request;
        try {
            if (side.mode() == Mode.AUTO) {
                AutoSource auto = autoSource(template);
                datasourceId = auto.datasourceId();
                query = auto.sql();
                request = CountRequest.sql(query, timeout);
            } else if (side.mode() == Mode.SQL) {
                datasourceId = side.datasourceId();
                query = Variables.render(side.sql(), variables, scheduledTime).trim();
                request = CountRequest.sql(stripSemicolon(query), timeout);
            } else {
                datasourceId = side.datasourceId();
                String index = Variables.render(side.index(), variables, scheduledTime).trim();
                String dsl = side.query() == null || side.query().isBlank()
                        ? null
                        : Variables.render(side.query(), variables, scheduledTime);
                query = index + "/_count" + (dsl == null ? "" : " " + dsl);
                request = CountRequest.elasticsearch(index, dsl, timeout);
            }
        } catch (ConsoleException e) {
            return new Counted(null, null, e.getMessage());
        }
        try {
            return new Counted(datasourceService.count(datasourceId, request), query, null);
        } catch (ConsoleException e) {
            return new Counted(null, query, e.getMessage());
        }
    }

    private void checkSide(String name, Side side, HoconTemplate template) {
        if (side.mode() == null) {
            throw invalid("validation " + name + ": choose a mode");
        }
        switch (side.mode()) {
            case AUTO -> autoSource(template);
            case SQL -> {
                requireText(side.datasourceId(), "validation " + name + ": choose a datasource");
                requireText(side.sql(), "validation " + name + ": enter the count SQL");
                if (datasourceService.typeOf(side.datasourceId()).equals("ELASTICSEARCH")) {
                    throw invalid("validation " + name + ": SQL needs a database datasource, use ES mode for Elasticsearch");
                }
            }
            case ES -> {
                requireText(side.datasourceId(), "validation " + name + ": choose a datasource");
                requireText(side.index(), "validation " + name + ": enter the index");
                if (!datasourceService.typeOf(side.datasourceId()).equals("ELASTICSEARCH")) {
                    throw invalid("validation " + name + ": ES mode needs an Elasticsearch datasource");
                }
            }
        }
    }

    record AutoSource(String datasourceId, String sql) {}

    /**
     * The count for a job whose only source is Jdbc with a datasource_id: its query wrapped in
     * count(*), or its table_path; where_condition (which SeaTunnel requires to start with
     * "where") is applied on top in both cases.
     */
    static AutoSource autoSource(HoconTemplate template) {
        List<HoconTemplate.Block> sources = template.blocks("source");
        if (sources.size() != 1 || !"Jdbc".equalsIgnoreCase(sources.get(0).connector())) {
            throw invalid("automatic source count needs exactly one Jdbc source; write the source count SQL instead");
        }
        HoconTemplate.Block jdbc = sources.get(0);
        String datasourceId = jdbc.string(HoconTemplate.DATASOURCE_KEY);
        if (datasourceId == null || datasourceId.isBlank()) {
            throw invalid("automatic source count needs the Jdbc source to use datasource_id");
        }
        String where = jdbc.string("where_condition");
        String suffix = where == null || where.isBlank() ? "" : " " + where.trim();
        String query = jdbc.string("query");
        if (query != null && !query.isBlank()) {
            return new AutoSource(datasourceId, "select count(*) from (" + stripSemicolon(query.trim()) + ") t_count" + suffix);
        }
        String table = jdbc.string("table_path");
        if (table != null && !table.isBlank()) {
            return new AutoSource(datasourceId, "select count(*) from " + table.trim() + suffix);
        }
        throw invalid("automatic source count needs query or table_path on the Jdbc source");
    }

    private static String stripSemicolon(String sql) {
        String s = sql.trim();
        while (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1).trim();
        }
        return s;
    }

    private static void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw invalid(message);
        }
    }

    private static ConsoleException invalid(String message) {
        return new ConsoleException(ErrorCodes.INVALID_ARGUMENT, message);
    }
}
