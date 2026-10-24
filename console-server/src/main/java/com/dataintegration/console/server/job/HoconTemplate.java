package com.dataintegration.console.server.job;

import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.common.ErrorCodes;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigList;
import com.typesafe.config.ConfigObject;
import com.typesafe.config.ConfigRenderOptions;
import com.typesafe.config.ConfigValue;
import com.typesafe.config.ConfigValueFactory;
import com.typesafe.config.ConfigValueType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /** Option names that hold credentials; they belong in a datasource, never in a saved config. */
    private static final Pattern SECRET_KEY =
            Pattern.compile("(?i).*(password|passwd|secret|token|credential|api_?key|access_?key).*");

    public static final String MASK = "******";

    /**
     * A credential inside a string value: a {@code password=} style query parameter (JDBC URLs) or
     * the password of {@code scheme://user:password@host}. Groups 1 and 3 are kept, 2 and 4 masked.
     */
    private static final Pattern SECRET_IN_VALUE = Pattern.compile(
            "(?i)((?:^|[?&;])(?:password|passwd|pwd|sslpassword|secret|token)=)([^&;\\s]+)|(://[^/:@\\s]+:)([^@/\\s]+)(?=@)");
    private static final List<String> SECTIONS = List.of("source", "sink");

    public record DatasourceRef(String section, String connector, String datasourceId) {}

    /** Supplies connector options for a datasource reference. */
    @FunctionalInterface
    public interface OptionsResolver {
        Map<String, Object> resolve(String connector, String datasourceId);
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
        return SECRET_KEY.matcher(key).matches();
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
        return found;
    }

    private static void collectSecrets(String path, ConfigValue value, List<String> found) {
        if (value instanceof ConfigObject object) {
            object.forEach((key, child) -> {
                String childPath = path.isEmpty() ? key : path + "." + key;
                if (isSecretKey(key) && hasValue(child)) {
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
            if (text != null && SECRET_IN_VALUE.matcher(text).find()) {
                found.add(path);
            }
        }
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
        return render(resolver, false);
    }

    /**
     * Like {@link #render(OptionsResolver)}, then replaces every credential-like option with
     * {@code ******}, including ones written into the config itself (older versions may have them).
     */
    public String renderMasked(OptionsResolver resolver) {
        return render(resolver, true);
    }

    private String render(OptionsResolver resolver, boolean mask) {
        ConfigObject root = config.root();
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

    private static ConfigObject maskSecrets(ConfigObject object) {
        ConfigObject result = object;
        for (Map.Entry<String, ConfigValue> entry : object.entrySet()) {
            ConfigValue value = entry.getValue();
            // The whole value goes under a credential-like key, whatever its shape.
            ConfigValue masked = isSecretKey(entry.getKey()) && hasValue(value) ? ConfigValueFactory.fromAnyRef(MASK) : maskSecrets(value);
            result = result.withValue(entry.getKey(), masked);
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
        if (text != null && value.valueType() == ConfigValueType.STRING && SECRET_IN_VALUE.matcher(text).find()) {
            Matcher m = SECRET_IN_VALUE.matcher(text);
            StringBuilder out = new StringBuilder();
            while (m.find()) {
                String replacement = m.group(1) != null ? m.group(1) + MASK : m.group(3) + MASK;
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
            if (!result.containsKey(option.getKey())) {
                result = result.withValue(option.getKey(), ConfigValueFactory.fromAnyRef(option.getValue()));
            }
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
