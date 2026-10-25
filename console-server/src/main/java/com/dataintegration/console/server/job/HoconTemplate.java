package com.dataintegration.console.server.job;

import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.common.ErrorCodes;
import com.dataintegration.console.common.SecretRules;
import com.dataintegration.console.datasource.http.HttpDataSourcePlugin;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigList;
import com.typesafe.config.ConfigObject;
import com.typesafe.config.ConfigRenderOptions;
import com.typesafe.config.ConfigValue;
import com.typesafe.config.ConfigValueFactory;
import com.typesafe.config.ConfigValueType;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;


import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A job's HOCON as saved in {@code job_version.hocon_template}: plain SeaTunnel config whose
 * source/sink blocks may say {@code datasource_id = "<id>"} instead of carrying connection
 * settings. Rendering replaces that key with the datasource's connector options in memory, right
 * before submission (docs/design.md §6).
 *
 * <p>Both SeaTunnel layouts are understood: {@code source { Jdbc { ... } }} and
 * {@code source = [{ plugin_name = "Jdbc", ... }]}. Substitutions such as {@code ${x}} are kept as
 * written, so SeaTunnel can still resolve its own variables.
 */
public final class HoconTemplate {

    public static final String DATASOURCE_KEY = "datasource_id";

    /** A FieldEncrypt option naming a console key; replaced by the key itself at submit time (§7.11). */
    public static final String ENCRYPT_KEY_ID = "encrypt_key_id";

    private static final String FIELD_ENCRYPT = "FieldEncrypt";

    public static final String MASK = "******";
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    /**
     * Where a string value may carry a credential: every {@code key=value} parameter (JDBC URLs,
     * connection strings; group 2 is the key) and the password of {@code scheme://user:password@host}
     * (group 5). Which parameters are credentials is decided on the decoded key, see
     * {@link #isSecretParam}.
     */
    private static final Pattern SECRET_IN_VALUE = Pattern.compile(
            "((?:^|[?&;])([^=&;?\\s]+)=)([^&;\\s]*)|(://[^/:@\\s]+:)([^@/\\s]+)(?=@)");
    private static final List<String> SECTIONS = List.of("source", "sink");

    public record DatasourceRef(String section, String connector, String datasourceId) {}

    /** Supplies connector options for a datasource reference. */
    @FunctionalInterface
    public interface OptionsResolver {
        Map<String, Object> resolve(String connector, String datasourceId);
    }

    /** Supplies the Base64 key of a console encryption key. */
    @FunctionalInterface
    public interface KeyResolver {
        String resolve(String keyId);
    }

    private final Config config;

    private HoconTemplate(Config config) {
        this.config = config;
    }

    public static HoconTemplate parse(String text) {
        if (text == null || text.isBlank()) {
            throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "job config is empty");
        }
        Config config;
        try {
            config = ConfigFactory.parseString(text);
        } catch (ConfigException e) {
            throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "invalid HOCON: " + e.getMessage());
        }
        for (String section : SECTIONS) {
            if (!config.hasPath(section)) {
                throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "job config has no " + section + " section");
            }
        }
        return new HoconTemplate(config);
    }

    /** One connector block of a section, read-only. */
    public static final class Block {
        private final String connector;
        private final ConfigObject options;

        private Block(String connector, ConfigObject options) {
            this.connector = connector;
            this.options = options;
        }

        public String connector() {
            return connector;
        }

        /** A string option, or null when absent or not a plain string (e.g. an unresolved ${...}). */
        public String string(String key) {
            ConfigValue value = options.get(key);
            return value != null && value.valueType() == ConfigValueType.STRING ? (String) value.unwrapped() : null;
        }

        /** String fields of each object in a list option, e.g. the entries of Jdbc's table_list. */
        public List<Map<String, String>> objects(String key) {
            ConfigValue value = options.get(key);
            List<Map<String, String>> result = new ArrayList<>();
            if (value instanceof ConfigList list) {
                for (ConfigValue item : list) {
                    if (item instanceof ConfigObject object) {
                        Map<String, String> fields = new LinkedHashMap<>();
                        object.forEach((k, v) -> {
                            if (v.valueType() == ConfigValueType.STRING) {
                                fields.put(k, (String) v.unwrapped());
                            }
                        });
                        result.add(fields);
                    }
                }
            }
            return result;
        }
    }

    /** The connector blocks of {@code source} or {@code sink}, in order. */
    public List<Block> blocks(String section) {
        List<Block> blocks = new ArrayList<>();
        forEachBlock((s, connector, block) -> {
            if (s.equals(section)) {
                blocks.add(new Block(connector, block));
            }
            return block;
        });
        return blocks;
    }

    public static boolean isSecretKey(String key) {
        return SecretRules.isSecretKey(key);
    }

    private static boolean isSecretHeader(String key) {
        return SecretRules.isSecretHeader(key);
    }

    /**
     * A URL or connection-string parameter that holds a credential, judged on the decoded name so
     * {@code clientCertificateKeyStorePassword} or an encoded {@code %70assword} count as well.
     */
    static boolean isSecretParam(String rawKey) {
        String key;
        try {
            key = URLDecoder.decode(rawKey, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            key = rawKey;
        }
        return isSecretKey(key) || key.equalsIgnoreCase("pwd");
    }

    /** Whether a string carries a credential: a secret parameter with a value, or a URL password. */
    private static boolean carriesSecret(String text) {
        Matcher m = SECRET_IN_VALUE.matcher(text);
        while (m.find()) {
            if (m.group(5) != null || (isSecretParam(m.group(2)) && !m.group(3).isEmpty())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Credentials written anywhere in the config (env, source, transform, sink; nested objects and
     * lists included), as paths like {@code source.Jdbc.password} or {@code source.Jdbc.url}: an
     * option named like a credential, or a value carrying one (a password in a URL). Saving a
     * script that has any is refused (docs/design.md §6).
     */
    public List<String> explicitSecrets() {
        List<String> found = new ArrayList<>();
        collectSecrets("", config.root(), found);
        // FieldEncrypt's option is plain "key", which the name rule cannot tell from other keys
        forEachFieldEncrypt((path, block) -> collectEncryptKeys(path, block, found));
        return found;
    }

    private static void collectEncryptKeys(String path, ConfigValue value, List<String> found) {
        if (value instanceof ConfigObject object) {
            object.forEach((key, child) -> {
                if (key.equals("key") && hasValue(child)) {
                    found.add(path + ".key");
                } else {
                    collectEncryptKeys(path + "." + key, child, found);
                }
            });
        } else if (value instanceof ConfigList list) {
            for (int i = 0; i < list.size(); i++) {
                collectEncryptKeys(path + "[" + i + "]", list.get(i), found);
            }
        }
    }

    /** Console encryption keys the config refers to (FieldEncrypt {@code encrypt_key_id}, nested entries included). */
    public List<String> encryptKeyRefs() {
        List<String> ids = new ArrayList<>();
        if (config.root().get("transform") != null) {
            collectKeyRefs(config.root().get("transform"), ids);
        }
        return ids;
    }

    private static void collectKeyRefs(ConfigValue value, List<String> ids) {
        if (value instanceof ConfigObject object) {
            object.forEach((key, child) -> {
                if (key.equals(ENCRYPT_KEY_ID) && text(child) != null) {
                    ids.add(text(child));
                } else {
                    collectKeyRefs(child, ids);
                }
            });
        } else if (value instanceof ConfigList list) {
            list.forEach(item -> collectKeyRefs(item, ids));
        }
    }

    /** Visits each FieldEncrypt block of the transform section with its path. */
    private void forEachFieldEncrypt(java.util.function.BiConsumer<String, ConfigObject> visitor) {
        ConfigValue transform = config.root().get("transform");
        if (transform instanceof ConfigObject keyed) {
            keyed.forEach((name, block) -> {
                if (name.equals(FIELD_ENCRYPT) && block instanceof ConfigObject object) {
                    visitor.accept("transform." + name, object);
                }
            });
        } else if (transform instanceof ConfigList list) {
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i) instanceof ConfigObject block && block.get("plugin_name") != null
                        && FIELD_ENCRYPT.equals(String.valueOf(block.get("plugin_name").unwrapped()))) {
                    visitor.accept("transform[" + i + "]", block);
                }
            }
        }
    }

    private static void collectSecrets(String path, ConfigValue value, List<String> found) {
        if (value instanceof ConfigObject object) {
            object.forEach((key, child) -> {
                String childPath = path.isEmpty() ? key : path + "." + key;
                if (key.equals("headers") && child instanceof ConfigObject headers) {
                    collectHeaderSecrets(childPath, headers, found);
                } else if (isSecretKey(key) && hasValue(child)) {
                    found.add(childPath);
                } else {
                    collectSecrets(childPath, child, found);
                }
            });
        } else if (value instanceof ConfigList list) {
            for (int i = 0; i < list.size(); i++) {
                collectSecrets(path + "[" + i + "]", list.get(i), found);
            }
        } else {
            String text = text(value);
            if (text != null && (carriesSecret(text) || jsonCarriesSecret(text))) {
                found.add(path);
            }
        }
    }

    /**
     * A JSON text, such as an Http {@code body}, with a credential-like key holding a value:
     * {@code {"token":"..."}} is a password written into the config like any other.
     */
    private static boolean jsonCarriesSecret(String text) {
        String trimmed = text.trim();
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return false;
        }
        try {
            return jsonHasSecret(JSON.readTree(trimmed));
        } catch (JacksonException e) {
            return false;
        }
    }

    private static boolean jsonHasSecret(JsonNode node) {
        if (node == null) {
            return false;
        }
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                if (isSecretKey(field.getKey()) && jsonHasValue(field.getValue())) {
                    return true;
                }
                if (jsonHasSecret(field.getValue())) {
                    return true;
                }
            }
            return false;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                if (jsonHasSecret(item)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean jsonHasValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return false;
        }
        if (node.isContainer()) {
            for (JsonNode item : node) {
                if (jsonHasValue(item)) {
                    return true;
                }
            }
            return false;
        }
        return !node.asString().isBlank();
    }

    private static void collectHeaderSecrets(String path, ConfigObject headers, List<String> found) {
        headers.forEach((key, value) -> {
            String childPath = path + "." + key;
            if ((isSecretKey(key) || isSecretHeader(key)) && hasValue(value)) {
                found.add(childPath);
            } else {
                collectSecrets(childPath, value, found);
            }
        });
    }

    /**
     * Whether a value under a credential-like key carries anything: a non-blank scalar, or such a
     * scalar anywhere inside a list or object ({@code password = ["p@ss"]} is a password too).
     */
    private static boolean hasValue(ConfigValue value) {
        if (value instanceof ConfigObject object) {
            return object.values().stream().anyMatch(HoconTemplate::hasValue);
        }
        if (value instanceof ConfigList list) {
            return list.stream().anyMatch(HoconTemplate::hasValue);
        }
        String text = text(value);
        return text != null && !text.isBlank();
    }

    /** A scalar's text; null for objects, lists, nulls and unresolved substitutions. */
    private static String text(ConfigValue value) {
        try {
            return value instanceof ConfigObject || value instanceof ConfigList || value.unwrapped() == null
                    ? null
                    : String.valueOf(value.unwrapped());
        } catch (ConfigException e) {
            return null;
        }
    }

    /** {@code env.job.mode}, or null when not set (SeaTunnel then defaults to BATCH). */
    public String jobMode() {
        return config.hasPath("env.job.mode") ? config.getString("env.job.mode") : null;
    }

    public List<DatasourceRef> datasourceRefs() {
        List<DatasourceRef> refs = new ArrayList<>();
        forEachBlock((section, connector, block) -> {
            ConfigValue id = block.get(DATASOURCE_KEY);
            if (id != null) {
                if (id.valueType() != ConfigValueType.STRING || ((String) id.unwrapped()).isBlank()) {
                    throw new ConsoleException(
                            ErrorCodes.INVALID_ARGUMENT, section + "." + connector + ": datasource_id must be a string");
                }
                refs.add(new DatasourceRef(section, connector, (String) id.unwrapped()));
            }
            return block;
        });
        return refs;
    }

    /**
     * Injects datasource options and renders the result. Options written explicitly in a block win
     * over the datasource's options.
     */
    public String render(OptionsResolver resolver) {
        return render(resolver, null, false);
    }

    /** Also puts the console encryption keys in, where FieldEncrypt names them by id. */
    public String render(OptionsResolver resolver, KeyResolver keys) {
        return render(resolver, keys, false);
    }

    /**
     * Like {@link #render(OptionsResolver)}, then replaces every credential-like option with
     * {@code ******}, including ones written into the config itself (older versions may have them).
     */
    public String renderMasked(OptionsResolver resolver) {
        return render(resolver, null, true);
    }

    private String render(OptionsResolver resolver, KeyResolver keys, boolean mask) {
        ConfigObject root = config.root();
        if (root.get("transform") != null && (keys != null || mask)) {
            root = root.withValue("transform", injectKeys(root.get("transform"), keys, mask, false));
        }
        for (String section : SECTIONS) {
            ConfigValue value = root.get(section);
            ConfigValue rewritten =
                    switch (value.valueType()) {
                        case OBJECT -> rewriteKeyed(section, (ConfigObject) value, resolver);
                        case LIST -> rewriteList(section, (ConfigList) value, resolver);
                        default -> value;
                    };
            root = root.withValue(section, rewritten);
        }
        return (mask ? maskSecrets(root) : root).render(ConfigRenderOptions.concise());
    }

    /**
     * Replaces each {@code encrypt_key_id} with the key ({@code ******} when masking), and masks a
     * key written into a FieldEncrypt block when masking.
     */
    private static ConfigValue injectKeys(ConfigValue value, KeyResolver keys, boolean mask, boolean inEncrypt) {
        if (value instanceof ConfigObject object) {
            boolean encrypt = inEncrypt || (object.get("plugin_name") != null
                    && FIELD_ENCRYPT.equals(String.valueOf(object.get("plugin_name").unwrapped())));
            ConfigObject result = object;
            for (Map.Entry<String, ConfigValue> entry : object.entrySet()) {
                String key = entry.getKey();
                if (key.equals(ENCRYPT_KEY_ID)) {
                    result = result.withoutKey(key);
                    String id = text(entry.getValue());
                    result = result.withValue("key", ConfigValueFactory.fromAnyRef(mask ? MASK : keys.resolve(id)));
                } else if (mask && encrypt && key.equals("key")) {
                    result = result.withValue(key, ConfigValueFactory.fromAnyRef(MASK));
                } else {
                    result = result.withValue(key, injectKeys(entry.getValue(), keys, mask, encrypt || key.equals(FIELD_ENCRYPT)));
                }
            }
            return result;
        }
        if (value instanceof ConfigList list) {
            List<ConfigValue> items = new ArrayList<>();
            list.forEach(item -> items.add(injectKeys(item, keys, mask, inEncrypt)));
            return ConfigValueFactory.fromIterable(items);
        }
        return value;
    }

    private static ConfigObject maskSecrets(ConfigObject object) {
        ConfigObject result = object;
        for (Map.Entry<String, ConfigValue> entry : object.entrySet()) {
            String key = entry.getKey();
            ConfigValue value = entry.getValue();
            ConfigValue masked;
            if (key.equals("headers") && value instanceof ConfigObject headers) {
                masked = maskHeaderSecrets(headers);
            } else {
                // The whole value goes under a credential-like key, whatever its shape.
                masked = isSecretKey(key) && hasValue(value) ? ConfigValueFactory.fromAnyRef(MASK) : maskSecrets(value);
            }
            result = result.withValue(key, masked);
        }
        return result;
    }

    private static ConfigObject maskHeaderSecrets(ConfigObject headers) {
        ConfigObject result = headers;
        for (Map.Entry<String, ConfigValue> entry : headers.entrySet()) {
            String key = entry.getKey();
            ConfigValue value = entry.getValue();
            ConfigValue masked = (isSecretKey(key) || isSecretHeader(key)) && hasValue(value)
                    ? ConfigValueFactory.fromAnyRef(MASK)
                    : maskSecrets(value);
            result = result.withValue(key, masked);
        }
        return result;
    }

    private static ConfigValue maskSecrets(ConfigValue value) {
        if (value instanceof ConfigObject object) {
            return maskSecrets(object);
        }
        if (value instanceof ConfigList list) {
            List<ConfigValue> items = new ArrayList<>();
            list.forEach(item -> items.add(maskSecrets(item)));
            return ConfigValueFactory.fromIterable(items);
        }
        String text = text(value);
        if (text != null && value.valueType() == ConfigValueType.STRING && carriesSecret(text)) {
            Matcher m = SECRET_IN_VALUE.matcher(text);
            StringBuilder out = new StringBuilder();
            while (m.find()) {
                String replacement = m.group(5) != null
                        ? m.group(4) + MASK
                        : isSecretParam(m.group(2)) ? m.group(1) + MASK : m.group();
                m.appendReplacement(out, Matcher.quoteReplacement(replacement));
            }
            m.appendTail(out);
            return ConfigValueFactory.fromAnyRef(out.toString());
        }
        return value;
    }

    private static ConfigObject rewriteKeyed(String section, ConfigObject sectionObject, OptionsResolver resolver) {
        ConfigObject result = sectionObject;
        for (Map.Entry<String, ConfigValue> entry : sectionObject.entrySet()) {
            if (entry.getValue() instanceof ConfigObject block) {
                result = result.withValue(entry.getKey(), inject(section, entry.getKey(), block, resolver));
            }
        }
        return result;
    }

    private static ConfigValue rewriteList(String section, ConfigList list, OptionsResolver resolver) {
        List<ConfigValue> items = new ArrayList<>();
        for (ConfigValue item : list) {
            if (item instanceof ConfigObject block && block.get("plugin_name") != null) {
                items.add(inject(section, String.valueOf(block.get("plugin_name").unwrapped()), block, resolver));
            } else {
                items.add(item);
            }
        }
        return ConfigValueFactory.fromIterable(items);
    }

    private static ConfigObject inject(String section, String connector, ConfigObject block, OptionsResolver resolver) {
        ConfigValue id = block.get(DATASOURCE_KEY);
        if (id == null) {
            return block;
        }
        ConfigObject result = block.withoutKey(DATASOURCE_KEY);
        Map<String, Object> options = resolver.resolve(connector, String.valueOf(id.unwrapped()));
        for (Map.Entry<String, Object> option : options.entrySet()) {
            ConfigValue datasourceValue = ConfigValueFactory.fromAnyRef(option.getValue());
            ConfigValue templateValue = result.get(option.getKey());
            if (templateValue == null) {
                result = result.withValue(option.getKey(), datasourceValue);
            } else if ("Http".equals(connector) && option.getKey().equals("url")) {
                result = result.withValue(option.getKey(), ConfigValueFactory.fromAnyRef(
                        HttpDataSourcePlugin.resolveUrl(text(datasourceValue), text(templateValue))));
            } else if (templateValue instanceof ConfigObject templateObject
                    && datasourceValue instanceof ConfigObject datasourceObject) {
                ConfigObject merged;
                if ("Http".equals(connector) && option.getKey().equals("params")) {
                    merged = mergeHttpParams(datasourceObject, templateObject);
                } else if ("Http".equals(connector) && option.getKey().equals("headers")) {
                    merged = mergeHttpHeaders(datasourceObject, templateObject);
                } else {
                    merged = mergeObjects(datasourceObject, templateObject);
                }
                result = result.withValue(option.getKey(), merged);
            }
        }
        return result;
    }

    private static ConfigObject mergeHttpParams(ConfigObject datasource, ConfigObject template) {
        for (String key : template.keySet()) {
            if (datasource.containsKey(key)) {
                throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT,
                        "Http task parameter conflicts with datasource API Key: " + key);
            }
        }
        return mergeObjects(datasource, template);
    }

    /**
     * A job may add headers but never replace the datasource's: its authentication header can have
     * any name (an API Key header called {@code appId}), so every datasource header is kept.
     * Header names are case-insensitive.
     */
    private static ConfigObject mergeHttpHeaders(ConfigObject datasource, ConfigObject template) {
        Set<String> names = new HashSet<>();
        datasource.keySet().forEach(name -> names.add(name.toLowerCase(Locale.ROOT)));
        for (String key : template.keySet()) {
            if (names.contains(key.toLowerCase(Locale.ROOT))) {
                throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT,
                        "Http task header conflicts with a datasource header: " + key);
            }
        }
        return mergeObjects(datasource, template);
    }

    private static ConfigObject mergeObjects(ConfigObject datasource, ConfigObject template) {
        ConfigObject result = datasource;
        for (Map.Entry<String, ConfigValue> entry : template.entrySet()) {
            result = result.withValue(entry.getKey(), entry.getValue());
        }
        return result;
    }

    @FunctionalInterface
    private interface BlockVisitor {
        ConfigObject visit(String section, String connector, ConfigObject block);
    }

    private void forEachBlock(BlockVisitor visitor) {
        for (String section : SECTIONS) {
            ConfigValue value = config.root().get(section);
            if (value instanceof ConfigObject keyed) {
                keyed.forEach((connector, block) -> {
                    if (block instanceof ConfigObject object) {
                        visitor.visit(section, connector, object);
                    }
                });
            } else if (value instanceof ConfigList list) {
                for (ConfigValue item : list) {
                    if (item instanceof ConfigObject block && block.get("plugin_name") != null) {
                        visitor.visit(section, String.valueOf(block.get("plugin_name").unwrapped()), block);
                    }
                }
            }
        }
    }
}
