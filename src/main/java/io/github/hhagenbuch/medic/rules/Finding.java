package io.github.hhagenbuch.medic.rules;

/**
 * A rule firing against one turn of one trace. {@code evidence} is a
 * human-readable statement of what the trace shows — it goes verbatim into the
 * incident bundle, so it must stand on its own in front of a reviewer.
 *
 * <p>{@code requiredTool} carries the rule's knowledge of what CORRECT
 * behavior looks like (the tool that must succeed / must have been consulted).
 * The Diagnoser uses it to shape the exported regression case: the antibody
 * asserts the correct behavior the rule defines, not similarity to the
 * recorded failure. Null for rules with no single correct tool.
 */
public record Finding(String ruleId, int turn, String evidence, String requiredTool) {

    public Finding(String ruleId, int turn, String evidence) {
        this(ruleId, turn, evidence, null);
    }
}
