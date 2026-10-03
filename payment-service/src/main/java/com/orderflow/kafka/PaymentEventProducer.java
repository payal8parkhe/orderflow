package com.orderflow.kafka;

import com.orderflow.dto.PaymentEventPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentEventProducer {

    private static final String TOPIC = "payment-events";
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void sendPaymentEvent(PaymentEventPayload payload) {
        log.info("Publishing Payment Event [{}] for Order #{} (Payment #{})",
                payload.getEventType(), payload.getOrderNumber(), payload.getPaymentId());
        kafkaTemplate.send(TOPIC, String.valueOf(payload.getOrderId()), payload);
    }
}
