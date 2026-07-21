package io.github.hhagenbuch.medic.mcp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the server's JSON-RPC loop directly (in-process over byte streams —
 * the same {@code serve} the stdio subprocess runs). The full subprocess
 * round-trip through the starter's MCP client is covered by SurgeonTest.
 */
class MedicMcpServerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path dir;

    Path bundle;
    Path prompt;
    Path history;

    @BeforeEach
    void seed() throws IOException {
        bundle = Files.createDirectories(dir.resolve("bundle"));
        Files.writeString(bundle.resolve("incident.json"), "{\"rule\":\"honesty.claimed-sent-but-queued\"}");
        Files.writeString(bundle.resolve("case.yaml"), "name: incident-case");
        Files.writeString(bundle.resolve("trace.jsonl"), "{\"type\":\"session_start\"}");
        prompt = Files.writeString(dir.resolve("prompt.txt"), "You are a support agent.");
        history = Files.createDirectories(dir.resolve("history"));
        Files.writeString(history.resolve("2026-07-20-report.md"), "pass rate 0.94");
    }

    @Test
    void speaksTheMcpHandshakeAndServesAllThreeTools() throws IOException {
        JsonNode[] responses = roundTrip(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"get_incident\",\"arguments\":{}}}",
                "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"get_current_prompt\",\"arguments\":{}}}",
                "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{\"name\":\"get_eval_history\",\"arguments\":{}}}");

        assertThat(responses).hasSize(5); // the notification gets no response
        assertThat(responses[0].path("result").path("serverInfo").path("name").asText()).isEqualTo("agent-medic");

        JsonNode tools = responses[1].path("result").path("tools");
        assertThat(tools).hasSize(3);
        assertThat(tools.findValuesAsString("name"))
                .containsExactly("get_incident", "get_current_prompt", "get_eval_history");

        assertThat(text(responses[2]))
                .contains("honesty.claimed-sent-but-queued")
                .contains("incident-case")
                .contains("session_start");
        assertThat(text(responses[3])).isEqualTo("You are a support agent.");
        assertThat(text(responses[4])).contains("pass rate 0.94");
    }

    @Test
    void unknownToolAndMissingFilesAreErrorsNotCrashes() throws IOException {
        Files.delete(bundle.resolve("incident.json"));
        JsonNode[] responses = roundTrip(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"write_prompt\",\"arguments\":{}}}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"get_incident\",\"arguments\":{}}}");

        assertThat(responses[0].path("result").path("isError").asBoolean()).isTrue();
        assertThat(text(responses[0])).contains("unknown tool");
        assertThat(responses[1].path("result").path("isError").asBoolean()).isTrue();
        assertThat(text(responses[1])).contains("missing incident.json");
    }

    @Test
    void emptyHistoryIsAnAnswerNotAnError() throws IOException {
        Files.delete(history.resolve("2026-07-20-report.md"));
        JsonNode[] responses = roundTrip(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"get_eval_history\",\"arguments\":{}}}");
        assertThat(responses[0].path("result").path("isError").asBoolean()).isFalse();
        assertThat(text(responses[0])).contains("no eval history");
    }

    private JsonNode[] roundTrip(String... requests) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new MedicMcpServer(bundle, prompt, history).serve(
                new ByteArrayInputStream(String.join("\n", requests).getBytes(StandardCharsets.UTF_8)), out);
        return out.toString(StandardCharsets.UTF_8).lines()
                .map(line -> {
                    try {
                        return MAPPER.readTree(line);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .toArray(JsonNode[]::new);
    }

    private static String text(JsonNode response) {
        return response.path("result").path("content").get(0).path("text").asText();
    }
}
