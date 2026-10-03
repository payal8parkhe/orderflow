package com.orderflow.kafka;

import com.orderflow.dto.DeliveryEventPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class DeliveryEventProducer {

    private static final String TOPIC = "delivery-events";
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void sendDeliveryEvent(DeliveryEventPayload payload) {
        log.info("Publishing Delivery Event [{}] for Order #{} (Delivery #{}, Tracking: {})",
                payload.getEventType(), payload.getOrderNumber(), payload.getDeliveryId(), payload.getTrackingNumber());
        kafkaTemplate.send(TOPIC, String.valueOf(payload.getOrderId()), payload);
    }
}
