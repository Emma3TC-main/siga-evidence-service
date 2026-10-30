package utp.siga.evidence.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import utp.siga.evidence.application.exception.EvidenceException;

class MetadataValidationServiceTest {
    private final MetadataValidationService validator = new MetadataValidationService(new ObjectMapper());

    @Test void blankBecomesEmptyObject() { assertThat(validator.validate(null)).isEmpty(); }
    @Test void acceptsBoundedObject() { assertThat(validator.validate("{\"document\":\"A-1\"}").get("document").asText()).isEqualTo("A-1"); }
    @Test void rejectsNonObjectRoot() { rejects("[1]"); }
    @Test void rejectsSecretKey() { rejects("{\"token\":\"abc\"}"); }
    @Test void rejectsBase64LikeValue() { rejects("{\"blob\":\"" + "A".repeat(300) + "\"}"); }
    @Test void rejectsLongString() { rejects("{\"note\":\"" + "x".repeat(1025) + "\"}"); }
    @Test void rejectsExcessiveDepth() { rejects("{\"a\":{\"b\":{\"c\":{\"d\":{\"e\":1}}}}}"); }
    @Test void rejectsInvalidJson() { rejects("{oops"); }

    private void rejects(String value) {
        assertThatThrownBy(() -> validator.validate(value)).isInstanceOf(EvidenceException.class);
    }
}
