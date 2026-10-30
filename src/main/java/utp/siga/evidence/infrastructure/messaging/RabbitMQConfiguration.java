package utp.siga.evidence.infrastructure.messaging;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfiguration {
    @Bean
    TopicExchange evidenceExchange(@Value("${evidence.outbox.exchange}") String exchange) {
        return new TopicExchange(exchange, true, false);
    }
}
