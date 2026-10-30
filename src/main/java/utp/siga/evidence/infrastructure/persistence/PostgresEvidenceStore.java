package utp.siga.evidence.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import utp.siga.evidence.application.dto.EvidenceResponse;
import utp.siga.evidence.application.exception.EvidenceException;
import utp.siga.evidence.application.port.out.EvidencePersistencePort;
import utp.siga.evidence.domain.model.EvidenceMetadata;
import utp.siga.evidence.domain.model.EvidenceStatus;
import utp.siga.evidence.domain.model.OwnerType;

@Repository
public class PostgresEvidenceStore implements EvidencePersistencePort {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public PostgresEvidenceStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Reservation reserve(UUID key, String requestHash, UUID proposedEvidenceId) {
        int inserted = jdbc.update("""
                INSERT INTO evidence.upload_idempotency
                    (idempotency_key, request_hash, evidence_id, status)
                VALUES (?, ?, ?, 'PROCESSING')
                ON CONFLICT (idempotency_key) DO NOTHING
                """, key, requestHash, proposedEvidenceId);

        IdempotencyRow row = jdbc.queryForObject("""
                SELECT request_hash, evidence_id, status, response_payload, created_at
                FROM evidence.upload_idempotency
                WHERE idempotency_key = ?
                FOR UPDATE
                """, (rs, ignored) -> idempotencyRow(rs), key);
        if (row == null) throw new IllegalStateException("No se pudo reservar Idempotency-Key");
        if (!row.requestHash().equals(requestHash)) {
            throw conflict("La Idempotency-Key ya fue usada con otra solicitud");
        }
        if (inserted == 1) {
            return new Reservation(ReservationKind.RESERVED, row.evidenceId(), row.createdAt(), null);
        }
        if (row.status().equals("COMPLETED")) {
            return new Reservation(ReservationKind.REPLAY, row.evidenceId(), row.createdAt(), readResponse(row.response()));
        }
        if (row.status().equals("PROCESSING")) {
            throw conflict("La solicitud con esta Idempotency-Key todavía está en proceso");
        }
        jdbc.update("""
                UPDATE evidence.upload_idempotency
                SET status='PROCESSING', response_status=NULL, response_payload=NULL, updated_at=now()
                WHERE idempotency_key=?
                """, key);
        return new Reservation(ReservationKind.RESERVED, row.evidenceId(), row.createdAt(), null);
    }

    @Override
    @Transactional
    public void complete(EvidenceMetadata evidence, OutboxRecord outbox, UUID idempotencyKey, EvidenceResponse response) {
        jdbc.update("""
                INSERT INTO evidence.evidence_metadata
                    (id, owner_type, owner_id, object_key, original_filename, content_type, size_bytes,
                     sha256, storage_provider, uploaded_by_user_id, status, metadata, uploaded_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'MINIO', ?, ?, CAST(? AS jsonb), ?, ?)
                """, evidence.id(), evidence.ownerType().name(), evidence.ownerId(), evidence.objectKey(),
                evidence.originalFilename(), evidence.contentType(), evidence.sizeBytes(), evidence.sha256(),
                evidence.uploadedByUserId(), evidence.status().name(), write(evidence.metadata()),
                Timestamp.from(evidence.uploadedAt()), Timestamp.from(evidence.updatedAt()));
        jdbc.update("""
                INSERT INTO evidence.outbox_event
                    (id, aggregate_type, aggregate_id, event_type, schema_version, correlation_id,
                     payload, status, attempts, next_attempt_at, occurred_at)
                VALUES (?, 'Evidence', ?, 'EvidenceUploaded', 1, ?, CAST(? AS jsonb), 'PENDING', 0, now(), ?)
                """, outbox.id(), outbox.aggregateId(), outbox.correlationId(), outbox.payload(),
                Timestamp.from(outbox.occurredAt()));
        int updated = jdbc.update("""
                UPDATE evidence.upload_idempotency
                SET status='COMPLETED', response_status=201, response_payload=CAST(? AS jsonb), updated_at=now()
                WHERE idempotency_key=? AND evidence_id=? AND status='PROCESSING'
                """, write(response), idempotencyKey, evidence.id());
        if (updated != 1) throw new IllegalStateException("La reserva idempotente dejó de estar disponible");
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID idempotencyKey) {
        jdbc.update("""
                UPDATE evidence.upload_idempotency
                SET status='FAILED', response_status=NULL, response_payload=NULL, updated_at=now()
                WHERE idempotency_key=? AND status='PROCESSING'
                """, idempotencyKey);
    }

    @Override
    public Optional<EvidenceMetadata> findById(UUID id) {
        List<EvidenceMetadata> rows = jdbc.query("""
                SELECT id, owner_type, owner_id, object_key, original_filename, content_type, size_bytes,
                       sha256, uploaded_by_user_id, status, metadata, uploaded_at, updated_at
                FROM evidence.evidence_metadata WHERE id=?
                """, (rs, ignored) -> evidence(rs), id);
        return rows.stream().findFirst();
    }

    @Override
    public List<EvidenceMetadata> findByOwner(OwnerType ownerType, UUID ownerId, int page, int size) {
        return jdbc.query("""
                SELECT id, owner_type, owner_id, object_key, original_filename, content_type, size_bytes,
                       sha256, uploaded_by_user_id, status, metadata, uploaded_at, updated_at
                FROM evidence.evidence_metadata
                WHERE owner_type=? AND owner_id=?
                ORDER BY uploaded_at DESC, id DESC
                LIMIT ? OFFSET ?
                """, (rs, ignored) -> evidence(rs), ownerType.name(), ownerId, size, page * size);
    }

    @Override
    public long countByOwner(OwnerType ownerType, UUID ownerId) {
        Long count = jdbc.queryForObject("""
                SELECT count(*) FROM evidence.evidence_metadata WHERE owner_type=? AND owner_id=?
                """, Long.class, ownerType.name(), ownerId);
        return count == null ? 0 : count;
    }

    private EvidenceMetadata evidence(ResultSet rs) throws SQLException {
        return new EvidenceMetadata(rs.getObject("id", UUID.class), OwnerType.valueOf(rs.getString("owner_type")),
                rs.getObject("owner_id", UUID.class), rs.getString("object_key"), rs.getString("original_filename"),
                rs.getString("content_type"), rs.getLong("size_bytes"), rs.getString("sha256"),
                rs.getObject("uploaded_by_user_id", UUID.class), EvidenceStatus.valueOf(rs.getString("status")),
                readTree(rs.getString("metadata")), rs.getTimestamp("uploaded_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private IdempotencyRow idempotencyRow(ResultSet rs) throws SQLException {
        Timestamp created = rs.getTimestamp("created_at");
        return new IdempotencyRow(rs.getString("request_hash"), rs.getObject("evidence_id", UUID.class),
                rs.getString("status"), rs.getString("response_payload"), created.toInstant());
    }

    private EvidenceResponse readResponse(String value) {
        try {
            return mapper.readValue(value, EvidenceResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Respuesta idempotente inválida", e);
        }
    }

    private JsonNode readTree(String value) {
        try {
            return mapper.readTree(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON persistido inválido", e);
        }
    }

    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo serializar JSON", e);
        }
    }

    private static EvidenceException conflict(String detail) {
        return new EvidenceException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", detail);
    }

    private record IdempotencyRow(String requestHash, UUID evidenceId, String status, String response,
            Instant createdAt) { }
}
