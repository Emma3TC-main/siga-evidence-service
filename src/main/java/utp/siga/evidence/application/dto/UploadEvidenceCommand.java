package utp.siga.evidence.application.dto;

import java.util.UUID;
import org.springframework.web.multipart.MultipartFile;
import utp.siga.evidence.domain.model.OwnerType;

public record UploadEvidenceCommand(
        OwnerType ownerType,
        UUID ownerId,
        MultipartFile file,
        String metadata,
        UUID idempotencyKey,
        UUID actorId,
        UUID correlationId,
        String bearerToken) {
}
