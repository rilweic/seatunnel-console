package com.dataintegration.console.server.job.wizard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dataintegration.console.datasource.http.HttpPreviewRequest;
import com.dataintegration.console.datasource.meta.ColumnMeta;
import com.dataintegration.console.datasource.meta.TableMeta;
import com.dataintegration.console.datasource.http.HttpPreviewResponse;
import com.dataintegration.console.server.datasource.DatasourceService;
import com.dataintegration.console.server.encryptkey.EncryptKeyService;
import com.dataintegration.console.server.job.wizard.WizardForm.DataMode;
import com.dataintegration.console.server.job.wizard.WizardForm.HttpField;
import com.dataintegration.console.server.job.wizard.WizardForm.HttpMethod;
import com.dataintegration.console.server.job.wizard.WizardForm.HttpPageLocation;
import com.dataintegration.console.server.job.wizard.WizardForm.HttpPaging;
import com.dataintegration.console.server.job.wizard.WizardForm.HttpPagingMode;
import com.dataintegration.console.server.job.wizard.WizardForm.HttpSpec;
import com.dataintegration.console.server.job.wizard.WizardForm.SchemaMode;
import com.dataintegration.console.server.job.wizard.WizardForm.Sync;
import com.dataintegration.console.server.job.wizard.WizardForm.SyncMode;
import com.dataintegration.console.server.job.wizard.WizardForm.TableSpec;
import com.dataintegration.console.server.job.wizard.WizardForm.Target;
import com.dataintegration.console.server.job.wizard.WizardForm.WriteMode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

@ExtendWith(MockitoExtension.class)
class WizardServiceTest {

    @Mock private DatasourceService datasourceService;
    @Mock private EncryptKeyService encryptKeyService;
    @InjectMocks private WizardService wizardService;

    @Test
    void previewHttpUsesParamsStartPageAndVariablesAndReturnsDatasourceResponse() {
        HttpPaging paging = new HttpPaging(
                HttpPagingMode.PAGE_NUMBER, "pageNo", HttpPageLocation.PARAMS, 3L, "size", 50, null, null, null);
        HttpSpec http = new HttpSpec("/orders/${tenant}", HttpMethod.GET,
                Map.of("tenant", "${tenant}", "pageNo", "999"), null, paging,
                "$", List.of(new HttpField("id", "BIGINT", null)), List.of("id"));
        HttpPreviewResponse response = new HttpPreviewResponse(201, 42, "{\"created\":true}");
        when(datasourceService.previewHttp(eq("http-ds"), any())).thenReturn(response);

        WizardService.HttpPreviewResult result = wizardService.previewHttp(form(http), Map.of("tenant", "acme"));

        assertThat(result).isEqualTo(new WizardService.HttpPreviewResult(201, 42, "{\"created\":true}"));
        ArgumentCaptor<HttpPreviewRequest> request = ArgumentCaptor.forClass(HttpPreviewRequest.class);
        verify(datasourceService).previewHttp(eq("http-ds"), request.capture());
        assertThat(request.getValue().path()).isEqualTo("/orders/acme");
        assertThat(request.getValue().method()).isEqualTo("GET");
        assertThat(request.getValue().params()).containsEntry("tenant", "acme")
                .containsEntry("pageNo", "3").containsEntry("size", "50");
    }

    @Test
    void previewHttpPopulatesPageAndPageSizeIntoPostBody() {
        HttpPaging paging = new HttpPaging(
                HttpPagingMode.PAGE_NUMBER, "pageNo", HttpPageLocation.BODY, 0L, "size", 25, null, null, null);
        HttpSpec http = new HttpSpec("/orders", HttpMethod.POST, null, "{\"tenant\":\"${tenant}\"}", paging,
                "$", List.of(new HttpField("id", "BIGINT", null)), List.of("id"));
        when(datasourceService.previewHttp(eq("http-ds"), any()))
                .thenReturn(new HttpPreviewResponse(200, 5, "[]"));

        wizardService.previewHttp(form(http), Map.of("tenant", "acme"));

        ArgumentCaptor<HttpPreviewRequest> request = ArgumentCaptor.forClass(HttpPreviewRequest.class);
        verify(datasourceService).previewHttp(eq("http-ds"), request.capture());
        assertThat(request.getValue().body()).isEqualTo("{\"tenant\":\"acme\",\"pageNo\":\"0\",\"size\":25}");
    }

    @Test
    void previewHttpRemovesCursorParameterFromFirstPage() {
        HttpPaging paging = new HttpPaging(
                HttpPagingMode.CURSOR, null, HttpPageLocation.PARAMS, null, null, null, null, "next", "$.next");
        HttpSpec http = new HttpSpec("/orders", HttpMethod.GET,
                Map.of("tenant", "acme", "next", "stale-cursor"), null, paging,
                "$", List.of(new HttpField("id", "BIGINT", null)), List.of("id"));
        when(datasourceService.previewHttp(eq("http-ds"), any()))
                .thenReturn(new HttpPreviewResponse(200, 5, "[]"));

        wizardService.previewHttp(form(http), Map.of());

        ArgumentCaptor<HttpPreviewRequest> request = ArgumentCaptor.forClass(HttpPreviewRequest.class);
        verify(datasourceService).previewHttp(eq("http-ds"), request.capture());
        assertThat(request.getValue().params()).containsOnly(Map.entry("tenant", "acme"));
    }

    @Test
    void precheckHttpUsesPreviewJsonAndContinuesToTargetChecks() {
        mockHttpTypes();
        HttpSpec http = http("$.data");
        when(datasourceService.previewHttp(eq("http-ds"), any()))
                .thenReturn(new HttpPreviewResponse(200, 8, "{\"data\":[{\"id\":1}]}"));
        when(datasourceService.namespaces("mysql-ds")).thenReturn(List.of());

        WizardService.PrecheckResult result = wizardService.precheck(form(http), Map.of());

        assertThat(result.generated()).isNotNull();
        assertThat(result.errors()).containsExactly("目标端不存在库或 schema ods（不会自动建库）");
        verify(datasourceService).namespaces("mysql-ds");
        verify(datasourceService, never()).namespaces("http-ds");
        verify(datasourceService, never()).tables(eq("http-ds"), any());
        verify(datasourceService, never()).columns(eq("http-ds"), any(), anyString());
    }

    @Test
    void precheckHttpRejectsNonArrayContent() {
        mockHttpTypes();
        when(datasourceService.previewHttp(eq("http-ds"), any()))
                .thenReturn(new HttpPreviewResponse(200, 8, "{\"data\":{\"id\":1}}"));

        WizardService.PrecheckResult result = wizardService.precheck(form(http("$.data")), Map.of());

        assertThat(result.errors()).anyMatch(message -> message.contains("content_field 没有取到 JSON 数组"));
    }

    @Test
    void precheckHttpRejectsNon2xxResponse() {
        mockHttpTypes();
        when(datasourceService.previewHttp(eq("http-ds"), any()))
                .thenReturn(new HttpPreviewResponse(503, 8, "unavailable"));

        WizardService.PrecheckResult result = wizardService.precheck(form(http("$")), Map.of());

        assertThat(result.errors()).contains("HTTP 请求返回状态 503");
    }

    @Test
    void precheckHttpRejectsFieldsMissingFromPreviewRecords() {
        mockHttpTypes();
        when(datasourceService.previewHttp(eq("http-ds"), any()))
                .thenReturn(new HttpPreviewResponse(200, 8, "[{\"other\":1}]"));

        WizardService.PrecheckResult result = wizardService.precheck(form(http("$")), Map.of());

        assertThat(result.errors()).anyMatch(message -> message.contains("前几条记录里找不到字段 id"));
    }

    @Test
    void precheckHttpAcceptsRootArrayAndPlainFieldName() {
        mockHttpTypes();
        when(datasourceService.previewHttp(eq("http-ds"), any()))
                .thenReturn(new HttpPreviewResponse(200, 8, "[{\"id\":1},{\"id\":2}]"));
        when(datasourceService.namespaces("mysql-ds")).thenReturn(List.of());

        WizardService.PrecheckResult result = wizardService.precheck(form(http("$")), Map.of());

        assertThat(result.errors()).containsExactly("目标端不存在库或 schema ods（不会自动建库）");
    }

    @Test
    void precheckHttpSupportsArrayIndexesAndQuotedFieldNames() {
        mockHttpTypes();
        HttpSpec http = new HttpSpec("/orders", HttpMethod.GET, null, null, null, "$",
                List.of(
                        new HttpField("order-id", "BIGINT", "$['order-id']"),
                        new HttpField("first", "STRING", "$.items[0].name")),
                null);
        when(datasourceService.previewHttp(eq("http-ds"), any()))
                .thenReturn(new HttpPreviewResponse(200, 8,
                        "[{\"order-id\":1,\"items\":[{\"name\":\"first\"}]}]"));
        when(datasourceService.namespaces("mysql-ds")).thenReturn(List.of());

        WizardService.PrecheckResult result = wizardService.precheck(form(http), Map.of());

        assertThat(result.errors()).containsExactly("目标端不存在库或 schema ods（不会自动建库）");
    }

    @Test
    void precheckHttpRejectsInvalidJsonPathInsteadOfSkippingTokens() {
        mockHttpTypes();
        when(datasourceService.previewHttp(eq("http-ds"), any()))
                .thenReturn(new HttpPreviewResponse(200, 8, "{\"data\":[{\"id\":1}]}"));

        WizardService.PrecheckResult result = wizardService.precheck(form(http("$.data[0")), Map.of());

        assertThat(result.errors()).anyMatch(message -> message.contains("JSON 路径不合法"));
    }

    @Test
    void precheckHttpValidatesFieldPathsWhenFirstPageIsEmpty() {
        mockHttpTypes();
        HttpSpec http = new HttpSpec("/orders", HttpMethod.GET, null, null, null, "$",
                List.of(new HttpField("id", "BIGINT", "$.id[0")), null);
        when(datasourceService.previewHttp(eq("http-ds"), any()))
                .thenReturn(new HttpPreviewResponse(200, 8, "[]"));

        WizardService.PrecheckResult result = wizardService.precheck(form(http), Map.of());

        assertThat(result.errors()).anyMatch(message -> message.contains("JSON 路径不合法"));
    }

    @Test
    void precheckHttpRequiresExplicitPathForSpecialFieldNames() {
        mockHttpTypes();
        HttpSpec http = new HttpSpec("/orders", HttpMethod.GET, null, null, null, "$",
                List.of(new HttpField("order.id", "BIGINT", null)), null);
        when(datasourceService.previewHttp(eq("http-ds"), any()))
                .thenReturn(new HttpPreviewResponse(200, 8, "[]"));

        WizardService.PrecheckResult result = wizardService.precheck(form(http), Map.of());

        assertThat(result.errors()).anyMatch(message -> message.contains("请填写明确的 jsonPath"));
    }

    @Test
    void precheckHttpRejectsExplicitRelativeJsonPath() {
        mockHttpTypes();
        HttpSpec http = new HttpSpec("/orders", HttpMethod.GET, null, null, null, "$",
                List.of(new HttpField("id", "BIGINT", "bad path")), null);
        WizardService.PrecheckResult result = wizardService.precheck(form(http), Map.of());

        assertThat(result.errors()).anyMatch(message -> message.contains("jsonPath 必须以 $ 开头"));
        verify(datasourceService, never()).previewHttp(eq("http-ds"), any());
    }

    @Test
    void precheckJdbcReportsMissingTablesIncrementalFieldsAndKeysThroughTheSourcePlugin() {
        when(datasourceService.typeOf("pg-ds")).thenReturn("POSTGRESQL");
        when(datasourceService.typeOf("mysql-ds")).thenReturn("MYSQL");
        when(datasourceService.property("pg-ds", "database")).thenReturn("app");
        when(datasourceService.namespaces("pg-ds")).thenReturn(List.of("public"));
        when(datasourceService.tables("pg-ds", "public")).thenReturn(List.of(
                new TableMeta("orders", List.of("id")), new TableMeta("users", List.of())));
        when(datasourceService.columns("pg-ds", "public", "orders")).thenReturn(List.of(
                new ColumnMeta("id", "int8", false, true, false), new ColumnMeta("updated_at", "timestamp", true, false, true)));
        when(datasourceService.columns("pg-ds", "public", "users")).thenReturn(List.of(
                new ColumnMeta("id", "int8", false, false, false), new ColumnMeta("updated_at", "varchar(20)", true, false, false)));
        // the generated incremental queries run under "where 1 = 0"
        when(datasourceService.count(eq("pg-ds"), any())).thenReturn(0L);
        when(datasourceService.namespaces("mysql-ds")).thenReturn(List.of("ods"));
        when(datasourceService.tables("mysql-ds", "ods")).thenReturn(List.of());
        WizardForm form = new WizardForm(
                new WizardForm.Source("pg-ds", "public", List.of(
                        new TableSpec("orders", null, null, null), new TableSpec("users", null, null, null),
                        new TableSpec("ghost", null, null, null))),
                new Target("mysql-ds", "ods", null, null, null, SchemaMode.CREATE, DataMode.KEEP, WriteMode.UPSERT),
                new Sync(SyncMode.INCREMENTAL, WizardForm.Window.DAY, "updated_at", null),
                1, false, null, null);

        WizardService.PrecheckResult result = wizardService.precheck(form, Map.of());

        assertThat(result.errors()).containsExactlyInAnyOrder(
                "源表不存在：ghost",
                "users：增量字段 updated_at 是 varchar(20)，不是时间类型；数字型字段请用\"自定义条件\"");
        assertThat(result.checks()).extracting(WizardService.Check::message)
                .contains("users：没有主键，只能按追加方式写入", "目标表 orders 不存在，运行时自动创建");
        assertThat(result.generated().hocon()).contains("table_path = \"app.public.orders\"");
        verify(datasourceService, org.mockito.Mockito.times(2)).count(eq("pg-ds"), any());
    }

    private void mockHttpTypes() {
        when(datasourceService.typeOf("http-ds")).thenReturn("HTTP");
        when(datasourceService.typeOf("mysql-ds")).thenReturn("MYSQL");
    }

    private static HttpSpec http(String contentField) {
        return new HttpSpec("/orders", HttpMethod.GET, null, null, null, contentField,
                List.of(new HttpField("id", "BIGINT", null)), List.of("id"));
    }

    private static WizardForm form(HttpSpec http) {
        return new WizardForm(
                new WizardForm.Source("http-ds", null, List.of(new TableSpec("orders", null, null, null)), http),
                new Target("mysql-ds", "ods", null, null, null, SchemaMode.CREATE, DataMode.KEEP, WriteMode.UPSERT),
                new Sync(SyncMode.INCREMENTAL, null, null, null),
                1,
                false,
                null,
                null);
    }
}
