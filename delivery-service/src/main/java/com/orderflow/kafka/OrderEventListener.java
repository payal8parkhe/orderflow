package com.orderflow.kafka;

import com.orderflow.dto.OrderEventPayload;
import com.orderflow.model.Delivery;
import com.orderflow.model.DeliveryStatus;
import com.orderflow.repository.DeliveryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class OrderEventListener {

    private final DeliveryRepository deliveryRepository;

    @KafkaListener(topics = "order-events", groupId = "delivery-service-order-group")
    public void handleOrderEvent(OrderEventPayload payload) {
        log.info("delivery-service consumed Order Event [{}] for Order #{}", payload.getEventType(), payload.getOrderNumber());

        if ("ORDER_CREATED".equals(payload.getEventType())) {
            deliveryRepository.findByOrderId(payload.getOrderId()).ifPresentOrElse(
                    existing -> {
                        existing.setOrderNumber(payload.getOrderNumber());
                        existing.setShippingAddress(payload.getShippingAddress());
                        deliveryRepository.save(existing);
                        log.info("Updated pre-existing delivery tracking for Order #{}", payload.getOrderNumber());
                    },
                    () -> {
                        Delivery delivery = Delivery.builder()
                                .orderId(payload.getOrderId())
                                .orderNumber(payload.getOrderNumber())
                                .shippingAddress(payload.getShippingAddress())
                                .deliveryAgentId(0L)
                                .status(DeliveryStatus.ASSIGNED)
                                .trackingNumber("TRK-" + UUID.randomUUID().toString().substring(0, 10).toUpperCase())
                                .build();
                        deliveryRepository.save(delivery);
                        log.info("Pre-created delivery entry with Tracking {} for Order #{}", delivery.getTrackingNumber(), payload.getOrderNumber());
                    }
            );
        }
    }
}
