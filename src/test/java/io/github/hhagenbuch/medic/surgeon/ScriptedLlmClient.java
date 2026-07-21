package io.github.hhagenbuch.medic.surgeon;

import io.github.hhagenbuch.agent.llm.LlmClient;
import io.github.hhagenbuch.agent.llm.LlmResponse;
import io.github.hhagenbuch.agent.llm.ToolCall;
import io.github.hhagenbuch.agent.tools.AgentTool;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;

/**
 * A fake {@link LlmClient} that plays back a script of responses and records
 * every message list it was shown — so a test can assert that real MCP tool
 * results actually reached "the model".
 */
class ScriptedLlmClient implements LlmClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Deque<LlmResponse> script = new ArrayDeque<>();
    final List<String> observedMessages = new ArrayList<>();

    ScriptedLlmClient callsTool(String toolName) {
        script.add(new LlmResponse("", List.of(new ToolCall("tu-" + script.size(), toolName,
                MAPPER.createObjectNode())), toolUseContent(toolName), "tool_use"));
        return this;
    }

    ScriptedLlmClient answers(String text) {
        script.add(new LlmResponse(text, List.of(), textContent(text), "end_turn"));
        return this;
    }

    @Override
    public Mono<LlmResponse> chat(List<ObjectNode> messages, Collection<AgentTool> tools) {
        observedMessages.add(messages.toString());
        if (script.isEmpty()) {
            return Mono.error(new IllegalStateException("scripted LLM ran out of responses"));
        }
        return Mono.just(script.pop());
    }

    private static tools.jackson.databind.node.ArrayNode toolUseContent(String toolName) {
        var content = MAPPER.createArrayNode();
        ObjectNode block = content.addObject();
        block.put("type", "tool_use");
        block.put("id", "tu-x");
        block.put("name", toolName);
        block.putObject("input");
        return content;
    }

    private static tools.jackson.databind.node.ArrayNode textContent(String text) {
        var content = MAPPER.createArrayNode();
        ObjectNode block = content.addObject();
        block.put("type", "text");
        block.put("text", text);
        return content;
    }
}
