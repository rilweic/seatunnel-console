package com.dataintegration.console.server.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AutoSourceCountTest {

    private static HoconTemplate template(String source) {
        return HoconTemplate.parse("source {\n" + source + "\n}\nsink { Console {} }");
    }

    @Test
    void wrapsTheQuery() {
        RowCountValidator.AutoSource auto = RowCountValidator.autoSource(template(
                "Jdbc { datasource_id = \"ds1\", query = \"select * from orders where dt = '20261020';\" }"));
        assertThat(auto.datasourceId()).isEqualTo("ds1");
        assertThat(auto.sql()).isEqualTo("select count(*) from (select * from orders where dt = '20261020') t_count");
    }

    @Test
    void countsTheTableWithItsWhereCondition() {
        RowCountValidator.AutoSource auto = RowCountValidator.autoSource(template(
                "Jdbc { datasource_id = \"ds1\", table_path = \"shop.orders\", where_condition = \"where id > 100\" }"));
        assertThat(auto.sql()).isEqualTo("select count(*) from shop.orders where id > 100");
    }

    @Test
    void worksWithTheListLayout() {
        HoconTemplate t = HoconTemplate.parse(
                "source = [{ plugin_name = \"Jdbc\", datasource_id = \"ds1\", table_path = \"t\" }]\nsink = [{ plugin_name = \"Console\" }]");
        assertThat(RowCountValidator.autoSource(t).sql()).isEqualTo("select count(*) from t");
    }

    @Test
    void refusesWhatItCannotDerive() {
        assertThatThrownBy(() -> RowCountValidator.autoSource(template("FakeSource { row.num = 10 }")))
                .hasMessageContaining("exactly one Jdbc source");
        assertThatThrownBy(() -> RowCountValidator.autoSource(template("Jdbc { url = \"jdbc:mysql://x\", query = \"select 1\" }")))
                .hasMessageContaining("datasource_id");
        assertThatThrownBy(() -> RowCountValidator.autoSource(template(
                "Jdbc { datasource_id = \"a\", query = \"select 1\" }\nFakeSource { row.num = 1 }")))
                .hasMessageContaining("exactly one");
    }
}
