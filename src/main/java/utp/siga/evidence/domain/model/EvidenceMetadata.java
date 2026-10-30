package utp.siga.evidence.domain.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public record EvidenceMetadata(
        UUID id,
        OwnerType ownerType,
        UUID ownerId,
        String objectKey,
        String originalFilename,
        String contentType,
        long sizeBytes,
        String sha256,
        UUID uploadedByUserId,
        EvidenceStatus status,
        JsonNode metadata,
        Instant uploadedAt,
        Instant updatedAt) {
}
