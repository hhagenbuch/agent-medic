package io.github.hhagenbuch.medic.mcp;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * The read-only stdio MCP server the Surgeon works through. Exposes exactly
 * three tools — {@code get_incident}, {@code get_current_prompt},
 * {@code get_eval_history} — and nothing else: this server is the whole of the
 * Surgeon's window onto the world, so its surface IS the authority boundary.
 * It only ever reads the paths it was launched with; there is no write, list,
 * or escape hatch to add one.
 *
 * <p>Launched as a subprocess (newline-delimited JSON-RPC 2.0 per the MCP
 * stdio spec) by the starter's MCP client:
 * <pre>
 * java -cp ... io.github.hhagenbuch.medic.mcp.MedicMcpServer \
 *     --bundle incidents/s-42-turn1-honesty --prompt prompts/support.txt --history reports/
 * </pre>
 */
public final class MedicMcpServer {

    private static final String PROTOCOL_VERSION = "2024-11-05";
    private static final int MAX_HISTORY_REPORTS = 5;

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path bundleDir;
    private final Path promptFile;
    private final Path historyDir;

    MedicMcpServer(Path bundleDir, Path promptFile, Path historyDir) {
        this.bundleDir = bundleDir;
        this.promptFile = promptFile;
        this.historyDir = historyDir;
    }

    public static void main(String[] args) throws IOException {
        Path bundle = null, prompt = null, history = null;
        for (int i = 0; i + 1 < args.length; i += 2) {
            switch (args[i]) {
                case "--bundle" -> bundle = Path.of(args[i + 1]);
                case "--prompt" -> prompt = Path.of(args[i + 1]);
                case "--history" -> history = Path.of(args[i + 1]);
                default -> throw new IllegalArgumentException("unknown option: " + args[i]);
            }
        }
        if (bundle == null || prompt == null || history == null) {
            throw new IllegalArgumentException("usage: --bundle <dir> --prompt <file> --history <dir>");
        }
        new MedicMcpServer(bundle, prompt, history).serve(System.in, System.out);
    }

    void serve(java.io.InputStream in, java.io.OutputStream out) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        PrintStream writer = new PrintStream(out, true, StandardCharsets.UTF_8);
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode request = mapper.readTree(line);
            JsonNode id = request.get("id");
            switch (request.path("method").asText()) {
                case "initialize" -> reply(writer, id, initializeResult());
                case "notifications/initialized" -> { /* notification — no response */ }
                case "tools/list" -> reply(writer, id, toolsResult());
                case "tools/call" -> reply(writer, id, call(request.path("params").path("name").asText()));
                default -> { /* ignore methods this server does not implement */ }
            }
        }
    }

    private ObjectNode initializeResult() {
        ObjectNode result = mapper.createObjectNode();
        result.put("protocolVersion", PROTOCOL_VERSION);
        result.putObject("capabilities").putObject("tools");
        ObjectNode info = result.putObject("serverInfo");
        info.put("name", "agent-medic");
        info.put("version", "0.1.0");
        return result;
    }

    private ObjectNode toolsResult() {
        ObjectNode result = mapper.createObjectNode();
        ArrayNode tools = result.putArray("tools");
        tool(tools, "get_incident",
                "The incident under repair: what fired, the evidence, the exported regression case, "
                        + "and the recorded trace of the failing session.");
        tool(tools, "get_current_prompt",
                "The misbehaving agent's current system prompt — the text a proposal replaces.");
        tool(tools, "get_eval_history",
                "Recent eval gate reports for this agent, most recent first.");
        return result;
    }

    private void tool(ArrayNode tools, String name, String description) {
        ObjectNode tool = tools.addObject();
        tool.put("name", name);
        tool.put("description", description);
        ObjectNode schema = tool.putObject("inputSchema");
        schema.put("type", "object");
        schema.putObject("properties");
    }

    private ObjectNode call(String tool) {
        try {
            return textResult(switch (tool) {
                case "get_incident" -> incident();
                case "get_current_prompt" -> Files.readString(promptFile);
                case "get_eval_history" -> evalHistory();
                default -> throw new IllegalArgumentException("unknown tool: " + tool);
            }, false);
        } catch (Exception e) {
            return textResult("ERROR: " + e.getMessage(), true);
        }
    }

    private String incident() throws IOException {
        StringBuilder out = new StringBuilder();
        section(out, "incident.json", bundleDir.resolve("incident.json"), true);
        section(out, "case.yaml (exported regression case)", bundleDir.resolve("case.yaml"), false);
        section(out, "trace.jsonl (recorded session)", bundleDir.resolve("trace.jsonl"), false);
        return out.toString();
    }

    private void section(StringBuilder out, String title, Path file, boolean required) throws IOException {
        out.append("=== ").append(title).append(" ===\n");
        if (Files.isRegularFile(file)) {
            out.append(Files.readString(file)).append('\n');
        } else if (required) {
            throw new IOException("incident bundle is missing " + file.getFileName());
        } else {
            out.append("(not present in this bundle)\n");
        }
    }

    private String evalHistory() throws IOException {
        if (!Files.isDirectory(historyDir)) {
            return "(no eval history recorded yet)";
        }
        try (Stream<Path> files = Files.list(historyDir)) {
            List<Path> reports = files.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .limit(MAX_HISTORY_REPORTS)
                    .toList();
            if (reports.isEmpty()) {
                return "(no eval history recorded yet)";
            }
            StringBuilder out = new StringBuilder();
            for (Path report : reports) {
                section(out, report.getFileName().toString(), report, false);
            }
            return out.toString();
        }
    }

    private ObjectNode textResult(String text, boolean isError) {
        ObjectNode result = mapper.createObjectNode();
        ObjectNode block = result.putArray("content").addObject();
        block.put("type", "text");
        block.put("text", text);
        result.put("isError", isError);
        return result;
    }

    private void reply(PrintStream writer, JsonNode id, ObjectNode result) {
        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        response.set("result", result);
        writer.println(mapper.writeValueAsString(response));
    }
}
