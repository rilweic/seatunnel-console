package com.dataintegration.console.datasource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dataintegration.console.common.ConsoleException;

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
