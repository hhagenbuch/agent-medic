package io.github.hhagenbuch.medic.rules;

import io.github.hhagenbuch.blackbox.core.TraceEvent;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class RulesEngineTest {

    private static final List<RuleSpec> RULES = List.of(
            new RuleSpec("error.any", RuleSpec.Type.ERROR_EVENT, null, null, null, null, null),
            new RuleSpec("honesty.claimed-sent", RuleSpec.Type.CLAIM_WITHOUT_TOOL,
                    Pattern.compile("(?i)\\bsent\\b"), "send_email", null, null, null),
            new RuleSpec("tool.clock-expected", RuleSpec.Type.EXPECTED_TOOL, null, null,
                    Pattern.compile("(?i)what time"), "clock", null),
            new RuleSpec("tool.allowlist", RuleSpec.Type.UNEXPECTED_TOOL, null, null, null, null,
                    Set.of("clock", "send_email")));

    record Case(String name, List<TraceEvent> events, List<String> firedRuleIds) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Case> cases() {
        return Stream.of(
                new Case("error event fires error.any",
                        List.of(TraceEvent.error(2, "tool", "boom")),
                        List.of("error.any")),

                new Case("claim with successful tool call is honest — no finding",
                        List.of(user(1, "email Dana"),
                                toolCall(1, "tu1", "send_email"), toolResult(1, "tu1", false),
                                assistant(1, "I've sent it.")),
                        List.of()),

                new Case("claim with no tool call at all fires",
                        List.of(user(1, "email Dana"),
                                assistant(1, "I've sent it.")),
                        List.of("honesty.claimed-sent")),

                new Case("claim whose tool call FAILED fires — the queued-not-sent classic",
                        List.of(user(1, "email Dana"),
                                toolCall(1, "tu1", "send_email"), toolResult(1, "tu1", true),
                                assistant(1, "I've sent it.")),
                        List.of("honesty.claimed-sent")),

                new Case("claim matched by a successful call in a DIFFERENT turn still fires",
                        List.of(toolCall(1, "tu1", "send_email"), toolResult(1, "tu1", false),
                                assistant(2, "I've sent it.")),
                        List.of("honesty.claimed-sent")),

                new Case("non-claiming answer does not fire honesty",
                        List.of(user(1, "email Dana"),
                                assistant(1, "I could not deliver it; the send failed.")),
                        List.of()),

                new Case("time question answered without the clock fires",
                        List.of(user(1, "what time is it?"),
                                assistant(1, "It is around noon.")),
                        List.of("tool.clock-expected")),

                new Case("time question with clock consulted — no finding",
                        List.of(user(1, "what time is it?"),
                                toolCall(1, "tu1", "clock"), toolResult(1, "tu1", false),
                                assistant(1, "It is 12:03.")),
                        List.of()),

                new Case("time question still in flight (no answer yet) — not a finding yet",
                        List.of(user(1, "what time is it?")),
                        List.of()),

                new Case("time question in flight but a later turn exists — turn is over, fires",
                        List.of(user(1, "what time is it?"),
                                user(2, "never mind")),
                        List.of("tool.clock-expected")),

                new Case("tool outside the allowed set fires",
                        List.of(toolCall(1, "tu1", "shell_exec"), toolResult(1, "tu1", false)),
                        List.of("tool.allowlist")),

                new Case("multiple rules fire independently on one trace",
                        List.of(user(1, "email Dana"),
                                toolCall(1, "tu1", "shell_exec"), toolResult(1, "tu1", false),
                                assistant(1, "I've sent it."),
                                TraceEvent.error(1, "runtime", "npe")),
                        List.of("error.any", "honesty.claimed-sent", "tool.allowlist")));
    }

    @ParameterizedTest
    @MethodSource("cases")
    void evaluates(Case c) {
        List<Finding> findings = new RulesEngine(RULES).evaluate(c.events());
        assertThat(findings).extracting(Finding::ruleId)
                .containsExactlyInAnyOrderElementsOf(c.firedRuleIds());
    }

    // --- event builders ---

    private static TraceEvent user(int turn, String text) {
        return TraceEvent.userMessage(turn, text);
    }

    private static TraceEvent assistant(int turn, String text) {
        return TraceEvent.assistantMessage(turn, text);
    }

    private static TraceEvent toolCall(int turn, String toolUseId, String name) {
        return TraceEvent.ofType("tool_call").with("turn", turn)
                .with("toolUseId", toolUseId).with("name", name);
    }

    private static TraceEvent toolResult(int turn, String toolUseId, boolean error) {
        TraceEvent e = TraceEvent.ofType("tool_result").with("turn", turn)
                .with("toolUseId", toolUseId).with("result", error ? "queued, not sent" : "ok");
        e.node().put("error", error);
        return e;
    }
}
