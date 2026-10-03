package com.orderflow.kafka;

import com.orderflow.config.KafkaConfig;
import com.orderflow.dto.CartEventPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class CartEventProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void sendCartEvent(CartEventPayload payload) {
        String key = payload.getUserEmail() != null ? payload.getUserEmail() : payload.getGuestSessionId();
        if (key == null) key = "anonymous";
        log.info("Publishing Cart Event [{}] for identifier '{}'", payload.getEventType(), key);
        try {
            kafkaTemplate.send(KafkaConfig.CART_EVENTS_TOPIC, key, payload);
        } catch (Exception e) {
            log.warn("Non-blocking: Failed to publish cart event to Kafka topic [{}]: {}", KafkaConfig.CART_EVENTS_TOPIC, e.getMessage());
        }
    }
}
