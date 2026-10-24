package com.dataintegration.console.server.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.server.job.HoconTemplate.DatasourceRef;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class HoconTemplateTest {

    private static final String KEYED =
            """
            env { job.mode = "BATCH" }
            source {
              Jdbc {
                datasource_id = "ds-1"
                query = "select * from orders where dt = '${biz_date}'"
                plugin_output = "orders"
              }
            }
            sink {
              Jdbc {
                datasource_id = "ds-2"
                username = "override"
                plugin_input = ["orders"]
              }
            }
            """;

    private static final HoconTemplate.OptionsResolver RESOLVER =
            (connector, id) -> Map.of(
                    "url", "jdbc:mysql://" + id + ":3306/db",
                    "driver", "com.mysql.cj.jdbc.Driver",
                    "username", "etl",
                    "password", "p@ss");

    @Test
    void findsDatasourceReferencesInKeyedLayout() {
        assertThat(HoconTemplate.parse(KEYED).datasourceRefs())
                .containsExactlyInAnyOrder(
                        new DatasourceRef("source", "Jdbc", "ds-1"), new DatasourceRef("sink", "Jdbc", "ds-2"));
    }

    @Test
    void renderInjectsOptionsRemovesReferenceAndKeepsExplicitValues() {
        String rendered = HoconTemplate.parse(KEYED).render(RESOLVER);

        assertThat(rendered)
                .doesNotContain("datasource_id")
                .contains("\"url\":\"jdbc:mysql://ds-1:3306/db\"")
                .contains("\"password\":\"p@ss\"")
                .contains("\"username\":\"override\"")
                // quoted substitution text is left for SeaTunnel untouched
                .contains("dt = '${biz_date}'");
    }

    @Test
    void understandsListLayoutWithPluginName() {
        String list =
                """
                source = [{ plugin_name = "Jdbc", datasource_id = "ds-1", query = "select 1" }]
                sink = [{ plugin_name = "Console" }]
                """;

        HoconTemplate template = HoconTemplate.parse(list);

        assertThat(template.datasourceRefs()).containsExactly(new DatasourceRef("source", "Jdbc", "ds-1"));
        assertThat(template.render(RESOLVER)).contains("jdbc:mysql://ds-1:3306/db").doesNotContain("datasource_id");
    }

    @Test
    void blocksWithoutReferenceStayAsWritten() {
        String plain = "source { FakeSource { row.num = 5 } }\nsink { Console {} }";

        assertThat(HoconTemplate.parse(plain).datasourceRefs()).isEmpty();
        assertThat(HoconTemplate.parse(plain).render((c, id) -> { throw new AssertionError("not called"); }))
                .contains("FakeSource");
    }

    @Test
    void reportsJobMode() {
        assertThat(HoconTemplate.parse(KEYED).jobMode()).isEqualTo("BATCH");
        assertThat(HoconTemplate.parse("source { A {} }\nsink { B {} }").jobMode()).isNull();
    }

    @Test
    void rejectsBrokenOrIncompleteConfig() {
        assertThatThrownBy(() -> HoconTemplate.parse("source { Jdbc { "))
                .isInstanceOf(ConsoleException.class)
                .hasMessageContaining("invalid HOCON");
        assertThatThrownBy(() -> HoconTemplate.parse("source { A {} }"))
                .isInstanceOf(ConsoleException.class)
                .hasMessageContaining("no sink");
        assertThatThrownBy(() -> HoconTemplate.parse("source { A { datasource_id = 3 } }\nsink { B {} }").datasourceRefs())
                .isInstanceOf(ConsoleException.class)
                .hasMessageContaining("must be a string");
    }

    @Test
    void secretsNeverAppearWhenRenderedMasked() {
        String masked = HoconTemplate.parse(KEYED).render((connector, id) -> Map.of("password", "******", "url", "u"));

        assertThat(masked).doesNotContain("p@ss").contains("******");
        assertThat(List.of(masked)).isNotEmpty();
    }

    @Test
    void findsAndMasksCredentialsWrittenIntoTheConfig() {
        HoconTemplate template = HoconTemplate.parse(
                """
                env { job.mode = "BATCH" }
                source {
                  Jdbc {
                    url = "jdbc:mysql://db/shop"
                    user = "etl"
                    password = "p@ss"
                    query = "select 1"
                  }
                }
                sink {
                  Elasticsearch {
                    datasource_id = "ds-es"
                    tls { keystore_password = "k" }
                  }
                }
                """);

        assertThat(template.explicitSecrets())
                .containsExactlyInAnyOrder("source.Jdbc.password", "sink.Elasticsearch.tls.keystore_password");

        String masked = template.renderMasked((connector, id) -> Map.of("hosts", List.of("http://es:9200"), "password", "******"));
        assertThat(masked).doesNotContain("p@ss").doesNotContain("\"k\"").contains("******").contains("select 1");
    }

    @Test
    void credentialsInListsObjectsAndUrlsAreFoundAndMasked() {
        HoconTemplate template = HoconTemplate.parse(
                """
                env { job.mode = "BATCH", password = ["p@ss"] }
                source {
                  Jdbc {
                    url = "jdbc:postgresql://db/shop?sslmode=require&password=s3cret"
                    query = "select 1"
                    secret { value = "v" }
                  }
                }
                transform = [{ plugin_name = "Http", hosts = ["http://u:hunter2@api:80"] }]
                sink { Console {} }
                """);

        assertThat(template.explicitSecrets()).containsExactlyInAnyOrder(
                "env.password", "source.Jdbc.url", "source.Jdbc.secret", "transform[0].hosts[0]");

        String masked = template.renderMasked((connector, id) -> Map.of());
        assertThat(masked).doesNotContain("p@ss").doesNotContain("s3cret").doesNotContain("hunter2").doesNotContain("\"v\"")
                .contains("sslmode=require").contains("u:******@api");
    }

    @Test
    void credentialParametersAreJudgedOnTheirDecodedName() {
        HoconTemplate template = HoconTemplate.parse(
                """
                env { job.mode = "BATCH" }
                source {
                  Jdbc {
                    url = "jdbc:mysql://db/shop?useSSL=true&clientCertificateKeyStorePassword=ks1&%70assword=enc2"
                    query = "select * from t where a=1 and token_count > 0"
                  }
                }
                sink { Console {} }
                """);

        assertThat(template.explicitSecrets()).containsExactly("source.Jdbc.url");
        String masked = template.renderMasked((connector, id) -> Map.of());
        assertThat(masked).doesNotContain("ks1").doesNotContain("enc2")
                .contains("useSSL=true").contains("where a=1 and token_count > 0");
    }

    @Test
    void encryptionKeysGoInAtSubmitAndNeverIntoArchivesOrSavedScripts() {
        HoconTemplate template = HoconTemplate.parse("""
                source { FakeSource { plugin_output = "s" } }
                transform {
                  FieldEncrypt {
                    plugin_input = ["s"]
                    table_match_regex = "(?!)"
                    fields = ["phone"], encrypt_key_id = "k1"
                    table_transform = [{ table_path = "db.users", fields = ["phone"], encrypt_key_id = "k1" }]
                  }
                }
                sink { Console { plugin_input = ["s"] } }
                """);
        assertThat(template.encryptKeyRefs()).containsExactly("k1", "k1");
        assertThat(template.explicitSecrets()).isEmpty();

        String real = template.render((connector, id) -> Map.of(), id -> id.equals("k1") ? "AAAAAAAAAAAAAAAAAAAAAA==" : null);
        assertThat(real).doesNotContain("encrypt_key_id").contains("\"key\":\"AAAAAAAAAAAAAAAAAAAAAA==\"");
        assertThat(real.split("AAAAAAAAAAAAAAAAAAAAAA==", -1)).hasSize(3);

        String masked = template.renderMasked((connector, id) -> Map.of());
        assertThat(masked).doesNotContain("encrypt_key_id").doesNotContain("AAAA").contains("\"key\":\"******\"");

        // a key written into a script is a plain-text secret, refused on save and masked in old archives
        HoconTemplate script = HoconTemplate.parse("""
                source { FakeSource { plugin_output = "s" } }
                transform { FieldEncrypt { fields = ["phone"], key = "c2VjcmV0c2VjcmV0c2VjcmV0" } }
                sink { Console { plugin_input = ["s"] } }
                """);
        assertThat(script.explicitSecrets()).containsExactly("transform.FieldEncrypt.key");
        assertThat(script.renderMasked((connector, id) -> Map.of())).doesNotContain("c2VjcmV0").contains("******");
    }
}
