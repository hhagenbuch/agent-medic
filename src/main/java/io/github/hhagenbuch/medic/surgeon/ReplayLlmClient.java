package io.github.hhagenbuch.medic.surgeon;

import io.github.hhagenbuch.agent.llm.LlmClient;
import io.github.hhagenbuch.agent.llm.LlmResponse;
import io.github.hhagenbuch.agent.llm.ToolCall;
import io.github.hhagenbuch.agent.tools.AgentTool;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

/**
 * A Surgeon "model" replayed from a file — FOR THE KEYLESS DEMO ONLY. It walks
 * the three evidence tools (the real MCP round-trips still happen: the medic
 * server is spawned and the results genuinely cross the stdio boundary) and
 * then answers with the proposal JSON read from the script file. Every other
 * part of the loop — Watcher, Diagnoser, gate, approval, antibody — runs for
 * real; only the model's reasoning is canned, and the logs say so loudly.
 * The CI live variant runs the same loop with a real model instead.
 */
public class ReplayLlmClient implements LlmClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> TOOL_WALK =
            List.of("get_incident", "get_current_prompt", "get_eval_history");

    private final Path scriptFile;

    public ReplayLlmClient(Path scriptFile) {
        this.scriptFile = scriptFile;
    }

    @Override
    public Mono<LlmResponse> chat(List<ObjectNode> messages, Collection<AgentTool> tools) {
        // Stateless: the step is how many assistant turns this conversation
        // already holds, so one client instance serves any number of runs.
        long step = messages.stream().filter(m -> "assistant".equals(m.path("role").asText())).count();
        if (step < TOOL_WALK.size()) {
            String tool = TOOL_WALK.get((int) step);
            return Mono.just(new LlmResponse("",
                    List.of(new ToolCall("replay-" + step, tool, MAPPER.createObjectNode())),
                    toolUseContent(tool, "replay-" + step), "tool_use"));
        }
        String proposal = readScript();
        return Mono.just(new LlmResponse(proposal, List.of(), textContent(proposal), "end_turn"));
    }

    private String readScript() {
        try {
            return Files.readString(scriptFile);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read Surgeon replay script " + scriptFile, e);
        }
    }

    private static ArrayNode toolUseContent(String tool, String id) {
        ArrayNode content = MAPPER.createArrayNode();
        ObjectNode block = content.addObject();
        block.put("type", "tool_use");
        block.put("id", id);
        block.put("name", tool);
        block.putObject("input");
        return content;
    }

    private static ArrayNode textContent(String text) {
        ArrayNode content = MAPPER.createArrayNode();
        ObjectNode block = content.addObject();
        block.put("type", "text");
        block.put("text", text);
        return content;
    }
}
