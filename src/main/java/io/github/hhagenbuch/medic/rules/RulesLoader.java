package io.github.hhagenbuch.medic.rules;

import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Loads {@code rules.yaml}:
 *
 * <pre>
 * rules:
 *   - id: honesty.claimed-sent-but-queued
 *     type: claim-without-tool
 *     claimPattern: "(?i)\\b(sent|emailed)\\b"
 *     requiredTool: send_email
 * </pre>
 *
 * Every rule is fully validated here — ids unique, patterns compiled, per-type
 * required fields present — so a broken rules file stops the app at startup
 * rather than producing a Watcher that silently watches for nothing.
 */
public final class RulesLoader {

    private static final YAMLMapper YAML = YAMLMapper.builder().build();

    private RulesLoader() {
    }

    public static List<RuleSpec> load(Path rulesFile) {
        if (!Files.isRegularFile(rulesFile)) {
            throw new IllegalArgumentException("rules file not found: " + rulesFile);
        }
        JsonNode root;
        try {
            root = YAML.readTree(Files.readString(rulesFile));
        } catch (Exception e) {
            throw new IllegalArgumentException("rules file is not valid YAML: " + rulesFile, e);
        }
        JsonNode rules = root.path("rules");
        if (!rules.isArray() || rules.isEmpty()) {
            throw new IllegalArgumentException("rules file must contain a non-empty 'rules' list: " + rulesFile);
        }

        List<RuleSpec> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonNode node : rules) {
            RuleSpec rule = parseRule(node);
            if (!ids.add(rule.id())) {
                throw new IllegalArgumentException("duplicate rule id: " + rule.id());
            }
            result.add(rule);
        }
        return List.copyOf(result);
    }

    private static RuleSpec parseRule(JsonNode node) {
        String id = required("?", "id", node.path("id").asText(null));
        RuleSpec.Type type = parseType(id, node.path("type").asText(null));

        return switch (type) {
            case ERROR_EVENT -> new RuleSpec(id, type, null, null, null, null, null);
            case CLAIM_WITHOUT_TOOL -> new RuleSpec(id, type,
                    pattern(id, "claimPattern", node),
                    required(id, "requiredTool", node.path("requiredTool").asText(null)),
                    null, null, null);
            case EXPECTED_TOOL -> new RuleSpec(id, type, null, null,
                    pattern(id, "promptPattern", node),
                    required(id, "expectedTool", node.path("expectedTool").asText(null)),
                    null);
            case UNEXPECTED_TOOL -> new RuleSpec(id, type, null, null, null, null,
                    allowedTools(id, node));
        };
    }

    private static RuleSpec.Type parseType(String id, String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("rule '" + id + "': missing 'type'");
        }
        try {
            return RuleSpec.Type.valueOf(raw.replace('-', '_').toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("rule '" + id + "': unknown type '" + raw + "'");
        }
    }

    private static Pattern pattern(String id, String field, JsonNode node) {
        String raw = required(id, field, node.path(field).asText(null));
        try {
            return Pattern.compile(raw);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("rule '" + id + "': invalid " + field + ": " + e.getMessage());
        }
    }

    private static Set<String> allowedTools(String id, JsonNode node) {
        JsonNode list = node.path("allowedTools");
        if (!list.isArray() || list.isEmpty()) {
            throw new IllegalArgumentException("rule '" + id + "': 'allowedTools' must be a non-empty list");
        }
        Set<String> tools = new LinkedHashSet<>();
        list.forEach(t -> tools.add(t.asText()));
        return Set.copyOf(tools);
    }

    private static String required(String id, String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("rule '" + id + "': missing '" + field + "'");
        }
        return value;
    }
}
