package utp.siga.evidence.application.dto;

import java.io.InputStream;

public record EvidenceContent(EvidenceResponse metadata, InputStream stream) {
}
