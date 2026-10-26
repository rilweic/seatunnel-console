package com.dataintegration.console.datasource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.datasource.jdbc.JdbcDataSourcePlugin;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

class DataSourcePluginsTest {

    private final DataSourcePlugins plugins = new DataSourcePlugins();

    @Test
    void mysqlMapsToJdbcConnectorOptions() {
        Map<String, Object> options =
                plugins.get(DataSourceType.MYSQL)
                        .toConnectorOptions(
                                "Jdbc",
                                Map.of(
                                        "host", "db1", "port", "3307", "database", "shop",
                                        "params", "useSSL=false", "username", "etl", "password", "p@ss"));

        assertThat(options)
                .containsEntry("url", "jdbc:mysql://db1:3307/shop?useSSL=false")
                .containsEntry("driver", "com.mysql.cj.jdbc.Driver")
                .containsEntry("username", "etl")
                .containsEntry("password", "p@ss");
    }

    @Test
    void mysqlDatabaseIsOptionalButPostgresIsNot() {
        assertThat(plugins.get(DataSourceType.MYSQL).toConnectorOptions("Jdbc", Map.of("host", "db1", "username", "u")))
                .containsEntry("url", "jdbc:mysql://db1:3306/");
        assertThatThrownBy(
                        () -> plugins.get(DataSourceType.POSTGRESQL).validate(Map.of("host", "pg", "username", "u")))
                .isInstanceOf(ConsoleException.class)
                .hasMessageContaining("database");
    }

    @Test
    void postgresUrlUsesDefaultPort() {
        assertThat(
                        plugins.get(DataSourceType.POSTGRESQL)
                                .toConnectorOptions("Jdbc", Map.of("host", "pg", "database", "dw", "username", "u")))
                .containsEntry("url", "jdbc:postgresql://pg:5432/dw")
                .containsEntry("driver", "org.postgresql.Driver");
    }

    @Test
    void mysqlAndPostgresBackTheirCdcSourcesWithoutADriverOption() {
        assertThat(plugins.get(DataSourceType.MYSQL).capabilities().cdcConnector()).isEqualTo("MySQL-CDC");
        assertThat(plugins.get(DataSourceType.MYSQL)
                        .toConnectorOptions("MySQL-CDC", Map.of("host", "db1", "database", "shop", "username", "u", "password", "p")))
                .containsEntry("url", "jdbc:mysql://db1:3306/shop")
                .containsEntry("username", "u")
                .containsEntry("password", "p")
                .doesNotContainKey("driver");
        assertThat(plugins.get(DataSourceType.POSTGRESQL)
                        .toConnectorOptions("Postgres-CDC", Map.of("host", "pg", "database", "dw", "username", "u")))
                .containsEntry("url", "jdbc:postgresql://pg:5432/dw")
                .doesNotContainKey("driver");
        // each database backs only its own CDC source
        assertThatThrownBy(() -> plugins.get(DataSourceType.MYSQL)
                        .toConnectorOptions("Postgres-CDC", Map.of("host", "db", "username", "u")))
                .isInstanceOf(ConsoleException.class);
        assertThat(plugins.get(DataSourceType.ELASTICSEARCH).capabilities().streamSource()).isFalse();
        assertThat(plugins.get(DataSourceType.HTTP).capabilities().streamSource()).isFalse();
    }

    @Test
    void elasticsearchSplitsHostsAndOmitsCredentialsWhenAnonymous() {
        DataSourcePlugin es = plugins.get(DataSourceType.ELASTICSEARCH);

        assertThat(es.toConnectorOptions("Elasticsearch", Map.of("hosts", "http://es1:9200/, http://es2:9200")))
                .containsEntry("hosts", List.of("http://es1:9200", "http://es2:9200"))
                .doesNotContainKey("username");
        assertThatThrownBy(() -> es.validate(Map.of("hosts", "es1:9200")))
                .isInstanceOf(ConsoleException.class)
                .hasMessageContaining("http://");
    }

    @Test
    void httpIsRegisteredByDefault() {
        assertThat(plugins.get(DataSourceType.HTTP).type()).isEqualTo(DataSourceType.HTTP);
        assertThat(plugins.get("HTTP").secretKeys()).containsExactlyInAnyOrder("token", "password", "apiKey");
    }

    @Test
    void everyTypeDeclaresWhatItCanDo() {
        DataSourcePlugins plugins = new DataSourcePlugins();
        assertThat(plugins.all()).extracting(DataSourcePlugin::type).containsExactly(DataSourceType.values());
        for (DataSourcePlugin plugin : plugins.all()) {
            DataSourceCapabilities c = plugin.capabilities();
            assertThat(c.label()).isNotBlank();
            assertThat(c.maxParallelism()).isPositive();
            // a JDBC driver check only makes sense for JDBC databases, which also need a dialect
            assertThat(c.jdbcDriver()).isEqualTo(plugin instanceof JdbcDataSourcePlugin);
        }
        assertThat(plugins.get("MYSQL").capabilities().wizardSource()).isTrue();
        assertThat(plugins.get("ELASTICSEARCH").capabilities().wizardSource()).isFalse();
        assertThat(plugins.get("HTTP").capabilities().wizardTarget()).isFalse();
        assertThat(((JdbcDataSourcePlugin) plugins.get("POSTGRESQL")).dialect().tablePath("app", "public", "t"))
                .isEqualTo("app.public.t");
        assertThat(((JdbcDataSourcePlugin) plugins.get("MYSQL")).dialect().qualified("shop", "a`b"))
                .isEqualTo("`shop`.`a``b`");
    }

    @Test
    void addressesShowWhereEachTypePoints() {
        DataSourcePlugins plugins = new DataSourcePlugins();
        assertThat(plugins.get("MYSQL").address(Map.of("host", "db1", "database", "shop"))).isEqualTo("db1:3306/shop");
        assertThat(plugins.get("POSTGRESQL").address(Map.of("host", "pg", "port", "6432", "database", "app")))
                .isEqualTo("pg:6432/app");
        assertThat(plugins.get("ELASTICSEARCH").address(Map.of("hosts", "http://es:9200"))).isEqualTo("http://es:9200");
        assertThat(plugins.get("HTTP").address(Map.of("baseUrl", "https://api.example.com"))).isEqualTo("https://api.example.com");
    }

    @Test
    void refusesConnectorsTheTypeCannotBack() {
        assertThatThrownBy(
                        () -> plugins.get(DataSourceType.MYSQL)
                                .toConnectorOptions("Elasticsearch", Map.of("host", "db", "username", "u")))
                .isInstanceOf(ConsoleException.class)
                .hasMessageContaining("cannot be used with connector Elasticsearch");
    }

    @Test
    void invalidPortIsRejected() {
        assertThatThrownBy(
                        () -> plugins.get(DataSourceType.MYSQL)
                                .validate(Map.of("host", "db", "port", "99999", "username", "u")))
                .isInstanceOf(ConsoleException.class)
                .hasMessageContaining("port");
    }

    @Test
    void unreachableDatabaseFailsTestWithoutThrowing() {
        ConnectionTestResult result =
                plugins.get(DataSourceType.MYSQL)
                        .testConnection(Map.of("host", "127.0.0.1", "port", "1", "username", "u", "password", "p"));

        assertThat(result.success()).isFalse();
        assertThat(result.message()).isNotBlank();
    }

    @Test
    void passwordsInJdbcParamsAreRejectedBecauseParamsAreStoredInPlainText() {
        Map<String, String> base = Map.of("host", "db1", "database", "shop", "username", "etl");
        DataSourcePlugin mysql = plugins.get(DataSourceType.MYSQL);

        mysql.validate(withParams(base, "useSSL=false&serverTimezone=Asia/Shanghai"));
        assertThatThrownBy(() -> mysql.validate(withParams(base, "useSSL=false&password=p@ss")))
                .isInstanceOf(ConsoleException.class)
                .hasMessageContaining("password");
        assertThatThrownBy(() -> plugins.get(DataSourceType.POSTGRESQL).validate(withParams(base, "?sslmode=require&sslpassword=x")))
                .isInstanceOf(ConsoleException.class);
        assertThatThrownBy(() -> mysql.validate(withParams(base, "%70assword=Secret123")))
                .isInstanceOf(ConsoleException.class);
        assertThatThrownBy(() -> mysql.validate(withParams(base, "clientCertificateKeyStorePassword=ks")))
                .isInstanceOf(ConsoleException.class);
    }

    private static Map<String, String> withParams(Map<String, String> props, String params) {
        Map<String, String> copy = new HashMap<>(props);
        copy.put("params", params);
        return copy;
    }
}
