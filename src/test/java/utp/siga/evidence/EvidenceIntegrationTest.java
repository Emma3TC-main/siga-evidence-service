package utp.siga.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import io.minio.GetBucketPolicyArgs;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import utp.siga.evidence.application.dto.EvidenceResponse;
import utp.siga.evidence.application.exception.EvidenceException;
import utp.siga.evidence.application.port.out.OwnerAccessPolicyPort;
import utp.siga.evidence.application.port.out.EvidencePersistencePort;
import utp.siga.evidence.domain.model.EvidenceMetadata;
import utp.siga.evidence.domain.model.EvidenceStatus;
import utp.siga.evidence.domain.model.OwnerType;
import utp.siga.evidence.infrastructure.messaging.EvidenceOutboxPublisher;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class EvidenceIntegrationTest {
    private static final String MINIO_USER = "evidence_test_user";
    private static final String MINIO_PASSWORD = "evidence_test_password_123";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("evidence_ephemeral_test").withUsername("evidence_test").withPassword("evidence_test");
    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13.7-management-alpine");
    @Container
    static final GenericContainer<?> MINIO = new GenericContainer<>(DockerImageName.parse("minio/minio:latest"))
            .withExposedPorts(9000).withEnv("MINIO_ROOT_USER", MINIO_USER)
            .withEnv("MINIO_ROOT_PASSWORD", MINIO_PASSWORD).withCommand("server", "/data");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
        registry.add("evidence.storage.endpoint", () -> "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
        registry.add("evidence.storage.access-key", () -> MINIO_USER);
        registry.add("evidence.storage.secret-key", () -> MINIO_PASSWORD);
        registry.add("evidence.outbox.initial-delay-ms", () -> "3600000");
        registry.add("evidence.outbox.fixed-delay-ms", () -> "3600000");
        registry.add("evidence.outbox.confirm-timeout-ms", () -> "10000");
        registry.add("evidence.storage.image-max-bytes", () -> "32");
        registry.add("evidence.storage.pdf-max-bytes", () -> "64");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired MinioClient minio;
    @Autowired EvidenceOutboxPublisher publisher;
    @Autowired EvidencePersistencePort persistence;
    @Autowired AmqpAdmin rabbitAdmin;
    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired TopicExchange exchange;
    @MockitoBean OwnerAccessPolicyPort ownerAccess;

    private final UUID actor = UUID.fromString("12000000-0000-0000-0000-000000000001");

    @BeforeEach void isolatedGuardAndOwner() {
        assertThat(POSTGRES.getDatabaseName()).contains("test");
        assertThat(POSTGRES.getJdbcUrl()).contains(String.valueOf(POSTGRES.getMappedPort(5432)));
        assertThat(MINIO.isRunning()).isTrue();
        assertThat(RABBIT.isRunning()).isTrue();
        reset(ownerAccess);
        doNothing().when(ownerAccess).validateAccess(any(), any(), any());
    }

    @Test void flywayCreatesOnlyCanonicalBusinessTables() {
        List<String> tables = jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema='evidence' AND table_type='BASE TABLE' AND table_name <> 'flyway_schema_history'
                ORDER BY table_name
                """, String.class);
        assertThat(tables).containsExactly("evidence_metadata", "outbox_event", "upload_idempotency");
        Integer version = jdbc.queryForObject("SELECT max(CAST(version AS integer)) FROM evidence.flyway_schema_history",
                Integer.class);
        assertThat(version).isEqualTo(3);
    }

    @Test void missingTokenIs401() throws Exception {
        mvc.perform(get("/api/v1/evidences").param("ownerType", "MOVEMENT").param("ownerId", UUID.randomUUID().toString()))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH_UNAUTHORIZED"));
    }

    @Test void wrongReadPermissionIs403() throws Exception {
        mvc.perform(get("/api/v1/evidences").param("ownerType", "MOVEMENT").param("ownerId", UUID.randomUUID().toString())
                        .with(jwt().authorities(new SimpleGrantedAuthority("EVIDENCE_WRITE"))))
                .andExpect(status().isForbidden());
    }

    @Test void wrongWritePermissionIs403() throws Exception {
        mvc.perform(multipart("/api/v1/evidences").file(pdf()).param("ownerType", "MOVEMENT")
                        .param("ownerId", UUID.randomUUID().toString()).header("Idempotency-Key", UUID.randomUUID())
                        .with(jwt().authorities(new SimpleGrantedAuthority("EVIDENCE_READ"))))
                .andExpect(status().isForbidden());
    }

    @Test void unsupportedOwnerAndInvalidUuidAre400() throws Exception {
        mvc.perform(multipart("/api/v1/evidences").file(pdf()).param("ownerType", "ASSET")
                        .param("ownerId", UUID.randomUUID().toString()).header("Idempotency-Key", UUID.randomUUID())
                        .with(writeJwt()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/evidences").param("ownerType", "MOVEMENT").param("ownerId", "not-a-uuid")
                        .with(readJwt()))
                .andExpect(status().isBadRequest());
    }

    @Test void missingMultipartIs400OversizedImageIs413AndMissingEvidenceIs404() throws Exception {
        UUID owner = UUID.randomUUID();
        mvc.perform(multipart("/api/v1/evidences").param("ownerType", "MOVEMENT").param("ownerId", owner.toString())
                        .header("Idempotency-Key", UUID.randomUUID()).with(writeJwt()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        byte[] jpeg = new byte[33]; jpeg[0]=(byte)0xff; jpeg[1]=(byte)0xd8; jpeg[2]=(byte)0xff;
        mvc.perform(uploadRequest(owner, UUID.randomUUID(),
                        new MockMultipartFile("file", "large.jpg", "image/jpeg", jpeg), "{}"))
                .andExpect(status().isPayloadTooLarge());
        mvc.perform(get("/api/v1/evidences/{id}", UUID.randomUUID()).with(readJwt()))
                .andExpect(status().isNotFound());
    }

    @Test void ownerNotFoundAndUnavailableAreMapped() throws Exception {
        UUID owner = UUID.randomUUID();
        doThrow(new EvidenceException(HttpStatus.NOT_FOUND, "OWNER_NOT_FOUND", "missing"))
                .when(ownerAccess).validateAccess(eq(OwnerType.MOVEMENT), eq(owner), any());
        mvc.perform(get("/api/v1/evidences").param("ownerType", "MOVEMENT").param("ownerId", owner.toString())
                        .with(readJwt()))
                .andExpect(status().isNotFound());
        reset(ownerAccess);
        doThrow(new EvidenceException(HttpStatus.SERVICE_UNAVAILABLE, "OWNER_SERVICE_UNAVAILABLE", "down"))
                .when(ownerAccess).validateAccess(eq(OwnerType.MOVEMENT), eq(owner), any());
        mvc.perform(get("/api/v1/evidences").param("ownerType", "MOVEMENT").param("ownerId", owner.toString())
                        .with(readJwt()))
                .andExpect(status().isServiceUnavailable());
    }

    @Test void uploadPersistsObjectMetadataAndOutboxThenExactRetryReplays() throws Exception {
        UUID owner = UUID.randomUUID(); UUID key = UUID.randomUUID();
        EvidenceResponse first = upload(owner, key, pdf(), "{\"document\":\"A-1\"}");
        EvidenceResponse retry = upload(owner, key, pdf(), "{\"document\":\"A-1\"}");
        assertThat(retry).isEqualTo(first);
        assertThat(count("evidence.evidence_metadata", "id", first.id())).isEqualTo(1);
        assertThat(count("evidence.outbox_event", "aggregate_id", first.id())).isEqualTo(1);
        String keyPath = jdbc.queryForObject("SELECT object_key FROM evidence.evidence_metadata WHERE id=?", String.class, first.id());
        assertThat(keyPath).matches("movements/\\d{4}/" + owner + "/" + first.id() + "\\.pdf");
        assertThat(minio.statObject(StatObjectArgs.builder().bucket("siga-evidences").object(keyPath).build()).size())
                .isEqualTo(7);
        int objects = 0;
        for (var result : minio.listObjects(ListObjectsArgs.builder().bucket("siga-evidences")
                .prefix("movements/").recursive(true).build())) { result.get(); objects++; }
        assertThat(objects).isGreaterThanOrEqualTo(1);
        int exactObjects = 0;
        for (var result : minio.listObjects(ListObjectsArgs.builder().bucket("siga-evidences")
                .prefix(keyPath).recursive(true).build())) { result.get(); exactObjects++; }
        assertThat(exactObjects).isEqualTo(1);
    }

    @Test void failedReservationRetriesWithSameEvidenceIdentity() {
        UUID key = UUID.randomUUID(); UUID reserved = UUID.randomUUID(); String hash = "b".repeat(64);
        var first = persistence.reserve(key, hash, reserved);
        persistence.markFailed(key);
        var retry = persistence.reserve(key, hash, UUID.randomUUID());
        assertThat(first.evidenceId()).isEqualTo(reserved);
        assertThat(retry.evidenceId()).isEqualTo(reserved);
        assertThat(retry.reservedAt()).isEqualTo(first.reservedAt());
        persistence.markFailed(key);
    }

    @Test void databaseCompletionRollsBackMetadataWhenOutboxInsertFails() {
        UUID key = UUID.randomUUID(); UUID id = UUID.randomUUID(); UUID owner = UUID.randomUUID();
        var reservation = persistence.reserve(key, "c".repeat(64), id);
        Instant now = Instant.now();
        EvidenceMetadata evidence = new EvidenceMetadata(id, OwnerType.MOVEMENT, owner,
                "movements/2026/" + owner + "/" + id + ".pdf", "proof.pdf", "application/pdf", 7,
                "d".repeat(64), actor, EvidenceStatus.AVAILABLE, mapper.createObjectNode(), now, now);
        EvidenceResponse response = EvidenceResponse.from(evidence);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> persistence.complete(evidence,
                new EvidencePersistencePort.OutboxRecord(UUID.randomUUID(), id, UUID.randomUUID(), now, "not-json"),
                key, response)).isInstanceOf(RuntimeException.class);
        assertThat(count("evidence.evidence_metadata", "id", id)).isZero();
        assertThat(count("evidence.outbox_event", "aggregate_id", id)).isZero();
        persistence.markFailed(key);
    }

    @Test void sameKeyDifferentRequestConflicts() throws Exception {
        UUID owner = UUID.randomUUID(); UUID key = UUID.randomUUID();
        upload(owner, key, pdf(), "{}");
        mvc.perform(uploadRequest(owner, key, new MockMultipartFile("file", "other.pdf", "application/pdf", "%PDF-ok".getBytes()), "{}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test void concurrentDuplicateProducesOneLogicalUpload() throws Exception {
        UUID owner = UUID.randomUUID(); UUID key = UUID.randomUUID();
        var executor = Executors.newFixedThreadPool(2);
        try {
            Callable<MvcResult> call = () -> mvc.perform(uploadRequest(owner, key, pdf(), "{}")).andReturn();
            var futures = executor.invokeAll(List.of(call, call));
            int firstStatus = futures.get(0).get().getResponse().getStatus();
            int secondStatus = futures.get(1).get().getResponse().getStatus();
            assertThat(List.of(firstStatus, secondStatus)).contains(201).allMatch(value -> value == 201 || value == 409);
            UUID evidenceId = jdbc.queryForObject(
                    "SELECT evidence_id FROM evidence.upload_idempotency WHERE idempotency_key=?", UUID.class, key);
            assertThat(count("evidence.evidence_metadata", "id", evidenceId)).isEqualTo(1);
            assertThat(count("evidence.outbox_event", "aggregate_id", evidenceId)).isEqualTo(1);
            String objectKey = jdbc.queryForObject("SELECT object_key FROM evidence.evidence_metadata WHERE id=?",
                    String.class, evidenceId);
            assertThat(minio.statObject(StatObjectArgs.builder().bucket("siga-evidences").object(objectKey).build()))
                    .isNotNull();
        } finally { executor.shutdownNow(); }
    }

    @Test void scopedListMetadataAndContentRevalidateOwner() throws Exception {
        UUID owner = UUID.randomUUID(); EvidenceResponse uploaded = upload(owner, UUID.randomUUID(), pdf(), "{}");
        mvc.perform(get("/api/v1/evidences").param("ownerType", "MOVEMENT").param("ownerId", owner.toString())
                        .param("page", "0").param("size", "1").with(readJwt()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].id").value(uploaded.id().toString()))
                .andExpect(jsonPath("$.size").value(1));
        mvc.perform(get("/api/v1/evidences/{id}", uploaded.id()).with(readJwt()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.objectKey").doesNotExist());
        MvcResult download = mvc.perform(get("/api/v1/evidences/{id}/content", uploaded.id()).with(readJwt()))
                .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/pdf"))
                .andReturn();
        String disposition = download.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION);
        assertThat(disposition).isEqualTo("attachment; filename=\"proof.pdf\"; filename*=UTF-8''proof.pdf");
        assertThat(disposition).doesNotContain("=?UTF-8?Q?", "=5F", "\r", "\n");
        mvc.perform(asyncDispatch(download)).andExpect(status().isOk()).andExpect(content().bytes("%PDF-ok".getBytes()));
        verify(ownerAccess, org.mockito.Mockito.atLeast(4)).validateAccess(eq(OwnerType.MOVEMENT), eq(owner), any());
    }

    @Test void unicodeDownloadFilenameUsesAsciiFallbackAndRfc5987Value() throws Exception {
        UUID owner = UUID.randomUUID();
        EvidenceResponse uploaded = upload(owner, UUID.randomUUID(),
                new MockMultipartFile("file", "evidencia_técnica.pdf", "application/pdf", "%PDF-ok".getBytes()), "{}");
        MvcResult download = mvc.perform(get("/api/v1/evidences/{id}/content", uploaded.id()).with(readJwt()))
                .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/pdf"))
                .andReturn();
        String disposition = download.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION);
        assertThat(disposition).isEqualTo("attachment; filename=\"evidencia_t_cnica.pdf\"; filename*=UTF-8''evidencia_t%C3%A9cnica.pdf");
        assertThat(disposition).doesNotContain("=?UTF-8?Q?", "\r", "\n", "movements/");
        mvc.perform(asyncDispatch(download)).andExpect(status().isOk()).andExpect(content().bytes("%PDF-ok".getBytes()));
    }

    @Test void invalidPaginationAndMetadataAre400AndMagicMismatch415() throws Exception {
        UUID owner = UUID.randomUUID();
        mvc.perform(get("/api/v1/evidences").param("ownerType", "MOVEMENT").param("ownerId", owner.toString())
                        .param("size", "101").with(readJwt()))
                .andExpect(status().isBadRequest());
        mvc.perform(uploadRequest(owner, UUID.randomUUID(), pdf(), "[]")).andExpect(status().isBadRequest());
        mvc.perform(uploadRequest(owner, UUID.randomUUID(),
                        new MockMultipartFile("file", "fake.pdf", "application/pdf", "fake".getBytes()), "{}"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test void bucketHasNoPolicy() throws Exception {
        assertThat(minio.getBucketPolicy(GetBucketPolicyArgs.builder().bucket("siga-evidences").build())).isBlank();
    }

    @Test void publishesPersistentCanonicalMessageAndMarksPublished() throws Exception {
        UUID owner = UUID.randomUUID(); EvidenceResponse evidence = upload(owner, UUID.randomUUID(), pdf(), "{}");
        String queueName = "evidence-test-" + UUID.randomUUID();
        Queue queue = new Queue(queueName, false, true, false);
        rabbitAdmin.declareQueue(queue);
        rabbitAdmin.declareBinding(BindingBuilder.bind(queue).to(exchange).with("evidence.uploaded.v1"));
        assertThat(publisher.publishPending()).isGreaterThanOrEqualTo(1);
        Message message = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            Message candidate = rabbitTemplate.receive(queueName, attempt == 0 ? 5000 : 200);
            if (candidate == null) break;
            if (mapper.readTree(candidate.getBody()).path("aggregateId").asText().equals(evidence.id().toString())) {
                message = candidate;
                break;
            }
        }
        assertThat(message).isNotNull();
        assertThat(message.getMessageProperties().getReceivedDeliveryMode().name()).isEqualTo("PERSISTENT");
        JsonNode body = mapper.readTree(message.getBody());
        assertThat(body.path("aggregateId").asText()).isEqualTo(evidence.id().toString());
        Path schemaPath = Path.of("..", "siga-documentation", "SIGA_Documentacion_Tecnica_Final_v1.2", "api",
                "evidence-uploaded-v1.schema.json").toAbsolutePath().normalize();
        assertThat(Files.exists(schemaPath)).isTrue();
        JsonSchema schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(Files.newInputStream(schemaPath));
        assertThat(schema.validate(body)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM evidence.outbox_event WHERE aggregate_id=?", String.class,
                evidence.id())).isEqualTo("PUBLISHED");
    }

    @Test void brokerFailureKeepsPendingAndRecoveryUsesStableEventId() throws Exception {
        EvidenceResponse evidence = upload(UUID.randomUUID(), UUID.randomUUID(), pdf(), "{}");
        UUID eventId = jdbc.queryForObject("SELECT id FROM evidence.outbox_event WHERE aggregate_id=?", UUID.class, evidence.id());
        rabbitAdmin.deleteExchange(exchange.getName());
        publisher.publishPending();
        assertThat(jdbc.queryForObject("SELECT status FROM evidence.outbox_event WHERE id=?", String.class, eventId))
                .isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT attempts FROM evidence.outbox_event WHERE id=?", Integer.class, eventId))
                .isGreaterThan(0);
        rabbitAdmin.declareExchange(exchange);
        String queueName = "evidence-recovery-" + UUID.randomUUID();
        Queue queue = new Queue(queueName, false, true, false);
        rabbitAdmin.declareQueue(queue); rabbitAdmin.declareBinding(BindingBuilder.bind(queue).to(exchange).with("evidence.uploaded.v1"));
        jdbc.update("UPDATE evidence.outbox_event SET next_attempt_at=now() WHERE id=?", eventId);
        publisher.publishPending();
        Message recovered = rabbitTemplate.receive(queueName, 5000);
        assertThat(recovered).isNotNull();
        assertThat(recovered.getMessageProperties().getMessageId()).isEqualTo(eventId.toString());
    }

    private EvidenceResponse upload(UUID owner, UUID key, MockMultipartFile file, String metadata) throws Exception {
        MvcResult result = mvc.perform(uploadRequest(owner, key, file, metadata)).andExpect(status().isCreated()).andReturn();
        return mapper.readValue(result.getResponse().getContentAsByteArray(), EvidenceResponse.class);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder uploadRequest(
            UUID owner, UUID key, MockMultipartFile file, String metadata) {
        return multipart("/api/v1/evidences").file(file).param("ownerType", "MOVEMENT").param("ownerId", owner.toString())
                .param("metadata", metadata).header("Idempotency-Key", key).with(writeJwt());
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor writeJwt() {
        return jwt().jwt(j -> j.subject(actor.toString()).claim("permissions", List.of("EVIDENCE_WRITE")))
                .authorities(new SimpleGrantedAuthority("EVIDENCE_WRITE"));
    }
    private org.springframework.test.web.servlet.request.RequestPostProcessor readJwt() {
        return jwt().jwt(j -> j.subject(actor.toString()).claim("permissions", List.of("EVIDENCE_READ")))
                .authorities(new SimpleGrantedAuthority("EVIDENCE_READ"));
    }
    private long count(String table, String column, UUID value) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + column + "=?", Long.class, value);
        return count == null ? 0 : count;
    }
    private static MockMultipartFile pdf() {
        return new MockMultipartFile("file", "proof.pdf", "application/pdf", "%PDF-ok".getBytes());
    }
}
