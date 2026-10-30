package utp.siga.evidence.application.dto;

import java.util.List;

public record EvidencePage(
        List<EvidenceResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last) {
}
