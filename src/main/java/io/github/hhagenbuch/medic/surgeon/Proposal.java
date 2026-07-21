package io.github.hhagenbuch.medic.surgeon;

/**
 * A validated repair proposal. This record is the Surgeon's entire authority:
 * there is deliberately no field for tools, model, or anything else — a
 * proposal that "wants" to change more than prompt text has no channel through
 * which to say so (DESIGN.md §3.3).
 */
public record Proposal(String systemPrompt, String rationale) {
}
