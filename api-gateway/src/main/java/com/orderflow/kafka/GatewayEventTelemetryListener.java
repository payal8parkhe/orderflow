package com.orderflow.kafka;

import com.orderflow.dto.DeliveryEventPayload;
import com.orderflow.dto.OrderEventPayload;
import com.orderflow.dto.PaymentEventPayload;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;

@Component
@Slf4j
public class GatewayEventTelemetryListener {

    private final Deque<Map<String, Object>> recentEvents = new ConcurrentLinkedDeque<>();
    private static final int MAX_BUFFER_SIZE = 60;

    @KafkaListener(topics = "order-events", groupId = "api-gateway-telemetry-order")
    public void consumeOrderEvent(OrderEventPayload payload) {
        log.debug("Gateway captured order event: {}", payload.getEventType());
        recordEvent(
                payload.getEventType(),
                payload.getOrderId(),
                payload.getOrderNumber(),
                payload.getStatus() != null ? payload.getStatus().name() : "N/A",
                payload.getTotalAmount() != null ? payload.getTotalAmount() : BigDecimal.ZERO,
                payload.getTimestamp()
        );
    }

    @KafkaListener(topics = "payment-events", groupId = "api-gateway-telemetry-payment")
    public void consumePaymentEvent(PaymentEventPayload payload) {
        log.debug("Gateway captured payment event: {}", payload.getEventType());
        recordEvent(
                payload.getEventType(),
                payload.getOrderId(),
                payload.getOrderNumber(),
                payload.getStatus() != null ? payload.getStatus().name() : "N/A",
                payload.getAmount() != null ? payload.getAmount() : BigDecimal.ZERO,
                payload.getTimestamp()
        );
    }

    @KafkaListener(topics = "delivery-events", groupId = "api-gateway-telemetry-delivery")
    public void consumeDeliveryEvent(DeliveryEventPayload payload) {
        log.debug("Gateway captured delivery event: {}", payload.getEventType());
        recordEvent(
                payload.getEventType(),
                payload.getOrderId(),
                payload.getOrderNumber(),
                payload.getStatus() != null ? payload.getStatus().name() : "N/A",
                BigDecimal.ZERO,
                payload.getTimestamp()
        );
    }

    private void recordEvent(String eventType, Long orderId, String orderNumber, String status, BigDecimal amount, LocalDateTime timestamp) {
        Map<String, Object> map = new HashMap<>();
        map.put("eventType", eventType);
        map.put("orderId", orderId);
        map.put("orderNumber", orderNumber);
        map.put("status", status);
        map.put("totalAmount", amount);
        map.put("timestamp", timestamp != null ? timestamp.toString() : LocalDateTime.now().toString());

        recentEvents.addFirst(map);
        while (recentEvents.size() > MAX_BUFFER_SIZE) {
            recentEvents.removeLast();
        }
    }

    public List<Map<String, Object>> getRecentEvents() {
        return new ArrayList<>(recentEvents);
    }
}
