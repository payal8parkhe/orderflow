package com.orderflow.kafka;

import com.orderflow.config.KafkaConfig;
import com.orderflow.dto.OrderEventPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderEventProducer {

    private final KafkaTemplate<String, OrderEventPayload> kafkaTemplate;

    public void sendOrderEvent(OrderEventPayload payload) {
        log.info("Publishing Order Event [{}] for order #{}", payload.getEventType(), payload.getOrderNumber());
        try {
            kafkaTemplate.send(KafkaConfig.ORDER_EVENTS_TOPIC, payload.getOrderNumber(), payload);
        } catch (Exception e) {
            log.error("Failed to publish order event to Kafka topic [{}]: {}", KafkaConfig.ORDER_EVENTS_TOPIC, e.getMessage());
        }
    }
}
