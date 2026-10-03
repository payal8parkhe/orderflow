package com.orderflow.kafka;

import com.orderflow.dto.DeliveryEventPayload;
import com.orderflow.model.OrderStatus;
import com.orderflow.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class DeliverySagaCoordinator {

    private final OrderService orderService;

    @KafkaListener(topics = "delivery-events", groupId = "orderflow-delivery-saga-group")
    public void handleDeliveryEvent(DeliveryEventPayload event) {
        log.info("Delivery Saga Coordinator received event: [{}] for Order #{} (Tracking: {})",
                event.getEventType(), event.getOrderNumber(), event.getTrackingNumber());

        try {
            if ("DELIVERY_ASSIGNED".equals(event.getEventType())) {
                try {
                    orderService.updateOrderStatus(event.getOrderId(), OrderStatus.PROCESSING);
                } catch (Exception e) {
                    log.debug("Skipping PROCESSING transition for Order #{}: {}", event.getOrderId(), e.getMessage());
                }
            } else if ("DELIVERY_OUT_FOR_DELIVERY".equals(event.getEventType())) {
                orderService.shipOrder(event.getOrderId());
            } else if ("DELIVERY_COMPLETED".equals(event.getEventType()) || "DELIVERY_DELIVERED".equals(event.getEventType())) {
                orderService.completeOrder(event.getOrderId());
            }
        } catch (com.orderflow.exception.BadRequestException e) {
            log.info("Idempotent delivery event handled safely: {}", e.getMessage());
        } catch (Exception e) {
            log.error("Error coordinating delivery saga for Order #{}: {}. Triggering Kafka retry / DLT recovery.",
                    event.getOrderNumber(), e.getMessage(), e);
            throw e;
        }
    }
}
