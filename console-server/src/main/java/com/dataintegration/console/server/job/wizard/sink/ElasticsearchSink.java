package com.dataintegration.console.server.job.wizard.sink;

import static com.dataintegration.console.server.job.wizard.Hocon.str;

import com.dataintegration.console.server.job.ValidationSpec.Mode;
import com.dataintegration.console.server.job.ValidationSpec.Side;
import com.dataintegration.console.server.job.wizard.WizardForm;
import com.dataintegration.console.server.job.wizard.WizardForm.TableSpec;
import com.dataintegration.console.server.job.wizard.WizardGenerator.Endpoints;
import com.dataintegration.console.server.job.wizard.WizardSink;

import java.util.List;

/**
 * One index per table through SeaTunnel's Elasticsearch sink (docs/design.md §7.4): upsert takes
 * {@code _id} from each table's primary key, append lets ES generate it; indices and their mappings
 * are created by SeaTunnel.
 */
public final class ElasticsearchSink implements WizardSink {

    @Override
    public String tableWord() {
        return "索引";
    }

    @Override
    public void appendHead(StringBuilder hocon, WizardForm form, Endpoints endpoints, boolean upsert) {
        hocon.append("  Elasticsearch {\n");
        hocon.append("    datasource_id = ").append(str(form.target().datasourceId())).append('\n');
        hocon.append("    index = \"${table_name}\"\n");
        if (upsert) {
            hocon.append("    primary_keys = [\"${primary_key}\"]\n");
        }
    }

    @Override
    public boolean comparesColumns() {
        return false;
    }

    @Override
    public String incrementalValidationSkipped() {
        return "ES 目标的增量窗口不做行数校验（日期格式在 ES 里不好保证一致）";
    }

    @Override
    public Side countSide(WizardForm form, Endpoints endpoints, TableSpec table, String targetName,
            boolean full, List<String> notes) {
        return new Side(Mode.ES, form.target().datasourceId(), null, targetName, null);
    }
}
