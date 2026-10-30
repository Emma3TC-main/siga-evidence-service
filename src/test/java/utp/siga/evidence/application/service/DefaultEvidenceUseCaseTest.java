package utp.siga.evidence.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import utp.siga.evidence.application.dto.EvidenceResponse;
import utp.siga.evidence.application.dto.UploadEvidenceCommand;
import utp.siga.evidence.application.port.out.EvidencePersistencePort;
import utp.siga.evidence.application.port.out.ObjectStoragePort;
import utp.siga.evidence.application.port.out.OwnerAccessPolicyPort;
import utp.siga.evidence.domain.model.EvidenceStatus;
import utp.siga.evidence.domain.model.OwnerType;

class DefaultEvidenceUseCaseTest {
    private final OwnerAccessPolicyPort owner = mock(OwnerAccessPolicyPort.class);
    private final ObjectStoragePort storage = mock(ObjectStoragePort.class);
    private final EvidencePersistencePort persistence = mock(EvidencePersistencePort.class);
    private final FileValidationService files = mock(FileValidationService.class);
    private final MetadataValidationService metadata = mock(MetadataValidationService.class);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final Instant now = Instant.parse("2026-10-15T12:00:00Z");
    private DefaultEvidenceUseCase service;
    private UploadEvidenceCommand command;
    private UUID evidenceId;

    @BeforeEach void setUp() {
        service = new DefaultEvidenceUseCase(owner, storage, persistence, files, metadata, mapper, meters,
                Clock.fixed(now, ZoneOffset.UTC));
        evidenceId = UUID.randomUUID();
        command = new UploadEvidenceCommand(OwnerType.MOVEMENT, UUID.randomUUID(),
                new MockMultipartFile("file", "proof.pdf", "application/pdf", "%PDF-ok".getBytes()), "{}",
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "Bearer test");
        when(files.validate(any())).thenReturn(new ValidatedFile("%PDF-ok".getBytes(), "proof.pdf",
                "application/pdf", "pdf", "a".repeat(64)));
        when(metadata.validate(any())).thenReturn(mapper.createObjectNode());
        when(persistence.reserve(any(), anyString(), any())).thenReturn(new EvidencePersistencePort.Reservation(
                EvidencePersistencePort.ReservationKind.RESERVED, evidenceId, now, null));
    }

    @Test void deletesObjectAndMarksFailedWhenDatabaseCompletionFails() {
        doThrow(new IllegalStateException("db")).when(persistence).complete(any(), any(), any(), any());
        assertThatThrownBy(() -> service.upload(command)).isInstanceOf(IllegalStateException.class);
        verify(storage).delete("movements/2026/" + command.ownerId() + "/" + evidenceId + ".pdf");
        verify(persistence).markFailed(command.idempotencyKey());
    }

    @Test void recordsOrphanMetricWhenCompensationFails() {
        doThrow(new IllegalStateException("db")).when(persistence).complete(any(), any(), any(), any());
        doThrow(new IllegalStateException("minio")).when(storage).delete(anyString());
        assertThatThrownBy(() -> service.upload(command)).isInstanceOf(IllegalStateException.class);
        assertThat(meters.counter("evidence.storage.orphan.incidents").count()).isEqualTo(1);
    }

    @Test void storageFailureDoesNotAttemptDatabaseCompletion() {
        doThrow(new IllegalStateException("storage")).when(storage).put(anyString(), any(), anyString());
        assertThatThrownBy(() -> service.upload(command)).isInstanceOf(IllegalStateException.class);
        verify(persistence, never()).complete(any(), any(), any(), any());
        verify(persistence).markFailed(command.idempotencyKey());
    }

    @Test void completedReservationReplaysWithoutStorageWrite() {
        EvidenceResponse response = new EvidenceResponse(evidenceId, OwnerType.MOVEMENT, command.ownerId(), "proof.pdf",
                "application/pdf", 7, "a".repeat(64), command.actorId(), EvidenceStatus.AVAILABLE,
                mapper.createObjectNode(), now, now);
        when(persistence.reserve(any(), anyString(), any())).thenReturn(new EvidencePersistencePort.Reservation(
                EvidencePersistencePort.ReservationKind.REPLAY, evidenceId, now, response));
        assertThat(service.upload(command)).isSameAs(response);
        verify(storage, never()).put(anyString(), any(), anyString());
    }
}
