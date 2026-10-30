package utp.siga.evidence.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import utp.siga.evidence.application.exception.EvidenceException;

class FileValidationServiceTest {
    private final FileValidationService validator = new FileValidationService(32, 64);

    @Test void acceptsPdfAndComputesSha() {
        ValidatedFile result = validator.validate(file("proof.pdf", "application/pdf", "%PDF-ok".getBytes()));
        assertThat(result.extension()).isEqualTo("pdf");
        assertThat(result.sha256()).matches("[0-9a-f]{64}");
    }

    @Test void acceptsJpeg() {
        ValidatedFile result = validator.validate(file("photo.jpeg", "image/jpeg",
                new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1}));
        assertThat(result.extension()).isEqualTo("jpg");
    }

    @Test void acceptsPng() {
        byte[] png = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1};
        assertThat(validator.validate(file("photo.png", "image/png", png)).contentType()).isEqualTo("image/png");
    }

    @Test void rejectsMimeMismatch() {
        assertThatThrownBy(() -> validator.validate(file("proof.pdf", "image/png", "%PDF-ok".getBytes())))
                .isInstanceOf(EvidenceException.class).hasMessageContaining("MIME");
    }

    @Test void rejectsMagicMismatch() {
        assertThatThrownBy(() -> validator.validate(file("proof.pdf", "application/pdf", "not-pdf".getBytes())))
                .isInstanceOf(EvidenceException.class).hasMessageContaining("contenido");
    }

    @Test void rejectsUnsupportedExtension() {
        assertThatThrownBy(() -> validator.validate(file("proof.txt", "text/plain", "x".getBytes())))
                .isInstanceOf(EvidenceException.class);
    }

    @Test void rejectsOversizedImage() {
        byte[] jpeg = new byte[33]; jpeg[0]=(byte)0xff; jpeg[1]=(byte)0xd8; jpeg[2]=(byte)0xff;
        assertThatThrownBy(() -> validator.validate(file("p.jpg", "image/jpeg", jpeg)))
                .isInstanceOf(EvidenceException.class).extracting("status.value").isEqualTo(413);
    }

    @Test void sanitizesPathAndControls() {
        ValidatedFile result = validator.validate(file("../bad\u0000name.pdf", "application/pdf", "%PDF-ok".getBytes()));
        assertThat(result.filename()).isEqualTo("badname.pdf");
    }

    private static MockMultipartFile file(String name, String type, byte[] bytes) {
        return new MockMultipartFile("file", name, type, bytes);
    }
}
