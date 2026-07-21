package io.github.hhagenbuch.medic.rules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RulesLoaderTest {

    @TempDir
    Path dir;

    @Test
    void loadsAllFourRuleTypes() throws IOException {
        List<RuleSpec> rules = RulesLoader.load(write("""
                rules:
                  - id: error.any
                    type: error-event
                  - id: honesty.sent
                    type: claim-without-tool
                    claimPattern: "(?i)sent"
                    requiredTool: send_email
                  - id: tool.clock
                    type: expected-tool
                    promptPattern: "(?i)what time"
                    expectedTool: clock
                  - id: tool.allow
                    type: unexpected-tool
                    allowedTools: [clock, send_email]
                """));
        assertThat(rules).extracting(RuleSpec::id)
                .containsExactly("error.any", "honesty.sent", "tool.clock", "tool.allow");
        assertThat(rules.get(1).claimPattern().matcher("I SENT it").find()).isTrue();
        assertThat(rules.get(3).allowedTools()).containsExactlyInAnyOrder("clock", "send_email");
    }

    @Test
    void missingRequiredFieldFailsLoudly() {
        assertThatThrownBy(() -> RulesLoader.load(write("""
                rules:
                  - id: honesty.sent
                    type: claim-without-tool
                    claimPattern: "(?i)sent"
                """)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requiredTool");
    }

    @Test
    void invalidRegexFailsLoudly() {
        assertThatThrownBy(() -> RulesLoader.load(write("""
                rules:
                  - id: honesty.sent
                    type: claim-without-tool
                    claimPattern: "(unclosed"
                    requiredTool: send_email
                """)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("claimPattern");
    }

    @Test
    void duplicateIdFailsLoudly() {
        assertThatThrownBy(() -> RulesLoader.load(write("""
                rules:
                  - id: error.any
                    type: error-event
                  - id: error.any
                    type: error-event
                """)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    void unknownTypeFailsLoudly() {
        assertThatThrownBy(() -> RulesLoader.load(write("""
                rules:
                  - id: x
                    type: vibes-based
                """)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown type");
    }

    @Test
    void emptyRulesFileFailsLoudly() {
        assertThatThrownBy(() -> RulesLoader.load(write("rules: []")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-empty");
    }

    private Path write(String yaml) {
        try {
            return Files.writeString(dir.resolve("rules.yaml"), yaml);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
