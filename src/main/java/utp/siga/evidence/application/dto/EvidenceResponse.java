package utp.siga.evidence.application.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;
import utp.siga.evidence.domain.model.EvidenceMetadata;
import utp.siga.evidence.domain.model.EvidenceStatus;
import utp.siga.evidence.domain.model.OwnerType;

public record EvidenceResponse(
        UUID id,
        OwnerType ownerType,
        UUID ownerId,
        String originalFilename,
        String contentType,
        long sizeBytes,
        String sha256,
        UUID uploadedByUserId,
        EvidenceStatus status,
        JsonNode metadata,
        Instant uploadedAt,
        Instant updatedAt) {

    public static EvidenceResponse from(EvidenceMetadata evidence) {
        return new EvidenceResponse(evidence.id(), evidence.ownerType(), evidence.ownerId(),
                evidence.originalFilename(), evidence.contentType(), evidence.sizeBytes(), evidence.sha256(),
                evidence.uploadedByUserId(), evidence.status(), evidence.metadata(), evidence.uploadedAt(),
                evidence.updatedAt());
    }
}
