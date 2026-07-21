package io.github.hhagenbuch.medic.rules;

import io.github.hhagenbuch.blackbox.core.TraceEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Evaluates the failure rules against a trace. Deterministic and LLM-free by
 * design — findings are evidence, and evidence must never be hallucinated.
 *
 * <p>Traces are append-only and may be read mid-write, so rules that judge a
 * whole turn ({@code EXPECTED_TOOL}) only evaluate turns that have their final
 * {@code assistant_message}; an in-flight turn is not a finding yet.
 * {@code CLAIM_WITHOUT_TOOL} keys off the {@code assistant_message} itself and
 * {@code ERROR_EVENT}/{@code UNEXPECTED_TOOL} key off single events, so those
 * are safe on partial traces by construction.
 */
public final class RulesEngine {

    private final List<RuleSpec> rules;

    public RulesEngine(List<RuleSpec> rules) {
        this.rules = List.copyOf(rules);
    }

    public List<Finding> evaluate(List<TraceEvent> events) {
        List<Finding> findings = new ArrayList<>();
        for (RuleSpec rule : rules) {
            switch (rule.type()) {
                case ERROR_EVENT -> errorEvents(rule, events, findings);
                case CLAIM_WITHOUT_TOOL -> claimsWithoutTool(rule, events, findings);
                case EXPECTED_TOOL -> expectedTool(rule, events, findings);
                case UNEXPECTED_TOOL -> unexpectedTool(rule, events, findings);
            }
        }
        return findings;
    }

    private void errorEvents(RuleSpec rule, List<TraceEvent> events, List<Finding> findings) {
        for (TraceEvent e : events) {
            if (e.type().equals("error")) {
                findings.add(new Finding(rule.id(), e.turn(),
                        "error in '" + e.node().path("where").asText("?") + "': "
                                + e.node().path("message").asText("")));
            }
        }
    }

    private void claimsWithoutTool(RuleSpec rule, List<TraceEvent> events, List<Finding> findings) {
        for (TraceEvent e : events) {
            if (!e.type().equals("assistant_message") || e.text() == null
                    || !rule.claimPattern().matcher(e.text()).find()) {
                continue;
            }
            if (!toolSucceededInTurn(events, e.turn(), rule.requiredTool())) {
                findings.add(new Finding(rule.id(), e.turn(),
                        "assistant claimed \"" + excerpt(e.text()) + "\" but no successful '"
                                + rule.requiredTool() + "' call exists in turn " + e.turn()));
            }
        }
    }

    private void expectedTool(RuleSpec rule, List<TraceEvent> events, List<Finding> findings) {
        for (TraceEvent e : events) {
            if (!e.type().equals("user_message") || e.text() == null
                    || !rule.promptPattern().matcher(e.text()).find()) {
                continue;
            }
            if (!turnAnswered(events, e.turn())) {
                continue; // turn still in flight — not a finding yet
            }
            boolean called = events.stream().anyMatch(t -> t.type().equals("tool_call")
                    && t.turn() == e.turn()
                    && rule.expectedTool().equals(t.node().path("name").asText(null)));
            if (!called) {
                findings.add(new Finding(rule.id(), e.turn(),
                        "user asked \"" + excerpt(e.text()) + "\" but '" + rule.expectedTool()
                                + "' was never called in turn " + e.turn()));
            }
        }
    }

    private void unexpectedTool(RuleSpec rule, List<TraceEvent> events, List<Finding> findings) {
        for (TraceEvent e : events) {
            if (!e.type().equals("tool_call")) {
                continue;
            }
            String name = e.node().path("name").asText("");
            if (!rule.allowedTools().contains(name)) {
                findings.add(new Finding(rule.id(), e.turn(),
                        "tool '" + name + "' called in turn " + e.turn()
                                + " but is not in the allowed set " + rule.allowedTools()));
            }
        }
    }

    private static boolean toolSucceededInTurn(List<TraceEvent> events, int turn, String tool) {
        for (TraceEvent call : events) {
            if (!call.type().equals("tool_call") || call.turn() != turn
                    || !tool.equals(call.node().path("name").asText(null))) {
                continue;
            }
            String toolUseId = call.node().path("toolUseId").asText(null);
            for (TraceEvent result : events) {
                if (result.type().equals("tool_result") && result.turn() == turn
                        && result.node().path("toolUseId").asText("").equals(toolUseId)
                        && !result.node().path("error").asBoolean(false)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean turnAnswered(List<TraceEvent> events, int turn) {
        return events.stream().anyMatch(e ->
                (e.type().equals("assistant_message") && e.turn() == turn)
                        || e.type().equals("session_end")
                        || e.turn() > turn);
    }

    private static String excerpt(String text) {
        return text.length() <= 120 ? text : text.substring(0, 117) + "...";
    }
}
