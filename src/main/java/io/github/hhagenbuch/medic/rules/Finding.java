package io.github.hhagenbuch.medic.rules;

/**
 * A rule firing against one turn of one trace. {@code evidence} is a
 * human-readable statement of what the trace shows — it goes verbatim into the
 * incident bundle, so it must stand on its own in front of a reviewer.
 */
public record Finding(String ruleId, int turn, String evidence) {
}
