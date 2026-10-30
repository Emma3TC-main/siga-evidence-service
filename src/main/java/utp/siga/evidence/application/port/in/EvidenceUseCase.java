package utp.siga.evidence.application.port.in;

import java.util.UUID;
import utp.siga.evidence.application.dto.EvidenceContent;
import utp.siga.evidence.application.dto.EvidencePage;
import utp.siga.evidence.application.dto.EvidenceResponse;
import utp.siga.evidence.application.dto.UploadEvidenceCommand;
import utp.siga.evidence.domain.model.OwnerType;

public interface EvidenceUseCase {
    EvidenceResponse upload(UploadEvidenceCommand command);
    EvidencePage list(OwnerType ownerType, UUID ownerId, int page, int size, String bearerToken);
    EvidenceResponse get(UUID id, String bearerToken);
    EvidenceContent content(UUID id, String bearerToken);
}
