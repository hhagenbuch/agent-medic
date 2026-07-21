package io.github.hhagenbuch.medic.surgeon;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProposalValidatorTest {

    @Test
    void acceptsAPlainProposal() {
        Proposal p = ProposalValidator.parse(
                "{\"systemPrompt\": \"Be honest.\", \"rationale\": \"The agent lied.\"}");
        assertThat(p.systemPrompt()).isEqualTo("Be honest.");
        assertThat(p.rationale()).isEqualTo("The agent lied.");
    }

    @Test
    void toleratesProseAndCodeFencesAroundTheObject() {
        Proposal p = ProposalValidator.parse("""
                Here is my proposal:
                ```json
                {"systemPrompt": "Be honest.", "rationale": "Evidence says so."}
                ```
                """);
        assertThat(p.systemPrompt()).isEqualTo("Be honest.");
    }

    @Test
    void rejectsAnyFieldOutsideTheAuthority() {
        // The load-bearing test: a proposal smuggling a model change has no channel.
        assertThatThrownBy(() -> ProposalValidator.parse(
                "{\"systemPrompt\": \"x\", \"rationale\": \"y\", \"model\": \"claude-opus-4-8\"}"))
                .isInstanceOf(ProposalValidator.InvalidProposalException.class)
                .hasMessageContaining("model")
                .hasMessageContaining("authority");
    }

    @Test
    void rejectsMissingOrBlankFields() {
        assertThatThrownBy(() -> ProposalValidator.parse("{\"systemPrompt\": \"x\"}"))
                .hasMessageContaining("rationale");
        assertThatThrownBy(() -> ProposalValidator.parse("{\"systemPrompt\": \"\", \"rationale\": \"y\"}"))
                .hasMessageContaining("systemPrompt");
        assertThatThrownBy(() -> ProposalValidator.parse("{\"systemPrompt\": 42, \"rationale\": \"y\"}"))
                .hasMessageContaining("systemPrompt");
    }

    @Test
    void rejectsOversizedPrompts() {
        String huge = "x".repeat(ProposalValidator.MAX_PROMPT_CHARS + 1);
        assertThatThrownBy(() -> ProposalValidator.parse(
                "{\"systemPrompt\": \"" + huge + "\", \"rationale\": \"y\"}"))
                .hasMessageContaining("exceeds");
    }

    @Test
    void rejectsNonJsonAnswers() {
        assertThatThrownBy(() -> ProposalValidator.parse("I think the prompt should be nicer."))
                .hasMessageContaining("no JSON object");
        assertThatThrownBy(() -> ProposalValidator.parse("{not json}"))
                .hasMessageContaining("not valid JSON");
    }

    @Test
    void rejectsANoOpRepair() {
        Proposal p = new Proposal("Be helpful.", "why");
        assertThatThrownBy(() -> ProposalValidator.requireChanged(p, "Be helpful.\n"))
                .hasMessageContaining("identical");
    }
}
