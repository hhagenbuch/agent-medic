package io.github.hhagenbuch.medic.surgeon;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import io.github.hhagenbuch.agent.config.AgentProperties;
import io.github.hhagenbuch.agent.config.AgentProperties.McpServer;
import io.github.hhagenbuch.agent.core.AgentLoop;
import io.github.hhagenbuch.agent.core.ConversationMemory;
import io.github.hhagenbuch.agent.llm.LlmClient;
import io.github.hhagenbuch.agent.mcp.McpConnectionManager;
import io.github.hhagenbuch.agent.tools.ToolRegistry;
import io.github.hhagenbuch.medic.mcp.MedicMcpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The repair agent. Runs on the starter's {@link AgentLoop}; its only tools
 * are the three read-only ones served by {@link MedicMcpServer}, mounted
 * through the starter's own MCP client over stdio — the same client any agent
 * on the platform uses, exercised here end to end.
 *
 * <p>Authority boundaries (DESIGN.md §3.3), enforced in code, not prose:
 * <ul>
 *   <li>the Surgeon's {@link ToolRegistry} starts empty and receives only what
 *       the medic MCP server exposes — no calculator, no send_email, no
 *       filesystem;</li>
 *   <li>its answer must survive {@link ProposalValidator} — a JSON object of
 *       exactly {@code systemPrompt} + {@code rationale}, size-capped,
 *       different from the current prompt;</li>
 *   <li>if the MCP server fails to mount all three tools the Surgeon refuses
 *       to operate rather than diagnosing blind.</li>
 * </ul>
 */
public class Surgeon {

    private static final Logger log = LoggerFactory.getLogger(Surgeon.class);
    private static final String REPAIR_PROMPT_RESOURCE = "/prompts/surgeon.md";
    private static final int EXPECTED_TOOLS = 3;

    /** The Surgeon's output: the validated proposal, a unified diff for the reviewer, and what it consulted. */
    public record Repair(Proposal proposal, String promptDiff, List<String> toolsUsed) {
    }

    private final LlmClient llm;
    private final ObjectMapper mapper = new ObjectMapper();

    public Surgeon(LlmClient llm) {
        this.llm = llm;
    }

    /**
     * Proposes a repair for the incident in {@code bundleDir}, given the
     * misbehaving agent's current prompt and its eval-report history.
     */
    public Mono<Repair> propose(Path bundleDir, Path promptFile, Path historyDir) {
        return Mono.fromCallable(() -> operatingRoom(bundleDir, promptFile, historyDir))
                .flatMap(room -> room.loop()
                        .run("surgeon-" + bundleDir.getFileName(), repairInstruction())
                        .map(result -> {
                            Proposal proposal = ProposalValidator.parse(result.answer());
                            String current = readCurrentPrompt(promptFile);
                            ProposalValidator.requireChanged(proposal, current);
                            log.info("surgeon proposed a repair for {} (consulted: {})",
                                    bundleDir.getFileName(), result.toolsUsed());
                            return new Repair(proposal, unifiedDiff(current, proposal.systemPrompt()),
                                    result.toolsUsed());
                        })
                        .doFinally(signal -> room.mcp().shutdown()));
    }

    private record OperatingRoom(AgentLoop loop, McpConnectionManager mcp) {
    }

    /** Spawns the medic MCP server and assembles the Surgeon's (minimal) runtime around it. */
    private OperatingRoom operatingRoom(Path bundleDir, Path promptFile, Path historyDir) {
        AgentProperties props = new AgentProperties("", "claude-sonnet-5", 4096, 6, 3,
                List.of(new McpServer("agent-medic", serverCommand(bundleDir, promptFile, historyDir))));
        ToolRegistry registry = new ToolRegistry(List.of());
        McpConnectionManager mcp = new McpConnectionManager(props, registry, mapper);
        mcp.connectAll();
        if (registry.all().size() != EXPECTED_TOOLS) {
            mcp.shutdown();
            throw new IllegalStateException("medic MCP server mounted " + registry.all().size()
                    + " tool(s), expected " + EXPECTED_TOOLS + " — refusing to diagnose blind");
        }
        return new OperatingRoom(new AgentLoop(llm, registry, new ConversationMemory(), props, mapper), mcp);
    }

    /**
     * The server runs from this JVM's own classpath — the same code, spawned
     * as the separate stdio process the MCP transport expects.
     */
    private List<String> serverCommand(Path bundleDir, Path promptFile, Path historyDir) {
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return List.of(javaBin, "-cp", System.getProperty("java.class.path"),
                MedicMcpServer.class.getName(),
                "--bundle", bundleDir.toString(),
                "--prompt", promptFile.toString(),
                "--history", historyDir.toString());
    }

    private String repairInstruction() {
        try (var in = Surgeon.class.getResourceAsStream(REPAIR_PROMPT_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing classpath resource " + REPAIR_PROMPT_RESOURCE);
            }
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String readCurrentPrompt(Path promptFile) {
        try {
            return Files.readString(promptFile);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read current prompt " + promptFile, e);
        }
    }

    private static String unifiedDiff(String current, String proposed) {
        List<String> currentLines = current.lines().toList();
        List<String> proposedLines = proposed.lines().toList();
        return String.join("\n", UnifiedDiffUtils.generateUnifiedDiff(
                "current-prompt", "proposed-prompt", currentLines,
                DiffUtils.diff(currentLines, proposedLines), 3));
    }
}
