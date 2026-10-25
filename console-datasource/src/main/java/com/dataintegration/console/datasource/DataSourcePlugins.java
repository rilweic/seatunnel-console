package com.dataintegration.console.datasource;

import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.common.ErrorCodes;
import com.dataintegration.console.datasource.es.ElasticsearchDataSourcePlugin;
import com.dataintegration.console.datasource.http.HttpDataSourcePlugin;
import com.dataintegration.console.datasource.jdbc.MySqlDataSourcePlugin;
import com.dataintegration.console.datasource.jdbc.PostgreSqlDataSourcePlugin;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Registry of the built-in plugins, one per {@link DataSourceType}. */
public class DataSourcePlugins {

    private final Map<DataSourceType, DataSourcePlugin> plugins = new EnumMap<>(DataSourceType.class);

    public DataSourcePlugins() {
        this(List.of(
                new MySqlDataSourcePlugin(),
                new PostgreSqlDataSourcePlugin(),
                new ElasticsearchDataSourcePlugin(),
                new HttpDataSourcePlugin()));
    }

    public DataSourcePlugins(List<DataSourcePlugin> plugins) {
        plugins.forEach(p -> this.plugins.put(p.type(), p));
    }

    public DataSourcePlugin get(DataSourceType type) {
        DataSourcePlugin plugin = plugins.get(type);
        if (plugin == null) {
            throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "unsupported datasource type " + type);
        }
        return plugin;
    }

    public DataSourcePlugin get(String type) {
        try {
            return get(DataSourceType.valueOf(type));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "unsupported datasource type " + type);
        }
    }
}
