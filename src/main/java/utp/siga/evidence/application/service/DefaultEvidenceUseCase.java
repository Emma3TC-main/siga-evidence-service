package utp.siga.evidence.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import utp.siga.evidence.application.dto.EvidenceContent;
import utp.siga.evidence.application.dto.EvidencePage;
import utp.siga.evidence.application.dto.EvidenceResponse;
import utp.siga.evidence.application.dto.UploadEvidenceCommand;
import utp.siga.evidence.application.exception.EvidenceException;
import utp.siga.evidence.application.port.in.EvidenceUseCase;
import utp.siga.evidence.application.port.out.EvidencePersistencePort;
import utp.siga.evidence.application.port.out.EvidencePersistencePort.OutboxRecord;
import utp.siga.evidence.application.port.out.EvidencePersistencePort.Reservation;
import utp.siga.evidence.application.port.out.EvidencePersistencePort.ReservationKind;
import utp.siga.evidence.application.port.out.ObjectStoragePort;
import utp.siga.evidence.application.port.out.OwnerAccessPolicyPort;
import utp.siga.evidence.domain.model.EvidenceMetadata;
import utp.siga.evidence.domain.model.EvidenceStatus;
import utp.siga.evidence.domain.model.OwnerType;

@Service
public class DefaultEvidenceUseCase implements EvidenceUseCase {
    private static final Logger log = LoggerFactory.getLogger(DefaultEvidenceUseCase.class);
    private final OwnerAccessPolicyPort ownerAccess;
    private final ObjectStoragePort storage;
    private final EvidencePersistencePort persistence;
    private final FileValidationService files;
    private final MetadataValidationService metadataValidator;
    private final ObjectMapper mapper;
    private final MeterRegistry meters;
    private final Clock clock;

    @Autowired
    public DefaultEvidenceUseCase(OwnerAccessPolicyPort ownerAccess, ObjectStoragePort storage,
            EvidencePersistencePort persistence, FileValidationService files,
            MetadataValidationService metadataValidator, ObjectMapper mapper, MeterRegistry meters) {
        this(ownerAccess, storage, persistence, files, metadataValidator, mapper, meters, Clock.systemUTC());
    }

    DefaultEvidenceUseCase(OwnerAccessPolicyPort ownerAccess, ObjectStoragePort storage,
            EvidencePersistencePort persistence, FileValidationService files,
            MetadataValidationService metadataValidator, ObjectMapper mapper, MeterRegistry meters, Clock clock) {
        this.ownerAccess = ownerAccess;
        this.storage = storage;
        this.persistence = persistence;
        this.files = files;
        this.metadataValidator = metadataValidator;
        this.mapper = mapper;
        this.meters = meters;
        this.clock = clock;
    }

    @Override
    public EvidenceResponse upload(UploadEvidenceCommand command) {
        requireUpload(command);
        ownerAccess.validateAccess(command.ownerType(), command.ownerId(), command.bearerToken());
        ValidatedFile file = files.validate(command.file());
        ObjectNode metadata = metadataValidator.validate(command.metadata());
        String requestHash = fingerprint(command.ownerType(), command.ownerId(), file, metadata);
        Reservation reservation = persistence.reserve(command.idempotencyKey(), requestHash, UUID.randomUUID());
        if (reservation.kind() == ReservationKind.REPLAY) return reservation.response();

        UUID evidenceId = reservation.evidenceId();
        int year = reservation.reservedAt().atZone(ZoneOffset.UTC).getYear();
        String objectKey = "movements/%d/%s/%s.%s".formatted(year, command.ownerId(), evidenceId, file.extension());
        try {
            storage.put(objectKey, file.bytes(), file.contentType());
        } catch (RuntimeException failure) {
            persistence.markFailed(command.idempotencyKey());
            throw failure;
        }

        Instant now = clock.instant();
        EvidenceMetadata evidence = new EvidenceMetadata(evidenceId, command.ownerType(), command.ownerId(),
                objectKey, file.filename(), file.contentType(), file.size(), file.sha256(), command.actorId(),
                EvidenceStatus.AVAILABLE, metadata, now, now);
        EvidenceResponse response = EvidenceResponse.from(evidence);
        UUID eventId = UUID.randomUUID();
        String envelope = envelope(eventId, command.correlationId(), evidence);
        try {
            persistence.complete(evidence, new OutboxRecord(eventId, evidenceId, command.correlationId(), now, envelope),
                    command.idempotencyKey(), response);
            return response;
        } catch (RuntimeException databaseFailure) {
            try {
                storage.delete(objectKey);
            } catch (RuntimeException compensationFailure) {
                meters.counter("evidence.storage.orphan.incidents").increment();
                log.error("evidence_orphan_compensation_failed evidenceId={} objectKey={} correlationId={}",
                        evidenceId, objectKey, command.correlationId(), compensationFailure);
            }
            persistence.markFailed(command.idempotencyKey());
            throw databaseFailure;
        }
    }

    @Override
    public EvidencePage list(OwnerType ownerType, UUID ownerId, int page, int size, String bearerToken) {
        if (ownerType == null || ownerId == null) throw badRequest("ownerType y ownerId son obligatorios");
        if (page < 0 || size < 1 || size > 100) throw badRequest("Paginación inválida");
        ownerAccess.validateAccess(ownerType, ownerId, bearerToken);
        List<EvidenceResponse> content = persistence.findByOwner(ownerType, ownerId, page, size).stream()
                .map(EvidenceResponse::from).toList();
        long total = persistence.countByOwner(ownerType, ownerId);
        int pages = total == 0 ? 0 : (int) Math.ceil((double) total / size);
        return new EvidencePage(content, page, size, total, pages, page == 0, page + 1 >= pages);
    }

    @Override
    public EvidenceResponse get(UUID id, String bearerToken) {
        EvidenceMetadata evidence = load(id);
        ownerAccess.validateAccess(evidence.ownerType(), evidence.ownerId(), bearerToken);
        return EvidenceResponse.from(evidence);
    }

    @Override
    public EvidenceContent content(UUID id, String bearerToken) {
        EvidenceMetadata evidence = load(id);
        ownerAccess.validateAccess(evidence.ownerType(), evidence.ownerId(), bearerToken);
        return new EvidenceContent(EvidenceResponse.from(evidence), storage.get(evidence.objectKey()));
    }

    private EvidenceMetadata load(UUID id) {
        if (id == null) throw badRequest("El id es obligatorio");
        return persistence.findById(id).orElseThrow(() ->
                new EvidenceException(HttpStatus.NOT_FOUND, "EVIDENCE_NOT_FOUND", "La evidencia no existe"));
    }

    private void requireUpload(UploadEvidenceCommand command) {
        if (command == null || command.ownerType() == null || command.ownerId() == null || command.idempotencyKey() == null
                || command.actorId() == null || command.correlationId() == null || command.bearerToken() == null) {
            throw badRequest("La solicitud de evidencia es incompleta");
        }
    }

    private String fingerprint(OwnerType ownerType, UUID ownerId, ValidatedFile file, ObjectNode metadata) {
        ObjectNode input = mapper.createObjectNode();
        input.put("ownerType", ownerType.name());
        input.put("ownerId", ownerId.toString());
        input.set("metadata", canonical(metadata));
        input.put("filename", file.filename());
        input.put("contentType", file.contentType());
        input.put("sha256", file.sha256());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(mapper.writeValueAsBytes(input)));
        } catch (NoSuchAlgorithmException | JsonProcessingException e) {
            throw new IllegalStateException("No se pudo calcular el fingerprint", e);
        }
    }

    private JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = mapper.createObjectNode();
            TreeMap<String, JsonNode> fields = new TreeMap<>();
            node.fields().forEachRemaining(entry -> fields.put(entry.getKey(), entry.getValue()));
            fields.forEach((key, value) -> sorted.set(key, canonical(value)));
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode array = mapper.createArrayNode();
            node.forEach(value -> array.add(canonical(value)));
            return array;
        }
        return node;
    }

    private String envelope(UUID eventId, UUID correlationId, EvidenceMetadata evidence) {
        ObjectNode root = mapper.createObjectNode();
        root.put("eventId", eventId.toString());
        root.put("eventType", "EvidenceUploaded");
        root.put("schemaVersion", 1);
        root.put("aggregateType", "Evidence");
        root.put("aggregateId", evidence.id().toString());
        root.put("occurredAt", evidence.uploadedAt().toString());
        root.put("correlationId", correlationId.toString());
        root.put("producer", "evidence-service");
        ObjectNode payload = root.putObject("payload");
        payload.put("id", evidence.id().toString());
        payload.put("ownerType", evidence.ownerType().name());
        payload.put("ownerId", evidence.ownerId().toString());
        payload.put("uploadedByUserId", evidence.uploadedByUserId().toString());
        payload.put("contentType", evidence.contentType());
        payload.put("sizeBytes", evidence.sizeBytes());
        payload.put("sha256", evidence.sha256());
        payload.put("status", evidence.status().name());
        payload.put("uploadedAt", evidence.uploadedAt().toString());
        try {
            return mapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo serializar EvidenceUploaded", e);
        }
    }

    private static EvidenceException badRequest(String message) {
        return new EvidenceException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }
}
