package utp.siga.evidence.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import utp.siga.evidence.application.dto.EvidenceResponse;
import utp.siga.evidence.domain.model.EvidenceMetadata;
import utp.siga.evidence.domain.model.OwnerType;

public interface EvidencePersistencePort {
    Reservation reserve(UUID key, String requestHash, UUID proposedEvidenceId);
    void complete(EvidenceMetadata evidence, OutboxRecord outbox, UUID idempotencyKey, EvidenceResponse response);
    void markFailed(UUID idempotencyKey);
    Optional<EvidenceMetadata> findById(UUID id);
    List<EvidenceMetadata> findByOwner(OwnerType ownerType, UUID ownerId, int page, int size);
    long countByOwner(OwnerType ownerType, UUID ownerId);

    enum ReservationKind { RESERVED, REPLAY }
    record Reservation(ReservationKind kind, UUID evidenceId, Instant reservedAt, EvidenceResponse response) { }
    record OutboxRecord(UUID id, UUID aggregateId, UUID correlationId, Instant occurredAt, String payload) { }
}
