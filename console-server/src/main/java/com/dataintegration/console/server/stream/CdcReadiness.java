package com.dataintegration.console.server.stream;

import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.datasource.cdc.CdcCheck;
import com.dataintegration.console.datasource.cdc.CdcSupport;
import com.dataintegration.console.server.datasource.DatasourceService;
import com.dataintegration.console.server.job.HoconTemplate;
import com.dataintegration.console.server.job.wizard.WizardForm;
import com.dataintegration.console.server.job.wizard.WizardService.Check;
import com.dataintegration.console.server.job.wizard.WizardService.Level;
import com.dataintegration.console.server.persistence.entity.JobStream;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Whether the source database of a streaming job is set up for CDC (docs/design.md §12 P2, Q30):
 * checked by the wizard's precheck, where a failure stops saving, and again at every start, since a
 * DBA may have changed the server since.
 */
@Component
public class CdcReadiness {

    private final DatasourceService datasources;

    public CdcReadiness(DatasourceService datasources) {
        this.datasources = datasources;
    }

    /** The conditions for the wizard form's tables, as precheck results; what holds is not listed. */
    public List<Check> wizardChecks(JobStream stream, WizardForm form) {
        String namespace = form.source().namespace();
        List<String> tables = form.source().tables() == null ? List.of()
                : form.source().tables().stream().map(WizardForm.TableSpec::name).toList();
        List<Check> checks = new ArrayList<>();
        try {
            for (CdcCheck c : datasources.cdcCheck(form.source().datasourceId(),
                    new CdcSupport.Scope(namespace, tables, stream == null ? null : stream.getReplicationName()))) {
                if (c.level() != CdcCheck.Level.OK) {
                    checks.add(new Check(c.level() == CdcCheck.Level.ERROR ? Level.ERROR : Level.WARN, message(c)));
                }
            }
        } catch (ConsoleException e) {
            checks.add(new Check(Level.ERROR, "无法检查实时同步条件：" + e.getMessage()));
        }
        if (checks.isEmpty()) {
            checks.add(new Check(Level.INFO, "源库的实时同步条件都满足（binlog / 逻辑复制、权限）"));
        }
        return checks;
    }

    /**
     * What stops a start, for every CDC source of the rendered config; scripts name no tables to
     * the console, so only the server and the account are checked for them.
     *
     * @param tables the wizard's tables, or empty for a script
     */
    public List<String> startErrors(JobStream stream, HoconTemplate template, String namespace, List<String> tables) {
        List<String> errors = new ArrayList<>();
        for (HoconTemplate.DatasourceRef ref : template.datasourceRefs()) {
            if (!"source".equals(ref.section()) || !ref.connector().endsWith("-CDC")) {
                continue;
            }
            CdcSupport.Scope scope = new CdcSupport.Scope(namespace, tables, stream.getReplicationName());
            for (CdcCheck c : datasources.cdcCheck(ref.datasourceId(), scope)) {
                if (c.level() == CdcCheck.Level.ERROR) {
                    errors.add(message(c));
                }
            }
        }
        return errors;
    }

    static String message(CdcCheck c) {
        return c.message() + (c.actual() == null ? "" : "（当前：" + c.actual() + "）") + (c.fix() == null ? "" : "。处理办法：" + c.fix());
    }
}
