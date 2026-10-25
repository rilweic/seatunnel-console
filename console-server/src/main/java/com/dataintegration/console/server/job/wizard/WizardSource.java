package com.dataintegration.console.server.job.wizard;

import com.dataintegration.console.datasource.meta.ColumnMeta;
import com.dataintegration.console.server.datasource.DatasourceService;
import com.dataintegration.console.server.job.ValidationSpec;
import com.dataintegration.console.server.job.wizard.WizardForm.TableSpec;
import com.dataintegration.console.server.job.wizard.WizardGenerator.Endpoints;
import com.dataintegration.console.server.job.wizard.WizardService.Check;

import java.util.List;
import java.util.Map;

/**
 * The source side of a wizard job for one SeaTunnel source connector (Jdbc, Http, ...): its part of
 * the form, its source block and its checks against the live datasource. What is common to every
 * source (transforms, target names, the sink) stays in {@link WizardGenerator} and
 * {@link WizardService}; a new kind of source implements this and is registered in
 * {@link WizardConnectors} (docs/design.md §7.14).
 */
public interface WizardSource {

    /** The live datasources and the job's variables, for checks that read the source. */
    record Live(DatasourceService datasources, Map<String, String> variables) {}

    /** What a check of the live source found about one selected table. */
    record SourceTable(List<ColumnMeta> columns, List<String> primaryKeys) {}

    /** Refuses an incomplete or inconsistent source part of the form (INVALID_ARGUMENT). */
    void requireShape(WizardForm form);

    /** Appends the {@code source { ... }} block, whose stream is named {@code source}. */
    void append(StringBuilder hocon, WizardForm form, Endpoints endpoints);

    /**
     * The table path this source gives a table's rows: per-table transforms are keyed by it and,
     * unless it equals the target name, a TableRename turns it into the target name.
     */
    String tablePath(WizardForm form, Endpoints endpoints, TableSpec table);

    /** The table name in {@link #tablePath}, compared with the target name to decide on a TableRename. */
    String emittedName(WizardForm form, Endpoints endpoints, TableSpec table);

    /** The table's synced columns as far as they are known without reading the datasource. */
    List<String> syncedColumns(WizardForm form, Endpoints endpoints, TableSpec table);

    /** Column names the generator needs from the live datasource, by table (see {@link Endpoints#sourceColumns()}). */
    Map<String, List<String>> knownColumns(WizardForm form, DatasourceService datasources);

    /** Where the rows come from, for the job overview: the database or schema, the endpoint path. */
    default String location(WizardForm form) {
        return form.source().namespace();
    }

    /** The value of {@link Endpoints#sourceDatabase()}: the datasource's database where table paths need it. */
    default String database(WizardForm form, DatasourceService datasources) {
        return null;
    }

    /** False when rows can only be appended whatever the form says, e.g. an HTTP source without keys. */
    default boolean upsertPossible(WizardForm form) {
        return true;
    }

    /** Count of the rows a table reads, for validation; only asked of countable sources. */
    default ValidationSpec.Side countSide(WizardForm form, Endpoints endpoints, TableSpec table) {
        throw new UnsupportedOperationException("not countable");
    }

    /** Why there is no row-count validation, for sources that cannot be counted. */
    default String uncountableNote() {
        return "来源无法计数，不做行数校验";
    }

    /**
     * Reads the live source: for every selected table that exists, its columns and primary keys.
     * Adds an ERROR for what is missing; returns null when the source cannot be used at all.
     */
    Map<String, SourceTable> inspect(WizardForm form, Endpoints endpoints, Live live, List<Check> checks);

    /** Checks of one existing table beyond its columns, e.g. running the generated query. */
    default void checkTable(WizardForm form, Endpoints endpoints, TableSpec table, Map<String, ColumnMeta> columns,
            Live live, List<Check> checks) {}
}
