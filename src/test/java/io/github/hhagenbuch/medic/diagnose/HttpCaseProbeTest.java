package io.github.hhagenbuch.medic.diagnose;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Drives the probe against a fake agent endpoint (JDK HttpServer). */
class HttpCaseProbeTest {

    private static HttpServer server;
    private static String url;

    @BeforeAll
    static void fakeAgent() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/chat", exchange -> {
            byte[] body = """
                    {"sessionId":"s","reply":"Your message is queued, delivery not confirmed.",
                     "toolsUsed":["send_email"],"usage":{"in":1,"out":1}}"""
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        url = "http://localhost:" + server.getAddress().getPort() + "/api/chat";
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    private static String caseYaml(String assertions) {
        return """
                name: incident
                cases:
                  - id: turn-1
                    prompt: "Please email Dana."
                    assert:
                %s""".formatted(assertions);
    }

    @Test
    void evaluatesDeterministicAssertionsAgainstTheLiveReply() {
        String passing = caseYaml("""
                      - type: tool_called
                        value: send_email
                      - type: contains
                        value: "queued"
                      - type: judge
                        criteria: "ignored by the probe"
                """);
        assertThat(new HttpCaseProbe(url).run(passing, "support-agent")).contains(true);

        String failing = caseYaml("""
                      - type: not_contains
                        value: "queued"
                """);
        assertThat(new HttpCaseProbe(url).run(failing, "support-agent")).contains(false);
    }

    @Test
    void anUnreachableAgentMeansTheProbeCannotJudge() {
        assertThat(new HttpCaseProbe("http://localhost:1/api/chat")
                .run(caseYaml("      - type: contains\n        value: x\n"), "support-agent"))
                .isEmpty();
    }

    @Test
    void theAgentNameFillsTheUrlTemplate() {
        String template = "http://localhost:" + server.getAddress().getPort() + "/%sapi/chat";
        // %s -> "" keeps the URL valid against the fake; a real template is http://%s:8080/...
        assertThat(new HttpCaseProbe(template)
                .run(caseYaml("      - type: tool_called\n        value: send_email\n"), ""))
                .contains(true);
    }
}
