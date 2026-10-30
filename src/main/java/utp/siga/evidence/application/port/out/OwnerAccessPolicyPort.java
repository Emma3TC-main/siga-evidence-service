package utp.siga.evidence.application.port.out;

import java.util.UUID;
import utp.siga.evidence.domain.model.OwnerType;

public interface OwnerAccessPolicyPort {
    void validateAccess(OwnerType ownerType, UUID ownerId, String bearerToken);
}
