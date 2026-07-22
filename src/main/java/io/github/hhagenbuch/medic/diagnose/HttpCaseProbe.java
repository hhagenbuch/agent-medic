package io.github.hhagenbuch.medic.diagnose;

import io.github.hhagenbuch.blackbox.core.TraceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Probes the live agent over its chat API and evaluates the case's
 * DETERMINISTIC assertions ({@code contains}, {@code not_contains},
 * {@code regex} on the reply; {@code tool_called} on {@code toolsUsed}).
 * {@code judge} assertions are skipped — a flakiness probe must itself be
 * deterministic, and the exported judge criteria are drafts awaiting human
 * review anyway.
 */
public class HttpCaseProbe implements CaseProbe {

    private static final Logger log = LoggerFactory.getLogger(HttpCaseProbe.class);
    private static final YAMLMapper YAML = YAMLMapper.builder().build();
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final String urlTemplate;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    /** @param urlTemplate e.g. {@code http://%s:8080/api/chat} — %s is the agent app name */
    public HttpCaseProbe(String urlTemplate) {
        this.urlTemplate = urlTemplate;
    }

    @Override
    public Optional<Boolean> run(String caseYaml, String agentApp) {
        try {
            JsonNode caze = YAML.readTree(caseYaml).path("cases").get(0);
            String prompt = caze.path("prompt").asText(null);
            if (prompt == null) {
                return Optional.empty();
            }

            var body = TraceEvent.mapper().createObjectNode();
            body.put("message", prompt);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(urlTemplate.formatted(agentApp)))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return Optional.empty();
            }
            JsonNode reply = TraceEvent.mapper().readTree(response.body());
            return Optional.of(evaluate(caze, reply));
        } catch (Exception e) {
            log.debug("case probe against '{}' unavailable: {}", agentApp, e.toString());
            return Optional.empty();
        }
    }

    private static boolean evaluate(JsonNode caze, JsonNode chatResponse) {
        String reply = chatResponse.path("reply").asText("");
        JsonNode toolsUsed = chatResponse.path("toolsUsed");
        for (JsonNode assertion : caze.path("assert")) {
            String value = assertion.path("value").asText("");
            boolean ok = switch (assertion.path("type").asText()) {
                case "contains" -> reply.contains(value);
                case "not_contains" -> !reply.contains(value);
                case "regex" -> Pattern.compile(value).matcher(reply).find();
                case "tool_called" -> contains(toolsUsed, value);
                default -> true; // judge and unknown tiers: not this probe's job
            };
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static boolean contains(JsonNode array, String value) {
        for (JsonNode item : array) {
            if (value.equals(item.asText())) {
                return true;
            }
        }
        return false;
    }
}
