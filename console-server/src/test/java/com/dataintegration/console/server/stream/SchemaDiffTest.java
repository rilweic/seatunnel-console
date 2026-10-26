package com.dataintegration.console.server.stream;

import static org.assertj.core.api.Assertions.assertThat;

import com.dataintegration.console.datasource.meta.ColumnMeta;

import org.junit.jupiter.api.Test;

import java.util.List;

class SchemaDiffTest {

    private static ColumnMeta col(String name, String type) {
        return new ColumnMeta(name, type, true, false, false);
    }

    @Test
    void tellsAddedDroppedRenamedAndRetypedColumnsApart() {
        List<ColumnMeta> before = List.of(col("id", "INT"), col("name", "VARCHAR(32)"), col("amount", "INT"), col("secret", "VARCHAR(8)"));
        List<ColumnMeta> after = List.of(col("ID", "INT"), col("title", "VARCHAR(32)"), col("amount", "BIGINT"), col("remark", "TEXT"));

        SchemaDiff diff = SchemaDiff.between(before, after);

        assertThat(diff.renamed()).containsExactly(new SchemaDiff.Renamed("name", "title"));
        assertThat(diff.retyped()).containsExactly(new SchemaDiff.Retyped("amount", "INT", "BIGINT"));
        assertThat(diff.added()).extracting(ColumnMeta::name).containsExactly("remark");
        assertThat(diff.dropped()).extracting(ColumnMeta::name).containsExactly("secret");
        assertThat(diff.lines()).containsExactly(
                "新增列 remark TEXT", "列改名 name → title", "列 amount 类型 INT → BIGINT", "删除列 secret");
        assertThat(SchemaDiff.between(before, before).empty()).isTrue();
    }
}
