package com.dataintegration.console.server.datasource;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.common.ErrorCodes;
import com.dataintegration.console.common.PageResult;
import com.dataintegration.console.datasource.ConnectionTestResult;
import com.dataintegration.console.datasource.cdc.CdcCheck;
import com.dataintegration.console.datasource.cdc.CdcSupport;
import com.dataintegration.console.datasource.CountRequest;
import com.dataintegration.console.datasource.meta.ColumnMeta;
import com.dataintegration.console.datasource.meta.TableMeta;
import com.dataintegration.console.datasource.DataSourceCapabilities;
import com.dataintegration.console.datasource.DataSourcePlugin;
import com.dataintegration.console.datasource.DataSourcePlugins;
import com.dataintegration.console.datasource.http.HttpDataSourcePlugin;
import com.dataintegration.console.datasource.http.HttpPreviewRequest;
import com.dataintegration.console.datasource.http.HttpPreviewResponse;
import com.dataintegration.console.server.auth.Access;
import com.dataintegration.console.server.auth.CurrentUser;
import com.dataintegration.console.server.crypto.SecretCipher;
import com.dataintegration.console.server.persistence.entity.DatasourceEntity;
import com.dataintegration.console.server.persistence.entity.JobDefinition;
import com.dataintegration.console.server.persistence.entity.JobVersionDatasource;
import com.dataintegration.console.server.persistence.mapper.DatasourceMapper;
import com.dataintegration.console.server.persistence.mapper.JobDefinitionMapper;
import com.dataintegration.console.server.persistence.mapper.JobVersionDatasourceMapper;
import com.dataintegration.console.server.user.UserDirectory;
import com.dataintegration.console.server.web.Pages;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Datasource center (docs/design.md §6). Secret properties are split off and encrypted on save,
 * never returned to the browser, and decrypted only when a job is submitted or a connection tested.
 */
@Service
public class DatasourceService {

    /**
     * @param config non-secret properties
     * @param secretsSet names of secret properties that currently have a value, so the form can show
     *     "unchanged" placeholders
     */
    public record DatasourceView(
            String id,
            String name,
            String type,
            String description,
            String address,
            Map<String, String> config,
            Set<String> secretsSet,
            String ownerId,
            String ownerName,
            boolean editable,
            String createdAt,
            String updatedAt) {}

    private static final TypeReference<Map<String, String>> MAP_TYPE = new TypeReference<>() {};

    private final DatasourceMapper datasourceMapper;
    private final JobVersionDatasourceMapper versionDatasourceMapper;
    private final JobDefinitionMapper jobMapper;
    private final DataSourcePlugins plugins;
    private final SecretCipher cipher;
    private final ObjectMapper objectMapper;
    private final UserDirectory users;

    public DatasourceService(
            DatasourceMapper datasourceMapper,
            JobVersionDatasourceMapper versionDatasourceMapper,
            JobDefinitionMapper jobMapper,
            DataSourcePlugins plugins,
            SecretCipher cipher,
            ObjectMapper objectMapper,
            UserDirectory users) {
        this.datasourceMapper = datasourceMapper;
        this.versionDatasourceMapper = versionDatasourceMapper;
        this.jobMapper = jobMapper;
        this.plugins = plugins;
        this.cipher = cipher;
        this.objectMapper = objectMapper;
        this.users = users;
    }

    /**
     * @param ownerId only datasources of this user, e.g. a job's owner when picking its datasources
     */
    public PageResult<DatasourceView> list(String keyword, String type, String ownerId, Integer page, Integer size) {
        Page<DatasourceEntity> result =
                datasourceMapper.selectPage(
                        Pages.of(page, size),
                        new LambdaQueryWrapper<DatasourceEntity>()
                                .like(keyword != null && !keyword.isBlank(), DatasourceEntity::getName, Pages.literal(keyword))
                                .eq(type != null && !type.isBlank(), DatasourceEntity::getType, type)
                                .eq(ownerId != null && !ownerId.isBlank(), DatasourceEntity::getCreatedBy, ownerId)
                                .orderByDesc(DatasourceEntity::getCreatedAt));
        Map<String, String> owners = users.names(result.getRecords().stream().map(DatasourceEntity::getCreatedBy).toList());
        return Pages.result(result).map(e -> view(e, owners));
    }

    public DatasourceView get(String id) {
        DatasourceEntity entity = require(id);
        return view(entity, users.names(Collections.singletonList(entity.getCreatedBy())));
    }

    public DatasourceView create(String name, String type, String description, Map<String, String> props) {
        DataSourcePlugin plugin = plugins.get(type);
        Map<String, String> cleaned = clean(props);
        plugin.validate(cleaned);
        DatasourceEntity entity = new DatasourceEntity();
        entity.setName(name.trim());
        entity.setType(plugin.type().name());
        entity.setDescription(description);
        entity.setCreatedBy(CurrentUser.idOrNull());
        store(entity, plugin, cleaned);
        datasourceMapper.insert(entity);
        return get(entity.getId());
    }

    /** Type cannot change. A secret left empty keeps its saved value. */
    public DatasourceView update(String id, String name, String description, Map<String, String> props) {
        DatasourceEntity entity = requireEditable(id);
        DataSourcePlugin plugin = plugins.get(entity.getType());
        Map<String, String> merged = mergeSecrets(entity, plugin, clean(props));
        plugin.validate(merged);
        // Only the edited columns: the whole row read above would put back an owner changed by a
        // concurrent transfer.
        DatasourceEntity update = new DatasourceEntity();
        update.setId(id);
        update.setName(name.trim());
        store(update, plugin, merged);
        datasourceMapper.update(update, new LambdaUpdateWrapper<DatasourceEntity>()
                .set(DatasourceEntity::getDescription, description)
                .set(DatasourceEntity::getSecretCipher, update.getSecretCipher())
                .eq(DatasourceEntity::getId, id));
        return get(id);
    }

    /**
     * Tests unsaved properties. When {@code datasourceId} is given (editing an existing datasource),
     * secrets the user did not retype are taken from the saved datasource.
     */
    public ConnectionTestResult test(String datasourceId, String type, Map<String, String> props) {
        Map<String, String> cleaned = clean(props);
        if (datasourceId != null && !datasourceId.isBlank()) {
            DatasourceEntity entity = requireEditable(datasourceId);
            return plugins.get(entity.getType()).testConnection(mergeSecrets(entity, plugins.get(entity.getType()), cleaned));
        }
        return plugins.get(type).testConnection(cleaned);
    }

    public ConnectionTestResult testSaved(String id) {
        DatasourceEntity entity = requireEditable(id);
        return plugins.get(entity.getType()).testConnection(decryptedProps(entity));
    }

    /**
     * Refused while the current version of any live job still references this datasource. The row
     * is locked first (before any other read of this transaction), so a version saved concurrently
     * either committed before and is seen, or waits and then finds the datasource gone.
     */
    @Transactional
    public void delete(String id) {
        requireLocked(id);
        requireEditable(id);
        List<String> users = jobsUsing(id);
        if (!users.isEmpty()) {
            throw new ConsoleException(ErrorCodes.CONFLICT, "datasource is used by jobs: " + String.join(", ", users));
        }
        datasourceMapper.deleteById(id);
    }

    /**
     * Connector options for submitting a job. Decrypted secrets only live in the returned map.
     *
     * @param masked replace secret values with {@code ******}, for the archived config of a run
     */
    public Map<String, Object> connectorOptions(String datasourceId, String connector, boolean masked) {
        DatasourceEntity entity = datasourceMapper.selectById(datasourceId);
        if (entity == null) {
            throw new ConsoleException(ErrorCodes.NOT_FOUND, "datasource not found or deleted: " + datasourceId);
        }
        DataSourcePlugin plugin = plugins.get(entity.getType());
        Map<String, String> props = decryptedProps(entity);
        if (masked) {
            plugin.secretKeys().forEach(key -> props.computeIfPresent(key, (k, v) -> "******"));
        }
        return plugin.toConnectorOptions(connector, props);
    }

    /** Sends one HTTP request with the saved credentials; the response is never persisted. */
    public HttpPreviewResponse previewHttp(String datasourceId, HttpPreviewRequest request) {
        DatasourceEntity entity = requireEditable(datasourceId);
        if (!(plugins.get(entity.getType()) instanceof HttpDataSourcePlugin plugin)) {
            throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "HTTP preview requires an HTTP datasource");
        }
        return plugin.preview(decryptedProps(entity), request);
    }

    /** Runs a validation count against the datasource with its real credentials. */
    public long count(String datasourceId, CountRequest request) {
        DatasourceEntity entity = require(datasourceId);
        return plugins.get(entity.getType()).count(decryptedProps(entity), request);
    }

    public List<String> namespaces(String datasourceId) {
        DatasourceEntity entity = requireEditable(datasourceId);
        return plugins.get(entity.getType()).namespaces(decryptedProps(entity));
    }

    public List<TableMeta> tables(String datasourceId, String namespace) {
        DatasourceEntity entity = requireEditable(datasourceId);
        return plugins.get(entity.getType()).tables(decryptedProps(entity), namespace);
    }

    public List<ColumnMeta> columns(String datasourceId, String namespace, String table) {
        DatasourceEntity entity = requireEditable(datasourceId);
        return plugins.get(entity.getType()).columns(decryptedProps(entity), namespace, table);
    }

    /** Whether the datasource is set up for real-time jobs (docs/design.md §12 P2), for its page. */
    public List<CdcCheck> cdcCheck(String id) {
        DatasourceEntity entity = requireEditable(id);
        return cdcSupport(entity).check(decryptedProps(entity), CdcSupport.Scope.server());
    }

    /**
     * The same for a job's tables, called by prechecks and starts that have checked the job's
     * rights already (the job may only use its creator's datasources).
     */
    public List<CdcCheck> cdcCheck(String id, CdcSupport.Scope scope) {
        DatasourceEntity entity = require(id);
        return cdcSupport(entity).check(decryptedProps(entity), scope);
    }

    /** A job's replication resource on the datasource, or null when there is none. */
    public CdcSupport.Replication replication(String id, String name) {
        DatasourceEntity entity = require(id);
        return cdcSupport(entity).replication(decryptedProps(entity), name);
    }

    /** Drops a deleted job's replication resources; returns the SQL left to run by hand. */
    public List<String> dropReplication(String id, String name) {
        DatasourceEntity entity = require(id);
        return cdcSupport(entity).dropReplication(decryptedProps(entity), name);
    }

    private CdcSupport cdcSupport(DatasourceEntity entity) {
        CdcSupport support = plugins.get(entity.getType()).cdcSupport();
        if (support == null) {
            throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, entity.getType() + " 数据源不能作为实时同步的来源");
        }
        return support;
    }

    /** A saved, non-secret property such as the PostgreSQL database name. */
    public String property(String datasourceId, String key) {
        return config(require(datasourceId)).get(key);
    }

    /** Where the datasource points, for the list; empty when its type is no longer supported. */
    private String address(DatasourceEntity entity) {
        try {
            return plugins.get(entity.getType()).address(config(entity));
        } catch (ConsoleException e) {
            return "";
        }
    }

    /** A datasource type and what it can do, for the UI. */
    public record TypeView(String type, DataSourceCapabilities capabilities) {}

    public List<TypeView> types() {
        return plugins.all().stream().map(p -> new TypeView(p.type().name(), p.capabilities())).toList();
    }

    /** What the type of a saved datasource can do. */
    public DataSourceCapabilities capabilitiesOf(String datasourceId) {
        return plugins.get(typeOf(datasourceId)).capabilities();
    }

    /** The plugin of a datasource type name, e.g. MYSQL. */
    public DataSourcePlugin plugin(String type) {
        return plugins.get(type);
    }

    /** Datasource type name, e.g. MYSQL, for picking the right validation form. */
    public String typeOf(String datasourceId) {
        return require(datasourceId).getType();
    }

    /** Editing, testing or browsing a datasource uses its credentials: creator or administrator only. */
    public DatasourceEntity requireEditable(String id) {
        DatasourceEntity entity = require(id);
        Access.requireOwner(entity.getCreatedBy(), "数据源「" + entity.getName() + "」");
        return entity;
    }

    /** Like {@link #require}, holding a row lock until the surrounding transaction ends. */
    public DatasourceEntity requireLocked(String id) {
        DatasourceEntity entity = datasourceMapper.selectOne(
                new LambdaQueryWrapper<DatasourceEntity>().eq(DatasourceEntity::getId, id).last("for update"));
        if (entity == null) {
            throw new ConsoleException(ErrorCodes.NOT_FOUND, "datasource not found: " + id);
        }
        return entity;
    }

    public DatasourceEntity require(String id) {
        DatasourceEntity entity = datasourceMapper.selectById(id);
        if (entity == null) {
            throw new ConsoleException(ErrorCodes.NOT_FOUND, "datasource not found: " + id);
        }
        return entity;
    }

    private List<String> jobsUsing(String datasourceId) {
        List<String> versionIds =
                versionDatasourceMapper
                        .selectList(new LambdaQueryWrapper<JobVersionDatasource>()
                                .eq(JobVersionDatasource::getDatasourceId, datasourceId))
                        .stream()
                        .map(JobVersionDatasource::getJobVersionId)
                        .toList();
        if (versionIds.isEmpty()) {
            return List.of();
        }
        return jobMapper
                .selectList(new LambdaQueryWrapper<JobDefinition>().in(JobDefinition::getCurrentVersionId, versionIds))
                .stream()
                .map(JobDefinition::getName)
                .toList();
    }

    private void store(DatasourceEntity entity, DataSourcePlugin plugin, Map<String, String> props) {
        Map<String, String> config = new LinkedHashMap<>();
        Map<String, String> secrets = new LinkedHashMap<>();
        props.forEach((k, v) -> (plugin.secretKeys().contains(k) ? secrets : config).put(k, v));
        entity.setConfigJson(objectMapper.writeValueAsString(config));
        entity.setSecretCipher(secrets.isEmpty() ? null : cipher.encrypt(objectMapper.writeValueAsString(secrets)));
    }

    private Map<String, String> mergeSecrets(DatasourceEntity entity, DataSourcePlugin plugin, Map<String, String> props) {
        Map<String, String> merged = new LinkedHashMap<>(props);
        Map<String, String> saved = secrets(entity);
        for (String key : plugin.secretKeys()) {
            if (!merged.containsKey(key) && saved.containsKey(key)) {
                merged.put(key, saved.get(key));
            }
        }
        return merged;
    }

    private Map<String, String> decryptedProps(DatasourceEntity entity) {
        Map<String, String> props = new LinkedHashMap<>(config(entity));
        props.putAll(secrets(entity));
        return props;
    }

    private Map<String, String> config(DatasourceEntity entity) {
        return entity.getConfigJson() == null ? Map.of() : objectMapper.readValue(entity.getConfigJson(), MAP_TYPE);
    }

    private Map<String, String> secrets(DatasourceEntity entity) {
        return entity.getSecretCipher() == null
                ? Map.of()
                : objectMapper.readValue(cipher.decrypt(entity.getSecretCipher()), MAP_TYPE);
    }

    /** Drops empty values so "left blank" means "not set" (and "keep saved secret" on update). */
    private static Map<String, String> clean(Map<String, String> props) {
        Map<String, String> cleaned = new LinkedHashMap<>();
        if (props != null) {
            props.forEach((k, v) -> {
                if (k != null && v != null && !v.isBlank()) {
                    cleaned.put(k.trim(), v);
                }
            });
        }
        return cleaned;
    }

    private DatasourceView view(DatasourceEntity entity, Map<String, String> owners) {
        Set<String> secretsSet = new TreeSet<>();
        if (entity.getSecretCipher() != null) {
            secretsSet.addAll(secrets(entity).keySet());
        }
        return new DatasourceView(
                entity.getId(),
                entity.getName(),
                entity.getType(),
                entity.getDescription(),
                address(entity),
                config(entity),
                secretsSet,
                entity.getCreatedBy(),
                owners.get(entity.getCreatedBy()),
                Access.canEdit(entity.getCreatedBy()),
                entity.getCreatedAt() == null ? null : entity.getCreatedAt().toString(),
                entity.getUpdatedAt() == null ? null : entity.getUpdatedAt().toString());
    }
}
