package com.dataintegration.console.server.job.wizard;

import com.dataintegration.console.server.datasource.DatasourceService;
import com.dataintegration.console.server.job.ValidationSpec;
import com.dataintegration.console.server.job.wizard.WizardForm.TableSpec;
import com.dataintegration.console.server.job.wizard.WizardGenerator.Endpoints;

import java.util.List;

/**
 * The target side of a wizard job for one SeaTunnel sink connector (Jdbc, Elasticsearch, ...). The
 * generator writes the options every sink shares (save modes, advanced options, input); a sink
 * writes its own. Registered in {@link WizardConnectors} (docs/design.md §7.14).
 */
public interface WizardSink {

    /** What a table is called on this target in messages: 目标表, 索引. */
    String tableWord();

    /**
     * Opens the connector's block inside {@code sink} and writes the options only this sink has,
     * e.g. database and table for Jdbc; the generator writes the shared options and closes it.
     *
     * @param upsert write by primary key
     */
    void appendHead(StringBuilder hocon, WizardForm form, Endpoints endpoints, boolean upsert);

    /** The value of {@link Endpoints#targetDatabase()}: the datasource's database where the sink needs it. */
    default String database(WizardForm form, DatasourceService datasources) {
        return null;
    }

    /** Whether an existing target table is compared with the mapped fields (not for ES indices). */
    boolean comparesColumns();

    /** Why an incremental sync is not validated on this target, or null when it is. */
    default String incrementalValidationSkipped() {
        return null;
    }

    /**
     * Count of the rows the target holds for a table, for validation: the whole table after a full
     * sync into a cleared target, else the same incremental window as the source.
     *
     * @param notes where to explain a table that cannot be counted (then null is returned)
     */
    ValidationSpec.Side countSide(WizardForm form, Endpoints endpoints, TableSpec table, String targetName,
            boolean full, List<String> notes);
}
