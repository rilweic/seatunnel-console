package com.dataintegration.console.server.job.wizard;

import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.datasource.DataSourceCapabilities;
import com.dataintegration.console.datasource.DataSourcePlugin;
import com.dataintegration.console.datasource.DataSourcePlugins;
import com.dataintegration.console.datasource.es.ElasticsearchDataSourcePlugin;
import com.dataintegration.console.datasource.http.HttpDataSourcePlugin;
import com.dataintegration.console.datasource.jdbc.JdbcDataSourcePlugin;
import com.dataintegration.console.server.job.wizard.sink.ElasticsearchSink;
import com.dataintegration.console.server.job.wizard.sink.JdbcSink;
import com.dataintegration.console.server.job.wizard.source.CdcSource;
import com.dataintegration.console.server.job.wizard.source.HttpSource;
import com.dataintegration.console.server.job.wizard.source.JdbcSource;

import java.util.List;
import java.util.function.Predicate;

/**
 * Which wizard source and sink serve a datasource type, from the connector its plugin declares
 * ({@link DataSourceCapabilities}). A new JDBC database needs only its plugin; a new connector also
 * needs its {@link WizardSource} or {@link WizardSink} added here (docs/design.md §7.14).
 */
public final class WizardConnectors {

    /** Plugins are stateless, so the generator can use its own registry and stay pure. */
    private static final DataSourcePlugins PLUGINS = new DataSourcePlugins();
    private static final WizardSource HTTP_SOURCE = new HttpSource();
    private static final WizardSink ES_SINK = new ElasticsearchSink();

    private WizardConnectors() {}

    public static DataSourceCapabilities capabilities(String type) {
        return PLUGINS.get(type).capabilities();
    }

    /** The source of an offline job. */
    public static WizardSource source(String type) {
        return source(type, false);
    }

    /** The source of the form's job: the CDC source for a real-time job (design.md §12 P2). */
    public static WizardSource source(String type, WizardForm form) {
        return source(type, form != null && form.streaming());
    }

    public static WizardSource source(String type, boolean streaming) {
        if (streaming) {
            DataSourcePlugin plugin = supported(type, DataSourceCapabilities::streamSource, "实时同步源端");
            return new CdcSource((JdbcDataSourcePlugin) plugin);
        }
        DataSourcePlugin plugin = supported(type, DataSourceCapabilities::wizardSource, "源端");
        return switch (plugin.capabilities().sourceConnector()) {
            case JdbcDataSourcePlugin.JDBC_CONNECTOR -> new JdbcSource(((JdbcDataSourcePlugin) plugin).dialect());
            case HttpDataSourcePlugin.HTTP_CONNECTOR -> HTTP_SOURCE;
            default -> throw Hocon.invalid("向导还不能生成 " + plugin.capabilities().sourceConnector() + " source");
        };
    }

    public static WizardSink sink(String type) {
        DataSourcePlugin plugin = supported(type, DataSourceCapabilities::wizardTarget, "目标端");
        return switch (plugin.capabilities().sinkConnector()) {
            case JdbcDataSourcePlugin.JDBC_CONNECTOR -> new JdbcSink(((JdbcDataSourcePlugin) plugin).dialect());
            case ElasticsearchDataSourcePlugin.ES_CONNECTOR -> ES_SINK;
            default -> throw Hocon.invalid("向导还不能生成 " + plugin.capabilities().sinkConnector() + " sink");
        };
    }

    private static DataSourcePlugin supported(String type, Predicate<DataSourceCapabilities> can, String side) {
        DataSourcePlugin plugin = null;
        try {
            plugin = PLUGINS.get(type);
        } catch (ConsoleException e) {
            // reported below with the types that are supported
        }
        if (plugin == null || !can.test(plugin.capabilities())) {
            List<String> labels = PLUGINS.all().stream().map(DataSourcePlugin::capabilities).filter(can)
                    .map(DataSourceCapabilities::label).toList();
            String list = labels.size() == 1 ? labels.get(0)
                    : String.join("、", labels.subList(0, labels.size() - 1)) + " 和 " + labels.get(labels.size() - 1);
            throw Hocon.invalid("向导的" + side + "只支持 " + list);
        }
        return plugin;
    }
}
