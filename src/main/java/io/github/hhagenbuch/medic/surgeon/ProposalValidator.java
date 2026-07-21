package io.github.hhagenbuch.medic.surgeon;

import io.github.hhagenbuch.blackbox.core.TraceEvent;
import tools.jackson.databind.JsonNode;

import java.util.Set;

/**
 * Model output is untrusted input. The Surgeon's final answer must be a JSON
 * object with exactly {@code systemPrompt} and {@code rationale} — nothing
 * more, nothing less, within size caps, and actually different from the
 * current prompt. Anything else is rejected with a reason a human can read in
 * the proposal's status.
 */
public final class ProposalValidator {

    static final int MAX_PROMPT_CHARS = 20_000;
    static final int MAX_RATIONALE_CHARS = 5_000;

    private static final Set<String> ALLOWED_FIELDS = Set.of("systemPrompt", "rationale");

    private ProposalValidator() {
    }

    /** Parses and validates the Surgeon's raw answer. Throws {@link InvalidProposalException} with the reason. */
    public static Proposal parse(String modelAnswer) {
        JsonNode node = extractJson(modelAnswer);

        for (String field : node.propertyNames()) {
            if (!ALLOWED_FIELDS.contains(field)) {
                throw new InvalidProposalException("proposal contains field '" + field
                        + "' — the Surgeon's authority is prompt text and rationale only");
            }
        }
        String prompt = requireText(node, "systemPrompt", MAX_PROMPT_CHARS);
        String rationale = requireText(node, "rationale", MAX_RATIONALE_CHARS);
        return new Proposal(prompt, rationale);
    }

    /** The one semantic check: a "repair" that changes nothing is not a repair. */
    public static void requireChanged(Proposal proposal, String currentPrompt) {
        if (proposal.systemPrompt().strip().equals(currentPrompt.strip())) {
            throw new InvalidProposalException("proposed prompt is identical to the current prompt");
        }
    }

    /**
     * Tolerates prose or code fences around the object — models decorate — but
     * the payload itself is held to the strict schema above.
     */
    private static JsonNode extractJson(String answer) {
        if (answer == null) {
            throw new InvalidProposalException("empty answer");
        }
        int start = answer.indexOf('{');
        int end = answer.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new InvalidProposalException("no JSON object found in the answer");
        }
        try {
            JsonNode node = TraceEvent.mapper().readTree(answer.substring(start, end + 1));
            if (!node.isObject()) {
                throw new InvalidProposalException("proposal is not a JSON object");
            }
            return node;
        } catch (InvalidProposalException e) {
            throw e;
        } catch (Exception e) {
            throw new InvalidProposalException("proposal is not valid JSON: " + e.getMessage());
        }
    }

    private static String requireText(JsonNode node, String field, int maxChars) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString() || value.asText().isBlank()) {
            throw new InvalidProposalException("proposal is missing a non-empty string '" + field + "'");
        }
        String text = value.asText();
        if (text.length() > maxChars) {
            throw new InvalidProposalException("'" + field + "' exceeds " + maxChars + " characters ("
                    + text.length() + ")");
        }
        return text;
    }

    public static final class InvalidProposalException extends RuntimeException {
        InvalidProposalException(String message) {
            super(message);
        }
    }
}
