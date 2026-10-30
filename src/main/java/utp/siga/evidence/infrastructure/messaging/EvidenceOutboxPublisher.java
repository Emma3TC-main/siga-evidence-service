package utp.siga.evidence.infrastructure.messaging;

import io.micrometer.core.instrument.MeterRegistry;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class EvidenceOutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(EvidenceOutboxPublisher.class);
    private final JdbcTemplate jdbc;
    private final RabbitTemplate rabbit;
    private final MeterRegistry meters;
    private final String exchange;
    private final String routingKey;
    private final long confirmTimeoutMs;
    private final long retryMaxDelaySeconds;

    public EvidenceOutboxPublisher(JdbcTemplate jdbc, RabbitTemplate rabbit, MeterRegistry meters,
            @Value("${evidence.outbox.exchange}") String exchange,
            @Value("${evidence.outbox.routing-key}") String routingKey,
            @Value("${evidence.outbox.confirm-timeout-ms}") long confirmTimeoutMs,
            @Value("${evidence.outbox.retry-max-delay-seconds}") long retryMaxDelaySeconds) {
        this.jdbc = jdbc;
        this.rabbit = rabbit;
        this.meters = meters;
        this.exchange = exchange;
        this.routingKey = routingKey;
        this.confirmTimeoutMs = confirmTimeoutMs;
        this.retryMaxDelaySeconds = retryMaxDelaySeconds;
    }

    @Scheduled(initialDelayString = "${evidence.outbox.initial-delay-ms}",
            fixedDelayString = "${evidence.outbox.fixed-delay-ms}")
    public void scheduledPublish() {
        publishPending();
    }

    @Transactional
    public int publishPending() {
        List<OutboxRow> rows = jdbc.query("""
                SELECT id, event_type, payload, attempts
                FROM evidence.outbox_event
                WHERE status='PENDING' AND (next_attempt_at IS NULL OR next_attempt_at <= now())
                ORDER BY occurred_at, id
                FOR UPDATE SKIP LOCKED
                LIMIT 10
                """, (rs, ignored) -> row(rs));
        int published = 0;
        for (OutboxRow row : rows) {
            if (!"EvidenceUploaded".equals(row.eventType())) {
                jdbc.update("UPDATE evidence.outbox_event SET status='FAILED', attempts=attempts+1 WHERE id=?", row.id());
                log.error("evidence_outbox_unknown_event eventId={} eventType={}", row.id(), row.eventType());
                meters.counter("evidence.outbox.failures", "reason", "unknown_event").increment();
                continue;
            }
            try {
                publish(row);
                jdbc.update("""
                        UPDATE evidence.outbox_event
                        SET status='PUBLISHED', published_at=now(), next_attempt_at=NULL
                        WHERE id=? AND status='PENDING'
                        """, row.id());
                published++;
            } catch (Exception failure) {
                long delay = Math.min(retryMaxDelaySeconds, 1L << Math.min(row.attempts(), 20));
                jdbc.update("""
                        UPDATE evidence.outbox_event
                        SET attempts=attempts+1, next_attempt_at=now() + (? * interval '1 second')
                        WHERE id=? AND status='PENDING'
                        """, delay, row.id());
                meters.counter("evidence.outbox.failures", "reason", "publish").increment();
                log.error("evidence_outbox_publish_failed eventId={} attempt={}", row.id(), row.attempts() + 1, failure);
            }
        }
        return published;
    }

    private void publish(OutboxRow row) throws Exception {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding("UTF-8");
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setMessageId(row.id().toString());
        properties.setHeader("eventId", row.id().toString());
        CorrelationData correlation = new CorrelationData(row.id().toString());
        rabbit.send(exchange, routingKey, new Message(row.payload().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                properties), correlation);
        CorrelationData.Confirm confirm = correlation.getFuture().get(confirmTimeoutMs, TimeUnit.MILLISECONDS);
        if (!confirm.isAck()) throw new IllegalStateException("RabbitMQ rechazó el evento: " + confirm.getReason());
    }

    private static OutboxRow row(ResultSet rs) throws SQLException {
        return new OutboxRow(rs.getObject("id", UUID.class), rs.getString("event_type"), rs.getString("payload"),
                rs.getInt("attempts"));
    }

    private record OutboxRow(UUID id, String eventType, String payload, int attempts) { }
}
