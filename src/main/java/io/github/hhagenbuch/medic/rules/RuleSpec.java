package io.github.hhagenbuch.medic.rules;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * One declarative failure rule. Which fields are required depends on {@link Type};
 * {@link RulesLoader} validates that at load time so a misconfigured rule fails
 * startup instead of silently never firing.
 */
public record RuleSpec(
        String id,
        Type type,
        Pattern claimPattern,   // CLAIM_WITHOUT_TOOL: matched against assistant_message text
        String requiredTool,    // CLAIM_WITHOUT_TOOL: the tool that must have succeeded
        Pattern promptPattern,  // EXPECTED_TOOL: matched against user_message text
        String expectedTool,    // EXPECTED_TOOL: the tool that must have been called
        Set<String> allowedTools // UNEXPECTED_TOOL: every called tool must be in this set
) {

    public enum Type {
        /** Any {@code error} event in the trace. */
        ERROR_EVENT,
        /** The assistant claimed an action (claimPattern) with no successful requiredTool call in the turn. */
        CLAIM_WITHOUT_TOOL,
        /** The user asked for something (promptPattern) but expectedTool was never called in the turn. */
        EXPECTED_TOOL,
        /** A tool outside allowedTools was called. */
        UNEXPECTED_TOOL
    }
}
