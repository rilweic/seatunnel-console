package com.dataintegration.console.server.job.wizard;

import static org.assertj.core.api.Assertions.assertThat;

import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.server.job.wizard.WizardGenerator.Endpoints;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Locks what the wizard generates: each {@code wizard-golden/*.json} holds a saved form and the
 * datasources it points at, and the generated config, validation and notes must equal the
 * {@code .golden} file next to it. The forms are kept in the shape versions were saved in, so
 * they also prove that old versions still generate the same config.
 *
 * <p>After an intended change of the output, rewrite the files with
 * {@code mvn test -pl console-server -Dtest=WizardGoldenTest -Dgolden.update=true} and review the diff.
 */
class WizardGoldenTest {

    private static final Path DIR = Path.of("src/test/resources/wizard-golden");
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    @TestFactory
    Stream<DynamicTest> generatedConfigMatchesGoldenFiles() throws IOException {
        List<Path> scenarios;
        try (Stream<Path> files = Files.list(DIR)) {
            scenarios = files.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
        assertThat(scenarios).isNotEmpty();
        return scenarios.stream().map(json -> DynamicTest.dynamicTest(json.getFileName().toString(), () -> check(json)));
    }

    private static void check(Path json) throws IOException {
        JsonNode scenario = JSON.readTree(Files.readString(json));
        String actual = render(endpoints(scenario.get("endpoints")), JSON.treeToValue(scenario.get("form"), WizardForm.class));
        Path golden = Path.of(json.toString().replaceFirst("\\.json$", ".golden"));
        if (Boolean.getBoolean("golden.update") || !Files.exists(golden)) {
            Files.writeString(golden, actual, StandardCharsets.UTF_8);
        }
        assertThat(actual).isEqualTo(Files.readString(golden, StandardCharsets.UTF_8));
    }

    private static Endpoints endpoints(JsonNode node) {
        Map<String, List<String>> columns = new LinkedHashMap<>();
        node.get("sourceColumns").properties().forEach(e ->
                columns.put(e.getKey(), e.getValue().valueStream().map(JsonNode::asString).toList()));
        return new Endpoints(text(node, "sourceType"), text(node, "sourceDatabase"),
                text(node, "targetType"), text(node, "targetDatabase"), columns);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static String render(Endpoints endpoints, WizardForm form) {
        try {
            WizardGenerator.Generated g = WizardGenerator.generate(form, endpoints);
            return g.hocon()
                    + "\n# ---- validation ----\n" + g.validation()
                    + "\n# ---- notes ----\n" + String.join("\n", g.notes()) + "\n";
        } catch (ConsoleException e) {
            return "# ---- error ----\n" + e.getMessage() + "\n";
        }
    }
}
